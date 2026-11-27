package pt.gicristovao.sattransit.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import pt.gicristovao.sattransit.MainActivity
import pt.gicristovao.sattransit.R
import pt.gicristovao.sattransit.data.SavedTransit
import pt.gicristovao.sattransit.data.SavedTransitStore
import pt.gicristovao.sattransit.data.TleRepository
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Tle
import pt.gicristovao.sattransit.engine.Transit
import pt.gicristovao.sattransit.engine.TransitFinder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object TransitCheck {
    private const val CHANNEL = "transit_changes"
    private const val PERIODIC = "transit-check"
    const val ISS = 25544
    const val ISS_SIZE_M = 109.0

    /** Volta a calcular um trânsito guardado com a órbita indicada. */
    fun find(s: SavedTransit, tle: Tle, eph: Ephemeris): Transit? {
        val jd = Transit.millisToJd(s.lastTimeMs)
        return TransitFinder(tle, eph, ISS_SIZE_M)
            .search(s.lat, s.lon, 0.0, 500.0, jd - 20.0 / 1440, 40.0 / 1440, null)
            .filter { it.body == s.body }
            .minByOrNull { abs(it.jdObserver - jd) }
    }

    fun recheck(s: SavedTransit, tle: Tle, eph: Ephemeris): Pair<SavedTransit, Transit?> {
        val t = find(s, tle, eph)
        val now = System.currentTimeMillis()
        val upd = if (t == null) s.copy(gone = true, lastCheckMs = now)
        else s.copy(
            lastTimeMs = Transit.jdToMillis(t.jdObserver), lastCenterLat = t.centerLat, lastCenterLon = t.centerLon,
            gone = false, lastCheckMs = now
        )
        return upd to t
    }

    /** Agenda a verificação periódica e o lembrete final de cada trânsito guardado. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val saved = SavedTransitStore(context).all().filter { it.lastTimeMs > System.currentTimeMillis() }
        if (saved.isEmpty()) {
            wm.cancelUniqueWork(PERIODIC)
        } else {
            val req = PeriodicWorkRequestBuilder<TransitCheckWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }
        saved.forEach { s ->
            val delay = s.lastTimeMs - 3 * 3_600_000L - System.currentTimeMillis()
            if (!s.finalNotified) {
                val req = OneTimeWorkRequestBuilder<TransitCheckWorker>()
                    .setInitialDelay(maxOf(0L, delay), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("final" to s.id))
                    .build()
                wm.enqueueUniqueWork("final-${s.id}", ExistingWorkPolicy.REPLACE, req)
            }
        }
    }

    fun cancelFinal(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("final-$id")
    }

    fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun hms(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
    fun day(ms: Long): String = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(ms)).replace(".", "")
    fun bodyOf(context: Context, b: Ephemeris.Body) =
        context.getString(if (b == Ephemeris.Body.SUN) R.string.body_of_sun else R.string.body_of_moon)
}

/** Verifica os trânsitos guardados com a órbita mais recente e avisa se mudaram. */
class TransitCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SavedTransitStore(ctx)
        store.prune()
        val now = System.currentTimeMillis()
        val finalId = inputData.getString("final")
        val targets = store.all().filter { it.lastTimeMs > now && (finalId == null || it.id == finalId) }
        if (targets.isEmpty()) return Result.success()

        val tle = runCatching { TleRepository(ctx).get(TransitCheck.ISS).tle }.getOrElse { return Result.retry() }
        val eph = ctx.assets.open("ephem_sun_moon.bin").use { Ephemeris(it) }

        for (s in targets) {
            val (upd, _) = TransitCheck.recheck(s, tle, eph)
            val what = TransitCheck.bodyOf(ctx, s.body)
            val dayStr = TransitCheck.day(upd.lastTimeMs)
            val dt = abs(upd.lastTimeMs - s.lastTimeMs) / 1000.0
            val dk = pt.gicristovao.sattransit.data.Places.distanceKm(s.lastCenterLat, s.lastCenterLon, upd.lastCenterLat, upd.lastCenterLon)
            val nid = s.id.hashCode()
            if (finalId != null) {
                if (upd.gone) {
                    TransitCheck.notify(ctx, nid, ctx.getString(R.string.notif_gone_title, what, dayStr), ctx.getString(R.string.notif_gone_text))
                } else if (upd.changed) {
                    TransitCheck.notify(ctx, nid, ctx.getString(R.string.notif_final_changed_title, TransitCheck.hms(upd.lastTimeMs)),
                        ctx.getString(R.string.notif_final_text, what, s.placeName))
                } else {
                    TransitCheck.notify(ctx, nid, ctx.getString(R.string.notif_final_ok_title, TransitCheck.hms(upd.lastTimeMs)),
                        ctx.getString(R.string.notif_final_text, what, s.placeName))
                }
                store.upsert(upd.copy(finalNotified = true))
            } else {
                if (upd.gone && !s.gone) {
                    TransitCheck.notify(ctx, nid, ctx.getString(R.string.notif_gone_title, what, dayStr), ctx.getString(R.string.notif_gone_text))
                } else if (!upd.gone && (dt >= 1.0 || dk >= 0.3)) {
                    TransitCheck.notify(
                        ctx, nid, ctx.getString(R.string.notif_changed_title, what, dayStr),
                        ctx.getString(R.string.notif_changed_text, TransitCheck.hms(s.lastTimeMs), TransitCheck.hms(upd.lastTimeMs), dk)
                    )
                }
                store.upsert(upd)
            }
        }
        if (finalId == null) TransitCheck.schedule(ctx)
        return Result.success()
    }
}

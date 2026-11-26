package pt.gicristovao.sattransit.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Verifica, descarrega e instala versões novas publicadas nas Releases do GitHub. */
class Updater(private val context: Context) {

    data class Release(
        val versionName: String,
        val versionCode: Long,
        val notes: List<String>,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    companion object {
        const val REPO = "gicristovao/sat-transit"
    }

    fun installedVersionCode(): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun installedVersionName(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Devolve a release mais recente se for mais nova do que a instalada; null caso contrário. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "SatTransit")
        val json = try {
            if (c.responseCode != 200) return@withContext null
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
        // etiqueta: vMAIOR.MENOR.CORREÇÃO.COMPILAÇÃO; o último número decide se é mais nova
        val tag = json.optString("tag_name").removePrefix("v")
        val code = tag.substringAfterLast('.').toLongOrNull() ?: return@withContext null
        val name = if (tag.count { it == '.' } >= 3) tag.substringBeforeLast('.') else tag
        if (code <= installedVersionCode()) return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                val notes = json.optString("body").lines()
                    .map { it.trim() }
                    .filter { it.startsWith("- ") || it.startsWith("· ") || it.startsWith("* ") }
                    .map { it.drop(2) }
                return@withContext Release(name, code, notes, a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        null
    }

    /** Descarrega o APK para a cache; onProgress recebe 0..1. */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "SatTransit-${release.versionName}.apk")
        var url = release.apkUrl
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "SatTransit")
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                url = conn.getHeaderField("Location")
                conn.disconnect()
                redirects++
                continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 256 * 1024) {
                        lastReport = done
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        conn.disconnect()
        onProgress(1f)
        file
    }

    /** O sistema permite que esta app instale outras? */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}

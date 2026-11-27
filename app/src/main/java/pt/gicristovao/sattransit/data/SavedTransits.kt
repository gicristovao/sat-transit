package pt.gicristovao.sattransit.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import kotlin.math.abs

/**
 * Trânsito guardado: o ponto de onde se vê, a previsão quando foi guardado e a última verificação.
 * Os instantes estão em milissegundos (hora vista do observador).
 */
data class SavedTransit(
    val body: Ephemeris.Body,
    val lat: Double,
    val lon: Double,
    val placeName: String,
    val savedAtMs: Long,
    val originalTimeMs: Long,
    val originalCenterLat: Double,
    val originalCenterLon: Double,
    val lastTimeMs: Long,
    val lastCenterLat: Double,
    val lastCenterLon: Double,
    val lastCheckMs: Long,
    /** A última verificação já não encontrou o trânsito. */
    val gone: Boolean = false,
    val finalNotified: Boolean = false,
) {
    val id get() = "${body.name}-${originalTimeMs / 60_000}"

    /** Diferença de hora (s) entre a previsão atual e a original. */
    val shiftSeconds get() = (lastTimeMs - originalTimeMs) / 1000.0

    /** Deslocação da linha central (km) desde que foi guardado. */
    val shiftKm get() = Places.distanceKm(originalCenterLat, originalCenterLon, lastCenterLat, lastCenterLon)

    val changed get() = gone || abs(shiftSeconds) >= 1.0 || shiftKm >= 0.3

    /** Este trânsito guardado corresponde ao trânsito t (mesmo corpo, menos de 10 min de diferença)? */
    fun matches(t: Transit) = t.body == body && abs(Transit.jdToMillis(t.jdObserver) - lastTimeMs) < 10 * 60_000

    companion object {
        fun from(t: Transit, lat: Double, lon: Double, name: String): SavedTransit {
            val ms = Transit.jdToMillis(t.jdObserver)
            val now = System.currentTimeMillis()
            return SavedTransit(t.body, lat, lon, name, now, ms, t.centerLat, t.centerLon, ms, t.centerLat, t.centerLon, now)
        }
    }
}

object Places {
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        pt.gicristovao.sattransit.engine.Geo.distanceKm(lat1, lon1, lat2, lon2)
}

class SavedTransitStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved_transits", Context.MODE_PRIVATE)

    fun all(): List<SavedTransit> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        List(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
    }.getOrDefault(emptyList())

    fun setAll(list: List<SavedTransit>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun upsert(s: SavedTransit) = setAll(all().filter { it.id != s.id } + s)
    fun remove(id: String) = setAll(all().filter { it.id != id })

    /** Apaga os trânsitos que já passaram há mais de 1 dia. */
    fun prune() {
        val limit = System.currentTimeMillis() - 86_400_000L
        val list = all()
        val kept = list.filter { it.lastTimeMs > limit }
        if (kept.size != list.size) setAll(kept)
    }

    private fun toJson(s: SavedTransit) = JSONObject()
        .put("b", s.body.name).put("la", s.lat).put("lo", s.lon).put("n", s.placeName).put("sa", s.savedAtMs)
        .put("ot", s.originalTimeMs).put("ola", s.originalCenterLat).put("olo", s.originalCenterLon)
        .put("lt", s.lastTimeMs).put("lla", s.lastCenterLat).put("llo", s.lastCenterLon).put("lc", s.lastCheckMs)
        .put("g", s.gone).put("f", s.finalNotified)

    private fun fromJson(o: JSONObject) = SavedTransit(
        Ephemeris.Body.valueOf(o.getString("b")), o.getDouble("la"), o.getDouble("lo"), o.optString("n"), o.optLong("sa"),
        o.getLong("ot"), o.getDouble("ola"), o.getDouble("olo"), o.getLong("lt"), o.getDouble("lla"), o.getDouble("llo"),
        o.optLong("lc"), o.optBoolean("g"), o.optBoolean("f")
    )
}

/** Confiança da previsão a partir da idade da órbita no instante do trânsito. */
enum class Confidence { RELIABLE, MAY_CHANGE, INDICATIVE }

object Reliability {
    /**
     * Margem estimada (s). Empírica: o erro cresce com o quadrado do tempo desde a época do TLE
     * (≈0,7 s a 3 dias, ≈18 s a 20 dias, comparando com o transit-finder).
     */
    fun marginSeconds(t: Transit): Double = maxOf(0.5, 0.05 * t.tleAgeDays * t.tleAgeDays)

    fun level(t: Transit): Confidence {
        val m = marginSeconds(t)
        return when {
            m <= 1.0 -> Confidence.RELIABLE
            m <= 5.0 -> Confidence.MAY_CHANGE
            else -> Confidence.INDICATIVE
        }
    }
}

package pt.gicristovao.sattransit.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.gicristovao.sattransit.engine.Tle
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Obtém o TLE mais recente de um satélite, com cache local.
 * Respeita a regra de uso do CelesTrak: no máximo um download a cada 2 h.
 */
class TleRepository(private val context: Context) {

    data class Result(val tle: Tle, val fetchedAtMillis: Long, val fromCache: Boolean)

    private val maxAgeMillis = 2 * 60 * 60 * 1000L

    suspend fun get(catalogNumber: Int, forceRefresh: Boolean = false): Result = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "tle_$catalogNumber.txt")
        val cached = file.takeIf { it.exists() }?.let { runCatching { Tle.parse(it.readText()) }.getOrNull() }
        val age = System.currentTimeMillis() - file.lastModified()
        if (cached != null && !forceRefresh && age < maxAgeMillis) {
            return@withContext Result(cached, file.lastModified(), true)
        }
        val sources = buildList {
            add("https://celestrak.org/NORAD/elements/gp.php?CATNR=$catalogNumber&FORMAT=TLE")
            if (catalogNumber == 25544) add("https://live.ariss.org/iss.txt")
        }
        for (src in sources) {
            val text = runCatching { download(src) }.getOrNull() ?: continue
            val tle = runCatching { Tle.parse(text) }.getOrNull() ?: continue
            if (tle.catalogNumber != catalogNumber) continue
            file.writeText(text)
            return@withContext Result(tle, System.currentTimeMillis(), false)
        }
        if (cached != null) return@withContext Result(cached, file.lastModified(), true)
        throw IllegalStateException(context.getString(pt.gicristovao.sattransit.R.string.no_orbit_data))
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "SatTransit/0.1 (Android)")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}

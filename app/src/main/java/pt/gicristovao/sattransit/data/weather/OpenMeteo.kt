package pt.gicristovao.sattransit.data.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.Locale

/** Uma hora da previsão do Open-Meteo. Percentagens 0–100; null = sem dado. */
data class OmHora(
    val instante: Instant,
    val total: Int?, val baixas: Int?, val medias: Int?, val altas: Int?,
    val chuva: Int?,              // probabilidade de precipitação (%)
    val vento: Double?,           // km/h, a 10 m
    val direcaoVento: Double?,    // graus, de onde sopra
)

/**
 * Open-Meteo (open-meteo.com), sem chave: nuvens baixas/médias/altas de hora a hora, até 16 dias.
 * Dados CC BY 4.0 — a fonte é sempre indicada no ecrã.
 * A API gratuita é só para uso não comercial; para uma versão paga da app é preciso o plano comercial.
 */
class OpenMeteo {

    companion object {
        private const val CACHE_MS = 30L * 60 * 1000

        fun url(lat: Double, lon: Double): String = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&hourly=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,precipitation_probability,wind_speed_10m,wind_direction_10m" +
                "&forecast_days=16&timeformat=unixtime&timezone=GMT",
            lat, lon
        )

        private fun JSONArray?.int(i: Int): Int? =
            if (this == null || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }?.let { Math.round(it).toInt() }

        private fun JSONArray?.dbl(i: Int): Double? =
            if (this == null || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }

        /** Converte a resposta em horas (sem rede; usado nos testes). */
        fun ler(texto: String): List<OmHora> {
            val h = JSONObject(texto).getJSONObject("hourly")
            val t = h.getJSONArray("time")
            val total = h.optJSONArray("cloud_cover")
            val low = h.optJSONArray("cloud_cover_low")
            val mid = h.optJSONArray("cloud_cover_mid")
            val high = h.optJSONArray("cloud_cover_high")
            val rain = h.optJSONArray("precipitation_probability")
            val wind = h.optJSONArray("wind_speed_10m")
            val dir = h.optJSONArray("wind_direction_10m")
            return (0 until t.length()).map { i ->
                OmHora(
                    Instant.ofEpochSecond(t.getLong(i)),
                    total.int(i), low.int(i), mid.int(i), high.int(i), rain.int(i), wind.dbl(i), dir.dbl(i)
                )
            }
        }
    }

    private val cache = HashMap<String, Pair<Long, List<OmHora>>>()

    suspend fun previsao(lat: Double, lon: Double): List<OmHora> {
        val key = String.format(Locale.US, "%.2f,%.2f", lat, lon)
        val agora = System.currentTimeMillis()
        cache[key]?.let { if (agora - it.first < CACHE_MS) return it.second }
        val txt = withContext(Dispatchers.IO) {
            val c = URI(url(lat, lon)).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", "SatTransit (Android)")
                if (c.responseCode != 200) throw IllegalStateException("Open-Meteo devolveu ${c.responseCode}")
                c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        val lista = ler(txt)
        cache[key] = agora to lista
        return lista
    }
}

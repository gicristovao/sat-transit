package pt.gicristovao.sattransit.data.weather

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant

/**
 * Junta as duas fontes para um local:
 * - em Portugal (localidade IPMA a ≤ 20 km): IPMA como fonte principal, Open-Meteo para as % por camada;
 * - fora de Portugal, ou se o IPMA falhar: só Open-Meteo.
 * Nunca lança: o que falhar fica a null e o ecrã mostra o que houver.
 */
class WeatherRepository(context: Context) {
    private val ipma = Ipma(context.filesDir)
    private val openMeteo = OpenMeteo()

    suspend fun load(lat: Double, lon: Double): PlaceWeather = coroutineScope {
        val om = async { runCatching { openMeteo.previsao(lat, lon) }.getOrNull() }
        val place = runCatching { ipma.maisProximo(lat, lon) }.getOrNull()?.takeIf { it.km <= Sky.IPMA_MAX_KM }
        val entries = place?.let { runCatching { ipma.previsao(it.id) }.getOrNull() }
        PlaceWeather(if (entries != null) place else null, entries, om.await(), Instant.now())
    }
}

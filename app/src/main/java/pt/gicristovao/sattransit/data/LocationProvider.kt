package pt.gicristovao.sattransit.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class Place(val lat: Double, val lon: Double, val altitudeM: Double, val name: String?)

class LocationProvider(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun current(): Place? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        // Uma posição com menos de 10 min e precisão melhor que 500 m serve (a faixa de visibilidade tem vários km).
        var loc: Location? = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 && it.hasAccuracy() && it.accuracy < 500f }
            .maxByOrNull { it.time }
        if (loc == null) for (p in providers) {
            loc = withTimeoutOrNull(15_000) { fresh(lm, p) }
            if (loc != null) break
        }
        if (loc == null) {
            loc = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        }
        loc ?: return null
        val alt = if (loc.hasAltitude()) loc.altitude else 0.0
        return Place(loc.latitude, loc.longitude, alt, placeName(loc.latitude, loc.longitude))
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(lm, provider, signal, context.mainExecutor) { l ->
                if (cont.isActive) cont.resume(l)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun placeName(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val a = Geocoder(context, Locale("pt", "PT")).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea
        }.getOrNull()
    }
}

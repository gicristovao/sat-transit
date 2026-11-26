package pt.gicristovao.sattransit.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class SavedPlace(val name: String, val lat: Double, val lon: Double)

/** Locais guardados, local escolhido e raio de pesquisa (guardados no telemóvel). */
class PlaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun saved(): List<SavedPlace> = runCatching {
        val arr = JSONArray(prefs.getString("saved", "[]"))
        List(arr.length()) { i -> arr.getJSONObject(i).let { SavedPlace(it.getString("n"), it.getDouble("a"), it.getDouble("o")) } }
    }.getOrDefault(emptyList())

    fun setSaved(list: List<SavedPlace>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("a", it.lat).put("o", it.lon)) }
        prefs.edit().putString("saved", arr.toString()).apply()
    }

    var chosen: SavedPlace?
        get() = prefs.getString("chosen", null)?.let { s ->
            runCatching { JSONObject(s).let { SavedPlace(it.getString("n"), it.getDouble("a"), it.getDouble("o")) } }.getOrNull()
        }
        set(v) {
            prefs.edit().putString("chosen", v?.let { JSONObject().put("n", it.name).put("a", it.lat).put("o", it.lon).toString() }).apply()
        }

    var radiusKm: Int
        get() = prefs.getInt("radius", 100)
        set(v) = prefs.edit().putInt("radius", v).apply()
}

/** Pesquisa e nome de locais através do Geocoder do Android (sem chave). */
class PlaceSearch(private val context: Context) {

    @Suppress("DEPRECATION")
    suspend fun search(query: String): List<SavedPlace> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent() || query.isBlank()) return@withContext emptyList()
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocationName(query, 6).orEmpty().map { a ->
                val parts = listOfNotNull(a.featureName, a.locality, a.adminArea, a.countryName).distinct()
                SavedPlace(parts.take(3).joinToString(", "), a.latitude, a.longitude)
            }
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    suspend fun nameOf(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            listOfNotNull(a?.locality ?: a?.subAdminArea, a?.adminArea).distinct().joinToString(", ").ifBlank { null }
        }.getOrNull()
    }
}

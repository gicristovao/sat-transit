package pt.gicristovao.sattransit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.gicristovao.sattransit.data.LocationProvider
import pt.gicristovao.sattransit.data.Place
import pt.gicristovao.sattransit.data.TleRepository
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import pt.gicristovao.sattransit.engine.TransitFinder

sealed interface UiState {
    data object NeedPermission : UiState
    data class Loading(val message: String, val progress: Float?) : UiState
    data class Ready(
        val place: Place,
        val transits: List<Transit>,
        val tleEpochMillis: Long,
        val radiusKm: Int,
        val days: Int,
    ) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val ISS = 25544
        const val ISS_SIZE_M = 109.0
        const val RADIUS_KM = 100
        const val DAYS = 14
    }

    private val _state = MutableStateFlow<UiState>(UiState.NeedPermission)
    val state: StateFlow<UiState> = _state

    private val tleRepo = TleRepository(app)
    private val locations = LocationProvider(app)
    private var ephemeris: Ephemeris? = null
    private var job: Job? = null

    fun onPermissionDenied() {
        _state.value = UiState.Error("Sem acesso à localização não é possível calcular os trânsitos onde estás.")
    }

    fun refresh(force: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                _state.value = UiState.Loading("A obter a tua posição…", null)
                val place = locations.current()
                    ?: throw IllegalStateException("Não foi possível obter a posição. Confirma que a localização está ligada.")

                _state.value = UiState.Loading("A obter dados orbitais…", null)
                val tle = tleRepo.get(ISS, force)

                val eph = ephemeris ?: withContext(Dispatchers.IO) {
                    getApplication<Application>().assets.open("ephem_sun_moon.bin").use { Ephemeris(it) }
                }.also { ephemeris = it }

                _state.value = UiState.Loading("A calcular trânsitos…", 0f)
                val transits = withContext(Dispatchers.Default) {
                    val finder = TransitFinder(tle.tle, eph, ISS_SIZE_M)
                    finder.search(
                        place.lat, place.lon, place.altitudeM, RADIUS_KM.toDouble(),
                        Transit.millisToJd(System.currentTimeMillis()), DAYS.toDouble()
                    ) { f ->
                        _state.value = UiState.Loading("A calcular trânsitos…", f.toFloat())
                        isActive
                    }
                }
                _state.value = UiState.Ready(
                    place, transits, Transit.jdToMillis(tle.tle.epochJd), RADIUS_KM, DAYS
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Erro inesperado.")
            }
        }
    }
}

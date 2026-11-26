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
import pt.gicristovao.sattransit.data.Updater
import java.io.File
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

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val release: Updater.Release, val progress: Float) : UpdateState
    data class Ready(val release: Updater.Release, val apk: File) : UpdateState
    data class Failed(val release: Updater.Release, val message: String) : UpdateState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private fun str(id: Int): String = getApplication<Application>().getString(id)

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

    val updater = Updater(app)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.None)
    val update: StateFlow<UpdateState> = _update
    private val _sheetOpen = MutableStateFlow(false)
    val sheetOpen: StateFlow<Boolean> = _sheetOpen
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    private var installPending = false

    init {
        checkUpdate(manual = false)
    }

    fun toastShown() { _toast.value = null }

    fun checkUpdate(manual: Boolean) {
        if (_update.value is UpdateState.Downloading) return
        viewModelScope.launch {
            val r = runCatching { updater.check() }
            val release = r.getOrNull()
            when {
                release != null -> {
                    _update.value = UpdateState.Available(release)
                    if (manual) _sheetOpen.value = true
                }
                manual && r.isFailure -> _toast.value = str(R.string.update_check_failed)
                manual -> _toast.value = str(R.string.up_to_date)
            }
        }
    }

    fun openSheet() { _sheetOpen.value = true }
    fun closeSheet() { _sheetOpen.value = false }

    fun startDownload() {
        val release = when (val u = _update.value) {
            is UpdateState.Available -> u.release
            is UpdateState.Failed -> u.release
            else -> return
        }
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(release, 0f)
            try {
                val apk = updater.download(release) { p -> _update.value = UpdateState.Downloading(release, p) }
                _update.value = UpdateState.Ready(release, apk)
                install()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(release, e.message ?: str(R.string.download_error))
            }
        }
    }

    fun install() {
        val u = _update.value as? UpdateState.Ready ?: return
        if (updater.canInstall()) {
            installPending = false
            updater.install(u.apk)
        } else {
            installPending = true
            _toast.value = str(R.string.allow_install)
            updater.openInstallPermissionSettings()
        }
    }

    /** Chamado quando a app volta ao primeiro plano (ex.: depois de dar a autorização). */
    fun onResume() {
        if (installPending && updater.canInstall()) install()
    }

    fun onPermissionDenied() {
        _state.value = UiState.Error(str(R.string.permission_denied))
    }

    fun refresh(force: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                _state.value = UiState.Loading(str(R.string.getting_position), null)
                val place = locations.current()
                    ?: throw IllegalStateException(str(R.string.no_position))

                _state.value = UiState.Loading(str(R.string.getting_orbit), null)
                val tle = tleRepo.get(ISS, force)

                val eph = ephemeris ?: withContext(Dispatchers.IO) {
                    getApplication<Application>().assets.open("ephem_sun_moon.bin").use { Ephemeris(it) }
                }.also { ephemeris = it }

                _state.value = UiState.Loading(str(R.string.computing), 0f)
                val transits = withContext(Dispatchers.Default) {
                    val finder = TransitFinder(tle.tle, eph, ISS_SIZE_M)
                    finder.search(
                        place.lat, place.lon, place.altitudeM, RADIUS_KM.toDouble(),
                        Transit.millisToJd(System.currentTimeMillis()), DAYS.toDouble()
                    ) { f ->
                        _state.value = UiState.Loading(str(R.string.computing), f.toFloat())
                        isActive
                    }
                }
                _state.value = UiState.Ready(
                    place, transits, Transit.jdToMillis(tle.tle.epochJd), RADIUS_KM, DAYS
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: str(R.string.unexpected_error))
            }
        }
    }
}

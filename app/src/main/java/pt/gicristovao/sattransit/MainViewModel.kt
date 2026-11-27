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
import pt.gicristovao.sattransit.data.PlaceSearch
import pt.gicristovao.sattransit.data.PlaceStore
import pt.gicristovao.sattransit.data.SavedPlace
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
        /** true = posição GPS; false = local escolhido. */
        val isGps: Boolean = true,
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
        const val DAYS = 14
    }

    private val _state = MutableStateFlow<UiState>(UiState.NeedPermission)
    val state: StateFlow<UiState> = _state

    private val tleRepo = TleRepository(app)
    private val locations = LocationProvider(app)
    private var ephemeris: Ephemeris? = null
    private var lastTle: pt.gicristovao.sattransit.engine.Tle? = null

    val places = PlaceStore(app)
    val placeSearch = PlaceSearch(app)
    private val _otherMode = MutableStateFlow(false)
    val otherMode: StateFlow<Boolean> = _otherMode
    private val _chosen = MutableStateFlow(places.chosen)
    val chosen: StateFlow<SavedPlace?> = _chosen
    /** Última posição GPS conhecida (para centrar o mapa do outro local). */
    var lastGps: Place? = null
        private set

    /** Muda o raio de "Onde estou" e recalcula. */
    fun setHereRadius(km: Int) {
        places.radiusHere = km
        refresh()
    }

    fun useHere() {
        if (!_otherMode.value) return
        _otherMode.value = false
        refresh()
    }

    /** Passa a mostrar os trânsitos de um local escolhido, com o raio indicado. */
    fun useOther(p: SavedPlace, radiusKm: Int) {
        places.chosen = p
        places.radiusOther = radiusKm
        _chosen.value = p
        _otherMode.value = true
        refresh()
    }

    private suspend fun ensureData(force: Boolean): Pair<pt.gicristovao.sattransit.engine.Tle, Ephemeris> {
        val tle = if (lastTle == null || force) tleRepo.get(ISS, force).tle.also { lastTle = it } else lastTle!!
        val eph = ephemeris ?: withContext(Dispatchers.IO) {
            getApplication<Application>().assets.open("ephem_sun_moon.bin").use { Ephemeris(it) }
        }.also { ephemeris = it }
        return tle to eph
    }

    /** Trânsitos à volta de um ponto (para desenhar as linhas centrais no mapa do outro local). */
    suspend fun transitsAt(lat: Double, lon: Double, radiusKm: Int): List<Transit> {
        val (tle, eph) = ensureData(false)
        return withContext(Dispatchers.Default) {
            TransitFinder(tle, eph, ISS_SIZE_M).search(
                lat, lon, 0.0, radiusKm.toDouble(), Transit.millisToJd(System.currentTimeMillis()), DAYS.toDouble()
            ) { isActive }
        }
    }

    /** Avalia um trânsito visto de outro ponto (o pino do mapa). */
    suspend fun evaluateAt(t: Transit, lat: Double, lon: Double): Transit? {
        val tle = lastTle ?: return null
        val eph = ephemeris ?: return null
        return withContext(Dispatchers.Default) {
            TransitFinder(tle, eph, ISS_SIZE_M).evaluateAt(t, lat, lon, 0.0)
        }
    }
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
                val other = _otherMode.value
                val chosenPlace = _chosen.value
                val radius = if (other && chosenPlace != null) places.radiusOther else places.radiusHere
                val place = if (other && chosenPlace != null) {
                    Place(chosenPlace.lat, chosenPlace.lon, 0.0, chosenPlace.name)
                } else {
                    _state.value = UiState.Loading(str(R.string.getting_position), null)
                    (locations.current() ?: throw IllegalStateException(str(R.string.no_position))).also { lastGps = it }
                }

                _state.value = UiState.Loading(str(R.string.getting_orbit), null)
                val (tle0, eph) = ensureData(force)

                _state.value = UiState.Loading(str(R.string.computing), 0f)
                val transits = withContext(Dispatchers.Default) {
                    val finder = TransitFinder(tle0, eph, ISS_SIZE_M)
                    finder.search(
                        place.lat, place.lon, place.altitudeM, radius.toDouble(),
                        Transit.millisToJd(System.currentTimeMillis()), DAYS.toDouble()
                    ) { f ->
                        _state.value = UiState.Loading(str(R.string.computing), f.toFloat())
                        isActive
                    }
                }
                _state.value = UiState.Ready(
                    place, transits, Transit.jdToMillis(tle0.epochJd), radius, DAYS, isGps = !(other && chosenPlace != null)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: str(R.string.unexpected_error))
            }
        }
    }
}

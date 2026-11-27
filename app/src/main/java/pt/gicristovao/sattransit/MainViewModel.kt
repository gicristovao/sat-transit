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
import pt.gicristovao.sattransit.data.SavedTransit
import pt.gicristovao.sattransit.data.SavedTransitStore
import pt.gicristovao.sattransit.work.TransitCheck
import pt.gicristovao.sattransit.data.TleRepository
import pt.gicristovao.sattransit.data.Updater
import pt.gicristovao.sattransit.data.weather.PlaceWeather
import pt.gicristovao.sattransit.data.weather.WeatherRepository
import java.io.File
import kotlin.math.abs
import pt.gicristovao.sattransit.engine.Ephemeris
import pt.gicristovao.sattransit.engine.Transit
import pt.gicristovao.sattransit.engine.TransitFinder

sealed interface UiState {
    data object NeedPermission : UiState
    /** Outro local ainda sem local escolhido. */
    data object NeedPlace : UiState
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

    // ---- trânsitos guardados ----
    private val savedStore = SavedTransitStore(app)
    private val _saved = MutableStateFlow(savedStore.all())
    val saved: StateFlow<List<SavedTransit>> = _saved

    /** Época (ms) da órbita em uso, para a caixa de confiança. */
    val tleEpochMillis: Long? get() = lastTle?.let { Transit.jdToMillis(it.epochJd) }

    fun savedFor(t: Transit): SavedTransit? = _saved.value.firstOrNull { it.matches(t) }

    /** Guarda ou deixa de guardar; devolve true se ficou guardado. */
    fun toggleSave(t: Transit, lat: Double, lon: Double, name: String): Boolean {
        val app = getApplication<Application>()
        val existing = savedFor(t)
        if (existing != null) {
            savedStore.remove(existing.id)
            TransitCheck.cancelFinal(app, existing.id)
        } else {
            savedStore.upsert(SavedTransit.from(t, lat, lon, name))
        }
        _saved.value = savedStore.all()
        TransitCheck.schedule(app)
        return existing == null
    }

    /** Recalcula os trânsitos guardados (para o filtro "Guardados"). */
    suspend fun savedTransits(): List<Pair<SavedTransit, Transit>> {
        savedStore.prune()
        val (tle, eph) = ensureData(false)
        val out = withContext(Dispatchers.Default) {
            savedStore.all().filter { it.lastTimeMs > System.currentTimeMillis() - 3_600_000 }.mapNotNull { s ->
                val (upd, t) = TransitCheck.recheck(s, tle, eph)
                savedStore.upsert(upd)
                t?.let { upd to it }
            }
        }
        _saved.value = savedStore.all()
        return out.sortedBy { it.first.lastTimeMs }
    }

    // ---- meteorologia ----
    private val weatherRepo = WeatherRepository(app)
    private val _weather = MutableStateFlow<Map<String, PlaceWeather>>(emptyMap())
    /** Previsão por local (chave de [weatherKey]); um local ausente ainda está a carregar. */
    val weather: StateFlow<Map<String, PlaceWeather>> = _weather
    private val weatherLoading = HashSet<String>()

    fun weatherKey(lat: Double, lon: Double): String = String.format(java.util.Locale.US, "%.3f,%.3f", lat, lon)

    /** Vai buscar a previsão de um local, se ainda não houver ou tiver mais de 30 min. */
    fun requestWeather(lat: Double, lon: Double) {
        val k = weatherKey(lat, lon)
        if (k in weatherLoading) return
        val cur = _weather.value[k]
        if (cur != null && System.currentTimeMillis() - cur.fetchedAt.toEpochMilli() < 30 * 60_000L) return
        weatherLoading += k
        viewModelScope.launch {
            try {
                val w = weatherRepo.load(lat, lon)
                _weather.value = _weather.value + (k to w)
            } finally {
                weatherLoading -= k
            }
        }
    }

    private var hereCache: UiState.Ready? = null
    private var otherCache: UiState.Ready? = null

    /** Volta a "Onde estou": mostra logo o último resultado e só recalcula se algo mudou. */
    fun useHere() {
        if (!_otherMode.value) return
        _otherMode.value = false
        showCachedOrRefresh(hereCache)
    }

    /**
     * Separador "Outro local": mostra o último resultado desta sessão, se houver.
     * Devolve false quando ainda não há local escolhido (a interface abre a escolha).
     */
    fun selectOther(): Boolean {
        _otherMode.value = true
        val c = otherCache
        if (c == null) {
            cancelJob()
            _state.value = UiState.NeedPlace
            return false
        }
        showCachedOrRefresh(c)
        return true
    }

    private fun showCachedOrRefresh(c: UiState.Ready?) {
        cancelJob()
        if (c == null) { refresh(); return }
        _state.value = c
        job = viewModelScope.launch {
            val stale = runCatching { isStale(c) }.getOrDefault(false)
            if (stale && _state.value === c) refresh()
        }
    }

    /** O resultado guardado deixou de servir? (órbita nova, mudança de posição > 1 km ou raio diferente) */
    private suspend fun isStale(c: UiState.Ready): Boolean {
        val tle = tleRepo.get(ISS, false).tle
        if (Transit.jdToMillis(tle.epochJd) != c.tleEpochMillis) return true
        return if (c.isGps) {
            if (c.radiusKm != places.radiusHere) return true
            val loc = locations.lastKnown() ?: return false
            val d = FloatArray(1)
            android.location.Location.distanceBetween(loc.latitude, loc.longitude, c.place.lat, c.place.lon, d)
            d[0] > 1000f
        } else {
            val ch = _chosen.value
            c.radiusKm != places.radiusOther || ch == null || ch.lat != c.place.lat || ch.lon != c.place.lon
        }
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
        val tle = tleRepo.get(ISS, force).tle.also { lastTle = it }
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
        checkMoved()
    }

    fun onPermissionDenied() {
        _state.value = UiState.Error(str(R.string.permission_denied))
    }

    private val _refreshing = MutableStateFlow(false)
    /** true enquanto um "puxar para atualizar" corre (a lista antiga fica visível, esbatida). */
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _locating = MutableStateFlow(false)
    /** true enquanto se espera pela leitura GPS de um "puxar para atualizar". */
    val locating: StateFlow<Boolean> = _locating

    /** Puxar para baixo: posição GPS nova (em "Onde estou") e trânsitos recalculados, sem esconder a lista. */
    fun pullRefresh() {
        if (_state.value !is UiState.Ready) { refresh(); return }
        refresh(keepList = true, freshGps = true)
    }

    /**
     * Regresso à app: se a posição mudou mais de 1 km desde o último cálculo, recalcula sozinha.
     * Usa uma leitura nova (não a última guardada, que pode ser antiga).
     */
    private var lastMoveCheck = 0L
    private fun checkMoved() {
        val c = _state.value as? UiState.Ready ?: return
        if (!c.isGps || _otherMode.value || _refreshing.value) return
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastMoveCheck < 60_000) return
        lastMoveCheck = now
        job = viewModelScope.launch {
            val p = runCatching { locations.current(fresh = true) }.getOrNull() ?: return@launch
            val d = FloatArray(1)
            android.location.Location.distanceBetween(p.lat, p.lon, c.place.lat, c.place.lon, d)
            if (_state.value !== c) return@launch
            if (d[0] > 1000f) {
                refresh(keepList = true, knownPlace = p)
            } else {
                // mesma zona: só atualiza a hora da leitura
                val upd = c.copy(place = c.place.copy(fixTimeMs = p.fixTimeMs))
                hereCache = upd
                _state.value = upd
            }
        }
    }

    /** Cancela o cálculo em curso e limpa os indicadores de "puxar para atualizar". */
    private fun cancelJob() {
        job?.cancel()
        _refreshing.value = false
        _locating.value = false
    }

    private suspend fun isCurrent(): Boolean = job === kotlin.coroutines.coroutineContext[Job]

    /**
     * Recalcula.
     * @param keepList mantém a lista atual visível (esbatida) em vez do ecrã "a carregar".
     * @param freshGps ignora a última posição guardada e pede uma leitura nova.
     * @param knownPlace posição já obtida (evita nova leitura).
     */
    fun refresh(force: Boolean = false, keepList: Boolean = false, freshGps: Boolean = false, knownPlace: Place? = null) {
        cancelJob()
        val previous = _state.value as? UiState.Ready
        val quiet = keepList && previous != null
        _refreshing.value = quiet
        job = viewModelScope.launch {
            try {
                val other = _otherMode.value
                val chosenPlace = _chosen.value
                if (other && chosenPlace == null) { _state.value = UiState.NeedPlace; return@launch }
                val radius = if (other && chosenPlace != null) places.radiusOther else places.radiusHere
                val place = if (other && chosenPlace != null) {
                    Place(chosenPlace.lat, chosenPlace.lon, 0.0, chosenPlace.name)
                } else if (knownPlace != null) {
                    knownPlace.also { lastGps = it }
                } else {
                    if (quiet) _locating.value = true
                    else _state.value = UiState.Loading(str(R.string.getting_position), null)
                    try {
                        (locations.current(fresh = freshGps) ?: throw IllegalStateException(str(R.string.no_position))).also { lastGps = it }
                    } finally {
                        if (isCurrent()) _locating.value = false
                    }
                }

                if (!quiet) _state.value = UiState.Loading(str(R.string.getting_orbit), null)
                val (tle0, eph) = ensureData(force)

                if (!quiet) _state.value = UiState.Loading(str(R.string.computing), 0f)
                val transits = withContext(Dispatchers.Default) {
                    val finder = TransitFinder(tle0, eph, ISS_SIZE_M)
                    finder.search(
                        place.lat, place.lon, place.altitudeM, radius.toDouble(),
                        Transit.millisToJd(System.currentTimeMillis()), DAYS.toDouble()
                    ) { f ->
                        if (!quiet) _state.value = UiState.Loading(str(R.string.computing), f.toFloat())
                        isActive
                    }
                }
                val ready = UiState.Ready(
                    place, transits, Transit.jdToMillis(tle0.epochJd), radius, DAYS, isGps = !other
                )
                if (other) otherCache = ready else hereCache = ready
                _state.value = ready
                if (quiet) _toast.value = str(if (other) R.string.transits_recalculated else R.string.position_updated)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (quiet) _toast.value = e.message ?: str(R.string.unexpected_error)
                else _state.value = UiState.Error(e.message ?: str(R.string.unexpected_error))
            } finally {
                // um cálculo cancelado por outro mais recente não apaga o indicador desse
                if (quiet && isCurrent()) _refreshing.value = false
            }
        }
    }
}

package com.matt.weather

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.matt.weather.data.Alert
import com.matt.weather.data.Forecast
import com.matt.weather.data.Loc
import com.matt.weather.data.Place
import com.matt.weather.data.RadarIndex
import com.matt.weather.data.Store
import com.matt.weather.data.StormCell
import com.matt.weather.data.WeatherApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class UiState(
    val place: Place = Place.DEFAULT,
    val recents: List<Place> = emptyList(),
    val forecast: Forecast? = null,
    val alerts: List<Alert> = emptyList(),
    val radar: RadarIndex? = null,
    /** Storm cells picked for [place]; empty when none, outside the US, or on failure. */
    val cells: List<StormCell> = emptyList(),
    val loading: Boolean = false,
    /** Showing a cached payload because the network did not answer. */
    val stale: Boolean = false,
    val fetchedAt: Long = 0L,
    val failed: Boolean = false,
    /** -1 = rolling 48 h, otherwise the index of the selected day. */
    val selectedDay: Int = -1,
    /** Length of the daily card: 10 or 5. */
    val dayCount: Int = 10,
    val message: String? = null
)

class WeatherVm(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    private val _state = MutableStateFlow(
        UiState(place = store.place, recents = store.recents, dayCount = store.dayCount)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _results = MutableStateFlow<List<Place>>(emptyList())
    val results: StateFlow<List<Place>> = _results.asStateFlow()

    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var radarLoad: Job? = null
    private var radarJob: Job? = null
    /** Last successful radar index fetch, epoch millis. */
    private var radarAt = 0L

    init {
        // Paint whatever was cached before the network is asked, so the app is
        // useful the instant it opens.
        store.cachedFor(store.place)?.let { (f, at) ->
            _state.update { it.copy(forecast = f, fetchedAt = at, stale = true) }
        }
        if (store.recents.isEmpty()) {
            store.pushRecent(Place.DEFAULT)
            _state.update { it.copy(recents = store.recents) }
        }
        refresh()
        loadRadar()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            val place = _state.value.place
            var ok = false
            for (attempt in 1..2) {
                try {
                    val raw = com.matt.weather.data.Net.getString(
                        WeatherApi.forecastUrl(place.lat, place.lon)
                    )
                    val f = WeatherApi.parseForecast(raw)
                    store.saveForecast(place, raw)
                    _state.update {
                        it.copy(
                            forecast = f, fetchedAt = f.fetchedAt,
                            stale = false, failed = false, loading = false
                        )
                    }
                    ok = true
                    break
                } catch (e: Exception) {
                    // One retry — a single dropped request on cell data should
                    // not leave a dead screen.
                    if (attempt == 1) delay(1500)
                }
            }
            if (!ok) {
                _state.update {
                    it.copy(
                        loading = false,
                        failed = it.forecast == null,
                        stale = it.forecast != null,
                        message = if (it.forecast != null) "Refresh failed — showing last data" else null
                    )
                }
            }
            loadAlerts()
        }
    }

    private fun loadAlerts() {
        viewModelScope.launch {
            val p = _state.value.place
            val list = try {
                WeatherApi.alerts(p.lat, p.lon)
            } catch (e: Exception) {
                emptyList() // any HTTP error = no alerts; NWS is occasionally down; silent by design
            }
            if (_state.value.place == p) _state.update { it.copy(alerts = list) }
        }
    }

    fun loadRadar() {
        val p = _state.value.place
        // IEM radar is US-only; the card says so instead of fetching.
        if (!WeatherApi.inRadarBox(p.lat, p.lon)) {
            loadCells()
            return
        }
        // Resume, the timer and the refresh button can all land together.
        if (radarLoad?.isActive == true) return
        radarLoad = viewModelScope.launch {
            val idx = try {
                WeatherApi.radar()
            } catch (e: Exception) {
                null
            }
            if (idx != null) {
                radarAt = System.currentTimeMillis()
                // An unchanged list leaves the overlays alone: no rebuild, no
                // flicker. A failed refresh keeps a working loop on screen.
                if (idx.key != _state.value.radar?.key) _state.update { it.copy(radar = idx) }
            }
            loadCells()
        }
    }

    /**
     * Storm cells for the current place. A failed refresh keeps the tracks
     * already drawn for this place; a first load that fails has none, silently.
     */
    private fun loadCells() {
        viewModelScope.launch {
            val p = _state.value.place
            val list = if (!WeatherApi.inRadarBox(p.lat, p.lon)) emptyList() else try {
                WeatherApi.stormCells(p.lat, p.lon)
            } catch (e: Exception) {
                return@launch // setPlace already cleared another place's cells
            }
            if (_state.value.place == p) _state.update { it.copy(cells = list) }
        }
    }

    /** Frames and cells every [RADAR_REFRESH_MS] while in the foreground. */
    private fun startRadarRefresh() {
        radarJob?.cancel()
        radarJob = viewModelScope.launch {
            while (true) {
                delay(RADAR_REFRESH_MS)
                loadRadar()
            }
        }
    }

    /** Called when the app leaves the foreground: no timers while backgrounded. */
    fun stopRadarRefresh() {
        radarJob?.cancel()
        radarJob = null
    }

    fun setPlace(p: Place) {
        store.place = p
        store.pushRecent(p)
        _state.update {
            it.copy(
                place = p,
                recents = store.recents,
                selectedDay = -1,
                alerts = emptyList(),
                cells = emptyList(),
                forecast = store.cachedFor(p)?.first,
                stale = store.cachedFor(p) != null
            )
        }
        _results.value = emptyList()
        refresh()
        // Frames are the same for any US place; only a first load (or a move in
        // from outside the coverage box) needs them fetched. Cells are per place.
        if (_state.value.radar == null) loadRadar() else loadCells()
    }

    fun selectDay(i: Int) {
        _state.update { it.copy(selectedDay = if (it.selectedDay == i || i == 0) -1 else i) }
    }

    fun toggleDays() {
        val n = if (_state.value.dayCount == 10) 5 else 10
        store.dayCount = n
        _state.update {
            it.copy(dayCount = n, selectedDay = if (it.selectedDay >= n) -1 else it.selectedDay)
        }
    }

    fun search(q: String) {
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(280)
            _results.value = try {
                WeatherApi.search(q)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun useGps() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!Loc.hasProvider(ctx)) {
                _state.update { it.copy(message = "Location is switched off") }
                return@launch
            }
            _state.update { it.copy(message = "Getting location…") }
            // Hard cap: getCurrentLocation can sit for ~30 s with no provider
            // actually reporting, and a progress toast must never hang.
            val fix = try {
                withTimeoutOrNull(15_000) { Loc.fix(ctx) }
            } catch (e: SecurityException) {
                null
            }
            if (fix == null) {
                _state.update { it.copy(message = "Could not get a fix") }
                return@launch
            }
            val p = Loc.describe(ctx, fix.latitude, fix.longitude)
            _state.update { it.copy(message = null) }
            setPlace(p)
        }
    }

    fun notify(msg: String) = _state.update { it.copy(message = msg) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Called when the app comes back to the foreground. */
    fun refreshIfStale() {
        val age = System.currentTimeMillis() - _state.value.fetchedAt
        if (_state.value.forecast == null || age > STALE_MS) refresh()
        // Catch up at once if the loop has gone stale, then resume the cadence.
        if (System.currentTimeMillis() - radarAt > RADAR_REFRESH_MS) loadRadar()
        startRadarRefresh()
    }

    /** The refresh button: forecast, alerts, radar frames and cells. */
    fun refreshAll() {
        refresh()
        loadRadar()
    }

    private companion object {
        const val STALE_MS = 10 * 60 * 1000L
        const val RADAR_REFRESH_MS = 5 * 60 * 1000L
    }
}

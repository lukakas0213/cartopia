package com.roadcanvas.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roadcanvas.app.car.Announcer
import com.roadcanvas.app.car.DemoRouteFeed
import com.roadcanvas.app.car.Gear
import com.roadcanvas.app.car.LocationFeed
import com.roadcanvas.app.car.PleosLocationFeed
import com.roadcanvas.app.car.VehicleSignals
import com.roadcanvas.app.data.VillageStore
import com.roadcanvas.app.drive.MileBreakdown
import com.roadcanvas.app.drive.Miles
import com.roadcanvas.app.drive.SafetyScorer
import com.roadcanvas.app.geo.GeoPoint
import com.roadcanvas.app.geo.MapPoint
import com.roadcanvas.app.geo.distanceMeters
import com.roadcanvas.app.map.BlockMap
import com.roadcanvas.app.map.BlockRevealer
import com.roadcanvas.app.map.RouteBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { DRIVE, RESULT, VILLAGE }

data class TripLive(
    val distanceKm: Double,
    val elapsedSec: Long,
    val score: Int,
    val newBlocks: Int,
    val estimatedMiles: Int
)

data class TripResult(
    val miles: MileBreakdown,
    val score: Int,
    val events: List<SafetyScorer.Event>,
    val durationSec: Long,
    val route: List<MapPoint>
)

data class UiState(
    val loaded: Boolean = false,
    val screen: Screen = Screen.DRIVE,
    val driving: Boolean = false,
    val demo: Boolean = false,
    val vehicleConnected: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val miles: Int = 0,
    val revealed: Set<Int> = emptySet(),
    val history: List<List<MapPoint>> = emptyList(),
    val current: List<MapPoint> = emptyList(),
    val car: MapPoint? = null,
    val trip: TripLive? = null,
    val result: TripResult? = null,
    val exploredPercent: Int = 0
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    lateinit var blockMap: BlockMap
        private set
    private lateinit var routeBook: RouteBook
    private lateinit var revealer: BlockRevealer

    private val store = VillageStore(app)
    private val vehicle = VehicleSignals(app)
    private val announcer = Announcer(app)
    private val liveFeed = PleosLocationFeed(app)

    private var feed: LocationFeed? = null
    private var scorer = SafetyScorer()
    private val tripGeo = ArrayList<GeoPoint>()
    private var tripMeters = 0.0
    private var tripNewBlocks = 0
    private var tripStartMillis = 0L
    private var tripStartOdometer: Float? = null
    private var lastBlockAnnounce = 0L

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val assets = app.assets
                blockMap = BlockMap.parse(assets.open("map/blocks.json").bufferedReader().readText())
                routeBook = RouteBook.parse(assets.open("map/routes.json").bufferedReader().readText())
                revealer = BlockRevealer(blockMap)
                if (!store.isSeeded) {
                    // First launch of the demo build: earlier drives around 응봉·성수 are already on the map.
                    routeBook.history.forEach { r -> revealer.addPolyline(r.map(blockMap.viewport::toMap)) }
                    store.seed(routeBook.history, revealer.revealed.toSet(), startMiles = 1240)
                } else {
                    revealer.restore(store.revealedBlocks)
                }
            }
            _state.update {
                it.copy(
                    loaded = true,
                    miles = store.miles,
                    revealed = revealer.revealed.toSet(),
                    history = store.routes.map { r -> r.map(blockMap.viewport::toMap) },
                    exploredPercent = explored()
                )
            }
            announcer.start()
            val connected = vehicle.start()
            _state.update { it.copy(vehicleConnected = connected) }
            if (connected) watchVehicle()
        }
    }

    private fun watchVehicle() {
        viewModelScope.launch {
            vehicle.gear.collect { gear ->
                _state.update { it.copy(gear = gear) }
                when {
                    gear == Gear.DRIVE && !_state.value.driving -> startTrip(demo = false)
                    gear == Gear.PARK && _state.value.driving && !_state.value.demo -> endTrip()
                }
            }
        }
        viewModelScope.launch { vehicle.brakePressed.collect { scorer.onBrakePedal(it, System.currentTimeMillis()) } }
        viewModelScope.launch { vehicle.turnSignalOn.collect { scorer.onTurnSignal(it, System.currentTimeMillis()) } }
        viewModelScope.launch {
            vehicle.seatBeltWarning.collect { scorer.onSeatBeltWarning(it, System.currentTimeMillis(), _state.value.driving) }
        }
    }

    /** Replays the bundled 왕십리역 → 응봉역 → 집 drive (for the emulator and the demo video). */
    fun startDemoDrive() {
        if (_state.value.driving || !_state.value.loaded) return
        startTrip(demo = true)
    }

    private fun startTrip(demo: Boolean) {
        scorer = SafetyScorer()
        tripGeo.clear()
        tripMeters = 0.0
        tripNewBlocks = 0
        tripStartMillis = System.currentTimeMillis()
        tripStartOdometer = if (demo) null else vehicle.odometerKm.value
        _state.update { it.copy(screen = Screen.DRIVE, driving = true, demo = demo, current = emptyList(), car = null, trip = liveSummary(0)) }

        val f: LocationFeed = if (demo) {
            DemoRouteFeed(
                route = routeBook.demo,
                scope = viewModelScope,
                onTurnSignal = { on, t -> scorer.onTurnSignal(on, t) },
                onBrakePedal = { on, t -> scorer.onBrakePedal(on, t) }
            )
        } else {
            liveFeed
        }
        feed = f
        f.start(
            onFix = { p -> viewModelScope.launch { onFix(p) } },
            onEnd = { viewModelScope.launch { endTrip() } }
        )
    }

    private fun onFix(p: GeoPoint) {
        if (!_state.value.driving) return
        val point = blockMap.viewport.toMap(p)
        val prev = tripGeo.lastOrNull()
        tripGeo += p
        scorer.onLocation(p)
        var opened = emptyList<Int>()
        if (prev != null) {
            tripMeters += distanceMeters(prev, p)
            opened = revealer.addSegment(blockMap.viewport.toMap(prev), point)
            tripNewBlocks += opened.size
        }
        if (opened.isNotEmpty() && System.currentTimeMillis() - lastBlockAnnounce > 8_000) {
            lastBlockAnnounce = System.currentTimeMillis()
            announcer.say("새 블록이 열렸어요")
        }
        val elapsed = (p.timeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()) - (tripGeo.first().timeMillis.takeIf { it > 0 } ?: tripStartMillis)
        _state.update {
            it.copy(
                current = it.current + point,
                car = point,
                revealed = if (opened.isEmpty()) it.revealed else revealer.revealed.toSet(),
                exploredPercent = if (opened.isEmpty()) it.exploredPercent else explored(),
                trip = liveSummary(elapsed / 1000)
            )
        }
    }

    private fun liveSummary(elapsedSec: Long): TripLive {
        val m = Miles.forTrip(tripMeters / 1000, null, scorer.multiplier, tripNewBlocks)
        return TripLive(tripMeters / 1000, elapsedSec, scorer.score, tripNewBlocks, m.total)
    }

    private fun endTrip() {
        if (!_state.value.driving) return
        feed?.stop()
        feed = null
        val odoKm = tripStartOdometer?.let { start -> vehicle.odometerKm.value?.let { it - start }?.toDouble() }
        val breakdown = Miles.forTrip(tripMeters / 1000, odoKm, scorer.multiplier, tripNewBlocks)
        val duration = _state.value.trip?.elapsedSec ?: 0
        store.miles = store.miles + breakdown.total
        store.revealedBlocks = revealer.revealed.toSet()
        if (tripGeo.size > 1) store.routes = store.routes + listOf(tripGeo.map { it.copy(timeMillis = 0) })
        val result = TripResult(breakdown, scorer.score, scorer.events.toList(), duration, _state.value.current)
        _state.update {
            it.copy(
                driving = false,
                demo = false,
                screen = Screen.RESULT,
                miles = store.miles,
                history = if (it.current.size > 1) it.history + listOf(it.current) else it.history,
                current = emptyList(),
                result = result,
                trip = null
            )
        }
        announcer.say(
            if (breakdown.withheld) "주행 거리가 차량 기록과 달라서 이번 주행의 마일은 보류했어요"
            else "오늘 안전 점수 ${scorer.score}점, ${breakdown.total}마일을 모았어요"
        )
    }

    fun openVillage() {
        if (_state.value.driving) return // the village only opens while parked
        _state.update { it.copy(screen = Screen.VILLAGE) }
    }

    fun openMap() = _state.update { it.copy(screen = Screen.DRIVE) }

    // ---- WebView bridge (called from the village page's JS thread) ----

    fun milesForVillage(): Int = store.miles

    fun earnMiles(n: Int) {
        if (n <= 0) return
        store.miles = store.miles + n
        _state.update { it.copy(miles = store.miles) }
    }

    fun spendMiles(n: Int): Boolean {
        if (n <= 0 || store.miles < n) return false
        store.miles = store.miles - n
        _state.update { it.copy(miles = store.miles) }
        return true
    }

    fun villageState(): String = store.villageState

    fun saveVillageState(json: String) {
        store.villageState = json
    }

    private fun explored(): Int = revealer.revealed.size * 100 / blockMap.blocks.size

    override fun onCleared() {
        feed?.stop()
        vehicle.stop()
        announcer.stop()
    }
}

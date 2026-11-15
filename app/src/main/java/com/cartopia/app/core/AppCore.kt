package com.cartopia.app.core

import android.app.Application
import com.cartopia.app.car.Announcer
import com.cartopia.app.car.DemoRouteFeed
import com.cartopia.app.car.Gear
import com.cartopia.app.car.LocationFeed
import com.cartopia.app.car.PleosLocationFeed
import com.cartopia.app.car.VehicleSignals
import com.cartopia.app.data.VillageStore
import com.cartopia.app.drive.MileBreakdown
import com.cartopia.app.drive.Miles
import com.cartopia.app.drive.SafetyScorer
import com.cartopia.app.geo.GeoPoint
import com.cartopia.app.geo.MapPoint
import com.cartopia.app.geo.distanceMeters
import com.cartopia.app.map.BlockMap
import com.cartopia.app.map.BlockRevealer
import com.cartopia.app.map.RoadSnapper
import com.cartopia.app.map.RouteBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The app's state and the trip recorder, one per process ([com.cartopia.app.CartopiaApp] owns it).
 * It does not live in the Activity: on Pleos the app shares the screen with the navigation app and is often pushed
 * off it, and a drive must keep recording while the driver looks at the navigation map.
 */
class AppCore(app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Messages for the village page, as JSON objects; the WebView passes each one to `window.__cartopia`. */
    private val _page = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val pageEvents: SharedFlow<String> = _page.asSharedFlow()

    lateinit var blockMap: BlockMap
        private set
    private lateinit var routeBook: RouteBook
    private lateinit var revealer: BlockRevealer
    private lateinit var snapper: RoadSnapper

    private val store = VillageStore(app)
    private val vehicle = VehicleSignals(app)
    private val announcer = Announcer(app)
    private val liveFeed = PleosLocationFeed(app)

    private var feed: LocationFeed? = null
    private var scorer = SafetyScorer()
    private val tripGeo = ArrayList<GeoPoint>()
    private var tripMeters = 0.0
    /** Ground of the blocks driven along: shown in full, and capped per block for the bonus. */
    private var tripNewM2 = 0.0
    private var tripBonusM2 = 0.0
    /** Ground opened around the drive; on the map, but no bonus. */
    private var tripNearM2 = 0.0
    /** The next opened-area total (m²) the voice reads out. */
    private var nextAreaCall = FIRST_AREA_CALL
    /** Nearby blocks of this trip; driving along one later still earns its bonus. */
    private val tripNearIds = HashSet<Int>()
    /** The previous fix, moved onto its road. */
    private var lastPoint: MapPoint? = null
    private var tripStartMillis = 0L
    private var tripStartOdometer: Float? = null
    private var lastBlockAnnounce = 0L
    private var lastSnapshot = 0L
    @Volatile private var unseenSummary: String? = null

    /** Fixes are handled one at a time off the main thread: the first fix near a new map tile reads that tile. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val tripThread = Dispatchers.Default.limitedParallelism(1)

    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                val assets = app.assets
                blockMap = BlockMap.load { name -> assets.open("map/seoul/$name").bufferedReader().use { it.readText() } }
                routeBook = RouteBook.parse(assets.open("map/routes.json").bufferedReader().readText())
                revealer = BlockRevealer(blockMap)
                snapper = RoadSnapper(blockMap)
                // block ids belong to one build of the map; a new map starts the village over
                if (store.isSeeded && store.mapVersion != blockMap.version) store.reset()
                if (!store.isSeeded) {
                    // First launch: an empty map and no coins; everything on the map comes from the user's own drives.
                    store.seed(emptyList(), emptySet(), startMiles = 0, mapVersion = blockMap.version)
                } else {
                    revealer.restore(store.revealedBlocks)
                }
            }
            val resumed = store.tripInProgress?.let { restoreTrip(it) } ?: false
            _state.update { it.copy(loaded = true, miles = store.miles, revealed = revealer.revealed.toSet()) }
            announcer.start()
            val connected = vehicle.start()
            _state.update { it.copy(vehicleConnected = connected) }
            if (connected) watchVehicle()
            // a drive cut off by the process being killed: the gear decides whether it goes on (D) or is saved (P);
            // without a vehicle (a demo or QA drive) it is simply saved as far as it got
            if (resumed && (!connected || _state.value.demo)) scope.launch(tripThread) { endTrip() }
        }
    }

    private fun watchVehicle() {
        scope.launch {
            vehicle.gear.collect { gear ->
                _state.update { it.copy(gear = gear) }
                val s = _state.value
                when {
                    gear == Gear.DRIVE && s.mode != Mode.DRIVE -> startTrip(demo = false)
                    gear == Gear.DRIVE && s.mode == Mode.DRIVE && feed == null -> followLiveFeed()
                    gear == Gear.PARK && s.mode == Mode.DRIVE && !s.demo -> scope.launch(tripThread) { endTrip() }
                }
            }
        }
        scope.launch { vehicle.brakePressed.collect { scorer.onBrakePedal(it, System.currentTimeMillis()) } }
        scope.launch { vehicle.turnSignalOn.collect { scorer.onTurnSignal(it, System.currentTimeMillis()) } }
        scope.launch {
            vehicle.seatBeltWarning.collect { scorer.onSeatBeltWarning(it, System.currentTimeMillis(), _state.value.driving) }
        }
    }

    /** Replays the bundled drive from 현대자동차 강남대로 사옥 (스케일타워) to 청담 한강 (for the emulator and the demo video). */
    fun startDemoDrive() {
        val s = _state.value
        if (s.mode != Mode.EDIT || !s.loaded) return
        startTrip(demo = true)
    }

    /**
     * QA: drives [route] the same way as the demo drive (adb `am start --es qa_route "lat,lon;..."`).
     * Ignored when a real vehicle is connected, so it can never replace a live trip.
     */
    fun startQaDrive(route: List<GeoPoint>, speedUp: Double) {
        val s = _state.value
        if (s.mode != Mode.EDIT || !s.loaded || s.vehicleConnected || route.size < 2) return
        startTrip(demo = true, route = route, speedUp = speedUp)
    }

    private fun startTrip(demo: Boolean, route: List<GeoPoint>? = null, speedUp: Double = 4.0) {
        scorer = SafetyScorer()
        tripGeo.clear()
        tripMeters = 0.0
        tripNewM2 = 0.0
        tripBonusM2 = 0.0
        tripNearM2 = 0.0
        nextAreaCall = FIRST_AREA_CALL
        tripNearIds.clear()
        lastPoint = null
        snapper.reset()
        tripStartMillis = System.currentTimeMillis()
        tripStartOdometer = if (demo) null else vehicle.odometerKm.value
        lastSnapshot = 0L
        _state.update { it.copy(mode = Mode.DRIVE, demo = demo, car = null, trip = liveSummary(0)) }
        push("mode", JSONObject().put("mode", Mode.DRIVE.key))

        if (demo) {
            follow(
                DemoRouteFeed(
                    route = route ?: routeBook.demo,
                    scope = scope,
                    onTurnSignal = { on, t -> scorer.onTurnSignal(on, t) },
                    onBrakePedal = { on, t -> scorer.onBrakePedal(on, t) },
                    speedUp = speedUp
                )
            )
        } else {
            followLiveFeed()
        }
    }

    private fun followLiveFeed() = follow(liveFeed)

    private fun follow(f: LocationFeed) {
        feed = f
        f.start(
            onFix = { p -> scope.launch(tripThread) { onFix(p) } },
            onEnd = { scope.launch(tripThread) { endTrip() } }
        )
    }

    /** Written every few seconds while driving, so a killed process loses at most that much of the drive. */
    private fun snapshotTrip() {
        val geo = JSONArray()
        tripGeo.forEach { geo.put(JSONArray().put(it.lat).put(it.lon).put(it.timeMillis)) }
        val events = JSONArray()
        scorer.events.forEach { events.put(JSONArray().put(it.kind.name).put(it.atMillis)) }
        store.revealedBlocks = revealer.revealed.toSet()
        store.tripInProgress = JSONObject()
            .put("demo", _state.value.demo).put("start", tripStartMillis)
            .put("odometer", tripStartOdometer?.toDouble() ?: JSONObject.NULL)
            .put("meters", tripMeters).put("newM2", tripNewM2).put("bonusM2", tripBonusM2).put("nearM2", tripNearM2)
            .put("nearIds", JSONArray(tripNearIds.toList())).put("nextCall", nextAreaCall)
            .put("geo", geo).put("events", events)
            .toString()
    }

    /** Picks a drive back up from [snapshotTrip]; the map's blocks were stored with it. */
    private fun restoreTrip(raw: String): Boolean = runCatching {
        val o = JSONObject(raw)
        scorer = SafetyScorer()
        val events = o.getJSONArray("events")
        scorer.restore((0 until events.length()).map { i ->
            val e = events.getJSONArray(i)
            SafetyScorer.Event(SafetyScorer.Kind.valueOf(e.getString(0)), e.getLong(1))
        })
        tripGeo.clear()
        val geo = o.getJSONArray("geo")
        for (i in 0 until geo.length()) {
            val g = geo.getJSONArray(i)
            tripGeo += GeoPoint(g.getDouble(0), g.getDouble(1), g.getLong(2))
        }
        tripMeters = o.getDouble("meters")
        tripNewM2 = o.getDouble("newM2")
        tripBonusM2 = o.getDouble("bonusM2")
        tripNearM2 = o.getDouble("nearM2")
        tripNearIds.clear()
        val near = o.getJSONArray("nearIds")
        for (i in 0 until near.length()) tripNearIds += near.getInt(i)
        nextAreaCall = o.getDouble("nextCall")
        tripStartMillis = o.getLong("start")
        tripStartOdometer = if (o.isNull("odometer")) null else o.getDouble("odometer").toFloat()
        snapper.reset()
        lastPoint = tripGeo.lastOrNull()?.let { snapper.snap(blockMap.viewport.toMap(it)) }
        lastSnapshot = 0L
        val elapsed = ((tripGeo.lastOrNull()?.timeMillis ?: tripStartMillis) - tripStartMillis) / 1000
        _state.update { it.copy(mode = Mode.DRIVE, demo = o.getBoolean("demo"), car = lastPoint, trip = liveSummary(elapsed)) }
        true
    }.getOrElse {
        store.tripInProgress = null
        false
    }

    private fun onFix(p: GeoPoint) {
        if (!_state.value.driving) return
        val point = snapper.snap(blockMap.viewport.toMap(p))
        val prev = tripGeo.lastOrNull()
        val prevPoint = lastPoint
        lastPoint = point
        tripGeo += p
        scorer.onLocation(p)
        var opened = BlockRevealer.Opened(emptyList(), emptyList())
        if (prev != null) {
            tripMeters += distanceMeters(prev, p)
            if (prevPoint != null) opened = revealer.addSegment(prevPoint, point)
            // a nearby block of this trip that is now driven along moves over to the bonus
            val movedOver = opened.drivenOver.filter { tripNearIds.remove(it) }
            movedOver.forEach { tripNearM2 -= revealer.areaM2(it) }
            (opened.direct + movedOver).forEach { val m2 = revealer.areaM2(it); tripNewM2 += m2; tripBonusM2 += Miles.bonusM2(m2) }
            opened.nearby.forEach { tripNearM2 += revealer.areaM2(it) }
            tripNearIds += opened.nearby
        }
        val openedM2 = tripNewM2 + tripNearM2
        if (openedM2 >= nextAreaCall && System.currentTimeMillis() - lastBlockAnnounce > AREA_CALL_GAP_MS) {
            lastBlockAnnounce = System.currentTimeMillis()
            announcer.say("탐험 면적이 ${spokenArea(openedM2)}가 되었어요")
            while (nextAreaCall <= openedM2) nextAreaCall = if (nextAreaCall < AREA_CALL_STEP) AREA_CALL_STEP else nextAreaCall + AREA_CALL_STEP
        }
        val elapsed = (p.timeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()) - (tripGeo.first().timeMillis.takeIf { it > 0 } ?: tripStartMillis)
        val live = liveSummary(elapsed / 1000)
        _state.update {
            it.copy(car = point, revealed = if (opened.isEmpty()) it.revealed else revealer.revealed.toSet(), trip = live)
        }
        push("car", JSONObject().put("x", point.x.toDouble()).put("y", point.y.toDouble()))
        if (!opened.isEmpty()) push("reveal", JSONObject().put("ids", JSONArray(opened.all)))
        push("trip", tripJson(live))
        if (System.currentTimeMillis() - lastSnapshot > SNAPSHOT_MS) {
            lastSnapshot = System.currentTimeMillis()
            snapshotTrip()
        }
    }

    private fun liveSummary(elapsedSec: Long): TripLive {
        val m = Miles.forTrip(tripMeters / 1000, null, scorer.multiplier, tripNewM2, tripBonusM2)
        return TripLive(tripMeters / 1000, elapsedSec, scorer.score, tripNewM2 + tripNearM2, m.total)
    }

    /** Gear P: the trip is saved without asking, then the page shows the summary over the decorating map. */
    private fun endTrip() {
        if (!_state.value.driving) return
        feed?.stop()
        feed = null
        val odoKm = tripStartOdometer?.let { start -> vehicle.odometerKm.value?.let { it - start }?.toDouble() }
        val breakdown = Miles.forTrip(tripMeters / 1000, odoKm, scorer.multiplier, tripNewM2, tripBonusM2)
        val duration = _state.value.trip?.elapsedSec ?: 0
        val score = scorer.score
        val events = scorer.events.toList()
        val nearM2 = tripNearM2

        store.miles = store.miles + breakdown.total
        store.revealedBlocks = revealer.revealed.toSet()
        if (tripGeo.size > 1) store.routes = store.routes + listOf(tripGeo.map { it.copy(timeMillis = 0) })
        // the village grows its items by this distance the next time the page asks
        store.addPendingTrip(breakdown.km, score)
        store.tripInProgress = null

        _state.update { it.copy(mode = Mode.SAVING, demo = false, miles = store.miles, trip = null) }
        push("mode", JSONObject().put("mode", Mode.SAVING.key))
        announcer.say(
            if (breakdown.withheld) "주행거리 차이가 커서 코인을 지급하지 않았어요"
            else "안전 점수 ${score}점, ${breakdown.total}코인을 받았어요",
            maxWaitMs = 60_000 // parked, so navigation is usually done talking; worth waiting for
        )
        scope.launch {
            delay(SAVE_ANIMATION_MS) // everything is already stored; this only lets the steps play out
            _state.update { it.copy(mode = Mode.EDIT) }
            val summary = summaryJson(breakdown, nearM2, score, events, duration)
            // nobody to show it to (the app was not on screen when the drive was saved): the page asks on its next start
            if (_page.subscriptionCount.value == 0) unseenSummary = summary.toString() else push("summary", summary)
        }
    }

    private fun tripJson(t: TripLive) = JSONObject()
        .put("km", t.distanceKm).put("sec", t.elapsedSec).put("score", t.score)
        .put("areaM2", t.areaM2).put("miles", t.estimatedMiles)

    private fun summaryJson(m: MileBreakdown, nearM2: Double, score: Int, events: List<SafetyScorer.Event>, durationSec: Long): JSONObject {
        val grouped = JSONArray()
        events.groupBy { it.kind }.forEach { (kind, list) ->
            grouped.put(JSONObject().put("label", kind.label).put("count", list.size).put("penalty", kind.penalty * list.size))
        }
        return JSONObject()
            .put("km", m.km).put("minutes", (durationSec / 60.0).roundToInt()).put("score", score)
            .put("multiplier", m.multiplier).put("newM2", m.newM2).put("nearM2", nearM2).put("base", m.base)
            .put("bonus", m.bonus).put("total", m.total).put("withheld", m.withheld).put("events", grouped)
    }

    private fun push(type: String, body: JSONObject) {
        _page.tryEmit(body.put("t", type).toString())
    }

    // ---- WebView bridge (called from the village page's JS thread) ----

    /** Everything the page needs to draw itself, including a drive already in progress. */
    fun pageInit(): String {
        val s = _state.value
        val o = JSONObject()
            .put("mode", s.mode.key)
            .put("vehicle", s.vehicleConnected)
            .put("miles", store.miles)
            .put("revealed", JSONArray(s.revealed.toList()))
            .put("state", store.villageState)
        s.car?.let { o.put("car", JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble())) }
        s.trip?.let { o.put("trip", tripJson(it)) }
        unseenSummary?.let { o.put("summary", JSONObject(it)); unseenSummary = null }
        return o.toString()
    }

    fun milesForVillage(): Int = store.miles

    fun earnMiles(n: Int) {
        if (n <= 0) return
        store.miles = store.miles + n
        _state.update { it.copy(miles = store.miles) }
    }

    fun spendMiles(n: Int): Boolean {
        // decorating is parked-only; a late tap from the page while driving must not spend anything
        if (n <= 0 || _state.value.driving || store.miles < n) return false
        store.miles = store.miles - n
        _state.update { it.copy(miles = store.miles) }
        return true
    }

    fun saveVillageState(json: String) {
        store.villageState = json
    }

    fun takePendingTrips(): String = store.takePendingTrips()

    /** "3만 제곱미터" under 1 km², "1.5제곱킬로미터" above, rounded the way the drive card shows it. */
    private fun spokenArea(m2: Double): String =
        if (m2 < 1_000_000) "${(m2 / 10_000).roundToInt()}만 제곱미터"
        else "${"%.1f".format(m2 / 1_000_000).removeSuffix(".0")}제곱킬로미터"

    private companion object {
        /**
         * The voice reads the opened area out at 0.1 km², then every 1 km², and never twice within 3 minutes:
         * the driver is listening to the navigation app, so this stays a rare aside.
         */
        const val FIRST_AREA_CALL = 100_000.0
        const val AREA_CALL_STEP = 1_000_000.0
        const val AREA_CALL_GAP_MS = 180_000L
        const val SAVE_ANIMATION_MS = 1_600L
        const val SNAPSHOT_MS = 5_000L
    }
}

package com.cartopia.app.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import com.cartopia.app.drive.SafetyScorer
import com.cartopia.app.geo.GeoPoint
import com.cartopia.app.geo.bearingDegrees
import com.cartopia.app.geo.distanceMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

interface LocationFeed {
    fun start(onFix: (GeoPoint) -> Unit, onEnd: () -> Unit = {})
    fun stop()
}

/** Live vehicle position from the Pleos FusedLocation SDK. */
class PleosLocationFeed(context: Context) : LocationFeed {

    private val fused = FusedLocation(context.applicationContext)
    private var listener: FusedLocationListener? = null

    fun isAvailable(): Boolean = runCatching { fused.isSupported() }.getOrDefault(false)

    override fun start(onFix: (GeoPoint) -> Unit, onEnd: () -> Unit) {
        val l = object : FusedLocationListener {
            override fun onChanged(data: LocationData?) {
                if (data == null) return
                onFix(GeoPoint(data.latitude, data.longitude, data.timestampMillis))
            }

            override fun onError(e: Exception) {
                Log.w(TAG, "FusedLocation error", e)
            }
        }
        listener = l
        runCatching {
            fused.initialize()
            fused.registerFusedLocationCallback(l)
        }.onFailure { Log.w(TAG, "FusedLocation unavailable", it) }
    }

    override fun stop() {
        listener?.let { l -> runCatching { fused.unregisterFusedLocationCallback(l) } }
        listener = null
        runCatching { fused.release() }
    }

    private companion object {
        const val TAG = "PleosLocationFeed"
    }
}

/**
 * Replays a bundled route as if driving it, for emulator demos.
 * The whole drive is planned up front as 1 Hz fixes with simulated timestamps, like a real GPS: the car eases off
 * for bends, makes one hard stop on the brake pedal, and signals before every turn but one.
 * [speedUp] only shortens the wall-clock wait.
 */
class DemoRouteFeed(
    private val route: List<GeoPoint>,
    private val scope: CoroutineScope,
    private val onTurnSignal: (Boolean, Long) -> Unit,
    private val onBrakePedal: (Boolean, Long) -> Unit,
    private val speedUp: Double = 4.0
) : LocationFeed {

    data class Step(val fix: GeoPoint, val signal: Boolean, val brake: Boolean)

    private var job: Job? = null

    override fun start(onFix: (GeoPoint) -> Unit, onEnd: () -> Unit) {
        val steps = plan(route, System.currentTimeMillis())
        job = scope.launch {
            var prev: Long? = null
            replay(steps, onTurnSignal, onBrakePedal) { p ->
                prev?.let { delay(((p.timeMillis - it) / speedUp).toLong()) }
                prev = p.timeMillis
                onFix(p)
            }
            onEnd()
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val CRUISE_MS = 11.0 // about 40 km/h
        private const val EASE_MS2 = 1.5 // ordinary speeding up and slowing down
        private const val HARD_MS2 = 6.5 // the one hard stop, well past SafetyScorer's threshold
        private const val GRID_M = 2.0
        private const val STOP_SEC = 4.0
        private const val SIGNAL_BEFORE_MS = 6_000L
        private const val SIGNAL_AFTER_MS = 1_000L

        /** Plans the drive so that [SafetyScorer] finds exactly one hard brake and one turn without a signal. */
        fun plan(route: List<GeoPoint>, startMillis: Long): List<Step> {
            val pts = route.filterIndexed { i, p -> i == 0 || distanceMeters(route[i - 1], p) > 0.5 }
            val cum = DoubleArray(pts.size)
            for (i in 1 until pts.size) cum[i] = cum[i - 1] + distanceMeters(pts[i - 1], pts[i])
            val total = cum.last()
            fun at(s: Double): GeoPoint {
                val d = s.coerceIn(0.0, total)
                var k = cum.indexOfLast { it <= d }.coerceAtMost(pts.size - 2)
                if (k < 0) k = 0
                val f = ((d - cum[k]) / (cum[k + 1] - cum[k])).coerceIn(0.0, 1.0)
                return GeoPoint(pts[k].lat + (pts[k + 1].lat - pts[k].lat) * f, pts[k].lon + (pts[k + 1].lon - pts[k].lon) * f)
            }

            // Speed along the route: slower through bends, gentle ramps, a hard stop on a straight about 40% in.
            val n = ceil(total / GRID_M).toInt() + 1
            val grid = DoubleArray(n) { min(it * GRID_M, total) }
            val v = DoubleArray(n) { i ->
                val a = at(grid[i] - 12)
                val b = at(grid[i])
                val c = at(grid[i] + 12)
                if (distanceMeters(a, b) < 3 || distanceMeters(b, c) < 3) return@DoubleArray CRUISE_MS
                val bend = abs(angleDiff(bearingDegrees(a, b), bearingDegrees(b, c)))
                when {
                    bend > 60 -> 4.5
                    bend > 30 -> 6.5
                    bend > 15 -> 9.0
                    else -> CRUISE_MS
                }
            }
            v[0] = 0.0
            v[n - 1] = 0.0
            val stopAt = (n * 2 / 5 until n * 3 / 5).firstOrNull { i -> (i - 30..i + 10).all { v[it] == CRUISE_MS } } ?: (n * 2 / 5)
            v[stopAt] = 0.0
            for (i in n - 2 downTo 0) {
                val a = if (i in stopAt - 15 until stopAt) HARD_MS2 else EASE_MS2
                v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + 2 * a * (grid[i + 1] - grid[i])))
            }
            for (i in 1 until n) v[i] = min(v[i], sqrt(v[i - 1] * v[i - 1] + 2 * EASE_MS2 * (grid[i] - grid[i - 1])))

            // (time, distance) knots, holding still at the stop.
            val knotT = ArrayList<Double>()
            val knotS = ArrayList<Double>()
            var t = 0.0
            for (i in 0 until n) {
                if (i > 0) {
                    val ds = grid[i] - grid[i - 1]
                    if (ds > 0) t += ds / ((v[i - 1] + v[i]) / 2)
                }
                knotT += t
                knotS += grid[i]
                if (i == stopAt) {
                    t += STOP_SEC
                    knotT += t
                    knotS += grid[i]
                }
            }
            val brakeFrom = knotT[(stopAt - 15 until stopAt).first { v[it] < CRUISE_MS } - 1]
            val brakeTo = knotT[stopAt + 1]

            var k = 0
            val fixes = (0..ceil(t).toInt()).map { sec ->
                val ts = min(sec.toDouble(), t)
                while (k < knotT.size - 2 && knotT[k + 1] <= ts) k++
                val span = knotT[k + 1] - knotT[k]
                val s = if (span <= 0) knotS[k] else knotS[k] + (knotS[k + 1] - knotS[k]) * (ts - knotT[k]) / span
                ts to at(s).copy(timeMillis = startMillis + (ts * 1000).toLong())
            }

            // Signal for every turn the scorer notices except the second; repeat until no other turn is penalised.
            val signalled = ArrayList<Long>()
            var skipped: Long? = null
            var steps = emptyList<Step>()
            repeat(6) {
                steps = fixes.map { (ts, p) ->
                    val ms = p.timeMillis
                    Step(
                        fix = p,
                        signal = signalled.any { ms in it - SIGNAL_BEFORE_MS..it + SIGNAL_AFTER_MS },
                        brake = ts in brakeFrom..brakeTo
                    )
                }
                val turns = score(steps).events.filter { it.kind == SafetyScorer.Kind.TURN_WITHOUT_SIGNAL }.map { it.atMillis }
                if (skipped == null) skipped = turns.getOrNull(1)
                val unwanted = turns.filter { it != skipped }
                if (unwanted.isEmpty()) return steps
                signalled += unwanted
            }
            return steps
        }

        /** Scores a planned drive the same way the app does live. */
        fun score(steps: List<Step>): SafetyScorer {
            val scorer = SafetyScorer()
            replay(steps, scorer::onTurnSignal, { on, t -> scorer.onBrakePedal(on, t) }) { scorer.onLocation(it) }
            return scorer
        }

        internal inline fun replay(
            steps: List<Step>,
            onTurnSignal: (Boolean, Long) -> Unit,
            onBrakePedal: (Boolean, Long) -> Unit,
            onFix: (GeoPoint) -> Unit
        ) {
            var signal = false
            var brake = false
            for (s in steps) {
                val t = s.fix.timeMillis
                if (s.signal != signal) { signal = s.signal; onTurnSignal(signal, t) }
                if (s.brake != brake) { brake = s.brake; onBrakePedal(brake, t) }
                onFix(s.fix)
            }
            val end = steps.lastOrNull()?.fix?.timeMillis ?: return
            if (signal) onTurnSignal(false, end)
            if (brake) onBrakePedal(false, end)
        }

        private fun angleDiff(a: Double, b: Double): Double = ((b - a + 540) % 360) - 180
    }
}

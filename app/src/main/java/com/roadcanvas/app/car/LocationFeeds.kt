package com.roadcanvas.app.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import com.roadcanvas.app.geo.GeoPoint
import com.roadcanvas.app.geo.bearingDegrees
import com.roadcanvas.app.geo.distanceMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
 * Fixes carry simulated timestamps at real driving speed; [speedUp] only shortens the wall-clock wait.
 * It also plays the driver's part: the turn signal goes on before most turns, and one stop is a hard brake.
 */
class DemoRouteFeed(
    private val route: List<GeoPoint>,
    private val scope: CoroutineScope,
    private val onTurnSignal: (Boolean, Long) -> Unit,
    private val onBrakePedal: (Boolean, Long) -> Unit,
    private val speedUp: Double = 4.0,
    private val cruiseMs: Double = 11.0
) : LocationFeed {

    private var job: Job? = null

    override fun start(onFix: (GeoPoint) -> Unit, onEnd: () -> Unit) {
        val turns = turnIndices()
        val skippedSignal = turns.getOrNull(1)
        val hardBrakeAt = route.size * 2 / 5
        job = scope.launch {
            var simTime = System.currentTimeMillis()
            var speed = cruiseMs
            route.forEachIndexed { i, p ->
                if (i > 0) {
                    val d = distanceMeters(route[i - 1], p)
                    val dtMs = (d / speed * 1000).toLong().coerceAtLeast(1)
                    simTime += dtMs
                    delay((dtMs / speedUp).toLong())
                }
                val upcoming = turns.firstOrNull { it in i..i + 3 }
                if (upcoming != null && upcoming != skippedSignal) onTurnSignal(true, simTime)
                if (upcoming == null) onTurnSignal(false, simTime)
                when (i) {
                    hardBrakeAt -> { onBrakePedal(true, simTime); speed = 2.0 }
                    hardBrakeAt + 2 -> { onBrakePedal(false, simTime); speed = cruiseMs }
                }
                onFix(p.copy(timeMillis = simTime))
            }
            onTurnSignal(false, simTime)
            onEnd()
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private fun turnIndices(): List<Int> {
        val out = ArrayList<Int>()
        for (i in 2 until route.size - 2) {
            if (distanceMeters(route[i - 2], route[i + 2]) < 15) continue
            val before = bearingDegrees(route[i - 2], route[i])
            val after = bearingDegrees(route[i], route[i + 2])
            val change = abs(((after - before + 540) % 360) - 180)
            if (change > 55 && (out.isEmpty() || i - out.last() > 4)) out += i
        }
        return out
    }
}

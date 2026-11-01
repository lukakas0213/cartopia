package com.roadcanvas.app.drive

import com.roadcanvas.app.geo.GeoPoint
import com.roadcanvas.app.geo.bearingDegrees
import com.roadcanvas.app.geo.distanceMeters
import kotlin.math.abs

/**
 * Scores one drive out of 100 from location fixes and vehicle signals.
 * Speed and acceleration come from consecutive fixes because the Pleos SDKs expose no vehicle speed.
 */
class SafetyScorer {

    enum class Kind(val label: String, val penalty: Int) {
        HARD_BRAKE("급제동", 5),
        HARD_ACCEL("급가속", 4),
        TURN_WITHOUT_SIGNAL("방향지시등 없이 방향 전환", 3),
        SEAT_BELT("안전벨트 미착용", 10)
    }

    data class Event(val kind: Kind, val atMillis: Long)

    private val _events = ArrayList<Event>()
    val events: List<Event> get() = _events

    val score: Int get() = (100 - _events.sumOf { it.kind.penalty }).coerceIn(0, 100)

    /** 0.5x at 0 points up to 1.5x at 100 points. */
    val multiplier: Double get() = 0.5 + score / 100.0

    private var last: GeoPoint? = null
    private var lastSpeed: Double? = null
    private var lastSpeedAt = 0L
    private var brakePressedAt = Long.MIN_VALUE / 2
    private var signalOnAt = Long.MIN_VALUE / 2
    private var signalOn = false
    private var seatBeltWarned = false
    private val bearings = ArrayDeque<Pair<Long, Double>>()
    private val lastEventAt = HashMap<Kind, Long>()

    fun onBrakePedal(pressed: Boolean, now: Long) {
        if (pressed) brakePressedAt = now
    }

    fun onTurnSignal(on: Boolean, now: Long) {
        signalOn = on
        if (on) signalOnAt = now
    }

    fun onSeatBeltWarning(warning: Boolean, now: Long, moving: Boolean) {
        if (warning && moving && !seatBeltWarned) {
            seatBeltWarned = true
            _events += Event(Kind.SEAT_BELT, now)
        }
    }

    fun onLocation(p: GeoPoint) {
        val prev = last
        last = p
        if (prev == null) return
        val dt = (p.timeMillis - prev.timeMillis) / 1000.0
        if (dt <= 0.2) return
        val moved = distanceMeters(prev, p)
        val speed = moved / dt

        lastSpeed?.let { v0 ->
            val accelDt = (p.timeMillis - lastSpeedAt) / 1000.0
            if (accelDt > 0) {
                val accel = (speed - v0) / accelDt
                if (accel > HARD_ACCEL_MS2) record(Kind.HARD_ACCEL, p.timeMillis, cooldownMs = 8_000)
                // A GPS-only drop can be noise; require the brake pedal shortly before, or a very sharp drop.
                val braking = p.timeMillis - brakePressedAt < 3_000
                if (accel < -HARD_BRAKE_MS2 && (braking || accel < -HARD_BRAKE_MS2 * 1.4)) {
                    record(Kind.HARD_BRAKE, p.timeMillis, cooldownMs = 8_000)
                }
            }
        }
        lastSpeed = speed
        lastSpeedAt = p.timeMillis

        if (moved > 4 && speed > 2.5) {
            bearings.addLast(p.timeMillis to bearingDegrees(prev, p))
            while (bearings.isNotEmpty() && p.timeMillis - bearings.first().first > TURN_WINDOW_MS) bearings.removeFirst()
            val change = abs(angleDiff(bearings.first().second, bearings.last().second))
            val signalled = signalOn || p.timeMillis - signalOnAt < SIGNAL_GRACE_MS
            if (change > TURN_DEGREES) {
                if (!signalled) record(Kind.TURN_WITHOUT_SIGNAL, p.timeMillis, cooldownMs = 15_000)
                bearings.clear()
            }
        }
    }

    private fun record(kind: Kind, now: Long, cooldownMs: Long) {
        val prev = lastEventAt[kind]
        if (prev != null && now - prev < cooldownMs) return
        lastEventAt[kind] = now
        _events += Event(kind, now)
    }

    private fun angleDiff(a: Double, b: Double): Double = ((b - a + 540) % 360) - 180

    companion object {
        const val HARD_ACCEL_MS2 = 3.0
        const val HARD_BRAKE_MS2 = 3.5
        const val TURN_DEGREES = 55.0
        const val TURN_WINDOW_MS = 7_000L
        const val SIGNAL_GRACE_MS = 10_000L
    }
}

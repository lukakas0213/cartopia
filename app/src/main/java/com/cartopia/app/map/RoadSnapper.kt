package com.cartopia.app.map

import com.cartopia.app.geo.MapPoint
import kotlin.math.hypot

/**
 * Moves a fix onto the nearest road centre line within [maxM]. Blocks are cut along centre lines, but the car
 * drives a lane or more off them and GPS adds its own error, which would keep it outside the reveal band.
 * Keeps to the previous road unless another one is more than [stickM] closer, so fixes do not hop to a side street.
 */
class RoadSnapper(private val map: BlockMap, maxM: Double = 20.0, stickM: Double = 3.0) {
    private val maxPx: Float
    private val stickPx: Float

    init {
        val mpu = map.viewport.metersPerUnit(map.viewport.centerLat)
        maxPx = (maxM / mpu).toFloat()
        stickPx = (stickM / mpu).toFloat()
    }

    private var last: FloatArray? = null

    /** Forgets the previous road (a new trip). */
    fun reset() {
        last = null
    }

    fun snap(p: MapPoint): MapPoint {
        var best: FloatArray? = null
        var bestD = Float.MAX_VALUE
        var bestP = p
        var lastD = Float.MAX_VALUE
        var lastP = p
        for (line in map.roadsNear(p.x, p.y, maxPx)) {
            val n = line.size / 2
            for (i in 0 until n - 1) {
                val ax = line[i * 2]; val ay = line[i * 2 + 1]
                val dx = line[i * 2 + 2] - ax; val dy = line[i * 2 + 3] - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0f) 0f else (((p.x - ax) * dx + (p.y - ay) * dy) / len2).coerceIn(0f, 1f)
                val qx = ax + t * dx; val qy = ay + t * dy
                val d = hypot(p.x - qx, p.y - qy)
                if (d < bestD) { bestD = d; best = line; bestP = MapPoint(qx, qy) }
                if (line === last && d < lastD) { lastD = d; lastP = MapPoint(qx, qy) }
            }
        }
        if (best == null || bestD > maxPx) {
            last = null
            return p
        }
        if (last != null && best !== last && lastD <= bestD + stickPx) return lastP
        last = best
        return bestP
    }
}

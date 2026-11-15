package com.cartopia.app.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double, val timeMillis: Long = 0L)

data class MapPoint(val x: Float, val y: Float)

/**
 * The fixed Web Mercator view the bundled map and blocks were rendered for.
 * Map units are the CSS pixels of that view (the bundled image is drawn at 2x).
 */
class Viewport(
    val centerLat: Double,
    centerLon: Double,
    val zoom: Int,
    val width: Int,
    val height: Int
) {
    private val centerX: Double
    private val centerY: Double

    init {
        val c = worldPx(centerLat, centerLon)
        centerX = c.first
        centerY = c.second
    }

    fun toMap(lat: Double, lon: Double): MapPoint {
        val (x, y) = worldPx(lat, lon)
        return MapPoint((x - centerX + width / 2.0).toFloat(), (y - centerY + height / 2.0).toFloat())
    }

    fun toMap(p: GeoPoint): MapPoint = toMap(p.lat, p.lon)

    /** Metres covered by one map unit at the view centre latitude. */
    fun metersPerUnit(lat: Double): Double = 156_543.03392 * cos(lat * PI / 180) / 2.0.pow(zoom)

    private fun worldPx(lat: Double, lon: Double): Pair<Double, Double> {
        val n = 256.0 * 2.0.pow(zoom)
        val s = sin(lat * PI / 180)
        val x = (lon + 180) / 360 * n
        val y = (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * n
        return x to y
    }
}

fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = (b.lat - a.lat) * PI / 180
    val dLon = (b.lon - a.lon) * PI / 180
    val h = sin(dLat / 2).pow(2) + cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/** Initial bearing from a to b in degrees, 0..360. */
fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
    val la1 = a.lat * PI / 180
    val la2 = b.lat * PI / 180
    val dLon = (b.lon - a.lon) * PI / 180
    val y = sin(dLon) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    return (kotlin.math.atan2(y, x) * 180 / PI + 360) % 360
}

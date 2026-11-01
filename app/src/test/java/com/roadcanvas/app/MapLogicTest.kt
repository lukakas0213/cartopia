package com.roadcanvas.app

import com.roadcanvas.app.drive.Miles
import com.roadcanvas.app.drive.SafetyScorer
import com.roadcanvas.app.geo.GeoPoint
import com.roadcanvas.app.map.BlockMap
import com.roadcanvas.app.map.BlockRevealer
import com.roadcanvas.app.map.RouteBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapLogicTest {

    private val map = BlockMap.parse(File("src/main/assets/map/blocks.json").readText())
    private val routes = RouteBook.parse(File("src/main/assets/map/routes.json").readText())

    @Test
    fun homeProjectsToTheSamePixelAsTheRenderer() {
        // The Python renderer put 성수동1가 home (37.5468, 127.0452) at (999, 372) in the village view.
        val p = map.viewport.toMap(37.5468, 127.0452)
        assertEquals(999f, p.x, 1f)
        assertEquals(372f, p.y, 1f)
    }

    @Test
    fun allRoutesOpenAboutAsManyBlocksAsTheShapelyReference() {
        val revealer = BlockRevealer(map)
        (routes.history + listOf(routes.demo)).forEach { r -> revealer.addPolyline(r.map { map.viewport.toMap(it) }) }
        // shapely (exterior ∩ route band > 36 px) opened 57 of 409 blocks for the same routes.
        val n = revealer.revealed.size
        assertTrue("opened $n blocks", n in 50..64)
    }

    @Test
    fun crossingAnIntersectionDoesNotOpenTheCornerBlocks() {
        val revealer = BlockRevealer(map)
        val a = map.viewport.toMap(routes.demo[40])
        val b = map.viewport.toMap(routes.demo[41])
        val opened = revealer.addSegment(a, b)
        assertTrue("short hop opened $opened", opened.size <= 2)
    }

    @Test
    fun milesFollowDistanceSafetyAndNewBlocks() {
        val m = Miles.forTrip(gpsKm = 23.4, odometerKm = 23.1, multiplier = 1.42, newBlocks = 4)
        assertEquals(23, m.base)
        assertEquals((23 * 1.42).toInt().let { if (23 * 1.42 - it >= 0.5) it + 1 else it } + 8, m.total)
        val spoofed = Miles.forTrip(gpsKm = 30.0, odometerKm = 2.0, multiplier = 1.4, newBlocks = 10)
        assertEquals(0, spoofed.total)
    }

    @Test
    fun steadyDrivingKeepsFullScoreAndHardBrakingCostsPoints() {
        val s = SafetyScorer()
        // 10 m/s due north for 20 s: one fix per second, ~0.00009 deg lat per 10 m.
        var t = 0L
        var lat = 37.54
        repeat(20) { s.onLocation(GeoPoint(lat, 127.04, t)); lat += 0.00009; t += 1000 }
        assertEquals(100, s.score)
        // Brake pedal, then the next fix shows 10 m/s -> 2 m/s within a second.
        s.onBrakePedal(true, t)
        s.onLocation(GeoPoint(lat - 0.00009 + 0.000018, 127.04, t))
        assertEquals(SafetyScorer.Kind.HARD_BRAKE, s.events.single().kind)
        assertEquals(95, s.score)
    }
}

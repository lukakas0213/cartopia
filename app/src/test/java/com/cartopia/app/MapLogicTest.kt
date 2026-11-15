package com.cartopia.app

import com.cartopia.app.car.DemoRouteFeed
import com.cartopia.app.drive.Miles
import com.cartopia.app.drive.SafetyScorer
import com.cartopia.app.geo.GeoPoint
import com.cartopia.app.map.BlockMap
import com.cartopia.app.geo.MapPoint
import com.cartopia.app.map.BlockRevealer
import com.cartopia.app.map.RoadSnapper
import com.cartopia.app.map.RouteBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapLogicTest {

    private val map = BlockMap.load { File("src/main/assets/map/seoul/$it").readText() }
    private val routes = RouteBook.parse(File("src/main/assets/map/routes.json").readText())
    /** The first demo route (왕십리역 → 응봉역 → 성수동1가), kept as a fixture so these numbers do not move with the demo. */
    private val sungsu = routes.history.last()

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
        routes.history.forEach { r -> revealer.addPolyline(r.map { map.viewport.toMap(it) }) }
        // Driven along + across medians + the 150 m circle (small blocks touched, big ones half inside) + hole fill
        // opened 277 on the Seoul map when the circle rule came in.
        val n = revealer.revealed.size
        assertTrue("opened $n blocks", n in 265..290)
    }

    @Test
    fun aCarOneLaneOffTheCentreLineOpensTheSameBlocksOnceSnapped() {
        val line = sungsu.map { map.viewport.toMap(it) }
        val mpu = map.viewport.metersPerUnit(map.viewport.centerLat).toFloat()
        // 9 m to the right of the centre line: the outer lane of a wide road
        val shifted = line.mapIndexed { i, p ->
            val a = line[maxOf(0, i - 1)]
            val b = line[minOf(line.size - 1, i + 1)]
            val len = kotlin.math.hypot(b.x - a.x, b.y - a.y).takeIf { it > 0f } ?: 1f
            MapPoint(p.x - (b.y - a.y) / len * 9f / mpu, p.y + (b.x - a.x) / len * 9f / mpu)
        }
        // blocks driven along (the bonus ones); the nearby rules hide part of the loss in the total
        fun driven(points: List<MapPoint>): Int {
            val r = BlockRevealer(map)
            return points.zipWithNext().sumOf { (a, b) -> r.addSegment(a, b).let { it.direct.size + it.drivenOver.size } }
        }
        val onLine = driven(line)
        val raw = driven(shifted)
        val snapper = RoadSnapper(map)
        val snapped = driven(shifted.map(snapper::snap))
        assertTrue("on line $onLine, shifted $raw, snapped $snapped", raw < onLine * 0.9 && snapped >= onLine * 0.9)
    }

    @Test
    fun crossingAnIntersectionDoesNotOpenTheCornerBlocks() {
        val revealer = BlockRevealer(map)
        val a = map.viewport.toMap(sungsu[40])
        val b = map.viewport.toMap(sungsu[41])
        val opened = revealer.addSegment(a, b).direct
        assertTrue("short hop opened $opened", opened.size <= 2)
    }

    @Test
    fun milesFollowDistanceSafetyAndNewGround() {
        val m = Miles.forTrip(gpsKm = 23.4, odometerKm = 23.1, multiplier = 1.42, newM2 = 32_000.0)
        // odometer distance wins: 23.1 km x 10 per km; 32,000 m² of new ground is 8
        assertEquals(231, m.base)
        assertEquals(Math.round(231 * 1.42).toInt() + 8, m.total)
        // a park beside the road counts for at most 20,000 m²
        assertEquals(20_000.0, Miles.bonusM2(250_000.0), 0.0)
        val spoofed = Miles.forTrip(gpsKm = 30.0, odometerKm = 2.0, multiplier = 1.4, newM2 = 40_000.0)
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

    @Test
    fun demoDriveShowsExactlyOneHardBrakeAndOneMissedSignal() {
        val steps = DemoRouteFeed.plan(routes.demo, startMillis = 0)
        val scorer = DemoRouteFeed.score(steps)
        val kinds = scorer.events.groupingBy { it.kind }.eachCount()
        assertEquals(mapOf(SafetyScorer.Kind.HARD_BRAKE to 1, SafetyScorer.Kind.TURN_WITHOUT_SIGNAL to 1), kinds)
        // One fix a second, like a real GPS, and a believable 7-12 minute drive.
        assertTrue(steps.zipWithNext().all { (a, b) -> b.fix.timeMillis - a.fix.timeMillis in 1..1000 })
        assertTrue("${steps.size} s", steps.size in 420..720)
    }
}

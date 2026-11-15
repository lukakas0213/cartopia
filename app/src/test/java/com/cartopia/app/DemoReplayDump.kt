package com.cartopia.app

import com.cartopia.app.car.DemoRouteFeed
import com.cartopia.app.drive.Miles
import com.cartopia.app.drive.SafetyScorer
import com.cartopia.app.geo.GeoPoint
import com.cartopia.app.geo.distanceMeters
import com.cartopia.app.map.BlockMap
import com.cartopia.app.map.BlockRevealer
import com.cartopia.app.map.RoadSnapper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * Not a check: writes what the app would do on a demo drive, for the presentation video.
 * Runs only with REPLAY_ROUTE ("lat,lon;lat,lon;...") and REPLAY_OUT set, e.g.
 * `REPLAY_ROUTE=route.txt REPLAY_OUT=replay.json ./gradlew testReleaseUnitTest --tests '*DemoReplayDump*'`.
 * The drive goes through the same steps as AppCore.onFix from a blank map: DemoRouteFeed's plan, RoadSnapper,
 * BlockRevealer, SafetyScorer and Miles.
 */
class DemoReplayDump {

    @Test
    fun dump() {
        val routeFile = System.getenv("REPLAY_ROUTE")
        val out = System.getenv("REPLAY_OUT")
        assumeTrue(routeFile != null && out != null)
        val route = File(routeFile!!).readText().trim().split(";").map { p ->
            val (lat, lon) = p.split(",").map { it.toDouble() }
            GeoPoint(lat, lon)
        }
        val map = BlockMap.load { File("src/main/assets/map/seoul/$it").readText() }
        val revealer = BlockRevealer(map)
        val snapper = RoadSnapper(map)
        val scorer = SafetyScorer()
        val steps = DemoRouteFeed.plan(route, 0L)
        val fixes = JSONArray()
        val opens = JSONArray()
        var meters = 0.0
        var newM2 = 0.0
        var bonusM2 = 0.0
        var nearM2 = 0.0
        val nearIds = HashSet<Int>()
        var prevGeo: GeoPoint? = null
        var prevPoint: com.cartopia.app.geo.MapPoint? = null
        DemoRouteFeed.replay(steps, scorer::onTurnSignal, scorer::onBrakePedal) { p ->
            val point = snapper.snap(map.viewport.toMap(p))
            scorer.onLocation(p)
            prevGeo?.let { meters += distanceMeters(it, p) }
            prevPoint?.let { a ->
                val o = revealer.addSegment(a, point)
                val movedOver = o.drivenOver.filter { nearIds.remove(it) }
                movedOver.forEach { nearM2 -= revealer.areaM2(it) }
                (o.direct + movedOver).forEach { val m2 = revealer.areaM2(it); newM2 += m2; bonusM2 += Miles.bonusM2(m2) }
                o.nearby.forEach { nearM2 += revealer.areaM2(it) }
                nearIds += o.nearby
                o.all.forEach { opens.put(JSONArray().put(it).put(meters)) }
            }
            fixes.put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()).put(p.timeMillis).put(meters))
            prevGeo = p
            prevPoint = point
        }
        val m = Miles.forTrip(meters / 1000, null, scorer.multiplier, newM2, bonusM2)
        val events = JSONArray()
        scorer.events.forEach { events.put(JSONArray().put(it.kind.name).put(it.atMillis)) }
        val grouped = JSONArray()
        scorer.events.groupBy { it.kind }.forEach { (kind, list) ->
            grouped.put(JSONObject().put("label", kind.label).put("count", list.size).put("penalty", kind.penalty * list.size))
        }
        val durationSec = (steps.last().fix.timeMillis - steps.first().fix.timeMillis) / 1000.0
        val summary = JSONObject()
            .put("km", m.km).put("minutes", (durationSec / 60.0).roundToInt()).put("score", scorer.score)
            .put("multiplier", m.multiplier).put("newM2", m.newM2).put("nearM2", nearM2).put("base", m.base)
            .put("bonus", m.bonus).put("total", m.total).put("withheld", m.withheld).put("events", grouped)
        File(out!!).writeText(JSONObject().put("fixes", fixes).put("opens", opens).put("events", events).put("summary", summary).toString())
        println("replay: ${fixes.length()} fixes, ${opens.length()} blocks, ${(meters / 1000 * 10).roundToInt() / 10.0} km, summary $summary")
    }
}

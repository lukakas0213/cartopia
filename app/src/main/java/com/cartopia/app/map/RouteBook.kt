package com.cartopia.app.map

import com.cartopia.app.geo.GeoPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * assets/map/routes.json: [demo] is the route the 주행 체험하기 drive replays (스케일타워 → 청담 한강);
 * [history] holds three fixed drives around 성수동 that the unit tests reveal blocks with.
 */
class RouteBook(val history: List<List<GeoPoint>>, val demo: List<GeoPoint>) {
    companion object {
        fun parse(json: String): RouteBook {
            val root = JSONObject(json)
            val history = root.getJSONArray("history")
            return RouteBook(
                history = (0 until history.length()).map { points(history.getJSONArray(it)) },
                demo = points(root.getJSONArray("demo"))
            )
        }

        private fun points(arr: JSONArray) = (0 until arr.length()).map {
            val p = arr.getJSONArray(it)
            GeoPoint(p.getDouble(0), p.getDouble(1))
        }
    }
}

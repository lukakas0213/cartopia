package com.cartopia.app.map

import com.cartopia.app.geo.GeoPoint
import com.cartopia.app.geo.Viewport
import org.json.JSONArray
import org.json.JSONObject

/**
 * A city block: a face of the road network.
 * [edge] is the block outline along the road centre lines (used to decide reveals),
 * [outline] is the same block grown over its edge roads (used to draw it), both as x,y pairs in map units.
 */
class Block(val id: Int, val edge: FloatArray, val outline: FloatArray, val area: Float)

class BlockMap(
    val viewport: Viewport,
    val blocks: List<Block>,
    val bandPx: Float,
    val minSharedPx: Float
) {
    companion object {
        fun parse(json: String): BlockMap {
            val root = JSONObject(json)
            val view = root.getJSONObject("view")
            val reveal = root.getJSONObject("reveal")
            val list = root.getJSONArray("blocks")
            val blocks = (0 until list.length()).map { i ->
                val b = list.getJSONObject(i)
                Block(
                    id = b.getInt("id"),
                    edge = flatten(b.getJSONArray("edge")),
                    outline = flatten(b.getJSONArray("draw")),
                    area = b.getDouble("area").toFloat()
                )
            }
            return BlockMap(
                viewport = Viewport(view.getDouble("lat"), view.getDouble("lon"), view.getInt("zoom"), view.getInt("width"), view.getInt("height")),
                blocks = blocks,
                bandPx = reveal.getDouble("bandPx").toFloat(),
                minSharedPx = reveal.getDouble("minSharedPx").toFloat()
            )
        }

        private fun flatten(points: JSONArray): FloatArray {
            val out = FloatArray(points.length() * 2)
            for (i in 0 until points.length()) {
                val p = points.getJSONArray(i)
                out[i * 2] = p.getDouble(0).toFloat()
                out[i * 2 + 1] = p.getDouble(1).toFloat()
            }
            return out
        }
    }
}

/** Bundled demo data: earlier drives already on the map, and the route the demo drive replays. */
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

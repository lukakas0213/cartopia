package com.cartopia.app.map

import com.cartopia.app.geo.GeoPoint
import com.cartopia.app.geo.Viewport
import org.json.JSONArray
import org.json.JSONObject

/**
 * A city block: a face of the road network.
 * [edge] is the block outline along the road centre lines (used to decide reveals), as x,y pairs in map units;
 * [minX]..[maxY] is its bounding box; [areaM2] is its real ground area.
 */
class Block(val id: Int, val edge: FloatArray, val area: Float, val areaM2: Float, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

/**
 * The Seoul-wide block map, cut into square tiles (tools/build_seoul.py). Tiles are read from the assets
 * the first time a query reaches them and kept afterwards; blocks larger than a tile are always loaded.
 */
class BlockMap(
    val viewport: Viewport,
    val bandPx: Float,
    val minSharedPx: Float,
    val version: Int,
    private val tileSize: Int,
    /** Every tile in the build, including ones that own no blocks. */
    private val tileKeys: Set<String>,
    /** Tile key -> bounding box of the blocks it owns (x0, y0, x1, y1), for tiles that own any. */
    private val extents: Map<String, FloatArray>,
    private val read: (String) -> String
) {
    /** A tile's blocks, and its road centre lines (x,y pairs; clipped a little past the tile edge). */
    private class Tile(val blocks: List<Block>, val roads: List<FloatArray>)

    private val loaded = HashMap<String, Tile>()
    private val big: List<Block> = parseBlocks(JSONObject(read("big.json")).getJSONArray("blocks"))

    /** Every block whose bounding box comes within [pad] of the box (x0, y0)-(x1, y1). */
    @Synchronized
    fun blocksNear(x0: Float, y0: Float, x1: Float, y1: Float, pad: Float = 0f): List<Block> {
        val out = ArrayList<Block>()
        fun take(list: List<Block>) = list.filterTo(out) { it.maxX >= x0 - pad && it.minX <= x1 + pad && it.maxY >= y0 - pad && it.minY <= y1 + pad }
        take(big)
        for ((key, e) in extents) {
            if (e[2] < x0 - pad || e[0] > x1 + pad || e[3] < y0 - pad || e[1] > y1 + pad) continue
            take(tile(key).blocks)
        }
        return out
    }

    /** Road centre lines of the tiles within [pad] of (x, y). */
    @Synchronized
    fun roadsNear(x: Float, y: Float, pad: Float): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (tx in Math.floorDiv((x - pad).toInt(), tileSize)..Math.floorDiv((x + pad).toInt(), tileSize)) {
            for (ty in Math.floorDiv((y - pad).toInt(), tileSize)..Math.floorDiv((y + pad).toInt(), tileSize)) {
                val key = "${tx}_$ty"
                if (key in tileKeys) out += tile(key).roads
            }
        }
        return out
    }

    private fun tile(key: String): Tile = loaded.getOrPut(key) {
        val root = JSONObject(read("t/$key.json"))
        val roads = root.optJSONObject("ground")?.optJSONArray("roads")
        Tile(
            parseBlocks(root.getJSONArray("blocks")),
            if (roads == null) emptyList() else (0 until roads.length()).map { flatten(roads.getJSONArray(it).getJSONArray(1)) }
        )
    }

    companion object {
        /** [read] returns the text of a file under assets/map/seoul. */
        fun load(read: (String) -> String): BlockMap {
            val root = JSONObject(read("index.json"))
            val view = root.getJSONObject("view")
            val reveal = root.getJSONObject("reveal")
            val tiles = root.getJSONObject("tiles")
            val extents = HashMap<String, FloatArray>()
            tiles.keys().forEach { k ->
                val a = tiles.optJSONArray(k) ?: return@forEach
                extents[k] = FloatArray(4) { a.getDouble(it).toFloat() }
            }
            return BlockMap(
                viewport = Viewport(view.getDouble("lat"), view.getDouble("lon"), view.getInt("zoom"), view.getInt("width"), view.getInt("height")),
                bandPx = reveal.getDouble("bandPx").toFloat(),
                minSharedPx = reveal.getDouble("minSharedPx").toFloat(),
                version = root.getInt("version"),
                tileSize = root.getInt("tile"),
                tileKeys = tiles.keys().asSequence().toSet(),
                extents = extents,
                read = read
            )
        }

        private fun parseBlocks(list: JSONArray): List<Block> = (0 until list.length()).map { i ->
            val b = list.getJSONObject(i)
            val bbox = b.getJSONArray("bbox")
            Block(
                id = b.getInt("id"),
                edge = flatten(b.getJSONArray("edge")),
                area = b.getDouble("area").toFloat(),
                areaM2 = b.optDouble("areaM2", 0.0).toFloat(),
                minX = bbox.getDouble(0).toFloat(), minY = bbox.getDouble(1).toFloat(),
                maxX = bbox.getDouble(2).toFloat(), maxY = bbox.getDouble(3).toFloat()
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

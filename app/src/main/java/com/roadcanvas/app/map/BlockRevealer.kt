package com.roadcanvas.app.map

import com.roadcanvas.app.geo.MapPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Opens a block once the driven line has run along its edge for [BlockMap.minSharedPx] map units.
 * Each block edge is sampled every [step] units; a sample counts once a route segment passes within
 * [BlockMap.bandPx] of it, so crossing an intersection (touching only a corner) does not open the block.
 */
class BlockRevealer(private val map: BlockMap, private val step: Float = 1f) {

    private class Edge(
        val block: Block,
        val samples: FloatArray,
        val covered: BooleanArray,
        val minX: Float, val minY: Float, val maxX: Float, val maxY: Float
    ) {
        var shared = 0f
    }

    private val edges: List<Edge> = map.blocks.map { b -> sampleEdge(b) }
    private val opened = LinkedHashSet<Int>()

    val revealed: Set<Int> get() = opened

    /** Marks blocks as already open (restored from storage) without re-running the edge test. */
    fun restore(ids: Collection<Int>) {
        opened.addAll(ids)
    }

    /** Feeds one driven segment; returns the ids of blocks it opened. */
    fun addSegment(a: MapPoint, b: MapPoint): List<Int> {
        val band = map.bandPx
        val sMinX = min(a.x, b.x) - band
        val sMaxX = max(a.x, b.x) + band
        val sMinY = min(a.y, b.y) - band
        val sMaxY = max(a.y, b.y) + band
        val newly = ArrayList<Int>()
        for (e in edges) {
            if (e.block.id in opened && e.shared >= map.minSharedPx) continue
            if (e.maxX < sMinX || e.minX > sMaxX || e.maxY < sMinY || e.minY > sMaxY) continue
            val s = e.samples
            var i = 0
            while (i < e.covered.size) {
                if (!e.covered[i] && distToSegment(s[i * 2], s[i * 2 + 1], a, b) <= band) {
                    e.covered[i] = true
                    e.shared += step
                }
                i++
            }
            if (e.shared > map.minSharedPx && opened.add(e.block.id)) newly += e.block.id
        }
        return newly
    }

    fun addPolyline(points: List<MapPoint>): List<Int> =
        points.zipWithNext().flatMap { (a, b) -> addSegment(a, b) }

    private fun sampleEdge(b: Block): Edge {
        val pts = b.edge
        val n = pts.size / 2
        val out = ArrayList<Float>()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x0 = pts[i * 2]; val y0 = pts[i * 2 + 1]
            val x1 = pts[((i + 1) % n) * 2]; val y1 = pts[((i + 1) % n) * 2 + 1]
            val len = hypot(x1 - x0, y1 - y0)
            val count = max(1, (len / step).toInt())
            for (k in 0 until count) {
                val t = (k + 0.5f) / count
                out += x0 + (x1 - x0) * t
                out += y0 + (y1 - y0) * t
            }
            minX = min(minX, x0); maxX = max(maxX, x0); minY = min(minY, y0); maxY = max(maxY, y0)
        }
        val arr = out.toFloatArray()
        return Edge(b, arr, BooleanArray(arr.size / 2), minX, minY, maxX, maxY)
    }

    private fun distToSegment(px: Float, py: Float, a: MapPoint, b: MapPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - a.x - t * dx, py - a.y - t * dy)
    }
}

package com.cartopia.app.map

import com.cartopia.app.geo.MapPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Opens a block once the driven line has run along its edge for [BlockMap.minSharedPx] map units.
 * Each block edge is sampled every [step] units; a sample counts once a route segment passes within
 * [BlockMap.bandPx] of it, so crossing an intersection (touching only a corner) does not open the block.
 *
 * Around the car the map opens like a circle of [wideM] (nearby, without being driven along):
 * - a block up to [touchM2] opens as soon as any part of it comes within [wideM] of the driven line, long thin
 *   ones (road strips, rail lines) included;
 * - a bigger block (park, hill) opens once [wideShare] of its area lies within [wideM], measured on a grid of
 *   points inside the block, or once the drive has run [alongM] beside it (within [besideM] of its outline), so a
 *   park or hill next to a long road opens while the car passes it, but crossing its corner does not;
 * - fill: a block up to [smallM2] with at least [fillShare] of its outline touching open blocks (checked
 *   [fillRounds] times outward from the blocks a segment opened), which closes the holes a loop leaves behind.
 * Only the driven-along blocks earn the area bonus; the others just show on the map.
 *
 * A divided road is two centre lines with a thin strip (median, slip-lane island) between them, so the block
 * across it sits farther off than the band. An edge sample within [farM] still counts when the way from it to
 * the driven line runs through such a strip (average width up to [stripM]).
 */
class BlockRevealer(
    private val map: BlockMap,
    private val step: Float = 1f,
    touchM2: Double = 20000.0,
    smallM2: Double = 8000.0,
    private val fillShare: Float = 0.6f,
    private val fillPad: Float = 6f,
    private val fillRounds: Int = 2,
    stripM: Double = 15.0,
    farM: Double = 30.0,
    wideM: Double = 150.0,
    private val wideShare: Float = 0.5f,
    besideM: Double = 80.0,
    alongM: Double = 200.0
) {
    /**
     * What one segment opened: blocks driven along, and small blocks opened around them.
     * [drivenOver] are blocks that were already open (nearby, or from storage) and have now been driven along.
     */
    class Opened(val direct: List<Int>, val nearby: List<Int>, val drivenOver: List<Int> = emptyList()) {
        val all: List<Int> get() = direct + nearby
        fun isEmpty() = direct.isEmpty() && nearby.isEmpty()
    }

    private class Edge(
        val block: Block,
        val samples: FloatArray,
        val covered: BooleanArray,
        val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
        val strip: Boolean
    ) {
        var shared = 0f
        var driven = false
        /** Grid points inside the block, and which of them have come within the wide distance. */
        var inner: FloatArray? = null
        var innerNear: BooleanArray? = null
        var innerCount = 0
        /** Distance driven beside a big block (segment midpoints inside it or near its outline). */
        var beside = 0f
    }

    private val touchArea: Float
    private val smallArea: Float
    private val stripPx: Float
    private val farPx: Float
    private val widePx: Float
    private val besidePx: Float
    private val alongPx: Float

    init {
        val mpu = map.viewport.metersPerUnit(map.viewport.centerLat)
        touchArea = (touchM2 / (mpu * mpu)).toFloat()
        smallArea = (smallM2 / (mpu * mpu)).toFloat()
        stripPx = (stripM / mpu).toFloat()
        farPx = (farM / mpu).toFloat()
        widePx = (wideM / mpu).toFloat()
        besidePx = (besideM / mpu).toFloat()
        alongPx = (alongM / mpu).toFloat()
    }

    /** Sampled edges of the blocks the drive has come near so far; built on first contact. */
    private val edges = HashMap<Int, Edge>()
    private val opened = LinkedHashSet<Int>()

    val revealed: Set<Int> get() = opened

    /** Real ground area of a block this revealer has opened or come near (0 for one it never saw). */
    fun areaM2(id: Int): Double = edges[id]?.block?.areaM2?.toDouble() ?: 0.0

    /** Marks blocks as already open (restored from storage) without re-running the edge test. */
    fun restore(ids: Collection<Int>) {
        opened.addAll(ids)
    }

    /** Feeds one driven segment; returns the blocks it opened. */
    fun addSegment(a: MapPoint, b: MapPoint): Opened {
        val band = map.bandPx
        val reach = max(band, max(farPx, widePx))
        val sMinX = min(a.x, b.x)
        val sMaxX = max(a.x, b.x)
        val sMinY = min(a.y, b.y)
        val sMaxY = max(a.y, b.y)
        val direct = ArrayList<Int>()
        val nearby = ArrayList<Int>()
        val drivenOver = ArrayList<Int>()
        val near = map.blocksNear(sMinX, sMinY, sMaxX, sMaxY, reach).map { edges.getOrPut(it.id) { sampleEdge(it) } }
        val strips = near.filter {
            it.strip && it.maxX >= sMinX - farPx && it.minX <= sMaxX + farPx && it.maxY >= sMinY - farPx && it.minY <= sMaxY + farPx
        }
        for (e in near) {
            val block = e.block
            val across = strips.any { it !== e }
            val r = if (across) farPx else band
            if (!e.driven &&
                e.maxX >= sMinX - r && e.minX <= sMaxX + r && e.maxY >= sMinY - r && e.minY <= sMaxY + r) {
                val s = e.samples
                var i = 0
                while (i < e.covered.size) {
                    if (!e.covered[i]) {
                        val px = s[i * 2]
                        val py = s[i * 2 + 1]
                        val d = distToSegment(px, py, a, b)
                        if (d <= band || (across && d <= farPx && acrossStrip(px, py, a, b, strips, e))) {
                            e.covered[i] = true
                            e.shared += step
                        }
                    }
                    i++
                }
                if (e.shared > map.minSharedPx && !e.driven) {
                    e.driven = true
                    if (opened.add(e.block.id)) direct += e.block.id else drivenOver += e.block.id
                }
            }
            // inside the circle around the car, though the drive never ran along it
            if (block.id !in opened &&
                (if (block.area <= touchArea) touched(e, a, b) else markInnerNear(e, a, b) or drivenBeside(e, a, b)) &&
                opened.add(block.id)) nearby += block.id
        }
        var frontier: List<Int> = direct + nearby
        repeat(fillRounds) {
            if (frontier.isEmpty()) return@repeat
            frontier = fillAround(frontier)
            nearby += frontier
        }
        return Opened(direct, nearby, drivenOver)
    }

    /** Seeds and restores only need the final set. */
    fun addPolyline(points: List<MapPoint>): List<Int> =
        points.zipWithNext().flatMap { (a, b) -> addSegment(a, b).all }

    /** Whether any part of the block lies within the circle distance of the segment. */
    private fun touched(e: Edge, a: MapPoint, b: MapPoint): Boolean {
        if (e.maxX < min(a.x, b.x) - widePx || e.minX > max(a.x, b.x) + widePx ||
            e.maxY < min(a.y, b.y) - widePx || e.minY > max(a.y, b.y) + widePx) return false
        if (inside(e.block, a.x, a.y)) return true
        val s = e.samples
        for (i in 0 until s.size / 2) if (distToSegment(s[i * 2], s[i * 2 + 1], a, b) <= widePx) return true
        return false
    }

    /** Whether the way from (px, py) to the nearest point of the driven segment runs through a strip other than [own]. */
    private fun acrossStrip(px: Float, py: Float, a: MapPoint, b: MapPoint, strips: List<Edge>, own: Edge): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
        val mx = (px + a.x + t * dx) / 2
        val my = (py + a.y + t * dy) / 2
        return strips.any { it !== own && inside(it.block, mx, my) }
    }

    /** Adds the segment's length when it runs beside the block; true once that adds up to [alongPx]. */
    private fun drivenBeside(e: Edge, a: MapPoint, b: MapPoint): Boolean {
        val mx = (a.x + b.x) / 2
        val my = (a.y + b.y) / 2
        if (mx < e.minX - besidePx || mx > e.maxX + besidePx || my < e.minY - besidePx || my > e.maxY + besidePx) return false
        var near = inside(e.block, mx, my)
        if (!near) {
            val s = e.samples
            var i = 0
            while (i < s.size / 2 && !near) {
                if (hypot(s[i * 2] - mx, s[i * 2 + 1] - my) <= besidePx) near = true
                i++
            }
        }
        if (near) e.beside += hypot(b.x - a.x, b.y - a.y)
        return e.beside >= alongPx
    }

    /** Marks the block's inner grid points near the segment; true once [wideShare] of them are near. */
    private fun markInnerNear(e: Edge, a: MapPoint, b: MapPoint): Boolean {
        if (e.maxX < min(a.x, b.x) - widePx || e.minX > max(a.x, b.x) + widePx ||
            e.maxY < min(a.y, b.y) - widePx || e.minY > max(a.y, b.y) + widePx) return false
        val pts = e.inner ?: innerGrid(e.block).also { e.inner = it; e.innerNear = BooleanArray(it.size / 2) }
        val near = e.innerNear!!
        val n = near.size
        if (n == 0) return false
        for (i in 0 until n) {
            if (!near[i] && distToSegment(pts[i * 2], pts[i * 2 + 1], a, b) <= widePx) {
                near[i] = true
                e.innerCount++
            }
        }
        return e.innerCount >= wideShare * n
    }

    /** About 64 evenly spaced points inside the block (none for a block thinner than the grid). */
    private fun innerGrid(b: Block): FloatArray {
        val step = max(2f, kotlin.math.sqrt(b.area / 64f))
        val out = ArrayList<Float>()
        var y = b.minY + step / 2
        while (y < b.maxY) {
            var x = b.minX + step / 2
            while (x < b.maxX) {
                if (inside(b, x, y)) { out += x; out += y }
                x += step
            }
            y += step
        }
        return out.toFloatArray()
    }

    /** Opens the small blocks next to [ids] whose outline is mostly shared with open blocks. */
    private fun fillAround(ids: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        val checked = HashSet<Int>()
        for (id in ids) {
            val src = edges[id]?.block ?: continue
            for (c in map.blocksNear(src.minX, src.minY, src.maxX, src.maxY, fillPad)) {
                if (c.id in opened || c.area > smallArea || !checked.add(c.id)) continue
                val e = edges.getOrPut(c.id) { sampleEdge(c) }
                val around = map.blocksNear(c.minX, c.minY, c.maxX, c.maxY, fillPad).filter { it.id in opened }
                val s = e.samples
                val total = s.size / 2
                var touching = 0
                for (i in 0 until total) {
                    val px = s[i * 2]
                    val py = s[i * 2 + 1]
                    if (around.any { touches(it, px, py) }) touching++
                }
                if (total > 0 && touching >= fillShare * total && opened.add(c.id)) out += c.id
            }
        }
        return out
    }

    /** Whether the point lies inside [b] or within [fillPad] of its outline. */
    private fun touches(b: Block, px: Float, py: Float): Boolean {
        if (px < b.minX - fillPad || px > b.maxX + fillPad || py < b.minY - fillPad || py > b.maxY + fillPad) return false
        val pts = b.edge
        val n = pts.size / 2
        var j = n - 1
        for (i in 0 until n) {
            if (distToSegment(px, py, pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1]) <= fillPad) return true
            j = i
        }
        return inside(b, px, py)
    }

    private fun inside(b: Block, px: Float, py: Float): Boolean {
        if (px < b.minX || px > b.maxX || py < b.minY || py > b.maxY) return false
        val pts = b.edge
        val n = pts.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = pts[i * 2]; val yi = pts[i * 2 + 1]
            val xj = pts[j * 2]; val yj = pts[j * 2 + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun sampleEdge(b: Block): Edge {
        val pts = b.edge
        val n = pts.size / 2
        val out = ArrayList<Float>()
        var perimeter = 0f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x0 = pts[i * 2]; val y0 = pts[i * 2 + 1]
            val x1 = pts[((i + 1) % n) * 2]; val y1 = pts[((i + 1) % n) * 2 + 1]
            val len = hypot(x1 - x0, y1 - y0)
            perimeter += len
            val count = max(1, (len / step).toInt())
            for (k in 0 until count) {
                val t = (k + 0.5f) / count
                out += x0 + (x1 - x0) * t
                out += y0 + (y1 - y0) * t
            }
            minX = min(minX, x0); maxX = max(maxX, x0); minY = min(minY, y0); maxY = max(maxY, y0)
        }
        val arr = out.toFloatArray()
        // average width of a long thin face is about twice its area over its perimeter
        val strip = perimeter > 0f && 2 * b.area / perimeter <= stripPx
        return Edge(b, arr, BooleanArray(arr.size / 2), minX, minY, maxX, maxY, strip)
    }

    private fun distToSegment(px: Float, py: Float, a: MapPoint, b: MapPoint): Float =
        distToSegment(px, py, a.x, a.y, b.x, b.y)

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - ax - t * dx, py - ay - t * dy)
    }
}

package com.roadcanvas.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.roadcanvas.app.geo.MapPoint
import com.roadcanvas.app.map.BlockMap
import kotlin.math.max

/**
 * Black world, the gray map only inside opened blocks, every driven road as a white line.
 * [focus] is kept at the centre (the car while driving); [zoom] is relative to "fill the view".
 */
@Composable
fun MapCanvas(
    blockMap: BlockMap,
    baseMap: ImageBitmap,
    revealed: Set<Int>,
    history: List<List<MapPoint>>,
    current: List<MapPoint>,
    car: MapPoint?,
    focus: MapPoint?,
    zoom: Float,
    modifier: Modifier = Modifier
) {
    val w = blockMap.viewport.width.toFloat()
    val h = blockMap.viewport.height.toFloat()
    val openPath = remember(revealed) { blocksPath(blockMap, revealed) }
    val historyPaths = remember(history) { history.map(::linePath) }
    val currentPath = remember(current) { linePath(current) }

    Canvas(modifier) {
        drawRect(RcColors.Fog)
        val s = max(size.width / w, size.height / h) * zoom
        val cx = focus?.x ?: (w / 2)
        val cy = focus?.y ?: (h / 2)
        val tx = (size.width / 2 - cx * s).coerceIn(size.width - w * s, 0f)
        val ty = (size.height / 2 - cy * s).coerceIn(size.height - h * s, 0f)
        withTransform({
            translate(tx, ty)
            scale(s, s, Offset.Zero)
        }) {
            clipPath(openPath) {
                drawImage(
                    image = baseMap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(baseMap.width, baseMap.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(w.toInt(), h.toInt()),
                    filterQuality = FilterQuality.High
                )
            }
            historyPaths.forEach { route(it, 2.2f / zoom.coerceAtLeast(1f) + 0.6f, 0.85f) }
            route(currentPath, 3.2f / zoom.coerceAtLeast(1f) + 0.8f, 1f)
            car?.let {
                drawCircle(RcColors.Accent.copy(alpha = 0.25f), radius = 11f / zoom, center = Offset(it.x, it.y))
                drawCircle(Color.White, radius = 6.5f / zoom, center = Offset(it.x, it.y))
                drawCircle(RcColors.Accent, radius = 4.5f / zoom, center = Offset(it.x, it.y))
            }
        }
    }
}

private fun DrawScope.route(path: Path, width: Float, alpha: Float) {
    drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun blocksPath(map: BlockMap, ids: Set<Int>): Path = Path().apply {
    map.blocks.forEach { b ->
        if (b.id !in ids) return@forEach
        val o = b.outline
        moveTo(o[0], o[1])
        var i = 2
        while (i < o.size) {
            lineTo(o[i], o[i + 1])
            i += 2
        }
        close()
    }
}

private fun linePath(points: List<MapPoint>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
}

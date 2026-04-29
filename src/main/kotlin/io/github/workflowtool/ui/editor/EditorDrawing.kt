package io.github.workflowtool.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.hasMask
import kotlin.math.floor
import kotlin.math.max

internal const val CheckerboardCellPx = 16f
internal const val PixelGridThreshold = 6f
internal const val PixelGridStrongThreshold = 10f
internal const val CheckerboardLightArgb = 0xFFF6F7F9.toInt()
internal const val CheckerboardDarkArgb = 0xFFD8DCE2.toInt()
internal val CheckerboardLight = Color(CheckerboardLightArgb)
internal val CheckerboardDark = Color(CheckerboardDarkArgb)

internal data class RegionOverlayStyle(
    val strokeColor: Color,
    val fillColor: Color,
    val accentColor: Color,
    val secondaryStrokeColor: Color,
    val strokeWidth: Float
)

internal data class MaskFillRun(
    val localY: Int,
    val startX: Int,
    val endX: Int,
    val alpha: Int
)

internal data class MaskBoundarySegment(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int
)

internal data class MaskOverlayGeometry(
    val fillRuns: List<MaskFillRun>,
    val boundarySegments: List<MaskBoundarySegment>
)

internal class MaskOverlayGeometryCache(private val maxEntries: Int = 96) {
    private data class Key(
        val maskIdentity: Int,
        val width: Int,
        val height: Int,
        val size: Int
    )

    private val cache = object : LinkedHashMap<Key, MaskOverlayGeometry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, MaskOverlayGeometry>): Boolean =
            size > maxEntries
    }

    fun geometryFor(region: CropRegion): MaskOverlayGeometry {
        val key = Key(
            maskIdentity = System.identityHashCode(region.alphaMask),
            width = region.maskWidth,
            height = region.maskHeight,
            size = region.alphaMask.size
        )
        return cache.getOrPut(key) {
            buildMaskOverlayGeometry(region.alphaMask, region.maskWidth, region.maskHeight)
        }
    }
}

private fun buildMaskOverlayGeometry(mask: List<Int>, width: Int, height: Int): MaskOverlayGeometry {
    val fillRuns = ArrayList<MaskFillRun>()
    for (localY in 0 until height) {
        var localX = 0
        while (localX < width) {
            val alpha = mask[localY * width + localX].coerceIn(0, 255)
            if (alpha <= 0) {
                localX += 1
                continue
            }
            val startX = localX
            var endX = localX
            while (endX + 1 < width && mask[localY * width + endX + 1] > 0) {
                endX += 1
            }
            fillRuns += MaskFillRun(localY = localY, startX = startX, endX = endX, alpha = alpha)
            localX = endX + 1
        }
    }

    val boundarySegments = ArrayList<MaskBoundarySegment>()
    forEachMaskBoundarySegment(
        width = width,
        height = height,
        isFilled = { x, y -> mask[y * width + x] > 0 }
    ) { startX, startY, endX, endY ->
        boundarySegments += MaskBoundarySegment(startX, startY, endX, endY)
    }
    return MaskOverlayGeometry(
        fillRuns = fillRuns,
        boundarySegments = boundarySegments
    )
}

fun DrawScope.drawCheckerboard(size: Size, zoom: Float = 1f, viewportOffset: Offset = Offset.Zero) {
    val cell = CheckerboardCellPx
    val startX = floor(-viewportOffset.x / cell) * cell + viewportOffset.x
    val startY = floor(-viewportOffset.y / cell) * cell + viewportOffset.y
    var y = startY
    var row = floor((y - viewportOffset.y) / cell).toInt()
    while (y < size.height) {
        var x = startX
        var column = floor((x - viewportOffset.x) / cell).toInt()
        while (x < size.width) {
            drawRect(
                color = if ((row + column) % 2 == 0) CheckerboardDark else CheckerboardLight,
                topLeft = Offset(x, y),
                size = Size(cell, cell)
            )
            x += cell
            column += 1
        }
        y += cell
        row += 1
    }
}

fun DrawScope.drawPixelGrid(size: Size, zoomX: Float, zoomY: Float = zoomX, viewportOffset: Offset) {
    if (zoomX < PixelGridThreshold || zoomY < PixelGridThreshold) return
    val alpha = if (max(zoomX, zoomY) >= PixelGridStrongThreshold) 0.34f else 0.18f
    val color = Color.Black.copy(alpha = alpha)
    var x = viewportOffset.x % zoomX
    if (x > 0f) x -= zoomX
    while (x <= size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += zoomX
    }
    var y = viewportOffset.y % zoomY
    if (y > 0f) y -= zoomY
    while (y <= size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += zoomY
    }
}

fun DrawScope.drawGrid(size: Size, zoom: Float, viewportOffset: Offset) {
    val spacing = 48f * zoom.coerceAtLeast(0.4f)
    var x = viewportOffset.x % spacing
    while (x <= size.width) {
        drawLine(Color(0x1FFFFFFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += spacing
    }
    var y = viewportOffset.y % spacing
    while (y <= size.height) {
        drawLine(Color(0x1FFFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += spacing
    }
}

internal fun DrawScope.drawRegionOutline(
    region: CropRegion,
    zoom: Float,
    zoomY: Float = zoom,
    viewportOffset: Offset,
    style: RegionOverlayStyle,
    antsPhase: Float,
    maskGeometryCache: MaskOverlayGeometryCache
) {
    if (region.hasMask()) {
        drawMaskBackedRegion(region, zoom, zoomY, viewportOffset, style, antsPhase, maskGeometryCache.geometryFor(region))
        return
    }

    val left = region.x * zoom + viewportOffset.x
    val top = region.y * zoomY + viewportOffset.y
    val right = region.right * zoom + viewportOffset.x
    val bottom = region.bottom * zoomY + viewportOffset.y
    val path = Path().apply {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }

    drawPath(path, style.fillColor)
    if (region.selected) {
        drawMarchingAntsPath(path = path, strokeWidth = max(1.4f, style.strokeWidth), phase = antsPhase)
    } else {
        val dash = floatArrayOf(8f, 6f)
        drawPath(
            path = path,
            color = style.secondaryStrokeColor,
            style = Stroke(
                width = (style.strokeWidth + 1.2f).coerceAtMost(4.0f),
                pathEffect = PathEffect.dashPathEffect(dash),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
        drawPath(
            path = path,
            color = style.strokeColor,
            style = Stroke(
                width = style.strokeWidth,
                pathEffect = PathEffect.dashPathEffect(dash),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

private fun DrawScope.drawMaskBackedRegion(
    region: CropRegion,
    zoom: Float,
    zoomY: Float,
    viewportOffset: Offset,
    style: RegionOverlayStyle,
    antsPhase: Float,
    geometry: MaskOverlayGeometry
) {
    val baseAlpha = when {
        region.selected -> max(style.fillColor.alpha, 0.18f)
        else -> max(style.fillColor.alpha, 0.08f)
    }
    geometry.fillRuns.forEach { run ->
        val resolvedAlpha = baseAlpha * (0.60f + 0.40f * (run.alpha / 255f))
        drawRect(
            color = style.fillColor.copy(alpha = resolvedAlpha.coerceIn(0f, 1f)),
            topLeft = Offset((region.x + run.startX) * zoom, (region.y + run.localY) * zoomY) + viewportOffset,
            size = Size((run.endX - run.startX + 1) * zoom, zoomY)
        )
    }

    if (region.selected) {
        geometry.boundarySegments.forEach { segment ->
            val start = segment.toStartOffset(region, zoom, zoomY, viewportOffset)
            val end = segment.toEndOffset(region, zoom, zoomY, viewportOffset)
            drawMarchingAntsLine(start = start, end = end, strokeWidth = max(1.0f, style.strokeWidth), phase = antsPhase)
        }
    } else {
        geometry.boundarySegments.forEach { segment ->
            val start = segment.toStartOffset(region, zoom, zoomY, viewportOffset)
            val end = segment.toEndOffset(region, zoom, zoomY, viewportOffset)
            drawLine(style.secondaryStrokeColor, start, end, strokeWidth = style.strokeWidth + 0.7f, cap = StrokeCap.Square)
            drawLine(style.strokeColor, start, end, strokeWidth = style.strokeWidth, cap = StrokeCap.Square)
        }
    }
}

private fun MaskBoundarySegment.toStartOffset(region: CropRegion, zoom: Float, zoomY: Float, viewportOffset: Offset): Offset =
    Offset((region.x + startX) * zoom, (region.y + startY) * zoomY) + viewportOffset

private fun MaskBoundarySegment.toEndOffset(region: CropRegion, zoom: Float, zoomY: Float, viewportOffset: Offset): Offset =
    Offset((region.x + endX) * zoom, (region.y + endY) * zoomY) + viewportOffset

private fun DrawScope.drawMarchingAntsPath(path: Path, strokeWidth: Float, phase: Float) {
    val pattern = floatArrayOf(6f, 6f)
    drawPath(
        path = path,
        color = Color.Black.copy(alpha = 0.92f),
        style = Stroke(
            width = strokeWidth + 0.2f,
            pathEffect = PathEffect.dashPathEffect(pattern, phase),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
    drawPath(
        path = path,
        color = Color.White.copy(alpha = 0.98f),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(pattern, phase + pattern.first()),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun DrawScope.drawMarchingAntsLine(start: Offset, end: Offset, strokeWidth: Float, phase: Float) {
    val pattern = floatArrayOf(6f, 6f)
    drawLine(
        color = Color.Black.copy(alpha = 0.92f),
        start = start,
        end = end,
        strokeWidth = strokeWidth + 0.25f,
        pathEffect = PathEffect.dashPathEffect(pattern, phase),
        cap = StrokeCap.Round
    )
    drawLine(
        color = Color.White.copy(alpha = 0.98f),
        start = start,
        end = end,
        strokeWidth = strokeWidth,
        pathEffect = PathEffect.dashPathEffect(pattern, phase + pattern.first()),
        cap = StrokeCap.Round
    )
}

internal inline fun forEachMaskBoundarySegment(
    width: Int,
    height: Int,
    isFilled: (Int, Int) -> Boolean,
    onSegment: (Int, Int, Int, Int) -> Unit
) {
    for (localY in 0 until height) {
        var localX = 0
        while (localX < width) {
            if (!isFilled(localX, localY) || (localY > 0 && isFilled(localX, localY - 1))) {
                localX += 1
                continue
            }
            val startX = localX
            while (
                localX + 1 < width &&
                isFilled(localX + 1, localY) &&
                (localY == 0 || !isFilled(localX + 1, localY - 1))
            ) {
                localX += 1
            }
            onSegment(startX, localY, localX + 1, localY)
            localX += 1
        }
    }

    for (localY in 0 until height) {
        var localX = 0
        while (localX < width) {
            if (!isFilled(localX, localY) || (localY + 1 < height && isFilled(localX, localY + 1))) {
                localX += 1
                continue
            }
            val startX = localX
            while (
                localX + 1 < width &&
                isFilled(localX + 1, localY) &&
                (localY == height - 1 || !isFilled(localX + 1, localY + 1))
            ) {
                localX += 1
            }
            onSegment(startX, localY + 1, localX + 1, localY + 1)
            localX += 1
        }
    }

    for (localX in 0 until width) {
        var localY = 0
        while (localY < height) {
            if (!isFilled(localX, localY) || (localX > 0 && isFilled(localX - 1, localY))) {
                localY += 1
                continue
            }
            val startY = localY
            while (
                localY + 1 < height &&
                isFilled(localX, localY + 1) &&
                (localX == 0 || !isFilled(localX - 1, localY + 1))
            ) {
                localY += 1
            }
            onSegment(localX, startY, localX, localY + 1)
            localY += 1
        }
    }

    for (localX in 0 until width) {
        var localY = 0
        while (localY < height) {
            if (!isFilled(localX, localY) || (localX + 1 < width && isFilled(localX + 1, localY))) {
                localY += 1
                continue
            }
            val startY = localY
            while (
                localY + 1 < height &&
                isFilled(localX, localY + 1) &&
                (localX == width - 1 || !isFilled(localX + 1, localY + 1))
            ) {
                localY += 1
            }
            onSegment(localX + 1, startY, localX + 1, localY + 1)
            localY += 1
        }
    }
}

internal fun overlayStyleForRegion(region: CropRegion, hoveredRegionId: String?): RegionOverlayStyle {
    val selected = region.selected
    val hovered = hoveredRegionId == region.id
    return when {
        selected -> RegionOverlayStyle(
            strokeColor = Color(0xFF111111),
            fillColor = Color(0x26F3A23C),
            accentColor = Color(0xFFF6A23D),
            secondaryStrokeColor = Color(0xAAFFF3D1),
            strokeWidth = 1.45f
        )
        hovered -> RegionOverlayStyle(
            strokeColor = Color(0xFF86B6FF),
            fillColor = Color(0x1273A9FF),
            accentColor = Color(0xFF4E87FF),
            secondaryStrokeColor = Color(0x7ADDEBFF),
            strokeWidth = 1.65f
        )
        else -> RegionOverlayStyle(
            strokeColor = Color(0xC06B7EA1),
            fillColor = Color(0x081F4A82),
            accentColor = Color(0xFF5C7CCB),
            secondaryStrokeColor = Color(0x2EEAF2FF),
            strokeWidth = 1.25f
        )
    }
}

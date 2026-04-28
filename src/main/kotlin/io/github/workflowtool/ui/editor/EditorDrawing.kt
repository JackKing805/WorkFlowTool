package io.github.workflowtool.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.hasMask
import kotlin.math.floor
import kotlin.math.max

internal data class RegionOverlayStyle(
    val strokeColor: Color,
    val fillColor: Color,
    val accentColor: Color,
    val secondaryStrokeColor: Color,
    val strokeWidth: Float
)

fun DrawScope.drawCheckerboard(size: Size, zoom: Float = 1f, viewportOffset: Offset = Offset.Zero) {
    val cell = (12f * zoom.coerceIn(0.5f, 4f)).coerceIn(8f, 42f)
    val startX = floor(-viewportOffset.x / cell) * cell + viewportOffset.x
    val startY = floor(-viewportOffset.y / cell) * cell + viewportOffset.y
    var y = startY
    var row = floor((y - viewportOffset.y) / cell).toInt()
    while (y < size.height) {
        var x = startX
        var column = floor((x - viewportOffset.x) / cell).toInt()
        while (x < size.width) {
            drawRect(
                color = if ((row + column) % 2 == 0) Color(0xFFBFC4CC) else Color(0xFFE1E4E8),
                topLeft = Offset(x, y),
                size = Size(cell, cell)
            )
            x += cell
            column += 1
        }
        y += cell
        row += 1
    }
    drawPixelGrid(size, zoom, viewportOffset)
}

private fun DrawScope.drawPixelGrid(size: Size, zoom: Float, viewportOffset: Offset) {
    if (zoom < 3f) return
    val spacing = zoom
    val alpha = ((zoom - 3f) / 5f).coerceIn(0.10f, 0.26f)
    val color = Color.Black.copy(alpha = alpha)
    var x = viewportOffset.x % spacing
    if (x > 0f) x -= spacing
    while (x <= size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += spacing
    }
    var y = viewportOffset.y % spacing
    if (y > 0f) y -= spacing
    while (y <= size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += spacing
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
    viewportOffset: Offset,
    style: RegionOverlayStyle,
    antsPhase: Float
) {
    if (region.hasMask()) {
        drawMaskBackedRegion(region, zoom, viewportOffset, style, antsPhase)
        return
    }

    val left = region.x * zoom + viewportOffset.x
    val top = region.y * zoom + viewportOffset.y
    val right = region.right * zoom + viewportOffset.x
    val bottom = region.bottom * zoom + viewportOffset.y
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
            style = Stroke(width = (style.strokeWidth + 1.2f).coerceAtMost(4.0f), pathEffect = PathEffect.dashPathEffect(dash))
        )
        drawPath(
            path = path,
            color = style.strokeColor,
            style = Stroke(width = style.strokeWidth, pathEffect = PathEffect.dashPathEffect(dash))
        )
    }
}

private fun DrawScope.drawMaskBackedRegion(
    region: CropRegion,
    zoom: Float,
    viewportOffset: Offset,
    style: RegionOverlayStyle,
    antsPhase: Float
) {
    val baseAlpha = when {
        region.selected -> max(style.fillColor.alpha, 0.18f)
        else -> max(style.fillColor.alpha, 0.08f)
    }
    for (localY in 0 until region.maskHeight) {
        var localX = 0
        while (localX < region.maskWidth) {
            val alpha = region.alphaMask[localY * region.maskWidth + localX].coerceIn(0, 255)
            if (alpha <= 0) {
                localX += 1
                continue
            }
            val startX = localX
            var endX = localX
            while (endX + 1 < region.maskWidth && region.alphaMask[localY * region.maskWidth + endX + 1] > 0) {
                endX += 1
            }
            val resolvedAlpha = baseAlpha * (0.60f + 0.40f * (alpha / 255f))
            drawRect(
                color = style.fillColor.copy(alpha = resolvedAlpha.coerceIn(0f, 1f)),
                topLeft = Offset((region.x + startX) * zoom, (region.y + localY) * zoom) + viewportOffset,
                size = Size((endX - startX + 1) * zoom, zoom)
            )
            localX = endX + 1
        }
    }

    if (region.selected) {
        forEachMaskBoundarySegment(
            originX = region.x,
            originY = region.y,
            width = region.maskWidth,
            height = region.maskHeight,
            zoom = zoom,
            viewportOffset = viewportOffset,
            isFilled = { x, y -> region.alphaMask[y * region.maskWidth + x] > 0 }
        ) { start, end ->
            drawMarchingAntsLine(start = start, end = end, strokeWidth = max(1.25f, style.strokeWidth), phase = antsPhase)
        }
    } else {
        forEachMaskBoundarySegment(
            originX = region.x,
            originY = region.y,
            width = region.maskWidth,
            height = region.maskHeight,
            zoom = zoom,
            viewportOffset = viewportOffset,
            isFilled = { x, y -> region.alphaMask[y * region.maskWidth + x] > 0 }
        ) { start, end ->
            drawLine(style.secondaryStrokeColor, start, end, strokeWidth = style.strokeWidth + 0.9f)
            drawLine(style.strokeColor, start, end, strokeWidth = style.strokeWidth)
        }
    }
}

private fun DrawScope.drawMarchingAntsPath(path: Path, strokeWidth: Float, phase: Float) {
    val pattern = floatArrayOf(6f, 6f)
    drawPath(
        path = path,
        color = Color.Black.copy(alpha = 0.92f),
        style = Stroke(width = strokeWidth + 0.2f, pathEffect = PathEffect.dashPathEffect(pattern, phase))
    )
    drawPath(
        path = path,
        color = Color.White.copy(alpha = 0.98f),
        style = Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(pattern, phase + pattern.first()))
    )
}

private fun DrawScope.drawMarchingAntsLine(start: Offset, end: Offset, strokeWidth: Float, phase: Float) {
    val pattern = floatArrayOf(6f, 6f)
    drawLine(
        color = Color.Black.copy(alpha = 0.92f),
        start = start,
        end = end,
        strokeWidth = strokeWidth + 0.25f,
        pathEffect = PathEffect.dashPathEffect(pattern, phase)
    )
    drawLine(
        color = Color.White.copy(alpha = 0.98f),
        start = start,
        end = end,
        strokeWidth = strokeWidth,
        pathEffect = PathEffect.dashPathEffect(pattern, phase + pattern.first())
    )
}

private inline fun forEachMaskBoundarySegment(
    originX: Int,
    originY: Int,
    width: Int,
    height: Int,
    zoom: Float,
    viewportOffset: Offset,
    isFilled: (Int, Int) -> Boolean,
    onSegment: (Offset, Offset) -> Unit
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
            onSegment(
                Offset((originX + startX) * zoom, (originY + localY) * zoom) + viewportOffset,
                Offset((originX + localX + 1) * zoom, (originY + localY) * zoom) + viewportOffset
            )
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
            onSegment(
                Offset((originX + startX) * zoom, (originY + localY + 1) * zoom) + viewportOffset,
                Offset((originX + localX + 1) * zoom, (originY + localY + 1) * zoom) + viewportOffset
            )
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
            onSegment(
                Offset((originX + localX) * zoom, (originY + startY) * zoom) + viewportOffset,
                Offset((originX + localX) * zoom, (originY + localY + 1) * zoom) + viewportOffset
            )
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
            onSegment(
                Offset((originX + localX + 1) * zoom, (originY + startY) * zoom) + viewportOffset,
                Offset((originX + localX + 1) * zoom, (originY + localY + 1) * zoom) + viewportOffset
            )
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

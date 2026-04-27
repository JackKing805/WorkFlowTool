package io.github.workflowtool.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import io.github.workflowtool.application.MagicSelectionPreview
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.RegionPoint
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.ui.theme.Accent
import io.github.workflowtool.ui.theme.Panel
import io.github.workflowtool.ui.theme.SoftBorder
import kotlin.math.abs
import kotlin.math.roundToInt
import java.awt.Cursor
import java.util.UUID

internal data class RegionOverlayStyle(
    val strokeColor: Color,
    val fillColor: Color,
    val accentColor: Color,
    val secondaryStrokeColor: Color,
    val strokeWidth: Float,
    val showAllHandles: Boolean,
    val showHandles: Boolean,
    val emphasizeCorners: Boolean,
    val handleOuterRadius: Float,
    val handleInnerRadius: Float
)

fun DrawScope.drawCheckerboard(size: Size) {
    val cell = 16f
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = 0f
        var column = 0
        while (x < size.width) {
            drawRect(
                color = if ((row + column) % 2 == 0) Color(0xFF1E232B) else Color(0xFF252B34),
                topLeft = Offset(x, y),
                size = Size(cell, cell)
            )
            x += cell
            column++
        }
        y += cell
        row++
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

fun DrawScope.drawHandle(center: Offset, outerRadius: Float, innerRadius: Float, accentColor: Color, outerColor: Color, alpha: Float = 1f) {
    drawCircle(color = outerColor.copy(alpha = alpha), radius = outerRadius, center = center)
    drawCircle(color = accentColor.copy(alpha = alpha), radius = innerRadius, center = center)
}

internal fun DrawScope.drawRegionOutline(region: CropRegion, zoom: Float, viewportOffset: Offset, style: RegionOverlayStyle) {
    val points = region.editPoints.map { Offset(it.x * zoom, it.y * zoom) + viewportOffset }
    if (points.size < 3) return

    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    val dash = if (region.selected) floatArrayOf(10f, 5f) else floatArrayOf(8f, 6f)
    drawPath(path, style.fillColor)
    drawPath(
        path,
        style.secondaryStrokeColor,
        style = Stroke(width = (style.strokeWidth + 1.3f).coerceAtMost(4.4f), pathEffect = PathEffect.dashPathEffect(dash))
    )
    drawPath(
        path,
        style.strokeColor,
        style = Stroke(width = style.strokeWidth, pathEffect = PathEffect.dashPathEffect(dash))
    )
    if (!style.showHandles) return

    val emphasized = emphasizedHandleIndices(region)
    points.forEachIndexed { index, point ->
        if (!style.showAllHandles && index !in emphasized) return@forEachIndexed
        val isEmphasized = !style.emphasizeCorners || index in emphasized
        val outerRadius = if (isEmphasized) style.handleOuterRadius else (style.handleOuterRadius - 0.9f).coerceAtLeast(2.35f)
        val innerRadius = if (isEmphasized) style.handleInnerRadius else (style.handleInnerRadius - 0.55f).coerceAtLeast(1.25f)
        val alpha = if (isEmphasized) 1f else 0.58f
        drawHandle(point, outerRadius, innerRadius, style.accentColor, Color.White, alpha)
    }
}

fun DrawScope.drawMagicMask(preview: MagicSelectionPreview, zoom: Float, viewportOffset: Offset) {
    val fillColor = Color(0x183DB2FF)
    val innerFillColor = Color(0x128ED1FF)
    val edgeColor = Color(0xCCB9E2FF)
    val width = preview.imageWidth
    val height = preview.imageHeight
    val mask = preview.mask

    for (y in 0 until height) {
        var x = 0
        while (x < width) {
            val start = y * width + x
            if (!mask[start]) {
                x += 1
                continue
            }
            var endX = x
            while (endX + 1 < width && mask[y * width + endX + 1]) {
                endX += 1
            }
            drawRect(
                color = fillColor,
                topLeft = Offset(x * zoom, y * zoom) + viewportOffset,
                size = Size((endX - x + 1) * zoom, zoom)
            )
            if (zoom >= 4f) {
                drawRect(
                    color = innerFillColor,
                    topLeft = Offset((x + 0.15f) * zoom, (y + 0.15f) * zoom) + viewportOffset,
                    size = Size((endX - x + 0.7f) * zoom, 0.7f * zoom)
                )
            }
            if (y == 0 || !mask[(y - 1) * width + x]) {
                drawLine(
                    color = edgeColor,
                    start = Offset(x * zoom, y * zoom) + viewportOffset,
                    end = Offset((endX + 1) * zoom, y * zoom) + viewportOffset,
                    strokeWidth = 1.65f
                )
            }
            if (y == height - 1 || !mask[(y + 1) * width + x]) {
                drawLine(
                    color = edgeColor,
                    start = Offset(x * zoom, (y + 1) * zoom) + viewportOffset,
                    end = Offset((endX + 1) * zoom, (y + 1) * zoom) + viewportOffset,
                    strokeWidth = 1.65f
                )
            }
            if (x == 0 || !mask[y * width + x - 1]) {
                drawLine(
                    color = edgeColor,
                    start = Offset(x * zoom, y * zoom) + viewportOffset,
                    end = Offset(x * zoom, (y + 1) * zoom) + viewportOffset,
                    strokeWidth = 1.4f
                )
            }
            if (endX == width - 1 || !mask[y * width + endX + 1]) {
                drawLine(
                    color = edgeColor,
                    start = Offset((endX + 1) * zoom, y * zoom) + viewportOffset,
                    end = Offset((endX + 1) * zoom, (y + 1) * zoom) + viewportOffset,
                    strokeWidth = 1.4f
                )
            }
            x = endX + 1
        }
    }
}

fun DrawScope.drawSeedMarker(seedX: Int, seedY: Int, zoom: Float, viewportOffset: Offset) {
    val center = Offset((seedX + 0.5f) * zoom, (seedY + 0.5f) * zoom) + viewportOffset
    val radius = (zoom * 0.65f).coerceIn(4f, 10f)
    drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 2f))
    drawCircle(Color(0xFF1E9BFF), radius = radius * 0.45f, center = center)
}

internal fun overlayStyleForRegion(region: CropRegion, hoveredRegionId: String?): RegionOverlayStyle {
    val selected = region.selected
    val hovered = hoveredRegionId == region.id
    return when {
        selected -> RegionOverlayStyle(
            strokeColor = Color(0xFF6EA2FF),
            fillColor = Color(0x123F83FF),
            accentColor = Color(0xFF2F6BFF),
            secondaryStrokeColor = Color(0xCCF5F9FF),
            strokeWidth = 2.3f,
            showAllHandles = true,
            showHandles = true,
            emphasizeCorners = true,
            handleOuterRadius = handleVisualOuterRadius(selected = true),
            handleInnerRadius = handleVisualInnerRadius(selected = true)
        )
        hovered -> RegionOverlayStyle(
            strokeColor = Color(0xFF82B5FF),
            fillColor = Color(0x0A74A5FF),
            accentColor = Color(0xFF4E87FF),
            secondaryStrokeColor = Color(0x66EAF2FF),
            strokeWidth = 1.8f,
            showAllHandles = region.points.isNotEmpty(),
            showHandles = true,
            emphasizeCorners = true,
            handleOuterRadius = handleVisualOuterRadius(selected = false),
            handleInnerRadius = handleVisualInnerRadius(selected = false)
        )
        else -> RegionOverlayStyle(
            strokeColor = Color(0xD05F7DAE),
            fillColor = Color(0x061D4A8A),
            accentColor = Color(0xFF5C7CCB),
            secondaryStrokeColor = Color(0x2EEAF2FF),
            strokeWidth = 1.4f,
            showAllHandles = false,
            showHandles = false,
            emphasizeCorners = false,
            handleOuterRadius = handleVisualOuterRadius(selected = false),
            handleInnerRadius = handleVisualInnerRadius(selected = false)
        )
    }
}

private fun emphasizedHandleIndices(region: CropRegion): Set<Int> {
    val points = region.editPoints
    if (points.isEmpty()) return emptySet()
    if (region.points.isEmpty()) {
        return setOf(0, 1, 2, 3).filter { it < points.size }.toSet()
    }
    return if (points.size <= 4) {
        points.indices.toSet()
    } else {
        setOf(0, points.size / 3, (points.size * 2) / 3, points.lastIndex)
    }
}

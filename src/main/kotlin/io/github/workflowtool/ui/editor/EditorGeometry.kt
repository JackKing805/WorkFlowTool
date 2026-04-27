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

fun screenToImage(point: Offset, viewportOffset: Offset, zoom: Float): Offset = (point - viewportOffset) / zoom

private fun Offset.div(value: Float) = Offset(x / value, y / value)

internal data class RegionHitTarget(
    val region: CropRegion,
    val nearEdge: Boolean
)

internal fun handleHitRadius(zoom: Float): Float = (13f / zoom).coerceAtLeast(5.25f)

internal fun handleVisualOuterRadius(selected: Boolean): Float = if (selected) 4.2f else 3.4f

internal fun handleVisualInnerRadius(selected: Boolean): Float = if (selected) 2.4f else 1.9f

fun findRegionHit(regions: List<CropRegion>, point: Offset): CropRegion? {
    return findRegionHitTarget(regions, point)?.region
}

internal fun findRegionHitTarget(regions: List<CropRegion>, point: Offset, zoom: Float = 1f): RegionHitTarget? {
    val ordered = regions.indices
        .sortedWith(
            compareByDescending<Int> { regions[it].selected }
                .thenByDescending { candidate -> candidate }
        )
    ordered.forEach { index ->
        val region = regions[index]
        if (!region.visible || !pointInsideRegion(region, point)) return@forEach
        return RegionHitTarget(region, pointNearRegionEdge(region, point, zoom))
    }
    return null
}

private fun pointInsideRegion(region: CropRegion, point: Offset): Boolean {
    val points = region.editPoints
    if (points.size < 3) {
        return point.x in region.x.toFloat()..region.right.toFloat() &&
            point.y in region.y.toFloat()..region.bottom.toFloat()
    }

    var inside = false
    var previous = points.last()
    for (current in points) {
        val crosses = (current.y > point.y) != (previous.y > point.y)
        if (crosses) {
            val intersectionX = (previous.x - current.x) * (point.y - current.y) / (previous.y - current.y).toFloat() + current.x
            if (point.x < intersectionX) inside = !inside
        }
        previous = current
    }
    return inside
}

fun findPointHit(region: CropRegion, point: Offset, zoom: Float): Int? {
    if (!region.visible) return null
    val radius = handleHitRadius(zoom)
    return region.editPoints.indexOfLast { abs(point.x - it.x) <= radius && abs(point.y - it.y) <= radius }
        .takeIf { it >= 0 }
}

fun findHandleHit(regions: List<CropRegion>, point: Offset, zoom: Float): Pair<CropRegion, Int>? {
    val ordered = regions.indices
        .sortedWith(
            compareByDescending<Int> { regions[it].selected }
                .thenByDescending { candidate -> candidate }
        )
    for (index in ordered) {
        val region = regions[index]
        val pointIndex = findPointHit(region, point, zoom)
        if (pointIndex != null) return region to pointIndex
    }
    return null
}

fun moveRegionPoint(region: CropRegion, pointIndex: Int, point: Offset, imageWidth: Int, imageHeight: Int): CropRegion {
    val points = region.editPoints.toMutableList()
    if (pointIndex !in points.indices) return region
    points[pointIndex] = RegionPoint(
        point.x.roundToInt().coerceIn(0, imageWidth),
        point.y.roundToInt().coerceIn(0, imageHeight)
    )
    return region.withPoints(points)
}

fun moveRegion(region: CropRegion, dx: Int, dy: Int, imageWidth: Int, imageHeight: Int): CropRegion {
    val clampedDx = dx.coerceIn(-region.x, imageWidth - region.right)
    val clampedDy = dy.coerceIn(-region.y, imageHeight - region.bottom)
    val points = region.editPoints.map { RegionPoint(it.x + clampedDx, it.y + clampedDy) }
    return region.withPoints(points)
}

fun addRegionPoint(region: CropRegion, point: Offset, imageWidth: Int, imageHeight: Int): CropRegion {
    val newPoint = RegionPoint(
        point.x.roundToInt().coerceIn(0, imageWidth),
        point.y.roundToInt().coerceIn(0, imageHeight)
    )
    val points = region.editPoints
    val insertAt = nearestSegmentIndex(points, newPoint) + 1
    return region.withPoints(points.toMutableList().apply { add(insertAt, newPoint) })
}

fun removeRegionPoint(region: CropRegion, pointIndex: Int): CropRegion {
    val points = region.editPoints
    if (points.size <= 3 || pointIndex !in points.indices) return region
    return region.withPoints(points.toMutableList().apply { removeAt(pointIndex) })
}

fun List<CropRegion>.replaceRegion(updated: CropRegion): List<CropRegion> =
    map { if (it.id == updated.id) updated else it }

private fun CropRegion.withPoints(points: List<RegionPoint>): CropRegion {
    val minX = points.minOf { it.x }
    val minY = points.minOf { it.y }
    val maxX = points.maxOf { it.x }
    val maxY = points.maxOf { it.y }
    return copy(
        x = minX,
        y = minY,
        width = (maxX - minX).coerceAtLeast(1),
        height = (maxY - minY).coerceAtLeast(1),
        points = points
    )
}

private fun nearestSegmentIndex(points: List<RegionPoint>, point: RegionPoint): Int {
    var bestIndex = 0
    var bestDistance = Float.MAX_VALUE
    for (index in points.indices) {
        val next = points[(index + 1) % points.size]
        val distance = distanceToSegment(point, points[index], next)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

private fun distanceToSegment(point: RegionPoint, start: RegionPoint, end: RegionPoint): Float {
    val dx = (end.x - start.x).toFloat()
    val dy = (end.y - start.y).toFloat()
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return abs(point.x - start.x) + abs(point.y - start.y).toFloat()
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val projectionX = start.x + t * dx
    val projectionY = start.y + t * dy
    val px = point.x - projectionX
    val py = point.y - projectionY
    return px * px + py * py
}

private fun pointNearRegionEdge(region: CropRegion, point: Offset, zoom: Float): Boolean {
    val points = region.editPoints
    if (points.size < 2) return false
    val threshold = (11f / zoom).coerceAtLeast(4.5f)
    for (index in points.indices) {
        val next = points[(index + 1) % points.size]
        if (distanceToSegment(
                RegionPoint(point.x.roundToInt(), point.y.roundToInt()),
                points[index],
                next
            ) <= threshold * threshold
        ) {
            return true
        }
    }
    return false
}

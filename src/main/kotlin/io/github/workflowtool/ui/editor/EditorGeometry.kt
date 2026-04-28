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
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.MaskEditMode
import io.github.workflowtool.model.applyBrushToMask
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.model.maskAlphaAt
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.ui.theme.Accent
import io.github.workflowtool.ui.theme.Panel
import io.github.workflowtool.ui.theme.SoftBorder
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
    if (region.hasMask()) return region.maskAlphaAt(point.x.roundToInt(), point.y.roundToInt()) > 12
    return point.x >= region.x && point.x <= region.right && point.y >= region.y && point.y <= region.bottom
}

fun moveRegion(region: CropRegion, dx: Int, dy: Int, imageWidth: Int, imageHeight: Int): CropRegion {
    val clampedDx = dx.coerceIn(-region.x, imageWidth - region.right)
    val clampedDy = dy.coerceIn(-region.y, imageHeight - region.bottom)
    return region.copy(x = region.x + clampedDx, y = region.y + clampedDy)
}

fun editSelectionMask(
    region: CropRegion,
    point: Offset,
    radius: Int,
    mode: MaskEditMode,
    imageWidth: Int,
    imageHeight: Int
): CropRegion = region.applyBrushToMask(
    centerX = point.x.roundToInt().coerceIn(0, imageWidth),
    centerY = point.y.roundToInt().coerceIn(0, imageHeight),
    radius = radius,
    mode = mode,
    imageWidth = imageWidth,
    imageHeight = imageHeight
)

fun List<CropRegion>.replaceRegion(updated: CropRegion): List<CropRegion> =
    map { if (it.id == updated.id) updated else it }

private fun pointNearRegionEdge(region: CropRegion, point: Offset, zoom: Float): Boolean {
    if (region.hasMask()) return region.maskAlphaAt(point.x.roundToInt(), point.y.roundToInt()) in 1..220
    val threshold = (11f / zoom).coerceAtLeast(4.5f)
    val x = point.x
    val y = point.y
    val inside = x >= region.x && x <= region.right && y >= region.y && y <= region.bottom
    if (!inside) return false
    return minOf(
        kotlin.math.abs(x - region.x),
        kotlin.math.abs(x - region.right),
        kotlin.math.abs(y - region.y),
        kotlin.math.abs(y - region.bottom)
    ) <= threshold
}

package io.github.workflowtool.ui.editor

import androidx.compose.ui.geometry.Offset
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.MaskEditMode
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.model.applyBrushToMask
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.model.maskAlphaAt
import kotlin.math.roundToInt

data class EditorViewportTransform(
    val viewportOffset: Offset,
    val zoom: Float,
    val imageWidth: Int?,
    val imageHeight: Int?
) {
    val renderOffset: Offset = Offset(
        viewportOffset.x.roundToInt().toFloat(),
        viewportOffset.y.roundToInt().toFloat()
    )
    val renderWidth: Int? = imageWidth?.let { (it * zoom).roundToInt().coerceAtLeast(1) }
    val renderHeight: Int? = imageHeight?.let { (it * zoom).roundToInt().coerceAtLeast(1) }
    val renderZoomX: Float = imageWidth?.let { width ->
        (renderWidth ?: (width * zoom).roundToInt().coerceAtLeast(1)).toFloat() / width.coerceAtLeast(1)
    } ?: zoom
    val renderZoomY: Float = imageHeight?.let { height ->
        (renderHeight ?: (height * zoom).roundToInt().coerceAtLeast(1)).toFloat() / height.coerceAtLeast(1)
    } ?: zoom

    fun screenToImage(point: Offset): Offset = Offset(
        x = (point.x - renderOffset.x) / renderZoomX,
        y = (point.y - renderOffset.y) / renderZoomY
    )

    fun imageToScreen(point: Offset): Offset = Offset(
        x = point.x * renderZoomX + renderOffset.x,
        y = point.y * renderZoomY + renderOffset.y
    )

    fun screenDeltaToImageDelta(delta: Offset): Offset = Offset(
        x = delta.x / renderZoomX,
        y = delta.y / renderZoomY
    )
}

internal data class RegionHitTarget(
    val region: CropRegion,
    val nearEdge: Boolean
)

internal data class ImageSelectionBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

internal fun imageSelectionBounds(start: Offset, end: Offset, imageWidth: Int, imageHeight: Int): ImageSelectionBounds =
    ImageSelectionBounds(
        left = minOf(start.x, end.x).roundToInt().coerceIn(0, imageWidth),
        top = minOf(start.y, end.y).roundToInt().coerceIn(0, imageHeight),
        right = maxOf(start.x, end.x).roundToInt().coerceIn(0, imageWidth),
        bottom = maxOf(start.y, end.y).roundToInt().coerceIn(0, imageHeight)
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

internal fun maskEditModeForModifiers(altPressed: Boolean, shiftPressed: Boolean): MaskEditMode =
    when {
        altPressed -> MaskEditMode.Subtract
        shiftPressed -> MaskEditMode.Add
        else -> MaskEditMode.Replace
    }

internal fun selectionBrushRadius(brushSizePx: Int, zoom: Float): Int =
    (brushSizePx / zoom.coerceAtLeast(0.25f)).roundToInt().coerceIn(4, 96)

internal fun canRefineHitRegion(toolMode: ToolMode): Boolean =
    toolMode != ToolMode.Move

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

fun regionIntersectsBounds(region: CropRegion, left: Int, top: Int, right: Int, bottom: Int): Boolean {
    if (!region.visible) return false
    return region.right >= left && region.x <= right && region.bottom >= top && region.y <= bottom
}

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

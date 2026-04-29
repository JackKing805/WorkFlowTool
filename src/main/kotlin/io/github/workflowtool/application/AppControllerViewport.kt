package io.github.workflowtool.application

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.workflowtool.model.CropRegion
import kotlin.math.roundToInt

fun AppController.adjustZoom(delta: Float) {
    if (delta == 0f) return
    val next = (zoom + delta).coerceIn(0.1f, 8f)
    if (next != zoom) {
        zoom = next
        clampViewport()
    }
}

fun AppController.updateZoom(value: Float) {
    val next = value.coerceIn(0.1f, 8f)
    if (next != zoom) {
        zoom = next
        clampViewport()
    }
}

fun AppController.zoomAroundImagePoint(factor: Float, anchor: Offset, imagePoint: Offset) {
    if (factor == 0f) return
    val currentZoom = zoom
    val nextZoom = (currentZoom * factor).coerceIn(0.1f, 8f)
    if (nextZoom == currentZoom) return
    val loaded = image
    val renderZoomX = loaded?.let {
        (it.width * nextZoom).toIntRoundedAtLeastOne().toFloat() / it.width.coerceAtLeast(1)
    } ?: nextZoom
    val renderZoomY = loaded?.let {
        (it.height * nextZoom).toIntRoundedAtLeastOne().toFloat() / it.height.coerceAtLeast(1)
    } ?: nextZoom
    zoom = nextZoom
    viewportOffset = Offset(
        x = anchor.x - imagePoint.x * renderZoomX,
        y = anchor.y - imagePoint.y * renderZoomY
    )
    clampViewport()
}

private fun Float.toIntRoundedAtLeastOne(): Int = roundToInt().coerceAtLeast(1)

fun AppController.panViewport(delta: Offset) {
    if (delta == Offset.Zero) return
    viewportOffset += delta
    clampViewport()
}

fun AppController.updateViewportSize(viewport: Size) {
    if (viewport == viewportSize) return
    viewportSize = viewport
    clampViewport()
}

fun AppController.updatePointerHover(point: Offset?) {
    val loaded = image
    hoveredImagePoint = point?.takeIf {
        loaded != null &&
            it.x >= 0f &&
            it.y >= 0f &&
            it.x <= loaded.width.toFloat() &&
            it.y <= loaded.height.toFloat()
    }
}

fun AppController.fitToViewport() {
    val loaded = image ?: run {
        updateZoom(1.0f)
        viewportOffset = Offset.Zero
        return
    }
    val viewport = viewportSize
    if (viewport.width <= 0f || viewport.height <= 0f) return
    val nextZoom = minOf(viewport.width / loaded.width, viewport.height / loaded.height).coerceIn(0.1f, 8f)
    zoom = nextZoom
    viewportOffset = Offset(
        x = (viewport.width - loaded.width * nextZoom) / 2f,
        y = (viewport.height - loaded.height * nextZoom) / 2f
    )
    clampViewport()
}

fun AppController.fitSelectionToViewport() {
    focusRegion(selectedRegion ?: run {
        fitToViewport()
        return
    }, fit = true)
}

fun AppController.focusRegion(regionId: String, fit: Boolean = false) {
    val region = regions.lastOrNull { it.id == regionId } ?: return
    focusRegion(region, fit)
}

fun AppController.toggleGrid() {
    showGrid = !showGrid
    persistSettings()
}

private fun AppController.focusRegion(region: CropRegion, fit: Boolean) {
    if (viewportSize.width <= 0f || viewportSize.height <= 0f) return
    val nextZoom = if (fit) {
        val padding = 40f
        minOf(
            viewportSize.width / (region.width + padding),
            viewportSize.height / (region.height + padding)
        ).coerceIn(0.1f, 8f)
    } else {
        zoom
    }
    zoom = nextZoom
    viewportOffset = Offset(
        x = viewportSize.width / 2f - (region.x + region.width / 2f) * nextZoom,
        y = viewportSize.height / 2f - (region.y + region.height / 2f) * nextZoom
    )
    clampViewport()
}

private fun AppController.clampViewport() {
    val viewport = viewportSize
    if (viewport.width <= 0f || viewport.height <= 0f) return
}

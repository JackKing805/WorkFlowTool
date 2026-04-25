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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
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
import java.util.UUID

private enum class DragKind {
    Pan,
    RegionEdit,
    Draw,
    Magic
}

private data class DragSession(
    val kind: DragKind,
    val regionId: String?,
    val pointIndex: Int?,
    val baseRegions: List<CropRegion>,
    val start: Offset
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditorCanvas(
    modifier: Modifier = Modifier,
    bitmap: ImageBitmap?,
    imageWidth: Int?,
    imageHeight: Int?,
    regions: List<CropRegion>,
    zoom: Float,
    viewportOffset: Offset,
    showGrid: Boolean,
    magicPreview: MagicSelectionPreview?,
    toolMode: ToolMode,
    backgroundPickArmed: Boolean,
    onViewport: (Size) -> Unit,
    onPan: (Offset) -> Unit,
    onZoom: (Float, Offset) -> Unit,
    onCommit: (String, List<CropRegion>) -> Unit,
    onSelect: (String, Boolean) -> Unit,
    onMagicSelect: (Offset) -> Unit,
    onMagicExtend: (Offset) -> Unit,
    onBackgroundPick: (Offset) -> Unit,
    onHover: (Offset?) -> Unit,
    onDeleteRegion: (String) -> Unit,
    onToggleRegionVisibility: (String) -> Unit,
    onFocusRegion: (String, Boolean) -> Unit,
    onOpenRegionPreview: (String) -> Unit,
    onFitToViewport: () -> Unit,
    onClearRegions: () -> Unit
) {
    var dragSession by remember { mutableStateOf<DragSession?>(null) }
    var workingRegions by remember { mutableStateOf<List<CropRegion>?>(null) }
    var draftRegion by remember { mutableStateOf<CropRegion?>(null) }
    var contextMenu by remember { mutableStateOf<CanvasContextMenuState?>(null) }
    val latestRegions by rememberUpdatedState(regions)
    val latestZoom by rememberUpdatedState(zoom)
    val latestViewportOffset by rememberUpdatedState(viewportOffset)
    val latestToolMode by rememberUpdatedState(toolMode)
    val latestBackgroundPickArmed by rememberUpdatedState(backgroundPickArmed)

    val renderedRegions = workingRegions ?: regions
    val visibleOverlayRegions = (renderedRegions + listOfNotNull(draftRegion)).filter { it.visible }
    val maxWidth = imageWidth ?: 1024
    val maxHeight = imageHeight ?: 1024

    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Panel)
            .border(1.dp, SoftBorder, RoundedCornerShape(5.dp))
            .onSizeChanged { onViewport(it.toSize()) }
            .onPointerEvent(PointerEventType.Press) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (!event.buttons.isSecondaryPressed) return@onPointerEvent
                val imagePoint = screenToImage(change.position, latestViewportOffset, latestZoom)
                val hit = findRegionHit(latestRegions, imagePoint)
                if (hit != null) {
                    onSelect(hit.id, false)
                }
                contextMenu = CanvasContextMenuState(
                    regionId = hit?.id,
                    imagePoint = imagePoint,
                    pointIndex = hit?.let { findPointHit(it, imagePoint, latestZoom) },
                    visible = true,
                    offset = IntOffset(change.position.x.roundToInt(), change.position.y.roundToInt())
                )
            }
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val imagePoint = screenToImage(offset, latestViewportOffset, latestZoom)
                            if (latestToolMode == ToolMode.Magic) {
                                if (findRegionHit(latestRegions, imagePoint) != null) return@detectTapGestures
                                onMagicSelect(imagePoint)
                            }
                        },
                        onTap = { offset ->
                            val imagePoint = screenToImage(offset, latestViewportOffset, latestZoom)
                            if (latestBackgroundPickArmed) {
                                onBackgroundPick(imagePoint)
                                return@detectTapGestures
                            }
                            contextMenu = null
                            val hit = findRegionHit(latestRegions, imagePoint)
                            if (hit != null) {
                                onSelect(hit.id, false)
                                return@detectTapGestures
                            }
                            if (latestToolMode == ToolMode.Magic) {
                                onMagicSelect(imagePoint)
                            }
                        }
                    )
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    val point = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    onHover(screenToImage(point, latestViewportOffset, latestZoom))
                }
                .onPointerEvent(PointerEventType.Exit) {
                    onHover(null)
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val factor = if (change.scrollDelta.y < 0f) 1.1f else 1f / 1.1f
                    onZoom(factor, change.position)
                }
                .pointerInput(imageWidth, imageHeight) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val imagePoint = screenToImage(offset, latestViewportOffset, latestZoom)
                            val handleHit = findHandleHit(latestRegions, imagePoint, latestZoom)
                            val hit = findRegionHit(latestRegions, imagePoint)
                            contextMenu = null
                            when {
                                latestToolMode == ToolMode.Move -> {
                                    dragSession = DragSession(DragKind.Pan, null, null, latestRegions, offset)
                                    workingRegions = null
                                }
                                handleHit != null || hit != null -> {
                                    val active = handleHit?.first ?: hit!!
                                    val base = latestRegions.map {
                                        if (it.id == active.id) it.copy(selected = true) else if (!it.selected) it else it.copy(selected = false)
                                    }
                                    dragSession = DragSession(DragKind.RegionEdit, active.id, handleHit?.second, base, imagePoint)
                                    workingRegions = base
                                    onSelect(active.id, false)
                                }
                                latestToolMode == ToolMode.Magic -> {
                                    dragSession = DragSession(DragKind.Magic, null, null, latestRegions, imagePoint)
                                    workingRegions = null
                                    onMagicSelect(imagePoint)
                                }
                                latestToolMode != ToolMode.Draw -> {
                                    dragSession = DragSession(DragKind.Pan, null, null, latestRegions, offset)
                                    workingRegions = null
                                }
                                else -> {
                                    dragSession = DragSession(DragKind.Draw, null, null, latestRegions, imagePoint)
                                    draftRegion = CropRegion(
                                        UUID.randomUUID().toString(),
                                        imagePoint.x.roundToInt(),
                                        imagePoint.y.roundToInt(),
                                        1,
                                        1,
                                        selected = true
                                    )
                                    workingRegions = null
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val session = dragSession ?: return@detectDragGestures
                            when (session.kind) {
                                DragKind.Pan -> onPan(dragAmount)
                                DragKind.RegionEdit -> {
                                    val current = screenToImage(change.position, latestViewportOffset, latestZoom)
                                    val next = if (session.pointIndex != null) {
                                        session.baseRegions.map {
                                            if (it.id == session.regionId) moveRegionPoint(it, session.pointIndex, current, maxWidth, maxHeight) else it
                                        }
                                    } else {
                                        val dx = (dragAmount.x / latestZoom).roundToInt()
                                        val dy = (dragAmount.y / latestZoom).roundToInt()
                                        (workingRegions ?: session.baseRegions).map {
                                            if (it.selected) {
                                                moveRegion(it, dx, dy, maxWidth, maxHeight)
                                            } else {
                                                it
                                            }
                                        }
                                    }
                                    workingRegions = next
                                }
                                DragKind.Draw -> {
                                    val start = session.start
                                    val current = screenToImage(change.position, latestViewportOffset, latestZoom)
                                    val x = minOf(start.x, current.x).roundToInt().coerceIn(0, maxWidth)
                                    val y = minOf(start.y, current.y).roundToInt().coerceIn(0, maxHeight)
                                    val right = maxOf(start.x, current.x).roundToInt().coerceIn(0, maxWidth)
                                    val bottom = maxOf(start.y, current.y).roundToInt().coerceIn(0, maxHeight)
                                    draftRegion = CropRegion(
                                        id = draftRegion?.id ?: UUID.randomUUID().toString(),
                                        x = x,
                                        y = y,
                                        width = (right - x).coerceAtLeast(1),
                                        height = (bottom - y).coerceAtLeast(1),
                                        selected = true
                                    )
                                }
                                DragKind.Magic -> {
                                    onMagicExtend(screenToImage(change.position, latestViewportOffset, latestZoom))
                                }
                            }
                        },
                        onDragEnd = {
                            when (dragSession?.kind) {
                                DragKind.Draw -> {
                                    draftRegion?.takeIf { it.width >= 2 && it.height >= 2 }?.let { created ->
                                        onCommit("创建区域", latestRegions.map { it.copy(selected = false) } + created)
                                    }
                                }
                                DragKind.RegionEdit -> {
                                    workingRegions?.let { onCommit("编辑区域", it) }
                                }
                                DragKind.Pan, DragKind.Magic, null -> Unit
                            }
                            dragSession = null
                            draftRegion = null
                            workingRegions = null
                        }
                    )
                }
        ) {
            drawRect(Color(0xFF14181E), size = size)
            drawCheckerboard(size)
            if (showGrid) drawGrid(size, zoom, viewportOffset)
            bitmap?.let {
                drawImage(
                    image = it,
                    dstOffset = IntOffset(viewportOffset.x.roundToInt(), viewportOffset.y.roundToInt()),
                    dstSize = IntSize((it.width * zoom).roundToInt(), (it.height * zoom).roundToInt())
                )
            }
            magicPreview?.let {
                drawMagicMask(it, zoom, viewportOffset)
                drawSeedMarker(it.seedX, it.seedY, zoom, viewportOffset)
            }
            visibleOverlayRegions.forEach { region ->
                val color = if (region.selected) Color(0xFF74A5FF) else Accent
                drawRegionOutline(region, zoom, viewportOffset, color)
            }
        }

        visibleOverlayRegions.forEachIndexed { index, region ->
            Box(
                Modifier.offset((region.x * zoom + viewportOffset.x + 1).roundToInt().dp, (region.y * zoom + viewportOffset.y + 1).roundToInt().dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Text((index + 1).toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        contextMenu?.let { state ->
            Box(
                Modifier.offset { state.offset }
                    .size(1.dp)
            ) {
                DropdownMenu(
                    expanded = state.visible,
                    onDismissRequest = { contextMenu = null }
                ) {
                    if (state.regionId != null) {
                        val targetRegion = regions.lastOrNull { it.id == state.regionId }
                        CanvasContextItem("选中区域", onDismiss = { contextMenu = null }) { onFocusRegion(state.regionId, false) }
                        CanvasContextItem("聚焦区域", onDismiss = { contextMenu = null }) { onFocusRegion(state.regionId, true) }
                        CanvasContextItem("预览区域", onDismiss = { contextMenu = null }) { onOpenRegionPreview(state.regionId) }
                        CanvasContextItem("添加角", onDismiss = { contextMenu = null }) {
                            targetRegion?.let { region ->
                                onCommit("添加选框角", latestRegions.replaceRegion(addRegionPoint(region, state.imagePoint, maxWidth, maxHeight)))
                            }
                        }
                        if (targetRegion != null && state.pointIndex != null && targetRegion.editPoints.size > 3) {
                            CanvasContextItem(
                                "删除角",
                                color = Color(0xFFFFB36A),
                                onDismiss = { contextMenu = null },
                                onClick = {
                                    onCommit("删除选框角", latestRegions.replaceRegion(removeRegionPoint(targetRegion, state.pointIndex)))
                                }
                            )
                        }
                        CanvasContextItem(
                            regions.lastOrNull { it.id == state.regionId }?.let { if (it.visible) "隐藏区域" else "显示区域" } ?: "切换显示",
                            onDismiss = { contextMenu = null }
                        ) {
                            onToggleRegionVisibility(state.regionId)
                        }
                        CanvasContextItem("删除区域", color = Color(0xFFFF7C74), onDismiss = { contextMenu = null }) {
                            onDeleteRegion(state.regionId)
                        }
                    } else {
                        CanvasContextItem("适应窗口", onDismiss = { contextMenu = null }, onClick = onFitToViewport)
                        CanvasContextItem("清空区域", color = Color(0xFFFF7C74), onDismiss = { contextMenu = null }, onClick = onClearRegions)
                    }
                }
            }
        }
    }
}

private data class CanvasContextMenuState(
    val regionId: String?,
    val imagePoint: Offset,
    val pointIndex: Int?,
    val visible: Boolean,
    val offset: IntOffset
)

@Composable
private fun CanvasContextItem(
    label: String,
    color: Color = Color.Unspecified,
    onDismiss: () -> Unit,
    onClick: () -> Unit
) {
    DropdownMenuItem(onClick = {
        onClick()
        onDismiss()
    }) {
        Text(label, color = color)
    }
}

private fun screenToImage(point: Offset, viewportOffset: Offset, zoom: Float): Offset = (point - viewportOffset) / zoom

private fun Offset.div(value: Float) = Offset(x / value, y / value)

private fun findRegionHit(regions: List<CropRegion>, point: Offset): CropRegion? {
    return regions.lastOrNull {
        it.visible && pointInsideRegion(it, point)
    }
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

private fun findPointHit(region: CropRegion, point: Offset, zoom: Float): Int? {
    if (!region.visible) return null
    val radius = (10f / zoom).coerceAtLeast(4f)
    return region.editPoints.indexOfLast { abs(point.x - it.x) <= radius && abs(point.y - it.y) <= radius }
        .takeIf { it >= 0 }
}

private fun findHandleHit(regions: List<CropRegion>, point: Offset, zoom: Float): Pair<CropRegion, Int>? {
    for (index in regions.indices.reversed()) {
        val region = regions[index]
        val pointIndex = findPointHit(region, point, zoom)
        if (pointIndex != null) return region to pointIndex
    }
    return null
}

private fun moveRegionPoint(region: CropRegion, pointIndex: Int, point: Offset, imageWidth: Int, imageHeight: Int): CropRegion {
    val points = region.editPoints.toMutableList()
    if (pointIndex !in points.indices) return region
    points[pointIndex] = RegionPoint(
        point.x.roundToInt().coerceIn(0, imageWidth),
        point.y.roundToInt().coerceIn(0, imageHeight)
    )
    return region.withPoints(points)
}

private fun moveRegion(region: CropRegion, dx: Int, dy: Int, imageWidth: Int, imageHeight: Int): CropRegion {
    val clampedDx = dx.coerceIn(-region.x, imageWidth - region.right)
    val clampedDy = dy.coerceIn(-region.y, imageHeight - region.bottom)
    val points = region.editPoints.map { RegionPoint(it.x + clampedDx, it.y + clampedDy) }
    return region.withPoints(points)
}

private fun addRegionPoint(region: CropRegion, point: Offset, imageWidth: Int, imageHeight: Int): CropRegion {
    val newPoint = RegionPoint(
        point.x.roundToInt().coerceIn(0, imageWidth),
        point.y.roundToInt().coerceIn(0, imageHeight)
    )
    val points = region.editPoints
    val insertAt = nearestSegmentIndex(points, newPoint) + 1
    return region.withPoints(points.toMutableList().apply { add(insertAt, newPoint) })
}

private fun removeRegionPoint(region: CropRegion, pointIndex: Int): CropRegion {
    val points = region.editPoints
    if (points.size <= 3 || pointIndex !in points.indices) return region
    return region.withPoints(points.toMutableList().apply { removeAt(pointIndex) })
}

private fun List<CropRegion>.replaceRegion(updated: CropRegion): List<CropRegion> =
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

fun DrawScope.drawHandle(center: Offset, color: Color) {
    drawCircle(color = Color.White, radius = 5f, center = center)
    drawCircle(color = color, radius = 3.2f, center = center)
}

private fun DrawScope.drawRegionOutline(region: CropRegion, zoom: Float, viewportOffset: Offset, color: Color) {
    val points = region.editPoints.map { Offset(it.x * zoom, it.y * zoom) + viewportOffset }
    if (points.size < 3) return

    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    val stroke = Stroke(
        width = if (region.selected) 3f else 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
    )
    drawPath(path, color.copy(alpha = 0.16f))
    drawPath(path, color, style = stroke)
    points.forEach { drawHandle(it, color) }
}

private fun DrawScope.drawMagicMask(preview: MagicSelectionPreview, zoom: Float, viewportOffset: Offset) {
    val fillColor = Color(0x4C3DB2FF)
    val innerFillColor = Color(0x2D8ED1FF)
    val edgeColor = Color(0xFFB9E2FF)
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
            drawRect(
                color = innerFillColor,
                topLeft = Offset((x + 0.15f) * zoom, (y + 0.15f) * zoom) + viewportOffset,
                size = Size((endX - x + 0.7f) * zoom, 0.7f * zoom)
            )
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

private fun DrawScope.drawSeedMarker(seedX: Int, seedY: Int, zoom: Float, viewportOffset: Offset) {
    val center = Offset((seedX + 0.5f) * zoom, (seedY + 0.5f) * zoom) + viewportOffset
    val radius = (zoom * 0.65f).coerceIn(4f, 10f)
    drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 2f))
    drawCircle(Color(0xFF1E9BFF), radius = radius * 0.45f, center = center)
}

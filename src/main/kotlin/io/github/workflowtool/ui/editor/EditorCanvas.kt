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
    var pointerPosition by remember { mutableStateOf<Offset?>(null) }
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
            .pointerHoverIcon(
                if (backgroundPickArmed) PointerIcon(Cursor(Cursor.CROSSHAIR_CURSOR)) else PointerIcon.Default
            )
            .onSizeChanged { onViewport(it.toSize()) }
            .onPointerEvent(PointerEventType.Press) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (latestBackgroundPickArmed && event.buttons.isPrimaryPressed) {
                    change.consume()
                    contextMenu = null
                    onBackgroundPick(screenToImage(change.position, latestViewportOffset, latestZoom))
                    return@onPointerEvent
                }
                if (event.buttons.isPrimaryPressed) {
                    val imagePoint = screenToImage(change.position, latestViewportOffset, latestZoom)
                    val hit = findRegionHit(latestRegions, imagePoint)
                    if (hit != null) {
                        onSelect(hit.id, false)
                    }
                }
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
                            if (latestBackgroundPickArmed) {
                                onBackgroundPick(imagePoint)
                                return@detectTapGestures
                            }
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
                    pointerPosition = point
                    onHover(screenToImage(point, latestViewportOffset, latestZoom))
                }
                .onPointerEvent(PointerEventType.Exit) {
                    pointerPosition = null
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
                            if (latestBackgroundPickArmed) {
                                onBackgroundPick(imagePoint)
                                return@detectDragGestures
                            }
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

        if (backgroundPickArmed) {
            pointerPosition?.let { EyedropperCursor(it) }
        }

        RegionNumberBadges(visibleOverlayRegions, zoom, viewportOffset)

        contextMenu?.let { state ->
            CanvasContextMenu(
                state = state,
                regions = regions,
                latestRegions = latestRegions,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                onDismiss = { contextMenu = null },
                onCommit = onCommit,
                onDeleteRegion = onDeleteRegion,
                onToggleRegionVisibility = onToggleRegionVisibility,
                onFocusRegion = onFocusRegion,
                onOpenRegionPreview = onOpenRegionPreview,
                onFitToViewport = onFitToViewport,
                onClearRegions = onClearRegions
            )
        }
    }
}


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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
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
    SelectionBrush,
    Draw
}

private data class DragSession(
    val kind: DragKind,
    val regionId: String?,
    val baseRegions: List<CropRegion>,
    val start: Offset
)

private fun selectionBrushRadius(zoom: Float): Int = (18f / zoom.coerceAtLeast(0.25f)).roundToInt().coerceIn(4, 48)

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
    toolMode: ToolMode,
    backgroundPickArmed: Boolean,
    onViewport: (Size) -> Unit,
    onPan: (Offset) -> Unit,
    onZoom: (Float, Offset) -> Unit,
    onCommit: (String, List<CropRegion>) -> Unit,
    onSelect: (String, Boolean) -> Unit,
    onDetectInsideRegion: (CropRegion) -> Unit,
    onBackgroundPick: (Offset) -> Unit,
    onHover: (Offset?) -> Unit,
    onClearSelection: () -> Unit,
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
    var hoveredRegionId by remember { mutableStateOf<String?>(null) }
    var selectionEditMode by remember { mutableStateOf(MaskEditMode.Replace) }
    val latestRegions by rememberUpdatedState(regions)
    val latestZoom by rememberUpdatedState(zoom)
    val latestViewportOffset by rememberUpdatedState(viewportOffset)
    val latestToolMode by rememberUpdatedState(toolMode)
    val latestBackgroundPickArmed by rememberUpdatedState(backgroundPickArmed)
    val antTransition = rememberInfiniteTransition(label = "selection-ants")
    val antsPhase by antTransition.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "selection-ants-phase"
    )

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
                    selectionEditMode = when {
                        event.keyboardModifiers.isAltPressed -> MaskEditMode.Subtract
                        event.keyboardModifiers.isShiftPressed -> MaskEditMode.Add
                        else -> MaskEditMode.Replace
                    }
                    val imagePoint = screenToImage(change.position, latestViewportOffset, latestZoom)
                    val hit = findRegionHit(latestRegions, imagePoint)
                    if (hit != null) {
                        onSelect(hit.id, false)
                        hoveredRegionId = hit.id
                    }
                }
                if (!event.buttons.isSecondaryPressed) return@onPointerEvent
                val imagePoint = screenToImage(change.position, latestViewportOffset, latestZoom)
                val hit = findRegionHit(latestRegions, imagePoint)
                if (hit != null) {
                    onSelect(hit.id, false)
                    hoveredRegionId = hit.id
                }
                contextMenu = CanvasContextMenuState(
                    regionId = hit?.id,
                    imagePoint = imagePoint,
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
                                hoveredRegionId = hit.id
                                return@detectTapGestures
                            }
                            onClearSelection()
                        }
                    )
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    val point = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    selectionEditMode = when {
                        event.keyboardModifiers.isAltPressed -> MaskEditMode.Subtract
                        event.keyboardModifiers.isShiftPressed -> MaskEditMode.Add
                        else -> MaskEditMode.Replace
                    }
                    pointerPosition = point
                    val imagePoint = screenToImage(point, latestViewportOffset, latestZoom)
                    hoveredRegionId = findRegionHit(renderedRegions, imagePoint)?.id
                    onHover(imagePoint)
                }
                .onPointerEvent(PointerEventType.Exit) {
                    pointerPosition = null
                    hoveredRegionId = null
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
                            val hit = findRegionHit(latestRegions, imagePoint)
                            contextMenu = null
                            when {
                                latestToolMode == ToolMode.Move -> {
                                    dragSession = DragSession(DragKind.Pan, null, latestRegions, offset)
                                    workingRegions = null
                                }
                                hit != null -> {
                                    val active = hit
                                    hoveredRegionId = active.id
                                    val base = latestRegions.map {
                                        if (it.id == active.id) it.copy(selected = true) else if (!it.selected) it else it.copy(selected = false)
                                    }
                                    dragSession = DragSession(DragKind.RegionEdit, active.id, base, imagePoint)
                                    workingRegions = base
                                    onSelect(active.id, false)
                                }
                                latestToolMode != ToolMode.Draw -> {
                                    dragSession = DragSession(DragKind.Pan, null, latestRegions, offset)
                                    workingRegions = null
                                }
                                else -> {
                                    dragSession = DragSession(DragKind.Draw, null, latestRegions, imagePoint)
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
                                    hoveredRegionId = session.regionId
                                    val dx = (dragAmount.x / latestZoom).roundToInt()
                                    val dy = (dragAmount.y / latestZoom).roundToInt()
                                    val next = (workingRegions ?: session.baseRegions).map {
                                        if (it.selected) {
                                            moveRegion(it, dx, dy, maxWidth, maxHeight)
                                        } else {
                                            it
                                        }
                                    }
                                    workingRegions = next
                                }
                                DragKind.SelectionBrush -> {
                                    val current = screenToImage(change.position, latestViewportOffset, latestZoom)
                                    val next = (workingRegions ?: session.baseRegions).map {
                                        if (it.id == session.regionId) {
                                            editSelectionMask(it, current, selectionBrushRadius(latestZoom), selectionEditMode, maxWidth, maxHeight)
                                        } else {
                                            it
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
                            }
                        },
                        onDragEnd = {
                            when (dragSession?.kind) {
                                DragKind.Draw -> {
                                    draftRegion?.takeIf { it.width >= 2 && it.height >= 2 }?.let { created ->
                                        onDetectInsideRegion(created)
                                    }
                                }
                                DragKind.RegionEdit, DragKind.SelectionBrush -> {
                                    workingRegions?.let { onCommit("编辑区域", it) }
                                }
                                DragKind.Pan, null -> Unit
                            }
                            dragSession = null
                            draftRegion = null
                            workingRegions = null
                        }
                    )
                }
        ) {
            drawRect(Color(0xFFE1E4E8), size = size)
            drawCheckerboard(size, zoom, viewportOffset)
            if (showGrid) drawGrid(size, zoom, viewportOffset)
            bitmap?.let {
                drawImage(
                    image = it,
                    dstOffset = IntOffset(viewportOffset.x.roundToInt(), viewportOffset.y.roundToInt()),
                    dstSize = IntSize((it.width * zoom).roundToInt(), (it.height * zoom).roundToInt())
                )
            }
            visibleOverlayRegions.forEach { region ->
                drawRegionOutline(region, zoom, viewportOffset, overlayStyleForRegion(region, hoveredRegionId), antsPhase)
            }
        }

        if (backgroundPickArmed) {
            pointerPosition?.let { EyedropperCursor(it) }
        }

        RegionNumberBadges(visibleOverlayRegions, zoom, viewportOffset, hoveredRegionId)

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

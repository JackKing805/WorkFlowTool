package io.github.workflowtool.ui.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed as isKeyAltPressed
import androidx.compose.ui.input.key.isShiftPressed as isKeyShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.MaskEditMode
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.ui.isPrimaryShortcutPressed
import io.github.workflowtool.ui.theme.Panel
import io.github.workflowtool.ui.theme.SoftBorder
import java.awt.Cursor
import java.util.UUID
import kotlin.math.roundToInt

internal enum class DragKind {
    Pan,
    RegionEdit,
    SelectionBrush,
    MarqueeSelect,
    Draw
}

private data class DragSession(
    val kind: DragKind,
    val regionId: String?,
    val baseRegions: List<CropRegion>,
    val start: Offset,
    val additive: Boolean = false,
    val editableRegion: Boolean = false,
    val usedMaskBrush: Boolean = false,
    val usedAddBrush: Boolean = false
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
    toolMode: ToolMode,
    backgroundPickArmed: Boolean,
    refineBrushSizePx: Int,
    onViewport: (Size) -> Unit,
    onPan: (Offset) -> Unit,
    onZoom: (Float, Offset, Offset) -> Unit,
    onCommit: (String, List<CropRegion>) -> Unit,
    onSelect: (String, Boolean) -> Unit,
    onSelectBounds: (Int, Int, Int, Int, Boolean) -> Unit,
    onDetectInsideRegion: (CropRegion) -> Unit,
    onBackgroundPick: (Offset) -> Unit,
    onHover: (Offset?) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteRegion: (String) -> Unit,
    onDeleteSelectedRegions: () -> Unit,
    onToggleRegionVisibility: (String) -> Unit,
    onToggleSelectedVisibility: () -> Unit,
    onFocusRegion: (String, Boolean) -> Unit,
    onOpenRegionPreview: (String) -> Unit,
    onMergeSelectedRegions: () -> Unit,
    onFitToViewport: () -> Unit,
    onClearRegions: () -> Unit,
    onIncreaseRefineBrushSize: () -> Unit,
    onDecreaseRefineBrushSize: () -> Unit
) {
    var dragSession by remember { mutableStateOf<DragSession?>(null) }
    var workingRegions by remember { mutableStateOf<List<CropRegion>?>(null) }
    var draftRegion by remember { mutableStateOf<CropRegion?>(null) }
    var marqueeSelection by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var contextMenu by remember { mutableStateOf<CanvasContextMenuState?>(null) }
    var pointerPosition by remember { mutableStateOf<Offset?>(null) }
    var hoveredRegionId by remember { mutableStateOf<String?>(null) }
    var selectionEditMode by remember { mutableStateOf(MaskEditMode.Replace) }
    var ctrlPressed by remember { mutableStateOf(false) }
    var shiftPressed by remember { mutableStateOf(false) }
    var altPressed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val maskGeometryCache = remember { MaskOverlayGeometryCache() }
    val latestRegions by rememberUpdatedState(regions)
    val viewportTransform = remember(viewportOffset, zoom, bitmap) {
        EditorViewportTransform(viewportOffset, zoom, bitmap?.width, bitmap?.height)
    }
    val latestTransform by rememberUpdatedState(viewportTransform)
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
    val renderOffset = viewportTransform.renderOffset
    val renderZoomX = viewportTransform.renderZoomX
    val renderZoomY = viewportTransform.renderZoomY

    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Panel)
            .border(1.dp, SoftBorder, RoundedCornerShape(5.dp))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.LeftBracket -> {
                            onDecreaseRefineBrushSize()
                            return@onPreviewKeyEvent true
                        }
                        Key.RightBracket -> {
                            onIncreaseRefineBrushSize()
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                selectionEditMode = maskEditModeForModifiers(event.isKeyAltPressed, event.isKeyShiftPressed)
                shiftPressed = event.isKeyShiftPressed
                altPressed = event.isKeyAltPressed
                false
            }
            .pointerHoverIcon(
                if (backgroundPickArmed) PointerIcon(Cursor(Cursor.CROSSHAIR_CURSOR)) else PointerIcon.Default
            )
            .onSizeChanged { onViewport(it.toSize()) }
            .onPointerEvent(PointerEventType.Press) { event ->
                focusRequester.requestFocus()
                ctrlPressed = isPrimaryShortcutPressed(event.keyboardModifiers)
                shiftPressed = event.keyboardModifiers.isShiftPressed
                altPressed = event.keyboardModifiers.isAltPressed
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (latestBackgroundPickArmed && event.buttons.isPrimaryPressed) {
                    change.consume()
                    contextMenu = null
                    onBackgroundPick(latestTransform.screenToImage(change.position))
                    return@onPointerEvent
                }
                if (event.buttons.isPrimaryPressed) {
                    contextMenu = null
                    selectionEditMode = maskEditModeForModifiers(
                        event.keyboardModifiers.isAltPressed,
                        event.keyboardModifiers.isShiftPressed
                    )
                    val imagePoint = latestTransform.screenToImage(change.position)
                    val hitTarget = findRegionHitTarget(latestRegions, imagePoint, latestTransform.renderZoomX)
                    val hit = hitTarget?.region
                    if (hit != null) {
                        if (!hit.selected || latestRegions.any { it.id != hit.id && it.selected }) {
                            onSelect(hit.id, false)
                        }
                        if (hoveredRegionId != hit.id) hoveredRegionId = hit.id
                    }
                }
                if (!event.buttons.isSecondaryPressed) return@onPointerEvent
                val imagePoint = latestTransform.screenToImage(change.position)
                val hit = findRegionHit(latestRegions, imagePoint)
                if (hit != null) {
                    if (event.keyboardModifiers.isShiftPressed) {
                        onSelect(hit.id, true)
                    } else if (!hit.selected || latestRegions.any { it.id != hit.id && it.selected }) {
                        onSelect(hit.id, false)
                    }
                    if (hoveredRegionId != hit.id) hoveredRegionId = hit.id
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
                            focusRequester.requestFocus()
                            val imagePoint = latestTransform.screenToImage(offset)
                            if (latestBackgroundPickArmed) {
                                onBackgroundPick(imagePoint)
                                return@detectTapGestures
                            }
                        },
                        onTap = { offset ->
                            focusRequester.requestFocus()
                            val imagePoint = latestTransform.screenToImage(offset)
                            if (latestBackgroundPickArmed) {
                                onBackgroundPick(imagePoint)
                                return@detectTapGestures
                            }
                            contextMenu = null
                            val hitTarget = findRegionHitTarget(latestRegions, imagePoint, latestTransform.renderZoomX)
                            val hit = hitTarget?.region
                            if (hit != null) {
                                if (!hit.selected || latestRegions.any { it.id != hit.id && it.selected }) {
                                    onSelect(hit.id, false)
                                }
                                if (hoveredRegionId != hit.id) hoveredRegionId = hit.id
                                return@detectTapGestures
                            }
                            if (ctrlPressed || shiftPressed || altPressed) return@detectTapGestures
                            onClearSelection()
                        }
                    )
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    ctrlPressed = isPrimaryShortcutPressed(event.keyboardModifiers)
                    shiftPressed = event.keyboardModifiers.isShiftPressed
                    altPressed = event.keyboardModifiers.isAltPressed
                    val point = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    selectionEditMode = maskEditModeForModifiers(
                        event.keyboardModifiers.isAltPressed,
                        event.keyboardModifiers.isShiftPressed
                    )
                    pointerPosition = point
                    val imagePoint = latestTransform.screenToImage(point)
                    val nextHoveredRegionId = findRegionHit(renderedRegions, imagePoint)?.id
                    if (hoveredRegionId != nextHoveredRegionId) hoveredRegionId = nextHoveredRegionId
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
                    onZoom(factor, change.position, latestTransform.screenToImage(change.position))
                }
                .pointerInput(imageWidth, imageHeight) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            focusRequester.requestFocus()
                            val imagePoint = latestTransform.screenToImage(offset)
                            if (latestBackgroundPickArmed) {
                                onBackgroundPick(imagePoint)
                                return@detectDragGestures
                            }
                            val hitTarget = findRegionHitTarget(latestRegions, imagePoint, latestTransform.renderZoomX)
                            val hit = hitTarget?.region
                            contextMenu = null
                            when {
                                latestToolMode == ToolMode.Move -> {
                                    dragSession = DragSession(DragKind.Pan, null, latestRegions, offset)
                                    workingRegions = null
                                }
                                hit != null -> {
                                    val active = hit
                                    if (hoveredRegionId != active.id) hoveredRegionId = active.id
                                    val base = latestRegions.map {
                                        if (it.id == active.id) it.copy(selected = true) else if (!it.selected) it else it.copy(selected = false)
                                    }
                                    dragSession = DragSession(
                                        DragKind.RegionEdit,
                                        active.id,
                                        base,
                                        imagePoint,
                                        editableRegion = canRefineHitRegion(latestToolMode)
                                    )
                                    workingRegions = base
                                    if (!active.selected || latestRegions.any { it.id != active.id && it.selected }) {
                                        onSelect(active.id, false)
                                    }
                                }
                                latestToolMode != ToolMode.Eyedropper && ctrlPressed -> {
                                    dragSession = DragSession(
                                        DragKind.MarqueeSelect,
                                        null,
                                        latestRegions,
                                        imagePoint,
                                        additive = shiftPressed
                                    )
                                    marqueeSelection = imagePoint to imagePoint
                                    workingRegions = latestRegions
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
                                    if (hoveredRegionId != session.regionId) hoveredRegionId = session.regionId
                                    val liveEditMode = if (session.editableRegion) selectionEditMode else MaskEditMode.Replace
                                    if (liveEditMode != MaskEditMode.Replace && session.regionId != null) {
                                        val current = latestTransform.screenToImage(change.position)
                                        val next = (workingRegions ?: session.baseRegions).map {
                                            if (it.id == session.regionId) {
                                                editSelectionMask(
                                                    it,
                                                    current,
                                                    selectionBrushRadius(refineBrushSizePx, latestTransform.renderZoomX),
                                                    liveEditMode,
                                                    maxWidth,
                                                    maxHeight
                                                )
                                            } else {
                                                it
                                            }
                                        }
                                        workingRegions = next
                                        dragSession = session.copy(
                                            usedMaskBrush = true,
                                            usedAddBrush = session.usedAddBrush || liveEditMode == MaskEditMode.Add
                                        )
                                    } else {
                                        val imageDelta = latestTransform.screenDeltaToImageDelta(dragAmount)
                                        val dx = imageDelta.x.roundToInt()
                                        val dy = imageDelta.y.roundToInt()
                                        val next = (workingRegions ?: session.baseRegions).map {
                                            if (it.selected) {
                                                moveRegion(it, dx, dy, maxWidth, maxHeight)
                                            } else {
                                                it
                                            }
                                        }
                                        workingRegions = next
                                    }
                                }
                                DragKind.SelectionBrush -> {
                                    val liveEditMode = selectionEditMode.takeIf { it != MaskEditMode.Replace } ?: MaskEditMode.Add
                                    val current = latestTransform.screenToImage(change.position)
                                    val next = (workingRegions ?: session.baseRegions).map {
                                        if (it.id == session.regionId) {
                                            editSelectionMask(it, current, selectionBrushRadius(refineBrushSizePx, latestTransform.renderZoomX), liveEditMode, maxWidth, maxHeight)
                                        } else {
                                            it
                                        }
                                    }
                                    workingRegions = next
                                    dragSession = session.copy(
                                        usedMaskBrush = true,
                                        usedAddBrush = session.usedAddBrush || liveEditMode == MaskEditMode.Add
                                    )
                                }
                                DragKind.MarqueeSelect -> {
                                    val current = latestTransform.screenToImage(change.position)
                                    marqueeSelection = session.start to current
                                    val bounds = imageSelectionBounds(session.start, current, maxWidth, maxHeight)
                                    workingRegions = session.baseRegions.map { region ->
                                        val intersects = regionIntersectsBounds(region, bounds.left, bounds.top, bounds.right, bounds.bottom)
                                        region.copy(selected = if (session.additive) region.selected || intersects else intersects)
                                    }
                                }
                                DragKind.Draw -> {
                                    val start = session.start
                                    val current = latestTransform.screenToImage(change.position)
                                    val bounds = imageSelectionBounds(start, current, maxWidth, maxHeight)
                                    draftRegion = CropRegion(
                                        id = draftRegion?.id ?: UUID.randomUUID().toString(),
                                        x = bounds.left,
                                        y = bounds.top,
                                        width = bounds.width.coerceAtLeast(1),
                                        height = bounds.height.coerceAtLeast(1),
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
                                    val session = dragSession
                                    val label = if (session?.usedMaskBrush == true && session.usedAddBrush) {
                                        "整体贴合选区"
                                    } else {
                                        "编辑区域"
                                    }
                                    workingRegions?.let { onCommit(label, it) }
                                }
                                DragKind.MarqueeSelect -> {
                                    marqueeSelection?.let { (start, end) ->
                                        val bounds = imageSelectionBounds(start, end, maxWidth, maxHeight)
                                        onSelectBounds(bounds.left, bounds.top, bounds.right, bounds.bottom, dragSession?.additive == true)
                                    }
                                }
                                DragKind.Pan, null -> Unit
                            }
                            dragSession = null
                            draftRegion = null
                            marqueeSelection = null
                            workingRegions = null
                        }
                    )
                }
        ) {
            drawRect(Color(0xFF10151D), size = size)
            drawCheckerboard(size, zoom, renderOffset)
            if (showGrid) {
                drawGrid(size, zoom, renderOffset)
            }
            drawPixelGrid(size, renderZoomX, renderZoomY, renderOffset)
            bitmap?.let {
                drawImage(
                    image = it,
                    dstOffset = IntOffset(renderOffset.x.roundToInt(), renderOffset.y.roundToInt()),
                    dstSize = IntSize(
                        viewportTransform.renderWidth ?: (it.width * zoom).roundToInt(),
                        viewportTransform.renderHeight ?: (it.height * zoom).roundToInt()
                    ),
                    filterQuality = FilterQuality.None
                )
            }
            visibleOverlayRegions.forEach { region ->
                drawRegionOutline(
                    region = region,
                    zoom = renderZoomX,
                    zoomY = renderZoomY,
                    viewportOffset = renderOffset,
                    style = overlayStyleForRegion(region, hoveredRegionId),
                    antsPhase = antsPhase,
                    maskGeometryCache = maskGeometryCache
                )
            }
            pointerPosition?.let { pointer ->
                val mode = selectionEditMode
                if (mode != MaskEditMode.Replace && canRefineHitRegion(latestToolMode)) {
                    val imagePoint = viewportTransform.screenToImage(pointer)
                    if (findRegionHit(renderedRegions, imagePoint) != null) {
                        val radius = selectionBrushRadius(refineBrushSizePx, renderZoomX) * renderZoomX
                        val color = when (mode) {
                            MaskEditMode.Add -> Color(0xFF4A8CFF)
                            MaskEditMode.Subtract -> Color(0xFFFF6C63)
                            MaskEditMode.Replace -> Color.Transparent
                        }
                        drawCircle(
                            color = color.copy(alpha = 0.18f),
                            radius = radius,
                            center = pointer
                        )
                        drawCircle(
                            color = color.copy(alpha = 0.82f),
                            radius = radius,
                            center = pointer,
                            style = Stroke(width = 1.4f)
                        )
                    }
                }
            }
            marqueeSelection?.let { (start, end) ->
                val left = minOf(start.x, end.x) * renderZoomX + renderOffset.x
                val top = minOf(start.y, end.y) * renderZoomY + renderOffset.y
                val width = kotlin.math.abs(end.x - start.x) * renderZoomX
                val height = kotlin.math.abs(end.y - start.y) * renderZoomY
                drawRect(
                    color = Color(0x334A7EFF),
                    topLeft = Offset(left, top),
                    size = Size(width, height)
                )
                drawRect(
                    color = Color(0xFF4A7EFF),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.3f)
                )
            }
        }

        if (backgroundPickArmed) {
            pointerPosition?.let { EyedropperCursor(it) }
        }

        RegionNumberBadges(visibleOverlayRegions, renderZoomX, renderZoomY, renderOffset, hoveredRegionId)

        contextMenu?.let { state ->
            CanvasContextMenu(
                state = state,
                regions = regions,
                onDismiss = { contextMenu = null },
                onDeleteRegion = onDeleteRegion,
                onDeleteSelectedRegions = onDeleteSelectedRegions,
                onToggleRegionVisibility = onToggleRegionVisibility,
                onToggleSelectedVisibility = onToggleSelectedVisibility,
                onFocusRegion = onFocusRegion,
                onOpenRegionPreview = onOpenRegionPreview,
                onMergeSelectedRegions = onMergeSelectedRegions,
                onFitToViewport = onFitToViewport,
                onClearRegions = onClearRegions
            )
        }
    }
}

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

@Composable
fun EyedropperCursor(position: Offset) {
    Canvas(
        Modifier.offset {
            IntOffset((position.x + 12f).roundToInt(), (position.y + 12f).roundToInt())
        }.size(28.dp)
    ) {
        drawEyedropperIcon()
    }
}

private fun DrawScope.drawEyedropperIcon() {
    val handle = Color(0xFF74A5FF)
    val metal = Color.White
    val shadow = Color(0x99000000)
    drawLine(shadow, Offset(8f, 22f), Offset(23f, 7f), strokeWidth = 5.5f)
    drawLine(handle, Offset(7f, 21f), Offset(22f, 6f), strokeWidth = 4.2f)
    drawLine(metal, Offset(13f, 8f), Offset(20f, 15f), strokeWidth = 5.5f)
    drawLine(Color(0xFF202A36), Offset(13f, 8f), Offset(20f, 15f), strokeWidth = 2.2f)
    drawCircle(metal, radius = 4.2f, center = Offset(21f, 6f))
    drawCircle(Color(0xFF202A36), radius = 2.1f, center = Offset(21f, 6f))
    drawLine(metal, Offset(4f, 24f), Offset(9f, 19f), strokeWidth = 3f)
}

data class CanvasContextMenuState(
    val regionId: String?,
    val imagePoint: Offset,
    val pointIndex: Int?,
    val visible: Boolean,
    val offset: IntOffset
)

@Composable
fun CanvasContextItem(
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

@Composable
fun RegionNumberBadges(
    regions: List<CropRegion>,
    zoom: Float,
    viewportOffset: Offset,
    hoveredRegionId: String?
) {
    regions.forEachIndexed { index, region ->
        val selected = region.selected
        val hovered = hoveredRegionId == region.id
        val size = when {
            selected -> 20.dp
            hovered -> 18.dp
            else -> 15.dp
        }
        val badgeColor = when {
            selected -> Color(0xFF2F6BFF)
            hovered -> Color(0xFF3E79F0)
            else -> Accent.copy(alpha = 0.58f)
        }
        val textColor = if (selected || hovered) Color.White else Color.White.copy(alpha = 0.8f)
        Box(
            Modifier.offset(
                (region.x * zoom + viewportOffset.x + 1).roundToInt().dp,
                (region.y * zoom + viewportOffset.y + 1).roundToInt().dp
            )
                .size(size)
                .clip(CircleShape)
                .background(badgeColor),
            contentAlignment = Alignment.Center
        ) {
            val fontSize = if (selected) 10.sp else 9.sp
            Text(
                text = (index + 1).toString(),
                color = textColor,
                modifier = Modifier.offset(y = (-0.5).dp),
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CanvasContextMenu(
    state: CanvasContextMenuState,
    regions: List<CropRegion>,
    latestRegions: List<CropRegion>,
    maxWidth: Int,
    maxHeight: Int,
    onDismiss: () -> Unit,
    onCommit: (String, List<CropRegion>) -> Unit,
    onDeleteRegion: (String) -> Unit,
    onToggleRegionVisibility: (String) -> Unit,
    onFocusRegion: (String, Boolean) -> Unit,
    onOpenRegionPreview: (String) -> Unit,
    onFitToViewport: () -> Unit,
    onClearRegions: () -> Unit
) {
    Box(
        Modifier.offset { state.offset }
            .size(1.dp)
    ) {
        DropdownMenu(
            expanded = state.visible,
            onDismissRequest = onDismiss
        ) {
            if (state.regionId != null) {
                val targetRegion = regions.lastOrNull { it.id == state.regionId }
                CanvasContextItem("选中区域", onDismiss = onDismiss) { onFocusRegion(state.regionId, false) }
                CanvasContextItem("聚焦区域", onDismiss = onDismiss) { onFocusRegion(state.regionId, true) }
                CanvasContextItem("预览区域", onDismiss = onDismiss) { onOpenRegionPreview(state.regionId) }
                CanvasContextItem("添加角", onDismiss = onDismiss) {
                    targetRegion?.let { region ->
                        onCommit("添加选框角", latestRegions.replaceRegion(addRegionPoint(region, state.imagePoint, maxWidth, maxHeight)))
                    }
                }
                if (targetRegion != null && state.pointIndex != null && targetRegion.editPoints.size > 3) {
                    CanvasContextItem(
                        "删除角",
                        color = Color(0xFFFFB36A),
                        onDismiss = onDismiss,
                        onClick = {
                            onCommit("删除选框角", latestRegions.replaceRegion(removeRegionPoint(targetRegion, state.pointIndex)))
                        }
                    )
                }
                CanvasContextItem(
                    regions.lastOrNull { it.id == state.regionId }?.let { if (it.visible) "隐藏区域" else "显示区域" } ?: "切换显示",
                    onDismiss = onDismiss
                ) {
                    onToggleRegionVisibility(state.regionId)
                }
                CanvasContextItem("删除区域", color = Color(0xFFFF7C74), onDismiss = onDismiss) {
                    onDeleteRegion(state.regionId)
                }
            } else {
                CanvasContextItem("适应窗口", onDismiss = onDismiss, onClick = onFitToViewport)
                CanvasContextItem("清空区域", color = Color(0xFFFF7C74), onDismiss = onDismiss, onClick = onClearRegions)
            }
        }
    }
}

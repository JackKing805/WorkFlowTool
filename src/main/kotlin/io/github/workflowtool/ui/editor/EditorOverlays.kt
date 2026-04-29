package io.github.workflowtool.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.ui.theme.Accent
import kotlin.math.roundToInt

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
    zoomY: Float = zoom,
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
            selected -> Color(0xFFF3A23C)
            hovered -> Color(0xFF3E79F0)
            else -> Accent.copy(alpha = 0.58f)
        }
        val textColor = if (selected || hovered) Color.White else Color.White.copy(alpha = 0.8f)
        Box(
            Modifier.offset {
                IntOffset(
                    (region.x * zoom + viewportOffset.x + 1).roundToInt(),
                    (region.y * zoomY + viewportOffset.y + 1).roundToInt()
                )
            }
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
    onDismiss: () -> Unit,
    onDeleteRegion: (String) -> Unit,
    onDeleteSelectedRegions: () -> Unit,
    onToggleRegionVisibility: (String) -> Unit,
    onToggleSelectedVisibility: () -> Unit,
    onFocusRegion: (String, Boolean) -> Unit,
    onOpenRegionPreview: (String) -> Unit,
    onMergeSelectedRegions: () -> Unit,
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
                val region = regions.lastOrNull { it.id == state.regionId }
                val selectedCount = regions.count { it.selected }
                val selectedVisibleCount = regions.count { it.selected && it.visible }
                val multiSelectionContext = region?.selected == true && selectedCount >= 2
                if (multiSelectionContext) {
                    CanvasContextItem("合并选中区域", onDismiss = onDismiss) {
                        onMergeSelectedRegions()
                    }
                    CanvasContextItem(if (selectedVisibleCount > 0) "隐藏选中区域" else "显示选中区域", onDismiss = onDismiss) {
                        onToggleSelectedVisibility()
                    }
                    CanvasContextItem("删除选中区域", color = Color(0xFFFF7C74), onDismiss = onDismiss) {
                        onDeleteSelectedRegions()
                    }
                } else {
                    CanvasContextItem("选中区域", onDismiss = onDismiss) { onFocusRegion(state.regionId, false) }
                    CanvasContextItem("聚焦区域", onDismiss = onDismiss) { onFocusRegion(state.regionId, true) }
                    CanvasContextItem("预览区域", onDismiss = onDismiss) { onOpenRegionPreview(state.regionId) }
                    CanvasContextItem(
                        region?.let { if (it.visible) "隐藏区域" else "显示区域" } ?: "切换显示",
                        onDismiss = onDismiss
                    ) {
                        onToggleRegionVisibility(state.regionId)
                    }
                    CanvasContextItem("删除区域", color = Color(0xFFFF7C74), onDismiss = onDismiss) {
                        onDeleteRegion(state.regionId)
                    }
                }
            } else {
                CanvasContextItem("适应窗口", onDismiss = onDismiss, onClick = onFitToViewport)
                CanvasContextItem("清空区域", color = Color(0xFFFF7C74), onDismiss = onDismiss, onClick = onClearRegions)
            }
        }
    }
}

package io.github.workflowtool.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workflowtool.application.AppController
import io.github.workflowtool.application.adjustZoom
import io.github.workflowtool.application.clearRegions
import io.github.workflowtool.application.fitSelectionToViewport
import io.github.workflowtool.application.fitToViewport
import io.github.workflowtool.application.redo
import io.github.workflowtool.application.toggleGrid
import io.github.workflowtool.application.undo
import io.github.workflowtool.domain.StringKey
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.ui.theme.Border
import io.github.workflowtool.ui.theme.ControlActive
import io.github.workflowtool.ui.theme.ControlBg
import io.github.workflowtool.ui.theme.TextMuted
import kotlin.math.roundToInt


@Composable
fun Toolbar(controller: AppController, canUndo: Boolean, canRedo: Boolean) {
    val strings = controller.localization
    Row(
        Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconToolButton(ToolbarIcon.Select, strings.text(StringKey.SelectTool), controller.toolMode == ToolMode.Select) {
            controller.updateToolMode(ToolMode.Select)
        }
        IconToolButton(ToolbarIcon.Move, strings.text(StringKey.MoveTool), controller.toolMode == ToolMode.Move) {
            controller.updateToolMode(ToolMode.Move)
        }
        IconToolButton(ToolbarIcon.Draw, strings.text(StringKey.DrawRectangle), controller.toolMode == ToolMode.Draw) {
            controller.enterManualDrawMode()
        }
        IconToolButton(ToolbarIcon.Eyedropper, "吸色", controller.toolMode == ToolMode.Eyedropper) {
            controller.armBackgroundPicker()
        }
        Spacer(Modifier.width(8.dp))
        IconToolButton(ToolbarIcon.Undo, strings.text(StringKey.Undo), active = false, enabled = canUndo, onClick = controller::undo)
        IconToolButton(ToolbarIcon.Redo, strings.text(StringKey.Redo), active = false, enabled = canRedo, onClick = controller::redo)
        IconToolButton(ToolbarIcon.Clear, strings.text(StringKey.ClearRegions), active = false, onClick = controller::clearRegions)
        Spacer(Modifier.width(20.dp))
        IconToolButton(ToolbarIcon.ZoomOut, "缩小", active = false) { controller.adjustZoom(-0.1f) }
        Box(Modifier.width(58.dp), contentAlignment = Alignment.Center) {
            Text("${(controller.zoom * 100).roundToInt()}%", color = TextMuted, fontSize = 14.sp)
        }
        IconToolButton(ToolbarIcon.ZoomIn, "放大", active = false) { controller.adjustZoom(0.1f) }
        IconToolButton(ToolbarIcon.FitWindow, strings.text(StringKey.FitWindow), active = false, onClick = controller::fitToViewport)
        IconToolButton(
            ToolbarIcon.FitSelection,
            strings.text(StringKey.FitSelection),
            active = false,
            enabled = controller.selectedRegion != null,
            onClick = controller::fitSelectionToViewport
        )
        IconToolButton(ToolbarIcon.Grid, strings.text(StringKey.GridToggle), active = controller.showGrid, onClick = controller::toggleGrid)
    }
}

@Composable
private fun IconToolButton(
    icon: ToolbarIcon,
    contentDescription: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .width(36.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) ControlActive else ControlBg)
            .border(1.dp, if (active) Color(0xFF65A7FF) else Border, RoundedCornerShape(7.dp))
            .alpha(if (enabled) 1f else 0.42f)
            .clickable(enabled = enabled, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            drawToolbarIcon(icon, if (active) Color(0xFFDBE5FF) else Color.White)
        }
    }
}

private enum class ToolbarIcon {
    Select,
    Move,
    Draw,
    Eyedropper,
    Undo,
    Redo,
    Clear,
    ZoomOut,
    ZoomIn,
    FitWindow,
    FitSelection,
    Grid
}

private fun DrawScope.drawToolbarIcon(icon: ToolbarIcon, color: Color) {
    val stroke = Stroke(width = 2.1f, cap = StrokeCap.Round)
    val w = size.width
    val h = size.height
    when (icon) {
        ToolbarIcon.Select -> {
            val path = Path().apply {
                moveTo(w * 0.18f, h * 0.12f)
                lineTo(w * 0.78f, h * 0.55f)
                lineTo(w * 0.5f, h * 0.61f)
                lineTo(w * 0.66f, h * 0.9f)
                lineTo(w * 0.53f, h * 0.96f)
                lineTo(w * 0.37f, h * 0.68f)
                lineTo(w * 0.18f, h * 0.86f)
                close()
            }
            drawPath(path, color)
        }
        ToolbarIcon.Move -> {
            drawLine(color, Offset(w * 0.5f, h * 0.08f), Offset(w * 0.5f, h * 0.92f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.08f, h * 0.5f), Offset(w * 0.92f, h * 0.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.08f), Offset(w * 0.38f, h * 0.22f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.08f), Offset(w * 0.62f, h * 0.22f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.92f), Offset(w * 0.38f, h * 0.78f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.92f), Offset(w * 0.62f, h * 0.78f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.08f, h * 0.5f), Offset(w * 0.22f, h * 0.38f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.08f, h * 0.5f), Offset(w * 0.22f, h * 0.62f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.92f, h * 0.5f), Offset(w * 0.78f, h * 0.38f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.92f, h * 0.5f), Offset(w * 0.78f, h * 0.62f), strokeWidth = 2.1f, cap = StrokeCap.Round)
        }
        ToolbarIcon.Draw -> drawRect(color, topLeft = Offset(w * 0.18f, h * 0.2f), size = Size(w * 0.64f, h * 0.58f), style = stroke)
        ToolbarIcon.Eyedropper -> {
            drawLine(color, Offset(w * 0.28f, h * 0.78f), Offset(w * 0.73f, h * 0.33f), strokeWidth = 3f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.64f, h * 0.24f), Offset(w * 0.82f, h * 0.42f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.2f, h * 0.86f), Offset(w * 0.36f, h * 0.86f), strokeWidth = 2.1f, cap = StrokeCap.Round)
        }
        ToolbarIcon.Undo, ToolbarIcon.Redo -> drawHistoryIcon(color, icon == ToolbarIcon.Redo)
        ToolbarIcon.Clear -> {
            drawLine(color, Offset(w * 0.25f, h * 0.28f), Offset(w * 0.75f, h * 0.78f), strokeWidth = 2.3f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.75f, h * 0.28f), Offset(w * 0.25f, h * 0.78f), strokeWidth = 2.3f, cap = StrokeCap.Round)
        }
        ToolbarIcon.ZoomOut, ToolbarIcon.ZoomIn -> {
            drawCircle(color, radius = w * 0.27f, center = Offset(w * 0.43f, h * 0.43f), style = stroke)
            drawLine(color, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.84f, h * 0.84f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.31f, h * 0.43f), Offset(w * 0.55f, h * 0.43f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            if (icon == ToolbarIcon.ZoomIn) {
                drawLine(color, Offset(w * 0.43f, h * 0.31f), Offset(w * 0.43f, h * 0.55f), strokeWidth = 2.1f, cap = StrokeCap.Round)
            }
        }
        ToolbarIcon.FitWindow -> {
            drawRect(color, topLeft = Offset(w * 0.18f, h * 0.18f), size = Size(w * 0.64f, h * 0.64f), style = stroke)
            drawLine(color, Offset(w * 0.18f, h * 0.36f), Offset(w * 0.36f, h * 0.18f), strokeWidth = 2f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.82f, h * 0.64f), Offset(w * 0.64f, h * 0.82f), strokeWidth = 2f, cap = StrokeCap.Round)
        }
        ToolbarIcon.FitSelection -> {
            drawRect(color, topLeft = Offset(w * 0.26f, h * 0.26f), size = Size(w * 0.48f, h * 0.48f), style = stroke)
            drawCircle(color, radius = 2.1f, center = Offset(w * 0.26f, h * 0.26f))
            drawCircle(color, radius = 2.1f, center = Offset(w * 0.74f, h * 0.26f))
            drawCircle(color, radius = 2.1f, center = Offset(w * 0.74f, h * 0.74f))
            drawCircle(color, radius = 2.1f, center = Offset(w * 0.26f, h * 0.74f))
        }
        ToolbarIcon.Grid -> {
            for (i in 0..2) {
                val p = w * (0.24f + i * 0.26f)
                drawLine(color, Offset(p, h * 0.18f), Offset(p, h * 0.82f), strokeWidth = 1.7f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.18f, p), Offset(w * 0.82f, p), strokeWidth = 1.7f, cap = StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.drawHistoryIcon(color: Color, redo: Boolean) {
    val w = size.width
    val h = size.height
    val startX = if (redo) w * 0.28f else w * 0.72f
    val endX = if (redo) w * 0.72f else w * 0.28f
    drawArc(
        color = color,
        startAngle = if (redo) -130f else -50f,
        sweepAngle = if (redo) 260f else -260f,
        useCenter = false,
        topLeft = Offset(w * 0.18f, h * 0.2f),
        size = Size(w * 0.64f, h * 0.58f),
        style = Stroke(width = 2.1f, cap = StrokeCap.Round)
    )
    drawLine(color, Offset(endX, h * 0.28f), Offset(startX, h * 0.28f), strokeWidth = 2.1f, cap = StrokeCap.Round)
    drawLine(color, Offset(endX, h * 0.28f), Offset(endX + if (redo) -w * 0.1f else w * 0.1f, h * 0.16f), strokeWidth = 2.1f, cap = StrokeCap.Round)
    drawLine(color, Offset(endX, h * 0.28f), Offset(endX + if (redo) -w * 0.1f else w * 0.1f, h * 0.4f), strokeWidth = 2.1f, cap = StrokeCap.Round)
}

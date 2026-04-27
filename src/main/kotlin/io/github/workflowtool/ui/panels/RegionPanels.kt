package io.github.workflowtool.ui.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import io.github.workflowtool.application.AppController
import io.github.workflowtool.application.*
import io.github.workflowtool.application.clearRegions
import io.github.workflowtool.application.invertSelection
import io.github.workflowtool.application.openModelDirectory
import io.github.workflowtool.application.openNativeRuntimeDirectory
import io.github.workflowtool.application.openOutputDirectory
import io.github.workflowtool.application.openPythonRuntimeDirectory
import io.github.workflowtool.application.openRuntimeDirectory
import io.github.workflowtool.application.openTrainingSetDirectory
import io.github.workflowtool.application.removeRegion
import io.github.workflowtool.application.selectAll
import io.github.workflowtool.application.selectAndFocusRegion
import io.github.workflowtool.application.toggleVisibility
import io.github.workflowtool.core.IconExporter
import io.github.workflowtool.domain.LocalizationProvider
import io.github.workflowtool.domain.StringKey
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.GridConfig
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.ui.components.CompactNumber
import io.github.workflowtool.ui.components.CompactStepper
import io.github.workflowtool.ui.components.CompactTextField
import io.github.workflowtool.ui.components.GhostButton
import io.github.workflowtool.ui.components.PanelCard
import io.github.workflowtool.ui.components.PrimaryButton
import io.github.workflowtool.ui.components.SelectField
import io.github.workflowtool.ui.components.SettingSwitch
import io.github.workflowtool.ui.components.SmallCheck
import io.github.workflowtool.ui.components.SquareButton
import io.github.workflowtool.ui.components.StatusLine
import io.github.workflowtool.ui.components.ThumbnailBox
import io.github.workflowtool.ui.components.ToolButton
import io.github.workflowtool.ui.components.namingModeLabel
import io.github.workflowtool.ui.components.nextFormat
import io.github.workflowtool.ui.components.nextNamingMode
import io.github.workflowtool.ui.editor.drawCheckerboard
import io.github.workflowtool.ui.theme.Border
import io.github.workflowtool.ui.theme.ControlActive
import io.github.workflowtool.ui.theme.ControlBg
import io.github.workflowtool.ui.theme.Danger
import io.github.workflowtool.ui.theme.Panel
import io.github.workflowtool.ui.theme.SoftBorder
import io.github.workflowtool.ui.theme.TextDim
import io.github.workflowtool.ui.theme.TextMuted
import kotlin.math.roundToInt


@Composable
fun RightPanel(controller: AppController, modifier: Modifier = Modifier) {
    val strings = controller.localization
    Column(modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelCard("${strings.text(StringKey.RegionsTitle)} ${controller.regions.size}", modifier = Modifier.fillMaxWidth().weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolButton(strings.text(StringKey.SelectAll), false, controller::selectAll)
                ToolButton(strings.text(StringKey.InvertSelection), false, controller::invertSelection)
                ToolButton(strings.text(StringKey.Clear), false, controller::clearRegions)
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).border(1.dp, SoftBorder, RoundedCornerShape(6.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(controller.regions, key = { _, item -> item.id }) { index, region ->
                    RegionRow(index, region, controller)
                }
            }
        }
        PanelCard(strings.text(StringKey.ProcessTitle), Modifier.fillMaxWidth().height(252.dp)) {
            PrimaryButton(strings.text(StringKey.StartCrop), controller::exportRegionsAsync)
            Spacer(Modifier.height(16.dp))
            GhostButton("高级 ${strings.text(StringKey.AdvancedSettings)}", { controller.showAdvancedSettings(true) }, modifier = Modifier.fillMaxWidth().height(38.dp))
            Spacer(Modifier.weight(1f))
            GhostButton("打开 ${strings.text(StringKey.OpenOutputDirectory)}", controller::openOutputDirectory, modifier = Modifier.fillMaxWidth().height(36.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RegionRow(index: Int, region: CropRegion, controller: AppController) {
    Row(
        Modifier.fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (region.selected) Color(0xFF1E315F) else Color(0xFF131820))
            .border(1.dp, if (region.selected) Color(0xFF4A7EFF) else SoftBorder, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { controller.selectAndFocusRegion(region.id) },
                onDoubleClick = { controller.selectAndFocusRegion(region.id, fit = true) }
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${index + 1}",
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(22.dp)
        )
        Spacer(Modifier.width(6.dp))
        RegionThumbnail(region, controller, Modifier.width(40.dp).height(40.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = true)) {
            Text(
                "${region.width} x ${region.height}",
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${region.x}, ${region.y}",
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        RegionVisibilityButton(visible = region.visible, onClick = { controller.toggleVisibility(region.id) })
        Spacer(Modifier.width(8.dp))
        RegionDeleteButton(onClick = { controller.removeRegion(region.id) })
    }
}

@Composable
private fun RegionVisibilityButton(visible: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(32.dp).height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (visible) Color(0x1F3FA2FF) else ControlBg)
            .border(1.dp, if (visible) Color(0xFF4FA3FF) else Border, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        EyeVisibilityIcon(
            visible = visible,
            tint = if (visible) Color(0xFFD7EBFF) else TextMuted
        )
    }
}

@Composable
private fun EyeVisibilityIcon(visible: Boolean, tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.width(14.dp).height(14.dp)) {
        val stroke = Stroke(width = 1.7f)
        val eyeWidth = size.width * 0.9f
        val eyeHeight = size.height * 0.52f
        val left = (size.width - eyeWidth) / 2f
        val top = (size.height - eyeHeight) / 2f

        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(eyeWidth, eyeHeight),
            style = stroke
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(eyeWidth, eyeHeight),
            style = stroke
        )

        if (visible) {
            drawCircle(
                color = tint,
                radius = size.minDimension * 0.12f,
                center = center
            )
        } else {
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.78f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.22f),
                strokeWidth = 1.9f
            )
        }
    }
}

@Composable
private fun RegionDeleteButton(onClick: () -> Unit) {
    Box(
        Modifier.width(26.dp).height(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0x14FF554C))
            .border(1.dp, Color(0x33FF7A72), RoundedCornerShape(7.dp))
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "×",
            color = Danger,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(0.95f)
        )
    }
}

@Composable
private fun RegionThumbnail(region: CropRegion, controller: AppController, modifier: Modifier = Modifier) {
    val bitmap = remember(controller.image, controller.regions, region.x, region.y, region.width, region.height, region.points) {
        controller.image?.let { previewCropper.cropPreview(it, region, controller.regions).toComposeImageBitmap() }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF171C23))
            .border(1.dp, SoftBorder, RoundedCornerShape(4.dp))
            .combinedClickable(onClick = { controller.openRegionPreview(region.id) }),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("N/A", color = TextMuted, fontSize = 12.sp)
        }
    }
}

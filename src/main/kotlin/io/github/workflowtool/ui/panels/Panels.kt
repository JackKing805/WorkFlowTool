package io.github.workflowtool.ui.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.github.workflowtool.ui.theme.ControlBg
import io.github.workflowtool.ui.theme.Danger
import io.github.workflowtool.ui.theme.Panel
import io.github.workflowtool.ui.theme.SoftBorder
import io.github.workflowtool.ui.theme.TextDim
import io.github.workflowtool.ui.theme.TextMuted
import kotlin.math.roundToInt

@Composable
fun LeftPanel(controller: AppController, modifier: Modifier = Modifier) {
    val strings = controller.localization
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(end = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PanelCard(strings.text(StringKey.SelectImageTitle), Modifier.fillMaxWidth()) {
            GhostButton(
                "打开 ${strings.text(StringKey.OpenImage)}",
                controller::chooseImageFile,
                dashed = true,
                modifier = Modifier.fillMaxWidth().height(35.dp)
            )
            Spacer(Modifier.height(8.dp))
            ThumbnailBox(bitmap = remember(controller.image) { controller.image?.toComposeImageBitmap() })
            Spacer(Modifier.height(8.dp))
            Text(controller.imageFile?.name ?: "未选择图片", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(controller.image?.let { "${it.width} x ${it.height}" } ?: "-", color = TextMuted, fontSize = 14.sp)
        }

        PanelCard(strings.text(StringKey.SplitSettingsTitle), Modifier.fillMaxWidth()) {
            Text(strings.text(StringKey.BaseGeneration), color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    strings.text(StringKey.AutoMode),
                    { controller.rebuildFromAutoAsync(logResult = true) },
                    active = controller.splitSource == io.github.workflowtool.model.SplitSource.AutoDetect,
                    enabled = controller.isNativeSplitAvailable,
                    modifier = Modifier.weight(1f).height(38.dp)
                )
                GhostButton(
                    strings.text(StringKey.GridMode),
                    { controller.rebuildFromSmartGridAsync(logResult = true) },
                    active = controller.splitSource == io.github.workflowtool.model.SplitSource.SmartGrid,
                    enabled = controller.isNativeSplitAvailable,
                    modifier = Modifier.weight(1f).height(38.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            StatusLine(strings.text(StringKey.CurrentBaseSource), controller.baseSourceLabel)
            StatusLine(strings.text(StringKey.ManualEditsActive), controller.manualStatusLabel)
            StatusLine(strings.text(StringKey.DetectionMode), controller.detectionModeLabel)
            StatusLine(strings.text(StringKey.DetectionBackend), controller.detectionBackendLabel, multilineValue = true)
            StatusLine(strings.text(StringKey.DetectionTime), controller.detectionTimeLabel)
            StatusLine(strings.text(StringKey.CandidatePixels), controller.candidatePixelsLabel)
            StatusLine(strings.text(StringKey.BackgroundEstimate), controller.backgroundEstimateLabel)
            StatusLine("当前背景色", controller.activeBackgroundLabel)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(28.dp).height(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            controller.sampledBackgroundArgb?.let { Color(it) } ?: Color(0xFF2A3038)
                        )
                        .border(1.dp, SoftBorder, RoundedCornerShape(7.dp))
                )
                GhostButton(
                    if (controller.backgroundPickArmed) "点击预览取色中" else "取背景色",
                    controller::armBackgroundPicker,
                    active = controller.backgroundPickArmed,
                    modifier = Modifier.weight(1f).height(36.dp)
                )
                GhostButton(
                    "清除",
                    controller::clearSampledBackground,
                    enabled = controller.sampledBackgroundArgb != null,
                    modifier = Modifier.width(74.dp).height(36.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            GhostButton(
                strings.text(StringKey.Regenerate),
                controller::regenerateBaseAsync,
                enabled = controller.isNativeSplitAvailable,
                modifier = Modifier.fillMaxWidth().height(38.dp)
            )
            Spacer(Modifier.height(12.dp))
            if (!controller.isNativeSplitAvailable) {
                Text("C++ 检测后端当前不可用，自动识别和网格拆分会被禁用。", color = Danger, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
            }
            Text(strings.text(StringKey.ManualAdjustments), color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    strings.text(StringKey.DrawRectangle),
                    controller::enterManualDrawMode,
                    active = controller.toolMode == ToolMode.Draw,
                    modifier = Modifier.weight(1f).height(38.dp)
                )
                GhostButton(
                    strings.text(StringKey.MagicTool),
                    controller::enterMagicSelectionMode,
                    active = controller.toolMode == ToolMode.Magic,
                    modifier = Modifier.weight(1f).height(38.dp)
                )
                GhostButton(
                    strings.text(StringKey.ResetManualEdits),
                    controller::resetManualEdits,
                    modifier = Modifier.weight(1f).height(38.dp)
                )
            }
            if (controller.toolMode == ToolMode.Magic) {
                CompactStepper(
                    title = "魔棒容差",
                    value = controller.magicTolerance,
                    suffix = "",
                    onDecrease = { controller.updateMagicTolerance(controller.magicTolerance - 2) },
                    onIncrease = { controller.updateMagicTolerance(controller.magicTolerance + 2) }
                )
                StatusLine("魔棒像素", controller.magicSelectionPreview?.pixelCount?.toString() ?: "-")
                StatusLine("魔棒种子", controller.magicSelectionPreview?.let { "${it.seedX}, ${it.seedY}" } ?: "-")
            }
            Spacer(Modifier.height(8.dp))
            Text("在预览区可以拖拽创建区域，或使用魔棒根据连通颜色块自动生成选区。", color = TextDim, fontSize = 12.sp)
            SettingSwitch("合并相邻区域", "将相近且紧挨的候选区域合并。", controller.detectionConfig.mergeNearbyRegions) {
                controller.updateDetectionConfig(controller.detectionConfig.copy(mergeNearbyRegions = it))
            }
            SettingSwitch("移除小区域", "过滤尺寸或面积过小的候选区域。", controller.detectionConfig.removeSmallRegions) {
                controller.updateDetectionConfig(controller.detectionConfig.copy(removeSmallRegions = it))
            }
            CompactNumber(strings.text(StringKey.MinSize), controller.detectionConfig.minWidth, "px") {
                controller.updateDetectionConfig(controller.detectionConfig.copy(minWidth = it, minHeight = it))
            }
            CompactNumber(strings.text(StringKey.GapThreshold), controller.detectionConfig.gapThreshold, "px") {
                controller.updateDetectionConfig(controller.detectionConfig.copy(gapThreshold = it))
            }
            CompactNumber("颜色容差", controller.detectionConfig.colorDistanceThreshold, "") {
                controller.updateDetectionConfig(controller.detectionConfig.copy(colorDistanceThreshold = it.coerceAtLeast(1)))
            }
            CompactNumber("杈圭晫琛ュ伩", controller.detectionConfig.bboxPadding, "px") {
                controller.updateDetectionConfig(controller.detectionConfig.copy(bboxPadding = it.coerceAtLeast(0)))
            }
            Spacer(Modifier.height(10.dp))
            GridControls(controller.gridConfig, controller::updateGridConfig, strings)
        }

        PanelCard(strings.text(StringKey.OutputSettingsTitle), Modifier.fillMaxWidth()) {
            SelectField(strings.text(StringKey.OutputFormat), controller.outputFormat.name) {
                controller.updateOutputFormat(nextFormat(controller.outputFormat))
            }
            SelectField(strings.text(StringKey.OutputDirectory), controller.outputDirectory.toString(), controller::chooseOutputDirectory)
            SelectField(strings.text(StringKey.NamingMode), namingModeLabel(controller.namingMode)) {
                controller.updateNamingMode(nextNamingMode(controller.namingMode))
            }
            if (controller.namingMode == io.github.workflowtool.model.NamingMode.CustomPrefixSequence) {
                Text("自定义前缀", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                CompactTextField(controller.customPrefix, controller::updateCustomPrefix)
                Spacer(Modifier.height(12.dp))
            }
            SmallCheck(strings.text(StringKey.KeepOriginalSize), controller.keepOriginalSize, controller::updateKeepOriginalSize)
            SmallCheck(strings.text(StringKey.TrimTransparent), controller.trimTransparent, controller::updateTrimTransparent)
            SmallCheck(strings.text(StringKey.PadToSquare), controller.padToSquare, controller::updatePadToSquare)
        }
    }
}

@Composable
fun GridControls(config: GridConfig, onChange: (GridConfig) -> Unit, strings: LocalizationProvider) {
    Text(strings.text(StringKey.SmartGridSettings), color = Color.White, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    CompactNumber(strings.text(StringKey.GridColumns), config.columns, "") { onChange(config.copy(columns = it.coerceAtLeast(1))) }
    CompactNumber(strings.text(StringKey.GridRows), config.rows, "") { onChange(config.copy(rows = it.coerceAtLeast(1))) }
    CompactNumber(strings.text(StringKey.GridCellWidth), config.cellWidth, "px") { onChange(config.copy(cellWidth = it.coerceAtLeast(1))) }
    CompactNumber(strings.text(StringKey.GridCellHeight), config.cellHeight, "px") { onChange(config.copy(cellHeight = it.coerceAtLeast(1))) }
    CompactNumber(strings.text(StringKey.SearchPadding), config.searchPadding, "px") { onChange(config.copy(searchPadding = it.coerceAtLeast(0))) }
    SmallCheck(strings.text(StringKey.SnapToContent), config.snapToContent) { onChange(config.copy(snapToContent = it)) }
    SmallCheck(strings.text(StringKey.TrimCellToContent), config.trimCellToContent) { onChange(config.copy(trimCellToContent = it)) }
    SmallCheck(strings.text(StringKey.IgnoreEmptyCells), config.ignoreEmptyCells) { onChange(config.copy(ignoreEmptyCells = it)) }
}

@Composable
fun Toolbar(controller: AppController, canUndo: Boolean, canRedo: Boolean) {
    val strings = controller.localization
    Row(
        Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ToolButton("选择 ${strings.text(StringKey.SelectTool)}", controller.toolMode == ToolMode.Select, onClick = {
            controller.updateToolMode(ToolMode.Select)
        })
        ToolButton("移动 ${strings.text(StringKey.MoveTool)}", controller.toolMode == ToolMode.Move, onClick = {
            controller.updateToolMode(ToolMode.Move)
        })
        ToolButton("绘制 ${strings.text(StringKey.DrawRectangle)}", controller.toolMode == ToolMode.Draw, onClick = {
            controller.enterManualDrawMode()
        })
        ToolButton("魔棒 ${strings.text(StringKey.MagicTool)}", controller.toolMode == ToolMode.Magic, onClick = {
            controller.enterMagicSelectionMode()
        })
        Spacer(Modifier.width(8.dp))
        ToolButton("撤销 ${strings.text(StringKey.Undo)}", canUndo, controller::undo)
        ToolButton("重做 ${strings.text(StringKey.Redo)}", canRedo, controller::redo)
        ToolButton("清空 ${strings.text(StringKey.ClearRegions)}", false, controller::clearRegions)
        Spacer(Modifier.width(20.dp))
        SquareButton("-", onClick = { controller.adjustZoom(-0.1f) })
        Box(Modifier.width(58.dp), contentAlignment = Alignment.Center) {
            Text("${(controller.zoom * 100).roundToInt()}%", color = TextMuted, fontSize = 14.sp)
        }
        SquareButton("+", onClick = { controller.adjustZoom(0.1f) })
        ToolButton(strings.text(StringKey.FitWindow), false, controller::fitToViewport)
        ToolButton(strings.text(StringKey.FitSelection), false, controller::fitSelectionToViewport, enabled = controller.selectedRegion != null)
        ToolButton(strings.text(StringKey.GridToggle), controller.showGrid, controller::toggleGrid)
    }
}

@Composable
fun LogPanel(title: String, logs: List<String>, modifier: Modifier = Modifier) {
    PanelCard(title, modifier) {
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("鏆傛棤鏃ュ織", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            val visibleLogs = logs.takeLast(200)
            val listState = rememberLazyListState()
            LaunchedEffect(visibleLogs.size) {
                listState.animateScrollToItem((visibleLogs.size - 1).coerceAtLeast(0))
            }
            LazyColumn(state = listState) {
                itemsIndexed(visibleLogs) { _, line ->
                    SelectionContainer {
                        Text(line, color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

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

@Composable
fun AdvancedSettingsDialog(controller: AppController) {
    val strings = controller.localization
    AlertDialog(
        onDismissRequest = { controller.showAdvancedSettings(false) },
        title = { Text(strings.text(StringKey.AdvancedSettings), color = Color.White) },
        backgroundColor = Panel,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallCheck(strings.text(StringKey.KeepOriginalSize), controller.keepOriginalSize, controller::updateKeepOriginalSize)
                Text(strings.text(StringKey.FixedSize), color = Color.White, fontSize = 13.sp)
                CompactTextField(controller.fixedSizeText, controller::updateFixedSizeText, suffix = "px")
                SmallCheck(strings.text(StringKey.OverwriteExisting), controller.overwriteExisting, controller::updateOverwriteExisting)
                SmallCheck("持续学习训练集", controller.continuousTrainingEnabled, controller::updateContinuousTrainingEnabled)
                Text("开启后，识别和导出确认的区域会追加到训练集，并自动训练为下一次识别使用的新模型。", color = TextDim, fontSize = 12.sp)
            }
        },
        confirmButton = {
            GhostButton(strings.text(StringKey.Save), { controller.showAdvancedSettings(false) }, modifier = Modifier.width(96.dp))
        },
        dismissButton = {
            GhostButton(strings.text(StringKey.Cancel), { controller.showAdvancedSettings(false) }, modifier = Modifier.width(96.dp))
        }
    )
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
    val bitmap = remember(controller.image, region.x, region.y, region.width, region.height) {
        controller.image?.safeCrop(region)?.toComposeImageBitmap()
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RegionPreviewDialog(controller: AppController, region: CropRegion) {
    val bitmap = remember(controller.image, region.x, region.y, region.width, region.height) {
        controller.image?.safeCrop(region)?.toComposeImageBitmap()
    }
    if (bitmap == null) return

    val dialogState = rememberDialogState(width = 960.dp, height = 720.dp)

    DialogWindow(
        onCloseRequest = controller::closeRegionPreview,
        title = "区域预览",
        state = dialogState,
        resizable = true
    ) {
        var zoom by remember(region.id) { mutableStateOf(1f) }
        var viewportOffset by remember(region.id) { mutableStateOf(Offset.Zero) }
        var viewportSize by remember(region.id) { mutableStateOf(Size.Zero) }

        fun fitToViewport() {
            if (viewportSize.width <= 0f || viewportSize.height <= 0f) return
            val nextZoom = minOf(
                viewportSize.width / bitmap.width.toFloat(),
                viewportSize.height / bitmap.height.toFloat()
            ).coerceIn(0.1f, 12f)
            zoom = nextZoom
            viewportOffset = Offset(
                x = (viewportSize.width - bitmap.width * nextZoom) / 2f,
                y = (viewportSize.height - bitmap.height * nextZoom) / 2f
            )
        }

        LaunchedEffect(region.id, viewportSize) {
            fitToViewport()
        }

        Column(
            Modifier.fillMaxSize().background(Color(0xFF0D1015)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "区域 ${region.id} · ${region.width} x ${region.height}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SquareButton("-", onClick = { zoom = (zoom / 1.1f).coerceIn(0.1f, 12f) })
                Box(Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                    Text("${(zoom * 100).roundToInt()}%", color = TextMuted, fontSize = 13.sp)
                }
                SquareButton("+", onClick = { zoom = (zoom * 1.1f).coerceIn(0.1f, 12f) })
                GhostButton("导出当前", onClick = { controller.exportPreviewRegion(region.id) }, modifier = Modifier.width(112.dp).height(38.dp))
                GhostButton("适应窗口", onClick = ::fitToViewport, modifier = Modifier.width(112.dp).height(38.dp))
                GhostButton("关闭预览", onClick = controller::closeRegionPreview, modifier = Modifier.width(112.dp).height(38.dp))
            }

            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF121821))
                    .border(1.dp, SoftBorder, RoundedCornerShape(8.dp))
                    .onSizeChanged { viewportSize = it.toSize() }
            ) {
                Canvas(
                    Modifier.fillMaxSize()
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val change = event.changes.firstOrNull() ?: return@onPointerEvent
                            val factor = if (change.scrollDelta.y < 0f) 1.1f else 1f / 1.1f
                            val currentZoom = zoom
                            val nextZoom = (currentZoom * factor).coerceIn(0.1f, 12f)
                            if (nextZoom == currentZoom) return@onPointerEvent
                            val imagePoint = (change.position - viewportOffset) / currentZoom
                            zoom = nextZoom
                            viewportOffset = change.position - Offset(imagePoint.x * nextZoom, imagePoint.y * nextZoom)
                        }
                        .pointerInput(region.id) {
                            detectTapGestures(
                                onDoubleTap = {
                                    fitToViewport()
                                }
                            )
                        }
                        .pointerInput(region.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                viewportOffset += dragAmount
                            }
                        }
                ) {
                    drawRect(Color(0xFF10151D), size = size)
                    drawCheckerboard(size)
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(viewportOffset.x.roundToInt(), viewportOffset.y.roundToInt()),
                        dstSize = IntSize((bitmap.width * zoom).roundToInt(), (bitmap.height * zoom).roundToInt())
                    )
                }
            }
        }
    }
}

private fun java.awt.image.BufferedImage.safeCrop(region: CropRegion): java.awt.image.BufferedImage {
    val x = region.x.coerceIn(0, width - 1)
    val y = region.y.coerceIn(0, height - 1)
    val cropWidth = region.width.coerceAtMost(width - x).coerceAtLeast(1)
    val cropHeight = region.height.coerceAtMost(height - y).coerceAtLeast(1)
    return getSubimage(x, y, cropWidth, cropHeight)
}


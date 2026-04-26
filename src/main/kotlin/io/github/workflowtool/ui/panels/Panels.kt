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
import io.github.workflowtool.application.chooseImageFile
import io.github.workflowtool.application.chooseOutputDirectory
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
            Text(
                if (controller.imageFiles.size > 1) "多图画布：${controller.imageFiles.size} 张" else controller.imageFile?.name ?: "未选择图片",
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(controller.image?.let { "${it.width} x ${it.height}" } ?: "-", color = TextMuted, fontSize = 14.sp)
            if (controller.imageFiles.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text("已按网格排布到同一无限画布，可拖动画布查看。", color = TextDim, fontSize = 12.sp)
            }
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
                    if (controller.backgroundPickArmed) "吸色中" else "吸色工具",
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
            CompactNumber("边界补偿", controller.detectionConfig.bboxPadding, "px") {
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
            SmallCheck("背景转透明", controller.removeBackgroundToTransparent, controller::updateRemoveBackgroundToTransparent)
            if (controller.removeBackgroundToTransparent) {
                StatusLine("透明背景色", controller.exportBackgroundLabel)
                CompactNumber("背景容差", controller.backgroundRemovalTolerance, "") {
                    controller.updateBackgroundRemovalTolerance(it)
                }
            }
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


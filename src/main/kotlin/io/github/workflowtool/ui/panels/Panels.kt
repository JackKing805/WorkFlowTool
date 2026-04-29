package io.github.workflowtool.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workflowtool.application.AppController
import io.github.workflowtool.application.chooseImageFile
import io.github.workflowtool.application.chooseOutputDirectory
import io.github.workflowtool.application.updateBackgroundRemovalTolerance
import io.github.workflowtool.application.updateCustomPrefix
import io.github.workflowtool.application.updateDetectionConfig
import io.github.workflowtool.application.updateKeepOriginalSize
import io.github.workflowtool.application.updateNamingMode
import io.github.workflowtool.application.updateOutputFormat
import io.github.workflowtool.application.updatePadToSquare
import io.github.workflowtool.application.updateRemoveBackgroundToTransparent
import io.github.workflowtool.application.updateTrimTransparent
import io.github.workflowtool.domain.StringKey
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.ui.components.CompactNumber
import io.github.workflowtool.ui.components.CompactTextField
import io.github.workflowtool.ui.components.DropdownSelectField
import io.github.workflowtool.ui.components.GhostButton
import io.github.workflowtool.ui.components.PanelCard
import io.github.workflowtool.ui.components.SelectField
import io.github.workflowtool.ui.components.SettingSwitch
import io.github.workflowtool.ui.components.SmallCheck
import io.github.workflowtool.ui.components.StatusLine
import io.github.workflowtool.ui.components.ThumbnailBox
import io.github.workflowtool.ui.components.namingModeLabel
import io.github.workflowtool.model.ImageFormat
import io.github.workflowtool.model.NamingMode
import io.github.workflowtool.ui.theme.Danger
import io.github.workflowtool.ui.theme.SoftBorder
import io.github.workflowtool.ui.theme.TextDim
import io.github.workflowtool.ui.theme.TextMuted

@Composable
fun LeftPanel(controller: AppController, modifier: Modifier = Modifier) {
    val strings = controller.localization
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(end = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PanelCard(strings.text(StringKey.SelectImageTitle), Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    strings.text(StringKey.OpenImage),
                    controller::chooseImageFile,
                    dashed = true,
                    modifier = Modifier.weight(1f).height(35.dp)
                )
                GhostButton(
                    "历史记录",
                    onClick = { controller.showHistoryDialog(true) },
                    modifier = Modifier.width(104.dp).height(35.dp)
                )
            }
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
            GhostButton(
                strings.text(StringKey.AutoMode),
                { controller.rebuildFromAutoAsync(logResult = true) },
                active = true,
                enabled = controller.isAutoDetectAvailable,
                modifier = Modifier.fillMaxWidth().height(38.dp)
            )
            Spacer(Modifier.height(12.dp))
            StatusLine(strings.text(StringKey.CurrentBaseSource), controller.baseSourceLabel)
            StatusLine(strings.text(StringKey.ManualEditsActive), controller.manualStatusLabel)
            StatusLine(strings.text(StringKey.DetectionMode), controller.detectionModeLabel)
            BackendWarning(controller)
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
                enabled = controller.canRegenerateBase,
                modifier = Modifier.fillMaxWidth().height(38.dp)
            )
            Spacer(Modifier.height(12.dp))
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
                    strings.text(StringKey.ResetManualEdits),
                    controller::resetManualEdits,
                    modifier = Modifier.weight(1f).height(38.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("在预览区拖拽框选一个范围，松开后会自动识别范围内的图标区域。", color = TextDim, fontSize = 12.sp)
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
        }

        PanelCard(strings.text(StringKey.OutputSettingsTitle), Modifier.fillMaxWidth()) {
            DropdownSelectField(
                title = strings.text(StringKey.OutputFormat),
                value = controller.outputFormat,
                options = ImageFormat.entries,
                optionLabel = { it.name },
                onSelect = controller::updateOutputFormat
            )
            SelectField(strings.text(StringKey.OutputDirectory), controller.outputDirectory.toString(), controller::chooseOutputDirectory)
            DropdownSelectField(
                title = strings.text(StringKey.NamingMode),
                value = controller.namingMode,
                options = NamingMode.entries,
                optionLabel = ::namingModeLabel,
                onSelect = controller::updateNamingMode
            )
            if (controller.namingMode == NamingMode.CustomPrefixSequence) {
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
private fun BackendWarning(controller: AppController) {
    if (controller.isAutoDetectAvailable) return
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF2A171A))
            .border(1.dp, Color(0xFF7A2F39), RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text("自动识别后端不可用", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            controller.runtimeStatusLabel,
            color = Color(0xFFFFC2CB),
            fontSize = 12.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(8.dp))
        GhostButton(
            "重试准备运行环境",
            controller::preparePythonRuntimeAsync,
            enabled = !controller.isBusy,
            modifier = Modifier.fillMaxWidth().height(34.dp)
        )
    }
}

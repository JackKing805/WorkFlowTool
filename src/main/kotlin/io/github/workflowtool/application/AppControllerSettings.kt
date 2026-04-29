package io.github.workflowtool.application

import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.ImageFormat
import io.github.workflowtool.model.NamingMode

fun AppController.updateDetectionConfig(next: DetectionConfig) {
    if (detectionConfig == next) return
    detectionConfig = next
    persistSettings()
}

fun AppController.updateOutputFormat(next: ImageFormat) {
    if (outputFormat == next) return
    outputFormat = next
    persistSettings()
}

fun AppController.updateNamingMode(next: NamingMode) {
    if (namingMode == next) return
    namingMode = next
    persistSettings()
}

fun AppController.updateCustomPrefix(next: String) {
    if (customPrefix == next) return
    customPrefix = next
    persistSettings()
}

fun AppController.updateKeepOriginalSize(next: Boolean) {
    if (keepOriginalSize == next && (!next || fixedSizeText.isEmpty())) return
    keepOriginalSize = next
    if (next) fixedSizeText = ""
    persistSettings()
}

fun AppController.updateTrimTransparent(next: Boolean) {
    if (trimTransparent == next) return
    trimTransparent = next
    persistSettings()
}

fun AppController.updateRemoveBackgroundToTransparent(next: Boolean) {
    if (removeBackgroundToTransparent == next) return
    removeBackgroundToTransparent = next
    persistSettings()
}

fun AppController.updateBackgroundRemovalTolerance(next: Int) {
    val sanitized = next.coerceIn(0, 255)
    if (backgroundRemovalTolerance == sanitized) return
    backgroundRemovalTolerance = sanitized
    persistSettings()
}

fun AppController.updatePadToSquare(next: Boolean) {
    if (padToSquare == next) return
    padToSquare = next
    persistSettings()
}

fun AppController.updateFixedSizeText(next: String) {
    val sanitized = next.filter(Char::isDigit)
    if (fixedSizeText == sanitized && (!keepOriginalSize || sanitized.isBlank())) return
    fixedSizeText = sanitized
    if (fixedSizeText.isNotBlank()) keepOriginalSize = false
    persistSettings()
}

fun AppController.updateOverwriteExisting(next: Boolean) {
    if (overwriteExisting == next) return
    overwriteExisting = next
    persistSettings()
}

fun AppController.updateContinuousTrainingEnabled(next: Boolean) {
    if (continuousTrainingEnabled == next) return
    continuousTrainingEnabled = next
    persistSettings()
    log(if (next) "持续学习已开启：只有手动修正后导出确认的区域会更新训练集并重训模型" else "持续学习已关闭")
}

fun AppController.updateRefineBrushSize(next: Int) {
    val sanitized = next.coerceIn(MinRefineBrushSizePx, MaxRefineBrushSizePx)
    if (refineBrushSizePx == sanitized) return
    refineBrushSizePx = sanitized
}

fun AppController.increaseRefineBrushSize() {
    updateRefineBrushSize(refineBrushSizePx + RefineBrushSizeStepPx)
}

fun AppController.decreaseRefineBrushSize() {
    updateRefineBrushSize(refineBrushSizePx - RefineBrushSizeStepPx)
}

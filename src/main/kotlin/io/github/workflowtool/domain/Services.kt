package io.github.workflowtool.domain

import androidx.compose.ui.unit.Dp
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.ExportResult
import java.awt.image.BufferedImage

interface RegionDetector {
    fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult
}

interface RegionExporter {
    fun export(
        image: BufferedImage,
        sourceFileName: String,
        regions: List<CropRegion>,
        config: ExportConfig
    ): ExportResult
}

interface NativeImageEngine {
    val isAvailable: Boolean
    val backendName: String
    val detail: String
}

interface WindowController {
    fun minimize()
    fun toggleMaximize()
    fun close()
}

enum class StringKey {
    AppTitle,
    PreviewTitle,
    LogTitle,
    SelectImageTitle,
    SplitSettingsTitle,
    OutputSettingsTitle,
    RegionsTitle,
    ProcessTitle,
    OpenImage,
    AutoMode,
    BaseGeneration,
    ManualAdjustments,
    CurrentBaseSource,
    ManualEditsActive,
    Regenerate,
    ResetManualEdits,
    DrawRectangle,
    MergeNearby,
    RemoveSmall,
    MinSize,
    GapThreshold,
    OutputFormat,
    OutputDirectory,
    NamingMode,
    KeepOriginalSize,
    TrimTransparent,
    PadToSquare,
    SelectTool,
    MoveTool,
    Undo,
    Redo,
    ClearRegions,
    FitWindow,
    FitSelection,
    GridToggle,
    DetectionMode,
    DetectionTime,
    CandidatePixels,
    BackgroundEstimate,
    DetectionBackend,
    SelectAll,
    InvertSelection,
    Clear,
    StartCrop,
    AdvancedSettings,
    OpenOutputDirectory,
    FixedSize,
    OverwriteExisting,
    Save,
    Cancel
}

interface LocalizationProvider {
    fun text(key: StringKey): String
}

data class LayoutState(
    val leftPanelWidth: Dp,
    val rightPanelWidth: Dp,
    val previewHeight: Dp
)

data class LayoutBounds(
    val totalWidth: Dp,
    val totalHeight: Dp,
    val leftPanelMinWidth: Dp,
    val centerMinWidth: Dp,
    val rightPanelMinWidth: Dp,
    val previewMinHeight: Dp,
    val logMinHeight: Dp
)

interface LayoutConstraintPolicy {
    fun clamp(state: LayoutState, bounds: LayoutBounds): LayoutState
}

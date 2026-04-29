package io.github.workflowtool.model

import java.nio.file.Path

data class CropRegion(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val visible: Boolean = true,
    val selected: Boolean = false,
    val maskWidth: Int = 0,
    val maskHeight: Int = 0,
    val alphaMask: List<Int> = emptyList(),
    val score: Float? = null
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

enum class MaskEditMode {
    Replace,
    Add,
    Subtract
}

data class DetectionConfig(
    val minWidth: Int = 16,
    val minHeight: Int = 16,
    val gapThreshold: Int = 4,
    val alphaThreshold: Int = 8,
    val backgroundTolerance: Int = 12,
    val edgeSampleWidth: Int = 2,
    val minPixelArea: Int = 24,
    val colorDistanceThreshold: Int = 36,
    val dilateIterations: Int = 0,
    val erodeIterations: Int = 0,
    val enableHoleFill: Boolean = true,
    val bboxPadding: Int = 1,
    val mergeNearbyRegions: Boolean = true,
    val removeSmallRegions: Boolean = true,
    val manualRefineExpansionRadius: Int = 4,
    val manualRefineConflictTolerance: Double = 0.45,
    val useManualBackground: Boolean = false,
    val manualBackgroundArgb: Int = 0
)

enum class DetectionMode {
    ALPHA_MASK,
    SOLID_BACKGROUND,
    FALLBACK_BACKGROUND
}

data class DetectionStats(
    val estimatedBackgroundArgb: Int,
    val candidatePixels: Int,
    val connectedComponents: Int,
    val regionCount: Int,
    val backgroundSampleCount: Int,
    val totalTimeMs: Long,
    val backend: String = ""
)

data class DetectionResult(
    val regions: List<CropRegion>,
    val mode: DetectionMode,
    val stats: DetectionStats
)

data class ExportConfig(
    val outputFormat: ImageFormat = ImageFormat.PNG,
    val outputDirectory: Path,
    val namingMode: NamingMode = NamingMode.Sequence,
    val customPrefix: String = "icon",
    val keepOriginalSize: Boolean = true,
    val trimTransparentPadding: Boolean = false,
    val removeBackgroundToTransparent: Boolean = false,
    val backgroundArgb: Int = 0,
    val backgroundTolerance: Int = 20,
    val padToSquare: Boolean = false,
    val fixedSize: Int? = null,
    val overwriteExisting: Boolean = false
)

enum class ImageFormat(val extension: String, val imageIoName: String) {
    PNG("png", "png"),
    JPG("jpg", "jpg"),
    WEBP("webp", "webp")
}

enum class NamingMode {
    Sequence,
    SourceNameSequence,
    CustomPrefixSequence
}

enum class ToolMode {
    Select,
    Move,
    Draw,
    Eyedropper
}

data class ExportResult(
    val successCount: Int,
    val failureCount: Int,
    val failures: List<String>
)

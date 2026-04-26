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
    val points: List<RegionPoint> = emptyList()
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    val editPoints: List<RegionPoint>
        get() = points.ifEmpty {
            listOf(
                RegionPoint(x, y),
                RegionPoint(right, y),
                RegionPoint(right, bottom),
                RegionPoint(x, bottom)
            )
        }
}

data class RegionPoint(
    val x: Int,
    val y: Int
)

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
    val totalTimeMs: Long
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

enum class SplitSource {
    AutoDetect,
    SmartGrid
}

enum class ToolMode {
    Select,
    Move,
    Draw,
    Magic,
    Eyedropper
}

data class GridConfig(
    val cellWidth: Int = 96,
    val cellHeight: Int = 96,
    val columns: Int = 8,
    val rows: Int = 3,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val gapX: Int = 0,
    val gapY: Int = 0,
    val snapToContent: Boolean = true,
    val searchPadding: Int = 12,
    val ignoreEmptyCells: Boolean = true,
    val trimCellToContent: Boolean = true,
    val alphaThreshold: Int = 8,
    val backgroundTolerance: Int = 12
)

data class ExportResult(
    val successCount: Int,
    val failureCount: Int,
    val failures: List<String>
)

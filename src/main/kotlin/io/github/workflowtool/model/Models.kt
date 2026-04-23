package io.github.workflowtool.model

import java.nio.file.Path

data class CropRegion(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val visible: Boolean = true,
    val selected: Boolean = false
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

data class DetectionConfig(
    val minWidth: Int = 16,
    val minHeight: Int = 16,
    val gapThreshold: Int = 4,
    val alphaThreshold: Int = 8,
    val backgroundTolerance: Int = 12,
    val mergeNearbyRegions: Boolean = true,
    val removeSmallRegions: Boolean = true
)

data class ExportConfig(
    val outputFormat: ImageFormat = ImageFormat.PNG,
    val outputDirectory: Path,
    val namingMode: NamingMode = NamingMode.Sequence,
    val customPrefix: String = "icon",
    val keepOriginalSize: Boolean = true,
    val trimTransparentPadding: Boolean = false,
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

enum class SplitMode {
    AutoDetect,
    Grid,
    Custom
}

enum class ToolMode {
    Select,
    Move,
    Draw
}

data class GridConfig(
    val cellWidth: Int = 96,
    val cellHeight: Int = 96,
    val columns: Int = 8,
    val rows: Int = 3,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val gapX: Int = 0,
    val gapY: Int = 0
)

data class ExportResult(
    val successCount: Int,
    val failureCount: Int,
    val failures: List<String>
)


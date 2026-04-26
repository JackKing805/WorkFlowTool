package io.github.workflowtool.application

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.GridConfig
import io.github.workflowtool.model.RegionPoint
import java.awt.image.BufferedImage

internal interface CppDetectorLibrary : Library {
    fun detect_icons_stub(): Int
    fun detector_backend_name(): String?
    fun detect_icons(
        image: NativeImageBuffer,
        config: NativeDetectionConfig,
        result: NativeDetectionResult
    ): Int
    fun split_grid(
        image: NativeImageBuffer,
        config: NativeGridConfig,
        result: NativeDetectionResult
    ): Int
    fun detect_magic_region(
        image: NativeImageBuffer,
        seedX: Int,
        seedY: Int,
        config: NativeDetectionConfig,
        result: NativeMagicResult
    ): Int
    fun merge_magic_masks(
        currentMask: Pointer,
        currentLength: Int,
        addedMask: Pointer,
        addedLength: Int,
        width: Int,
        height: Int,
        bboxPadding: Int,
        result: NativeMagicResult
    ): Int
    fun magic_mask_contains(
        mask: Pointer,
        maskLength: Int,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): Int

    fun free_detection_result(result: NativeDetectionResult)
    fun free_magic_result(result: NativeMagicResult)
}

@Structure.FieldOrder("width", "height", "pixels")
internal open class NativeImageBuffer() : Structure() {
    @JvmField
    var width: Int = 0

    @JvmField
    var height: Int = 0

    @JvmField
    var pixels: Pointer? = null

    constructor(width: Int, height: Int, pixels: Pointer) : this() {
        this.width = width
        this.height = height
        this.pixels = pixels
    }
}

@Structure.FieldOrder(
    "minWidth",
    "minHeight",
    "gapThreshold",
    "alphaThreshold",
    "backgroundTolerance",
    "edgeSampleWidth",
    "minPixelArea",
    "colorDistanceThreshold",
    "dilateIterations",
    "erodeIterations",
    "enableHoleFill",
    "bboxPadding",
    "mergeNearbyRegions",
    "removeSmallRegions",
    "useManualBackground",
    "manualBackgroundArgb"
)
internal open class NativeDetectionConfig() : Structure() {
    @JvmField var minWidth: Int = 0
    @JvmField var minHeight: Int = 0
    @JvmField var gapThreshold: Int = 0
    @JvmField var alphaThreshold: Int = 0
    @JvmField var backgroundTolerance: Int = 0
    @JvmField var edgeSampleWidth: Int = 0
    @JvmField var minPixelArea: Int = 0
    @JvmField var colorDistanceThreshold: Int = 0
    @JvmField var dilateIterations: Int = 0
    @JvmField var erodeIterations: Int = 0
    @JvmField var enableHoleFill: Byte = 0
    @JvmField var bboxPadding: Int = 0
    @JvmField var mergeNearbyRegions: Byte = 0
    @JvmField var removeSmallRegions: Byte = 0
    @JvmField var useManualBackground: Byte = 0
    @JvmField var manualBackgroundArgb: Int = 0

    constructor(config: DetectionConfig) : this() {
        minWidth = config.minWidth
        minHeight = config.minHeight
        gapThreshold = config.gapThreshold
        alphaThreshold = config.alphaThreshold
        backgroundTolerance = config.backgroundTolerance
        edgeSampleWidth = config.edgeSampleWidth
        minPixelArea = config.minPixelArea
        colorDistanceThreshold = config.colorDistanceThreshold
        dilateIterations = config.dilateIterations
        erodeIterations = config.erodeIterations
        enableHoleFill = config.enableHoleFill.toNativeFlag()
        bboxPadding = config.bboxPadding
        mergeNearbyRegions = config.mergeNearbyRegions.toNativeFlag()
        removeSmallRegions = config.removeSmallRegions.toNativeFlag()
        useManualBackground = config.useManualBackground.toNativeFlag()
        manualBackgroundArgb = config.manualBackgroundArgb
    }
}

@Structure.FieldOrder(
    "cellWidth",
    "cellHeight",
    "columns",
    "rows",
    "offsetX",
    "offsetY",
    "gapX",
    "gapY",
    "snapToContent",
    "searchPadding",
    "ignoreEmptyCells",
    "trimCellToContent",
    "alphaThreshold",
    "backgroundTolerance"
)
internal open class NativeGridConfig() : Structure() {
    @JvmField var cellWidth: Int = 0
    @JvmField var cellHeight: Int = 0
    @JvmField var columns: Int = 0
    @JvmField var rows: Int = 0
    @JvmField var offsetX: Int = 0
    @JvmField var offsetY: Int = 0
    @JvmField var gapX: Int = 0
    @JvmField var gapY: Int = 0
    @JvmField var snapToContent: Byte = 0
    @JvmField var searchPadding: Int = 0
    @JvmField var ignoreEmptyCells: Byte = 0
    @JvmField var trimCellToContent: Byte = 0
    @JvmField var alphaThreshold: Int = 0
    @JvmField var backgroundTolerance: Int = 0

    constructor(config: GridConfig) : this() {
        cellWidth = config.cellWidth
        cellHeight = config.cellHeight
        columns = config.columns
        rows = config.rows
        offsetX = config.offsetX
        offsetY = config.offsetY
        gapX = config.gapX
        gapY = config.gapY
        snapToContent = config.snapToContent.toNativeFlag()
        searchPadding = config.searchPadding
        ignoreEmptyCells = config.ignoreEmptyCells.toNativeFlag()
        trimCellToContent = config.trimCellToContent.toNativeFlag()
        alphaThreshold = config.alphaThreshold
        backgroundTolerance = config.backgroundTolerance
    }
}

@Structure.FieldOrder("x", "y", "width", "height", "visible", "selected")
internal open class NativeRegion() : Structure() {
    @JvmField var x: Int = 0
    @JvmField var y: Int = 0
    @JvmField var width: Int = 0
    @JvmField var height: Int = 0
    @JvmField var visible: Byte = 1
    @JvmField var selected: Byte = 0

    constructor(memory: Pointer) : this() {
        useMemory(memory)
    }
}

@Structure.FieldOrder(
    "estimatedBackgroundArgb",
    "candidatePixels",
    "connectedComponents",
    "regionCount",
    "backgroundSampleCount",
    "totalTimeMs"
)
internal open class NativeDetectionStats() : Structure() {
    @JvmField var estimatedBackgroundArgb: Int = 0
    @JvmField var candidatePixels: Int = 0
    @JvmField var connectedComponents: Int = 0
    @JvmField var regionCount: Int = 0
    @JvmField var backgroundSampleCount: Int = 0
    @JvmField var totalTimeMs: NativeLong = NativeLong(0)
}

@Structure.FieldOrder("mode", "regionCount", "regions", "stats")
internal open class NativeDetectionResult() : Structure() {
    @JvmField var mode: Int = DetectionMode.FALLBACK_BACKGROUND.nativeValue
    @JvmField var regionCount: Int = 0
    @JvmField var regions: Pointer? = null
    @JvmField var stats: NativeDetectionStats = NativeDetectionStats()
}

@Structure.FieldOrder("region", "mask", "maskLength", "imageWidth", "imageHeight", "seedX", "seedY", "pixelCount", "found")
internal open class NativeMagicResult() : Structure() {
    @JvmField var region: NativeRegion = NativeRegion()
    @JvmField var mask: Pointer? = null
    @JvmField var maskLength: Int = 0
    @JvmField var imageWidth: Int = 0
    @JvmField var imageHeight: Int = 0
    @JvmField var seedX: Int = 0
    @JvmField var seedY: Int = 0
    @JvmField var pixelCount: Int = 0
    @JvmField var found: Byte = 0
}

internal fun BufferedImage.toNativeImageBuffer(): Pair<NativeImageBuffer, Memory> {
    val pixels = IntArray(width * height)
    getRGB(0, 0, width, height, pixels, 0, width)
    val memory = Memory(pixels.size.toLong() * Int.SIZE_BYTES)
    memory.write(0, pixels, 0, pixels.size)
    val buffer = NativeImageBuffer(width, height, memory)
    buffer.write()
    return buffer to memory
}

internal fun BooleanArray.toNativeMask(): Memory {
    val memory = Memory(size.toLong())
    val bytes = ByteArray(size) { index -> if (this[index]) 1 else 0 }
    memory.write(0, bytes, 0, bytes.size)
    return memory
}

internal fun NativeDetectionResult.toDomainResult(): DetectionResult {
    read()
    stats.read()
    val regionStructSize = NativeRegion().size()
    val decodedRegions = buildList(regionCount.coerceAtLeast(0)) {
        val basePointer = regions ?: return@buildList
        repeat(regionCount.coerceAtLeast(0)) { index ->
            val region = NativeRegion(basePointer.share(index.toLong() * regionStructSize)).apply { read() }
            add(
                CropRegion(
                    id = (index + 1).toString(),
                    x = region.x,
                    y = region.y,
                    width = region.width,
                    height = region.height,
                    visible = region.visible.toBooleanFlag(default = true),
                    selected = region.selected.toBooleanFlag(default = false)
                )
            )
        }
    }

    return DetectionResult(
        regions = decodedRegions,
        mode = detectionModeFromNative(mode),
        stats = DetectionStats(
            estimatedBackgroundArgb = stats.estimatedBackgroundArgb,
            candidatePixels = stats.candidatePixels,
            connectedComponents = stats.connectedComponents,
            regionCount = stats.regionCount,
            backgroundSampleCount = stats.backgroundSampleCount,
            totalTimeMs = stats.totalTimeMs.toLong()
        )
    )
}

internal fun NativeMagicResult.toMagicSelectionResult(): MagicSelectionResult? {
    read()
    region.read()
    if (found.toInt() != 1 || mask == null || maskLength <= 0) return null
    val bytes = mask!!.getByteArray(0, maskLength)
    val decodedMask = BooleanArray(maskLength) { index -> bytes[index].toInt() != 0 }
    return MagicSelectionResult(
        region = CropRegion(
            id = java.util.UUID.randomUUID().toString(),
            x = region.x,
            y = region.y,
            width = region.width,
            height = region.height,
            visible = region.visible.toBooleanFlag(default = true),
            selected = true,
            points = magicMaskOutline(decodedMask, imageWidth, imageHeight)
        ),
        mask = decodedMask,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        seedX = seedX,
        seedY = seedY,
        pixelCount = pixelCount
    )
}

private fun magicMaskOutline(mask: BooleanArray, width: Int, height: Int): List<RegionPoint> {
    if (width <= 0 || height <= 0 || mask.isEmpty()) return emptyList()
    val rows = mutableListOf<MagicMaskSpan>()
    for (y in 0 until height) {
        var left = -1
        var right = -1
        for (x in 0 until width) {
            if (mask[y * width + x]) {
                if (left < 0) left = x
                right = x
            }
        }
        if (left >= 0) rows += MagicMaskSpan(y, left, right + 1)
    }
    if (rows.isEmpty()) return emptyList()

    val step = (rows.size / 96).coerceAtLeast(1)
    val sampled = rows.filterIndexed { index, _ -> index % step == 0 }.toMutableList()
    if (sampled.last() != rows.last()) sampled += rows.last()

    val leftSide = sampled.map { RegionPoint(it.left, it.y) }
    val rightSide = sampled.asReversed().map { RegionPoint(it.right, it.y + 1) }
    return (leftSide + rightSide).dedupeAdjacentPoints()
}

private data class MagicMaskSpan(
    val y: Int,
    val left: Int,
    val right: Int
)

private fun List<RegionPoint>.dedupeAdjacentPoints(): List<RegionPoint> {
    val output = mutableListOf<RegionPoint>()
    forEach { point ->
        if (output.lastOrNull() != point) output += point
    }
    if (output.size > 1 && output.first() == output.last()) output.removeAt(output.lastIndex)
    return output
}

internal val DetectionMode.nativeValue: Int
    get() = when (this) {
        DetectionMode.ALPHA_MASK -> 0
        DetectionMode.SOLID_BACKGROUND -> 1
        DetectionMode.FALLBACK_BACKGROUND -> 2
    }

internal fun detectionModeFromNative(value: Int): DetectionMode = when (value) {
    0 -> DetectionMode.ALPHA_MASK
    1 -> DetectionMode.SOLID_BACKGROUND
    else -> DetectionMode.FALLBACK_BACKGROUND
}

private fun Boolean.toNativeFlag(): Byte = if (this) 1 else 0

private fun Byte.toBooleanFlag(default: Boolean): Boolean = when (toInt()) {
    0 -> false
    1 -> true
    else -> default
}

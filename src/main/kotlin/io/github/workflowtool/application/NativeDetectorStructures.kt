package io.github.workflowtool.application

import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.GridConfig

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

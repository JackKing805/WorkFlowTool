package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.RegionPoint

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

internal fun Boolean.toNativeFlag(): Byte = if (this) 1 else 0

private fun Byte.toBooleanFlag(default: Boolean): Boolean = when (toInt()) {
    0 -> false
    1 -> true
    else -> default
}

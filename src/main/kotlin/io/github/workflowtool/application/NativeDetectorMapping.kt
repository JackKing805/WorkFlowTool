package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats

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
                    selected = region.selected.toBooleanFlag(default = false),
                    score = region.score.takeIf { it.isFinite() && it > 0f }
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

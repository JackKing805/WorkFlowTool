package io.github.workflowtool.application

import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.CropRegion
import java.awt.image.BufferedImage

internal data class MagicSelectionResult(
    val region: CropRegion,
    val mask: BooleanArray,
    val imageWidth: Int,
    val imageHeight: Int,
    val seedX: Int,
    val seedY: Int,
    val pixelCount: Int
)

data class MagicSelectionPreview(
    val seedX: Int,
    val seedY: Int,
    val regionId: String?,
    val mask: BooleanArray,
    val imageWidth: Int,
    val imageHeight: Int,
    val pixelCount: Int
)

internal data class MergedMagicMask(
    val mask: BooleanArray,
    val region: CropRegion,
    val pixelCount: Int
)

internal fun detectMagicRegion(
    image: BufferedImage,
    seedX: Int,
    seedY: Int,
    config: DetectionConfig
): MagicSelectionResult? {
    return CppDetectorBridge.detectMagicRegion(image, seedX, seedY, config)
}

internal fun magicMaskContains(preview: MagicSelectionPreview, x: Int, y: Int): Boolean {
    return CppDetectorBridge.magicMaskContains(preview.mask, preview.imageWidth, preview.imageHeight, x, y)
}

internal fun MagicSelectionResult.toPreview(regionId: String): MagicSelectionPreview {
    return MagicSelectionPreview(
        seedX = seedX,
        seedY = seedY,
        regionId = regionId,
        mask = mask,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        pixelCount = pixelCount
    )
}

internal fun MergedMagicMask.toPreview(seedX: Int, seedY: Int, regionId: String, source: MagicSelectionPreview): MagicSelectionPreview {
    return MagicSelectionPreview(
        seedX = seedX,
        seedY = seedY,
        regionId = regionId,
        mask = mask,
        imageWidth = source.imageWidth,
        imageHeight = source.imageHeight,
        pixelCount = pixelCount
    )
}

internal fun mergeMagicMasks(
    current: MagicSelectionPreview,
    added: MagicSelectionResult,
    bboxPadding: Int
): MergedMagicMask? {
    return CppDetectorBridge.mergeMagicMasks(current, added, bboxPadding)
}

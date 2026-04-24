package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
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

internal fun detectMagicRegion(
    image: BufferedImage,
    seedX: Int,
    seedY: Int,
    config: DetectionConfig
): MagicSelectionResult? {
    return CppDetectorBridge.detectMagicRegion(image, seedX, seedY, config)
}

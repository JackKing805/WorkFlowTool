package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.fromMaskBounds
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.model.withAlphaMask
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal fun postProcessDetection(
    image: BufferedImage,
    config: DetectionConfig,
    result: DetectionResult
): DetectionResult {
    if (result.regions.isEmpty()) return result
    val backgroundArgb = when {
        config.useManualBackground -> config.manualBackgroundArgb
        result.stats.estimatedBackgroundArgb != 0 -> result.stats.estimatedBackgroundArgb
        else -> estimateCornerBackgroundArgb(image)
    }
    val refined = dedupeRegions(
        result.regions.mapNotNull { region ->
            if (region.hasMask()) {
                refineMaskBackedRegion(region, image, config)
            } else {
                refineDetectedRegion(region, image, config, backgroundArgb, result.mode)
                    ?.let { attachSelectionMask(it, image, config, backgroundArgb, result.mode) }
            }
        }
    ).sortedWith(compareBy<CropRegion> { it.y }.thenBy { it.x })

    return result.copy(
        regions = refined,
        stats = result.stats.copy(
            connectedComponents = refined.size,
            regionCount = refined.size
        )
    )
}

private fun refineMaskBackedRegion(
    region: CropRegion,
    image: BufferedImage,
    config: DetectionConfig
): CropRegion? {
    if (!region.hasMask()) return null
    val normalized = fromMaskBounds(
        x = region.x,
        y = region.y,
        width = region.maskWidth,
        height = region.maskHeight,
        mask = region.alphaMask,
        imageWidth = image.width,
        imageHeight = image.height,
        id = region.id
    ) ?: return null
    val pixelCount = normalized.alphaMask.count { it > 0 }
    val area = normalized.width * normalized.height
    if (config.removeSmallRegions && (normalized.width < config.minWidth || normalized.height < config.minHeight)) return null
    if (config.removeSmallRegions && pixelCount < config.minPixelArea) return null
    if (area <= 0) return null
    val density = pixelCount.toFloat() / area.toFloat()
    if (density < max(0.02f, config.minPixelArea / 6000f)) return null
    return normalized.copy(
        visible = region.visible,
        selected = region.selected,
        score = region.score
    )
}

private fun attachSelectionMask(
    region: CropRegion,
    image: BufferedImage,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): CropRegion {
    val left = region.x.coerceIn(0, image.width)
    val top = region.y.coerceIn(0, image.height)
    val right = region.right.coerceIn(left, image.width)
    val bottom = region.bottom.coerceIn(top, image.height)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return region
    val hardMask = BooleanArray(width * height)
    for (localY in 0 until height) {
        val y = top + localY
        for (localX in 0 until width) {
            val x = left + localX
            hardMask[localY * width + localX] = isForegroundPixel(image.getRGB(x, y), config, backgroundArgb, mode)
        }
    }
    val alphaMask = MutableList(width * height) { 0 }
    for (localY in 0 until height) {
        for (localX in 0 until width) {
            val index = localY * width + localX
            if (!hardMask[index]) continue
            val edge = (-1..1).any { dy ->
                (-1..1).any { dx ->
                    val nx = localX + dx
                    val ny = localY + dy
                    nx !in 0 until width || ny !in 0 until height || !hardMask[ny * width + nx]
                }
            }
            alphaMask[index] = if (edge) 220 else 255
        }
    }
    return region.copy(x = left, y = top, width = width, height = height).withAlphaMask(alphaMask, width, height)
}

private fun refineDetectedRegion(
    region: CropRegion,
    image: BufferedImage,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): CropRegion? {
    val normalized = tightenRectRegion(region, image, config, backgroundArgb, mode) ?: return null

    val area = normalized.width * normalized.height
    if (area <= 0) return null
    val foregroundPixels = foregroundPixelCount(normalized, image, config, backgroundArgb, mode)
    val shortestSide = min(normalized.width, normalized.height).coerceAtLeast(1)
    val longestSide = max(normalized.width, normalized.height)
    val aspectRatio = longestSide.toFloat() / shortestSide.toFloat()
    val density = foregroundPixels.toFloat() / area.toFloat()
    val minimumForeground = max(4, config.minPixelArea / 3)

    if (config.removeSmallRegions && foregroundPixels < minimumForeground) return null
    if (config.removeSmallRegions && shortestSide <= 3 && aspectRatio > 5.5f && density < 0.42f) return null
    if (config.removeSmallRegions && density < 0.032f && aspectRatio > 6.5f) return null
    if (config.removeSmallRegions && density < 0.055f && area <= config.minPixelArea * 5 && aspectRatio > 3.5f) return null

    return normalized
}

private fun tightenRectRegion(
    region: CropRegion,
    image: BufferedImage,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): CropRegion? {
    val left = region.x.coerceIn(0, image.width)
    val top = region.y.coerceIn(0, image.height)
    val right = (region.x + region.width).coerceIn(0, image.width)
    val bottom = (region.y + region.height).coerceIn(0, image.height)
    if (right - left < 1 || bottom - top < 1) return null

    var minX = right
    var minY = bottom
    var maxX = left - 1
    var maxY = top - 1
    for (y in top until bottom) {
        for (x in left until right) {
            if (!isForegroundPixel(image.getRGB(x, y), config, backgroundArgb, mode)) continue
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
    }
    if (maxX < minX || maxY < minY) return null

    val pad = config.bboxPadding.coerceIn(0, 2)
    val refinedLeft = (minX - pad).coerceAtLeast(0)
    val refinedTop = (minY - pad).coerceAtLeast(0)
    val refinedRight = (maxX + 1 + pad).coerceAtMost(image.width)
    val refinedBottom = (maxY + 1 + pad).coerceAtMost(image.height)
    return region.copy(
        x = refinedLeft,
        y = refinedTop,
        width = (refinedRight - refinedLeft).coerceAtLeast(1),
        height = (refinedBottom - refinedTop).coerceAtLeast(1)
    )
}

private fun foregroundPixelCount(
    region: CropRegion,
    image: BufferedImage,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): Int {
    var total = 0
    val left = region.x.coerceIn(0, image.width)
    val top = region.y.coerceIn(0, image.height)
    val right = (region.x + region.width).coerceIn(0, image.width)
    val bottom = (region.y + region.height).coerceIn(0, image.height)
    for (y in top until bottom) {
        for (x in left until right) {
            if (isForegroundPixel(image.getRGB(x, y), config, backgroundArgb, mode)) {
                total += 1
            }
        }
    }
    return total
}

private fun isForegroundPixel(
    argb: Int,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): Boolean {
    val alpha = argb ushr 24 and 0xFF
    if (alpha <= config.alphaThreshold) return false
    if (mode == DetectionMode.ALPHA_MASK && alpha < 250) return true
    val bgAlpha = backgroundArgb ushr 24 and 0xFF
    if (bgAlpha <= config.alphaThreshold && alpha >= 250) {
        return true
    }
    val distance = weightedColorDistance(argb, backgroundArgb)
    return distance > max(12f, config.colorDistanceThreshold * 0.78f)
}

private fun weightedColorDistance(a: Int, b: Int): Float {
    val dr = ((a ushr 16 and 0xFF) - (b ushr 16 and 0xFF)).toFloat()
    val dg = ((a ushr 8 and 0xFF) - (b ushr 8 and 0xFF)).toFloat()
    val db = ((a and 0xFF) - (b and 0xFF)).toFloat()
    val da = ((a ushr 24 and 0xFF) - (b ushr 24 and 0xFF)).toFloat()
    return abs(dr) * 0.35f + abs(dg) * 0.50f + abs(db) * 0.15f + abs(da) * 0.45f
}

private fun dedupeRegions(regions: List<CropRegion>): List<CropRegion> {
    val ordered = regions.sortedWith(
        compareByDescending<CropRegion> { it.score ?: 0f }
            .thenByDescending { it.width * it.height }
    )
    val accepted = mutableListOf<CropRegion>()
    ordered.forEach { candidate ->
        if (accepted.any { similarRegion(it, candidate) }) return@forEach
        accepted += candidate
    }
    return accepted
}

private fun similarRegion(a: CropRegion, b: CropRegion): Boolean {
    if (abs(a.x - b.x) <= 2 && abs(a.y - b.y) <= 2 && abs(a.width - b.width) <= 3 && abs(a.height - b.height) <= 3) {
        return true
    }
    val left = max(a.x, b.x)
    val top = max(a.y, b.y)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return false
    val intersection = (right - left) * (bottom - top)
    val union = a.width * a.height + b.width * b.height - intersection
    return union > 0 && intersection.toFloat() / union.toFloat() >= 0.9f
}

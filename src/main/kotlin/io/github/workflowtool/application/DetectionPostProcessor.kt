package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.fromMaskBounds
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.model.maskAlphaAt
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

internal fun snapRegionToForeground(
    image: BufferedImage,
    region: CropRegion,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): CropRegion? {
    val clamped = CropRegion(
        id = region.id,
        x = region.x.coerceIn(0, image.width),
        y = region.y.coerceIn(0, image.height),
        width = region.right.coerceIn(0, image.width) - region.x.coerceIn(0, image.width),
        height = region.bottom.coerceIn(0, image.height) - region.y.coerceIn(0, image.height),
        visible = region.visible,
        selected = region.selected,
        score = region.score
    )
    if (clamped.width <= 0 || clamped.height <= 0) return null
    val mask = MutableList(clamped.width * clamped.height) { 0 }
    for (localY in 0 until clamped.height) {
        val y = clamped.y + localY
        for (localX in 0 until clamped.width) {
            val x = clamped.x + localX
            if (isForegroundPixel(image.getRGB(x, y), config, backgroundArgb, mode)) {
                mask[localY * clamped.width + localX] = 255
            }
        }
    }
    val smoothed = smoothAlphaMask(mask, clamped.width, clamped.height)
    return fromMaskBounds(
        x = clamped.x,
        y = clamped.y,
        width = clamped.width,
        height = clamped.height,
        mask = smoothed,
        imageWidth = image.width,
        imageHeight = image.height,
        id = region.id
    )?.copy(visible = region.visible, selected = region.selected, score = region.score)
}

internal fun snapRegionToForegroundWhole(
    image: BufferedImage,
    region: CropRegion,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): CropRegion? {
    val left = region.x.coerceIn(0, image.width)
    val top = region.y.coerceIn(0, image.height)
    val right = region.right.coerceIn(left, image.width)
    val bottom = region.bottom.coerceIn(top, image.height)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null

    val mask = MutableList(width * height) { 0 }
    for (localY in 0 until height) {
        val y = top + localY
        for (localX in 0 until width) {
            val x = left + localX
            val confirmedAlpha = if (region.hasMask()) region.maskAlphaAt(x, y) else 255
            val foregroundAlpha = if (isForegroundPixel(image.getRGB(x, y), config, backgroundArgb, mode)) 255 else 0
            mask[localY * width + localX] = max(confirmedAlpha, foregroundAlpha)
        }
    }

    return fromMaskBounds(
        x = left,
        y = top,
        width = width,
        height = height,
        mask = smoothAlphaMask(mask, width, height),
        imageWidth = image.width,
        imageHeight = image.height,
        id = region.id
    )?.copy(visible = region.visible, selected = region.selected, score = region.score)
}

internal fun mergeRegionsToOuterMask(
    imageWidth: Int,
    imageHeight: Int,
    regions: List<CropRegion>,
    id: String = regions.firstOrNull()?.id.orEmpty()
): CropRegion? {
    val visible = regions.filter { it.visible }
    if (visible.isEmpty()) return null
    val left = visible.minOf { it.x }.coerceIn(0, imageWidth)
    val top = visible.minOf { it.y }.coerceIn(0, imageHeight)
    val right = visible.maxOf { it.right }.coerceIn(left, imageWidth)
    val bottom = visible.maxOf { it.bottom }.coerceIn(top, imageHeight)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null
    val mask = MutableList(width * height) { 0 }
    visible.forEach { region ->
        val regionLeft = region.x.coerceIn(left, right)
        val regionTop = region.y.coerceIn(top, bottom)
        val regionRight = region.right.coerceIn(left, right)
        val regionBottom = region.bottom.coerceIn(top, bottom)
        for (y in regionTop until regionBottom) {
            for (x in regionLeft until regionRight) {
                val alpha = if (region.hasMask()) region.maskAlphaAt(x, y) else 255
                if (alpha > 0) {
                    val index = (y - top) * width + (x - left)
                    mask[index] = max(mask[index], alpha)
                }
            }
        }
    }
    return fromMaskBounds(left, top, width, height, smoothAlphaMask(mask, width, height), imageWidth, imageHeight, id)
        ?.copy(selected = true)
}

internal fun constrainedRefineUserRegion(
    image: BufferedImage,
    region: CropRegion,
    candidate: CropRegion?,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): CropRegion? {
    val fallbackCandidate = snapRegionToForegroundWhole(image, region, config, backgroundArgb, mode)
    val preferredCandidate = candidate?.let {
        normalizeManualRefineCandidate(it, image, config, backgroundArgb, mode)
    } ?: fallbackCandidate
    if (!hasLockedNegativeMask(region)) {
        return (preferredCandidate ?: fallbackCandidate)?.copy(
            id = region.id,
            visible = region.visible,
            selected = region.selected,
            score = region.score
        )
    }

    val overlapThreshold = (1.0 - config.manualRefineConflictTolerance).coerceIn(0.05, 0.95)
    val resolvedCandidate = when {
        preferredCandidate == null -> fallbackCandidate
        overlapRatio(region, preferredCandidate) >= overlapThreshold -> preferredCandidate
        else -> fallbackCandidate?.let {
            normalizeManualRefineCandidate(it, image, config, backgroundArgb, mode)
        }
    }

    return blendUserLockedMask(
        image = image,
        user = region,
        candidate = resolvedCandidate,
        expansionRadius = config.manualRefineExpansionRadius.coerceAtLeast(0)
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
    val smoothed = smoothAlphaMask(normalized.alphaMask, normalized.maskWidth, normalized.maskHeight)
    val refined = fromMaskBounds(
        x = normalized.x,
        y = normalized.y,
        width = normalized.maskWidth,
        height = normalized.maskHeight,
        mask = smoothed,
        imageWidth = image.width,
        imageHeight = image.height,
        id = normalized.id
    ) ?: normalized
    return refined.copy(
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
    return region.copy(x = left, y = top, width = width, height = height).withAlphaMask(
        smoothAlphaMask(alphaMask, width, height),
        width,
        height
    )
}

private fun normalizeManualRefineCandidate(
    candidate: CropRegion,
    image: BufferedImage,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): CropRegion? {
    return when {
        candidate.hasMask() -> refineMaskBackedRegion(candidate, image, config) ?: candidate
        else -> snapRegionToForeground(image, candidate, config, backgroundArgb, mode) ?: candidate
    }
}

private fun hasLockedNegativeMask(region: CropRegion): Boolean =
    region.hasMask() && region.alphaMask.any { it <= 0 }

private fun overlapRatio(user: CropRegion, candidate: CropRegion): Double {
    val left = min(user.x, candidate.x)
    val top = min(user.y, candidate.y)
    val right = max(user.right, candidate.right)
    val bottom = max(user.bottom, candidate.bottom)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return 0.0
    val userMask = buildBinaryMask(user, left, top, width, height)
    val candidateMask = buildBinaryMask(candidate, left, top, width, height)
    var userPixels = 0
    var overlapPixels = 0
    for (index in userMask.indices) {
        if (!userMask[index]) continue
        userPixels += 1
        if (candidateMask[index]) overlapPixels += 1
    }
    if (userPixels == 0) return 0.0
    return overlapPixels.toDouble() / userPixels.toDouble()
}

private fun blendUserLockedMask(
    image: BufferedImage,
    user: CropRegion,
    candidate: CropRegion?,
    expansionRadius: Int
): CropRegion? {
    val rightEdge = max(user.right, candidate?.right ?: user.right)
    val bottomEdge = max(user.bottom, candidate?.bottom ?: user.bottom)
    val left = min(user.x, candidate?.x ?: user.x).coerceIn(0, image.width)
    val top = min(user.y, candidate?.y ?: user.y).coerceIn(0, image.height)
    val right = rightEdge.coerceIn(left, image.width)
    val bottom = bottomEdge.coerceIn(top, image.height)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null

    val userMask = buildBinaryMask(user, left, top, width, height)
    val candidateMask = candidate?.let { buildBinaryMask(it, left, top, width, height) } ?: BooleanArray(width * height)
    val negativeMask = buildNegativeMask(user, left, top, width, height)
    val expansionMask = dilateMask(userMask, width, height, expansionRadius)
    val finalMask = BooleanArray(width * height)
    for (index in finalMask.indices) {
        finalMask[index] = userMask[index] || (candidateMask[index] && expansionMask[index] && !negativeMask[index])
    }

    return fromMaskBounds(
        x = left,
        y = top,
        width = width,
        height = height,
        mask = binaryMaskToAlpha(finalMask, width, height),
        imageWidth = image.width,
        imageHeight = image.height,
        id = user.id
    )?.copy(
        visible = user.visible,
        selected = user.selected,
        score = user.score
    )
}

private fun buildBinaryMask(region: CropRegion, originX: Int, originY: Int, width: Int, height: Int): BooleanArray {
    val mask = BooleanArray(width * height)
    val left = max(region.x, originX)
    val top = max(region.y, originY)
    val right = min(region.right, originX + width)
    val bottom = min(region.bottom, originY + height)
    for (y in top until bottom) {
        for (x in left until right) {
            val filled = if (region.hasMask()) region.maskAlphaAt(x, y) > 0 else true
            if (filled) {
                mask[(y - originY) * width + (x - originX)] = true
            }
        }
    }
    return mask
}

private fun buildNegativeMask(region: CropRegion, originX: Int, originY: Int, width: Int, height: Int): BooleanArray {
    val mask = BooleanArray(width * height)
    if (!region.hasMask()) return mask
    val left = max(region.x, originX)
    val top = max(region.y, originY)
    val right = min(region.right, originX + width)
    val bottom = min(region.bottom, originY + height)
    for (y in top until bottom) {
        for (x in left until right) {
            if (region.maskAlphaAt(x, y) <= 0) {
                mask[(y - originY) * width + (x - originX)] = true
            }
        }
    }
    return mask
}

private fun dilateMask(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
    if (radius <= 0) return mask.copyOf()
    val output = BooleanArray(mask.size)
    val radiusSquared = radius * radius
    for (y in 0 until height) {
        for (x in 0 until width) {
            var filled = false
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (dx * dx + dy * dy > radiusSquared) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    if (mask[ny * width + nx]) {
                        filled = true
                        break
                    }
                }
                if (filled) break
            }
            output[y * width + x] = filled
        }
    }
    return output
}

private fun binaryMaskToAlpha(mask: BooleanArray, width: Int, height: Int): List<Int> {
    val output = MutableList(width * height) { 0 }
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            if (!mask[index]) continue
            val edge = (-1..1).any { dy ->
                (-1..1).any { dx ->
                    val nx = x + dx
                    val ny = y + dy
                    nx !in 0 until width || ny !in 0 until height || !mask[ny * width + nx]
                }
            }
            output[index] = if (edge) 220 else 255
        }
    }
    return output
}

private fun smoothAlphaMask(mask: List<Int>, width: Int, height: Int): List<Int> {
    if (width < 6 || height < 6 || mask.size != width * height) return mask
    var binary = BooleanArray(width * height) { index -> mask[index] > 0 }
    binary = majorityFilter(binary, width, height, fillThreshold = 5)
    binary = majorityFilter(binary, width, height, fillThreshold = 4)
    val output = MutableList(width * height) { 0 }
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            if (!binary[index]) continue
            val edge = (-1..1).any { dy ->
                (-1..1).any { dx ->
                    val nx = x + dx
                    val ny = y + dy
                    nx !in 0 until width || ny !in 0 until height || !binary[ny * width + nx]
                }
            }
            output[index] = if (edge) 220 else 255
        }
    }
    return output
}

private fun majorityFilter(mask: BooleanArray, width: Int, height: Int, fillThreshold: Int): BooleanArray {
    val output = BooleanArray(mask.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var count = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until width && ny in 0 until height && mask[ny * width + nx]) count += 1
                }
            }
            output[y * width + x] = count >= fillThreshold
        }
    }
    return output
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

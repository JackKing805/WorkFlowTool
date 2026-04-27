package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.ArrayDeque

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
    val refinedOuter = dedupeRegions(
        result.regions.mapNotNull { region ->
            refineDetectedRegion(region, image, config, backgroundArgb, result.mode)
        }
    ).sortedWith(compareBy<CropRegion> { it.y }.thenBy { it.x })
    val refined = appendInteriorHoleRegions(refinedOuter, image, config, backgroundArgb, result.mode)

    return result.copy(
        regions = refined,
        stats = result.stats.copy(
            connectedComponents = refined.size,
            regionCount = refined.size
        )
    )
}

private fun appendInteriorHoleRegions(
    regions: List<CropRegion>,
    image: BufferedImage,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): List<CropRegion> {
    if (regions.isEmpty()) return regions
    val output = mutableListOf<CropRegion>()
    var nextHoleIndex = 1
    regions.forEach { outer ->
        output += outer
        detectInteriorHoles(outer, image, config, backgroundArgb, mode).forEach { hole ->
            output += hole.copy(id = "${outer.id}_hole_${nextHoleIndex++}")
        }
    }
    return output
}

private fun detectInteriorHoles(
    outer: CropRegion,
    image: BufferedImage,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): List<CropRegion> {
    val left = outer.x.coerceIn(0, image.width)
    val top = outer.y.coerceIn(0, image.height)
    val right = outer.right.coerceIn(left, image.width)
    val bottom = outer.bottom.coerceIn(top, image.height)
    val width = right - left
    val height = bottom - top
    if (width < 8 || height < 8) return emptyList()

    val candidate = BooleanArray(width * height)
    for (localY in 0 until height) {
        val y = top + localY
        for (localX in 0 until width) {
            val x = left + localX
            if (!pointInsideRegion(outer, x + 0.5f, y + 0.5f)) continue
            val argb = image.getRGB(x, y)
            val alpha = argb ushr 24 and 0xFF
            if (alpha <= config.alphaThreshold || !isForegroundPixel(argb, config, backgroundArgb, mode)) {
                candidate[localY * width + localX] = true
            }
        }
    }

    val visited = BooleanArray(candidate.size)
    val holes = mutableListOf<CropRegion>()
    for (start in candidate.indices) {
        if (!candidate[start] || visited[start]) continue
        val component = collectBackgroundComponent(start, candidate, visited, width, height)
        if (component.touchesBounds) continue
        val componentWidth = component.maxX - component.minX + 1
        val componentHeight = component.maxY - component.minY + 1
        if (component.pixelCount < max(6, config.minPixelArea / 8)) continue
        if (componentWidth < 3 || componentHeight < 3) continue
        if (!isMeaningfulHole(component, width, height, outer)) continue
        val x = left + component.minX
        val y = top + component.minY
        holes += CropRegion(
            id = "",
            x = x,
            y = y,
            width = componentWidth,
            height = componentHeight,
            points = listOf(
                io.github.workflowtool.model.RegionPoint(x, y),
                io.github.workflowtool.model.RegionPoint(x + componentWidth, y),
                io.github.workflowtool.model.RegionPoint(x + componentWidth, y + componentHeight),
                io.github.workflowtool.model.RegionPoint(x, y + componentHeight)
            )
        )
    }
    return holes
}

private data class HoleComponent(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val pixelCount: Int,
    val touchesBounds: Boolean
)

private fun collectBackgroundComponent(
    start: Int,
    candidate: BooleanArray,
    visited: BooleanArray,
    width: Int,
    height: Int
): HoleComponent {
    val queue = ArrayDeque<Int>()
    queue.add(start)
    visited[start] = true
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    var count = 0
    var touchesBounds = false
    while (!queue.isEmpty()) {
        val index = queue.removeFirst()
        val x = index % width
        val y = index / width
        minX = min(minX, x)
        minY = min(minY, y)
        maxX = max(maxX, x)
        maxY = max(maxY, y)
        count += 1
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) touchesBounds = true
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (!candidate[next] || visited[next]) continue
                visited[next] = true
                queue.add(next)
            }
        }
    }
    return HoleComponent(minX, minY, maxX, maxY, count, touchesBounds)
}

private fun isMeaningfulHole(component: HoleComponent, localWidth: Int, localHeight: Int, outer: CropRegion): Boolean {
    val componentWidth = component.maxX - component.minX + 1
    val componentHeight = component.maxY - component.minY + 1
    val outerArea = outer.width * outer.height
    val componentArea = componentWidth * componentHeight
    if (componentArea >= outerArea * 0.72f) return false
    val marginX = min(component.minX, localWidth - 1 - component.maxX)
    val marginY = min(component.minY, localHeight - 1 - component.maxY)
    return marginX >= 2 && marginY >= 2
}

private fun pointInsideRegion(region: CropRegion, x: Float, y: Float): Boolean {
    val points = region.editPoints
    if (points.size < 3) {
        return x >= region.x && x <= region.right && y >= region.y && y <= region.bottom
    }
    var inside = false
    var previous = points.last()
    for (current in points) {
        val crosses = (current.y > y) != (previous.y > y)
        if (crosses) {
            val intersectionX = (previous.x - current.x) * (y - current.y) / (previous.y - current.y).toFloat() + current.x
            if (x < intersectionX) inside = !inside
        }
        previous = current
    }
    return inside
}

private fun refineDetectedRegion(
    region: CropRegion,
    image: BufferedImage,
    config: DetectionConfig,
    backgroundArgb: Int,
    mode: DetectionMode
): CropRegion? {
    val normalized = if (region.points.isNotEmpty()) {
        normalizePolygonRegion(region, image.width, image.height)
    } else {
        tightenRectRegion(region, image, config, backgroundArgb, mode)
    } ?: return null

    val area = normalized.width * normalized.height
    if (area <= 0) return null
    val foregroundPixels = foregroundPixelCount(normalized, image, config, backgroundArgb, mode)
    val shortestSide = min(normalized.width, normalized.height).coerceAtLeast(1)
    val longestSide = max(normalized.width, normalized.height)
    val aspectRatio = longestSide.toFloat() / shortestSide.toFloat()
    val density = foregroundPixels.toFloat() / area.toFloat()
    val minimumForeground = max(4, config.minPixelArea / 3)

    if (foregroundPixels < minimumForeground) return null
    if (shortestSide <= 3 && aspectRatio > 5.5f && density < 0.42f) return null
    if (density < 0.032f && aspectRatio > 6.5f) return null
    if (density < 0.055f && area <= config.minPixelArea * 5 && aspectRatio > 3.5f) return null

    return normalized
}

private fun normalizePolygonRegion(region: CropRegion, imageWidth: Int, imageHeight: Int): CropRegion? {
    val normalizedPoints = region.editPoints
        .map { point ->
            io.github.workflowtool.model.RegionPoint(
                point.x.coerceIn(0, imageWidth),
                point.y.coerceIn(0, imageHeight)
            )
        }
        .fold(mutableListOf<io.github.workflowtool.model.RegionPoint>()) { output, point ->
            if (output.lastOrNull() != point) output += point
            output
        }
        .let { points ->
            if (points.size > 1 && points.first() == points.last()) points.dropLast(1) else points
        }
    if (normalizedPoints.size < 3) return null
    val minX = normalizedPoints.minOf { it.x }
    val minY = normalizedPoints.minOf { it.y }
    val maxX = normalizedPoints.maxOf { it.x }
    val maxY = normalizedPoints.maxOf { it.y }
    return region.copy(
        x = minX,
        y = minY,
        width = (maxX - minX).coerceAtLeast(1),
        height = (maxY - minY).coerceAtLeast(1),
        points = normalizedPoints
    )
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
        height = (refinedBottom - refinedTop).coerceAtLeast(1),
        points = emptyList()
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
        compareByDescending<CropRegion> { it.points.isNotEmpty() }
            .thenBy { it.width * it.height }
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

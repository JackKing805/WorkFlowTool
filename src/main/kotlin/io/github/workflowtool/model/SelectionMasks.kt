package io.github.workflowtool.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun CropRegion.hasMask(): Boolean =
    maskWidth > 0 && maskHeight > 0 && alphaMask.size == maskWidth * maskHeight

fun CropRegion.maskAlphaAt(imageX: Int, imageY: Int): Int {
    if (!hasMask()) return 0
    val localX = imageX - x
    val localY = imageY - y
    if (localX !in 0 until maskWidth || localY !in 0 until maskHeight) return 0
    return alphaMask[localY * maskWidth + localX].coerceIn(0, 255)
}

fun CropRegion.withAlphaMask(mask: List<Int>, maskWidth: Int, maskHeight: Int): CropRegion {
    val sanitized = if (mask.size == maskWidth * maskHeight) mask.map { it.coerceIn(0, 255) } else emptyList()
    return copy(
        maskWidth = if (sanitized.isEmpty()) 0 else maskWidth,
        maskHeight = if (sanitized.isEmpty()) 0 else maskHeight,
        alphaMask = sanitized
    )
}

fun CropRegion.applyBrushToMask(
    centerX: Int,
    centerY: Int,
    radius: Int,
    mode: MaskEditMode,
    imageWidth: Int,
    imageHeight: Int
): CropRegion {
    val brushRadius = radius.coerceAtLeast(1)
    val targetLeft = (centerX - brushRadius).coerceIn(0, imageWidth)
    val targetTop = (centerY - brushRadius).coerceIn(0, imageHeight)
    val targetRight = (centerX + brushRadius + 1).coerceIn(targetLeft, imageWidth)
    val targetBottom = (centerY + brushRadius + 1).coerceIn(targetTop, imageHeight)
    val existingMask = hasMask()
    val preserveExistingShape = mode != MaskEditMode.Replace
    val nextLeft = if (preserveExistingShape) min(x, targetLeft) else targetLeft
    val nextTop = if (preserveExistingShape) min(y, targetTop) else targetTop
    val nextRight = if (preserveExistingShape) max(right, targetRight) else targetRight
    val nextBottom = if (preserveExistingShape) max(bottom, targetBottom) else targetBottom
    val nextWidth = (nextRight - nextLeft).coerceAtLeast(1)
    val nextHeight = (nextBottom - nextTop).coerceAtLeast(1)
    val nextMask = MutableList(nextWidth * nextHeight) { 0 }

    if (preserveExistingShape) {
        for (py in y until bottom) {
            for (px in x until right) {
                val alpha = if (existingMask) maskAlphaAt(px, py) else 255
                if (alpha > 0) nextMask[(py - nextTop) * nextWidth + (px - nextLeft)] = alpha
            }
        }
    }

    val radiusSquared = brushRadius * brushRadius
    val featherStart = (brushRadius * 0.72f).roundToInt().coerceAtLeast(1)
    for (py in targetTop until targetBottom) {
        for (px in targetLeft until targetRight) {
            val dx = px - centerX
            val dy = py - centerY
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared > radiusSquared) continue
            val distance = kotlin.math.sqrt(distanceSquared.toFloat())
            val alpha = if (distance <= featherStart) {
                255
            } else {
                val ratio = 1f - ((distance - featherStart) / (brushRadius - featherStart).coerceAtLeast(1))
                (255f * ratio.coerceIn(0f, 1f)).roundToInt()
            }
            val index = (py - nextTop) * nextWidth + (px - nextLeft)
            nextMask[index] = when (mode) {
                MaskEditMode.Replace, MaskEditMode.Add -> max(nextMask[index], alpha)
                MaskEditMode.Subtract -> min(nextMask[index], 255 - alpha)
            }
        }
    }

    return fromMaskBounds(nextLeft, nextTop, nextWidth, nextHeight, nextMask, imageWidth, imageHeight)
        ?.copy(id = id, visible = visible, selected = selected, score = score)
        ?: copy(maskWidth = 0, maskHeight = 0, alphaMask = emptyList(), width = 1, height = 1)
}

fun fromMaskBounds(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    mask: List<Int>,
    imageWidth: Int,
    imageHeight: Int,
    id: String = ""
): CropRegion? {
    if (width <= 0 || height <= 0 || mask.size != width * height) return null
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    for (localY in 0 until height) {
        for (localX in 0 until width) {
            if (mask[localY * width + localX] <= 0) continue
            minX = min(minX, localX)
            minY = min(minY, localY)
            maxX = max(maxX, localX)
            maxY = max(maxY, localY)
        }
    }
    if (maxX < minX || maxY < minY) return null
    val nextX = (x + minX).coerceIn(0, imageWidth)
    val nextY = (y + minY).coerceIn(0, imageHeight)
    val nextWidth = (maxX - minX + 1).coerceAtLeast(1).coerceAtMost(imageWidth - nextX)
    val nextHeight = (maxY - minY + 1).coerceAtLeast(1).coerceAtMost(imageHeight - nextY)
    val cropped = MutableList(nextWidth * nextHeight) { 0 }
    for (localY in 0 until nextHeight) {
        for (localX in 0 until nextWidth) {
            cropped[localY * nextWidth + localX] = mask[(localY + minY) * width + localX + minX].coerceIn(0, 255)
        }
    }
    return CropRegion(
        id = id,
        x = nextX,
        y = nextY,
        width = nextWidth,
        height = nextHeight,
        maskWidth = nextWidth,
        maskHeight = nextHeight,
        alphaMask = cropped
    )
}

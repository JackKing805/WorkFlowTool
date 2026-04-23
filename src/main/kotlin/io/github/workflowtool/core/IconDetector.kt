package io.github.workflowtool.core

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class IconDetector {
    fun detect(image: BufferedImage, config: DetectionConfig): List<CropRegion> {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return emptyList()

        val background = estimateBackground(image)
        val visited = BooleanArray(width * height)
        val regions = mutableListOf<CropRegion>()
        val queue = ArrayDeque<Int>()

        fun index(x: Int, y: Int) = y * width + x

        for (y in 0 until height) {
            for (x in 0 until width) {
                val start = index(x, y)
                if (visited[start] || !isForeground(image.getRGB(x, y), background, config)) {
                    visited[start] = true
                    continue
                }

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var pixels = 0
                visited[start] = true
                queue.add(start)

                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    val cx = current % width
                    val cy = current / width
                    pixels++
                    minX = min(minX, cx)
                    maxX = max(maxX, cx)
                    minY = min(minY, cy)
                    maxY = max(maxY, cy)

                    for (ny in cy - 1..cy + 1) {
                        for (nx in cx - 1..cx + 1) {
                            if (nx !in 0 until width || ny !in 0 until height) continue
                            val next = index(nx, ny)
                            if (visited[next]) continue
                            visited[next] = true
                            if (isForeground(image.getRGB(nx, ny), background, config)) {
                                queue.add(next)
                            }
                        }
                    }
                }

                val regionWidth = maxX - minX + 1
                val regionHeight = maxY - minY + 1
                val keep = !config.removeSmallRegions ||
                    (regionWidth >= config.minWidth && regionHeight >= config.minHeight && pixels >= config.minWidth)
                if (keep) {
                    regions += CropRegion(
                        id = UUID.randomUUID().toString(),
                        x = minX,
                        y = minY,
                        width = regionWidth,
                        height = regionHeight
                    )
                }
            }
        }

        val merged = if (config.mergeNearbyRegions) mergeNearby(regions, config.gapThreshold) else regions
        return merged.sortedWith(compareBy<CropRegion> { it.y }.thenBy { it.x })
            .mapIndexed { index, region -> region.copy(id = (index + 1).toString()) }
    }

    private fun estimateBackground(image: BufferedImage): Color {
        val samples = listOf(
            image.getRGB(0, 0),
            image.getRGB(image.width - 1, 0),
            image.getRGB(0, image.height - 1),
            image.getRGB(image.width - 1, image.height - 1)
        )
        val grouped = samples.groupingBy { it }.eachCount()
        return Color(grouped.maxBy { it.value }.key, true)
    }

    private fun isForeground(argb: Int, background: Color, config: DetectionConfig): Boolean {
        val color = Color(argb, true)
        if (color.alpha <= config.alphaThreshold) return false
        if (background.alpha <= config.alphaThreshold) return true
        val diff = abs(color.red - background.red) + abs(color.green - background.green) + abs(color.blue - background.blue)
        return diff > config.backgroundTolerance * 3 || abs(color.alpha - background.alpha) > config.alphaThreshold
    }

    private fun mergeNearby(input: List<CropRegion>, gap: Int): List<CropRegion> {
        val regions = input.toMutableList()
        var changed: Boolean
        do {
            changed = false
            loop@ for (i in regions.indices) {
                for (j in i + 1 until regions.size) {
                    val a = regions[i]
                    val b = regions[j]
                    if (touches(a, b, gap)) {
                        regions[i] = union(a, b)
                        regions.removeAt(j)
                        changed = true
                        break@loop
                    }
                }
            }
        } while (changed)
        return regions
    }

    private fun touches(a: CropRegion, b: CropRegion, gap: Int): Boolean {
        return a.x - gap <= b.right &&
            a.right + gap >= b.x &&
            a.y - gap <= b.bottom &&
            a.bottom + gap >= b.y
    }

    private fun union(a: CropRegion, b: CropRegion): CropRegion {
        val x = min(a.x, b.x)
        val y = min(a.y, b.y)
        val right = max(a.right, b.right)
        val bottom = max(a.bottom, b.bottom)
        return a.copy(x = x, y = y, width = right - x, height = bottom - y)
    }
}


package io.github.workflowtool.core

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.ExportResult
import io.github.workflowtool.model.ImageFormat
import io.github.workflowtool.model.NamingMode
import io.github.workflowtool.model.hasMask
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.ArrayDeque

class IconExporter {
    fun export(
        image: BufferedImage,
        sourceFileName: String,
        regions: List<CropRegion>,
        config: ExportConfig
    ): ExportResult {
        Files.createDirectories(config.outputDirectory)
        val failures = mutableListOf<String>()
        var success = 0

        regions.filter { it.visible }.forEachIndexed { index, region ->
            try {
                val cropped = cropPreview(image, region)
                val processed = process(cropped, config)
                val output = nextOutputPath(sourceFileName, index + 1, config)
                if (output.exists() && !config.overwriteExisting) {
                    failures += "${output.fileName} already exists"
                    return@forEachIndexed
                }
                val written = writeImage(processed, output, config.outputFormat)
                if (!written) {
                    failures += "No writer available for ${config.outputFormat.extension}: ${output.fileName}"
                } else {
                    success++
                }
            } catch (error: Exception) {
                failures += "Region ${region.id}: ${error.message ?: error::class.simpleName}"
            }
        }

        return ExportResult(successCount = success, failureCount = failures.size, failures = failures)
    }

    fun exportSingle(
        image: BufferedImage,
        region: CropRegion,
        allRegions: List<CropRegion>,
        config: ExportConfig,
        output: java.nio.file.Path
    ): Boolean {
        output.parent?.let(Files::createDirectories)
        return writeImage(process(cropPreview(image, region, allRegions), config), output, config.outputFormat)
    }

    fun cropPreview(image: BufferedImage, region: CropRegion, allRegions: List<CropRegion> = listOf(region)): BufferedImage {
        val x = region.x.coerceIn(0, image.width - 1)
        val y = region.y.coerceIn(0, image.height - 1)
        val width = region.width.coerceAtMost(image.width - x).coerceAtLeast(1)
        val height = region.height.coerceAtMost(image.height - y).coerceAtLeast(1)
        if (region.hasMask()) {
            return cropPreviewWithMask(image, region, x, y, width, height)
        }
        return image.getSubimage(x, y, width, height)
    }

    private fun cropPreviewWithMask(
        image: BufferedImage,
        region: CropRegion,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): BufferedImage {
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (localY in 0 until height) {
            for (localX in 0 until width) {
                val sourceX = x + localX
                val sourceY = y + localY
                val maskX = sourceX - region.x
                val maskY = sourceY - region.y
                val maskAlpha = if (maskX in 0 until region.maskWidth && maskY in 0 until region.maskHeight) {
                    region.alphaMask[maskY * region.maskWidth + maskX].coerceIn(0, 255)
                } else {
                    0
                }
                if (maskAlpha <= 0) {
                    output.setRGB(localX, localY, 0)
                    continue
                }
                val argb = image.getRGB(sourceX, sourceY)
                val sourceAlpha = argb ushr 24 and 0xFF
                val alpha = (sourceAlpha * (maskAlpha / 255f)).roundToInt().coerceIn(0, 255)
                output.setRGB(localX, localY, (alpha shl 24) or (argb and 0x00FFFFFF))
            }
        }
        return output
    }

    private fun process(input: BufferedImage, config: ExportConfig): BufferedImage {
        var image = if (config.removeBackgroundToTransparent) removeBackground(input, config.backgroundArgb, config.backgroundTolerance) else copy(input)
        if (config.trimTransparentPadding) image = trimTransparent(image)
        if (config.padToSquare) image = padToSquare(image)
        config.fixedSize?.takeIf { it > 0 && !config.keepOriginalSize }?.let { size ->
            image = resize(image, size, size)
        }
        return image
    }

    private fun copy(input: BufferedImage): BufferedImage {
        val output = BufferedImage(input.width, input.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.drawImage(input, 0, 0, null)
        graphics.dispose()
        return output
    }

    private fun removeBackground(input: BufferedImage, backgroundArgb: Int, tolerance: Int): BufferedImage {
        val output = BufferedImage(input.width, input.height, BufferedImage.TYPE_INT_ARGB)
        val toleranceScore = tolerance.coerceIn(0, 255)
        val softness = max(10, (toleranceScore * 0.8f).roundToInt())
        val connectedBackground = edgeConnectedBackground(input, backgroundArgb, toleranceScore)
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                val argb = input.getRGB(x, y)
                val sourceAlpha = argb ushr 24 and 0xFF
                if (sourceAlpha == 0) {
                    output.setRGB(x, y, 0)
                    continue
                }
                val distance = colorDistance(argb, backgroundArgb)
                val index = y * input.width + x
                if (connectedBackground[index] && distance <= toleranceScore) {
                    output.setRGB(x, y, 0)
                    continue
                }
                val shouldFeather = distance < toleranceScore + softness && touchesConnectedBackground(connectedBackground, input.width, input.height, x, y)
                val featheredAlpha = if (!shouldFeather) {
                    sourceAlpha
                } else {
                    val ratio = (distance - toleranceScore).toFloat() / softness.toFloat()
                    (sourceAlpha * ratio.coerceIn(0f, 1f)).roundToInt().coerceIn(0, sourceAlpha)
                }
                output.setRGB(x, y, (featheredAlpha shl 24) or (argb and 0x00FFFFFF))
            }
        }
        return output
    }

    private fun edgeConnectedBackground(input: BufferedImage, backgroundArgb: Int, toleranceScore: Int): BooleanArray {
        val width = input.width
        val height = input.height
        val visited = BooleanArray(width * height)
        val queue = ArrayDeque<Int>()

        fun enqueue(x: Int, y: Int) {
            if (x !in 0 until width || y !in 0 until height) return
            val index = y * width + x
            if (visited[index]) return
            val argb = input.getRGB(x, y)
            val alpha = argb ushr 24 and 0xFF
            if (alpha == 0 || colorDistance(argb, backgroundArgb) <= toleranceScore) {
                visited[index] = true
                queue.add(index)
            }
        }

        for (x in 0 until width) {
            enqueue(x, 0)
            enqueue(x, height - 1)
        }
        for (y in 0 until height) {
            enqueue(0, y)
            enqueue(width - 1, y)
        }

        while (!queue.isEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            enqueue(x - 1, y)
            enqueue(x + 1, y)
            enqueue(x, y - 1)
            enqueue(x, y + 1)
        }

        return visited
    }

    private fun touchesConnectedBackground(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until height && mask[ny * width + nx]) return true
            }
        }
        return false
    }

    private fun colorDistance(argb: Int, backgroundArgb: Int): Int {
        val r = ((argb shr 16) and 255) - ((backgroundArgb shr 16) and 255)
        val g = ((argb shr 8) and 255) - ((backgroundArgb shr 8) and 255)
        val b = (argb and 255) - (backgroundArgb and 255)
        val a = ((argb ushr 24) and 255) - ((backgroundArgb ushr 24) and 255)
        return (kotlin.math.abs(r) * 0.35f + kotlin.math.abs(g) * 0.50f + kotlin.math.abs(b) * 0.15f + kotlin.math.abs(a) * 0.25f).roundToInt()
    }

    private fun trimTransparent(input: BufferedImage): BufferedImage {
        var minX = input.width
        var minY = input.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                val alpha = input.getRGB(x, y) ushr 24
                if (alpha > 0) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        if (maxX < minX || maxY < minY) return copy(input)
        return copy(input.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1))
    }

    private fun padToSquare(input: BufferedImage): BufferedImage {
        val size = max(input.width, input.height)
        val output = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.drawImage(input, (size - input.width) / 2, (size - input.height) / 2, null)
        graphics.dispose()
        return output
    }

    private fun resize(input: BufferedImage, width: Int, height: Int): BufferedImage {
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(input, 0, 0, width, height, null)
        graphics.dispose()
        return output
    }

    private fun prepareForFormat(input: BufferedImage, format: ImageFormat): BufferedImage {
        if (format == ImageFormat.PNG || format == ImageFormat.WEBP) return input
        val output = BufferedImage(input.width, input.height, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, output.width, output.height)
        graphics.drawImage(input, 0, 0, null)
        graphics.dispose()
        return output
    }

    private fun nextOutputPath(sourceFileName: String, sequence: Int, config: ExportConfig) =
        config.outputDirectory.resolve("${baseName(sourceFileName, sequence, config)}.${config.outputFormat.extension}")

    private fun writeImage(image: BufferedImage, output: java.nio.file.Path, format: ImageFormat): Boolean {
        val writable = prepareForFormat(image, format)
        return ImageIO.write(writable, format.imageIoName, output.toFile())
    }

    private fun baseName(sourceFileName: String, sequence: Int, config: ExportConfig): String {
        val padded = sequence.toString().padStart(3, '0')
        return when (config.namingMode) {
            NamingMode.Sequence -> padded
            NamingMode.SourceNameSequence -> "${sourceFileName.substringBeforeLast('.').ifBlank { sourceFileName }}_$padded"
            NamingMode.CustomPrefixSequence -> "${config.customPrefix.ifBlank { "icon" }}_$padded"
        }.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }
}

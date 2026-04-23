package io.github.workflowtool.core

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.ExportResult
import io.github.workflowtool.model.ImageFormat
import io.github.workflowtool.model.NamingMode
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.math.max

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
                val cropped = crop(image, region)
                val processed = process(cropped, config)
                val output = nextOutputPath(sourceFileName, index + 1, config)
                if (output.exists() && !config.overwriteExisting) {
                    failures += "${output.fileName} already exists"
                    return@forEachIndexed
                }
                val writable = prepareForFormat(processed, config.outputFormat)
                val written = ImageIO.write(writable, config.outputFormat.imageIoName, output.toFile())
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

    private fun crop(image: BufferedImage, region: CropRegion): BufferedImage {
        val x = region.x.coerceIn(0, image.width - 1)
        val y = region.y.coerceIn(0, image.height - 1)
        val width = region.width.coerceAtMost(image.width - x).coerceAtLeast(1)
        val height = region.height.coerceAtMost(image.height - y).coerceAtLeast(1)
        return image.getSubimage(x, y, width, height)
    }

    private fun process(input: BufferedImage, config: ExportConfig): BufferedImage {
        var image = if (config.trimTransparentPadding) trimTransparent(input) else copy(input)
        if (config.padToSquare) image = padToSquare(image)
        config.fixedSize?.takeIf { it > 0 }?.let { size ->
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

    private fun baseName(sourceFileName: String, sequence: Int, config: ExportConfig): String {
        val padded = sequence.toString().padStart(3, '0')
        return when (config.namingMode) {
            NamingMode.Sequence -> padded
            NamingMode.SourceNameSequence -> "${sourceFileName.substringBeforeLast('.').ifBlank { sourceFileName }}_$padded"
            NamingMode.CustomPrefixSequence -> "${config.customPrefix.ifBlank { "icon" }}_$padded"
        }.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }
}


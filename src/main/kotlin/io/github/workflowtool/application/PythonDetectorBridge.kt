package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.RegionPoint
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.exists

internal object PythonDetectorBridge {
    private val script get() = AppRuntimeFiles.pythonDir.resolve("detect_icons.py")
    val isAvailable: Boolean get() = script.exists()
    val status: String get() = if (isAvailable) "python script: $script" else "python detector missing ($script)"

    fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult? {
        if (!isAvailable) return null
        val temp = Files.createTempFile("workflowtool-python-detect", ".png")
        return try {
            ImageIO.write(image, "png", temp.toFile())
            val command = mutableListOf(
                "python",
                script.toString(),
                "--image",
                temp.toString(),
                "--min-width",
                config.minWidth.toString(),
                "--min-height",
                config.minHeight.toString(),
                "--min-pixel-area",
                config.minPixelArea.toString(),
                "--alpha-threshold",
                config.alphaThreshold.toString(),
                "--color-distance-threshold",
                config.colorDistanceThreshold.toString(),
                "--edge-sample-width",
                config.edgeSampleWidth.toString(),
                "--bbox-padding",
                config.bboxPadding.toString(),
                "--gap-threshold",
                config.gapThreshold.toString()
            )
            if (config.mergeNearbyRegions) command += "--merge-nearby-regions"
            val process = ProcessBuilder(command)
                .directory(AppRuntimeFiles.pythonDir.parent.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (process.waitFor() != 0) return null
            parseDetectionResult(output)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    private fun parseDetectionResult(json: String): DetectionResult? {
        val regionsBlock = extractArrayBlock(json, "regions") ?: return null
        val regions = extractRegionObjects(regionsBlock).mapIndexedNotNull { index, block ->
            val x = intField(block, "x", Int.MIN_VALUE)
            val y = intField(block, "y", Int.MIN_VALUE)
            val width = intField(block, "width", 0)
            val height = intField(block, "height", 0)
            if (x == Int.MIN_VALUE || y == Int.MIN_VALUE || width <= 0 || height <= 0) return@mapIndexedNotNull null
            CropRegion(
                id = (index + 1).toString(),
                x = x,
                y = y,
                width = width,
                height = height,
                points = parsePoints(block)
            )
        }
        return DetectionResult(
            regions = regions,
            mode = DetectionMode.SOLID_BACKGROUND,
            stats = DetectionStats(
                estimatedBackgroundArgb = intField(json, "estimatedBackgroundArgb"),
                candidatePixels = intField(json, "candidatePixels"),
                connectedComponents = intField(json, "connectedComponents", regions.size),
                regionCount = intField(json, "regionCount", regions.size),
                backgroundSampleCount = intField(json, "backgroundSampleCount"),
                totalTimeMs = intField(json, "totalTimeMs").toLong()
            )
        )
    }

    private fun intField(json: String, name: String, default: Int = 0): Int =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: default

    private fun extractRegionObjects(block: String): List<String> {
        val output = mutableListOf<String>()
        var depth = 0
        var start = -1
        block.forEachIndexed { index, char ->
            when (char) {
                '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }
                '}' -> {
                    depth -= 1
                    if (depth == 0 && start >= 0) {
                        output += block.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return output
    }

    private fun extractArrayBlock(json: String, field: String): String? {
        val fieldIndex = json.indexOf(""""$field"""")
        if (fieldIndex < 0) return null
        val arrayStart = json.indexOf('[', fieldIndex)
        if (arrayStart < 0) return null
        var depth = 0
        for (index in arrayStart until json.length) {
            when (json[index]) {
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) {
                        return json.substring(arrayStart + 1, index)
                    }
                }
            }
        }
        return null
    }

    private fun parsePoints(block: String): List<RegionPoint> {
        val pointsBlock = Regex(""""points"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(block)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()
        return Regex("""\{\s*"x"\s*:\s*(-?\d+)\s*,\s*"y"\s*:\s*(-?\d+)\s*}""")
            .findAll(pointsBlock)
            .map { RegionPoint(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            .toList()
    }
}

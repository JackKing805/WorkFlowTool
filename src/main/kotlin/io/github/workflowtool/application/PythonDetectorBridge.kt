package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
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
        val regionsBlock = Regex(""""regions"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)
            ?.groupValues
            ?.get(1)
            ?: return null
        val regions = Regex("""\{[^{}]*"x"\s*:\s*(-?\d+)[^{}]*"y"\s*:\s*(-?\d+)[^{}]*"width"\s*:\s*(\d+)[^{}]*"height"\s*:\s*(\d+)[^{}]*}""")
            .findAll(regionsBlock)
            .mapIndexed { index, match ->
                CropRegion(
                    id = (index + 1).toString(),
                    x = match.groupValues[1].toInt(),
                    y = match.groupValues[2].toInt(),
                    width = match.groupValues[3].toInt(),
                    height = match.groupValues[4].toInt()
                )
            }
            .toList()
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
}

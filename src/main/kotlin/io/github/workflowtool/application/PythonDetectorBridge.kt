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
    private data class Availability(val available: Boolean, val status: String)

    private val availability: Availability by lazy {
        val scriptPath = script
        if (!scriptPath.exists()) {
            return@lazy Availability(false, "python detector missing ($scriptPath)")
        }
        if (!PythonRuntime.isAvailable) {
            return@lazy Availability(false, PythonRuntime.status(scriptAvailable = true, script = scriptPath))
        }
        val command = PythonRuntime.buildCommand(listOf(scriptPath.toString(), "--help"))
            ?: return@lazy Availability(false, PythonRuntime.status(scriptAvailable = true, script = scriptPath))
        runCatching {
            val process = PythonRuntime.configureProcess(
                ProcessBuilder(command)
                .directory(AppRuntimeFiles.pythonDir.parent.toFile())
                .redirectErrorStream(true)
            ).start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            if (process.waitFor() == 0) {
                Availability(true, PythonRuntime.status(scriptAvailable = true, script = scriptPath))
            } else {
                val detail = output.lineSequence().firstOrNull { it.isNotBlank() } ?: "probe failed"
                Availability(false, "python detector unavailable ($detail); ${PythonRuntime.status(scriptAvailable = true, script = scriptPath)}")
            }
        }.getOrElse {
            Availability(
                false,
                "python detector unavailable (${it.message ?: it::class.simpleName}); ${PythonRuntime.status(scriptAvailable = true, script = scriptPath)}"
            )
        }
    }

    val isAvailable: Boolean get() = availability.available
    val status: String get() = availability.status

    fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult? {
        if (!isAvailable) return null
        val temp = Files.createTempFile("workflowtool-python-detect", ".png")
        return try {
            ImageIO.write(image, "png", temp.toFile())
            val command = PythonRuntime.buildCommand(
                listOf(
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
                    "--background-tolerance",
                    config.backgroundTolerance.toString(),
                    "--color-distance-threshold",
                    config.colorDistanceThreshold.toString(),
                    "--edge-sample-width",
                    config.edgeSampleWidth.toString(),
                    "--dilate-iterations",
                    config.dilateIterations.toString(),
                    "--erode-iterations",
                    config.erodeIterations.toString(),
                    "--bbox-padding",
                    config.bboxPadding.toString(),
                    "--gap-threshold",
                    config.gapThreshold.toString(),
                    "--manual-background-argb",
                    config.manualBackgroundArgb.toString()
                )
            )?.toMutableList() ?: return null
            if (config.enableHoleFill) command += "--enable-hole-fill"
            if (config.mergeNearbyRegions) command += "--merge-nearby-regions"
            if (config.removeSmallRegions) command += "--remove-small-regions"
            if (config.useManualBackground) command += "--use-manual-background"
            val process = PythonRuntime.configureProcess(
                ProcessBuilder(command)
                .directory(AppRuntimeFiles.pythonDir.parent.toFile())
                .redirectErrorStream(true)
            ).start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (process.waitFor() != 0) return null
            parseDetectionResult(output)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    internal fun parseDetectionResult(json: String): DetectionResult? {
        val root = parseJsonObject(json) ?: return null
        val regions = root["regions"]
            ?.asArray()
            ?.values
            .orEmpty()
            .mapIndexedNotNull { index, value ->
                val region = value.asObject() ?: return@mapIndexedNotNull null
                val bbox = region["bbox"]?.asObject()
                val x = bbox?.get("x")?.asInt() ?: region["x"]?.asInt() ?: return@mapIndexedNotNull null
                val y = bbox?.get("y")?.asInt() ?: region["y"]?.asInt() ?: return@mapIndexedNotNull null
                val width = bbox?.get("width")?.asInt() ?: region["width"]?.asInt() ?: return@mapIndexedNotNull null
                val height = bbox?.get("height")?.asInt() ?: region["height"]?.asInt() ?: return@mapIndexedNotNull null
                if (width <= 0 || height <= 0) return@mapIndexedNotNull null
                CropRegion(
                    id = (index + 1).toString(),
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    points = parsePoints(region),
                    score = region["score"]?.asFloat()
                )
            }
        val stats = root["stats"]?.asObject()
        return DetectionResult(
            regions = regions,
            mode = detectionMode(root["mode"]?.asString()),
            stats = DetectionStats(
                estimatedBackgroundArgb = stats?.get("estimatedBackgroundArgb")?.asInt() ?: 0,
                candidatePixels = stats?.get("candidatePixels")?.asInt() ?: 0,
                connectedComponents = stats?.get("connectedComponents")?.asInt() ?: regions.size,
                regionCount = stats?.get("regionCount")?.asInt() ?: regions.size,
                backgroundSampleCount = stats?.get("backgroundSampleCount")?.asInt() ?: 0,
                totalTimeMs = stats?.get("totalTimeMs")?.asLong() ?: 0L
            )
        )
    }

    private fun detectionMode(mode: String?): DetectionMode = when {
        mode?.contains("alpha", ignoreCase = true) == true -> DetectionMode.ALPHA_MASK
        else -> DetectionMode.SOLID_BACKGROUND
    }

    private fun parsePoints(region: JsonValue.JsonObject): List<RegionPoint> {
        return region["points"]
            ?.asArray()
            ?.values
            .orEmpty()
            .mapNotNull { value ->
                val point = value.asObject() ?: return@mapNotNull null
                val x = point["x"]?.asInt() ?: return@mapNotNull null
                val y = point["y"]?.asInt() ?: return@mapNotNull null
                RegionPoint(x, y)
            }
    }
}

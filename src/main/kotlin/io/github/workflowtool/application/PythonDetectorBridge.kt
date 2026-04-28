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

    val isAvailable: Boolean
        get() = script.exists() && PythonRuntime.isAvailable
    val status: String
        get() = PythonRuntime.status(scriptAvailable = script.exists(), script = script)

    fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult? {
        val environment = PythonEnvironmentManager.ensureReady()
        if (!environment.ready) return null
        val temp = Files.createTempFile("workflowtool-python-detect", ".png")
        return try {
            ImageIO.write(image, "png", temp.toFile())
            val command = PythonRuntime.buildCommand(
                listOf(
                    script.toString(),
                    "--image",
                    temp.toString(),
                    "--model",
                    AppRuntimeFiles.pythonDir.resolve("model").resolve("instance_segmentation").resolve("model.onnx").toString(),
                    "--score-threshold",
                    scoreThreshold(config).toString(),
                    "--mask-threshold",
                    "0.5"
                )
            )?.toMutableList() ?: return null
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
                val maskWidth = region["maskWidth"]?.asInt() ?: return@mapIndexedNotNull null
                val maskHeight = region["maskHeight"]?.asInt() ?: return@mapIndexedNotNull null
                val alphaMask = parseAlphaMask(region)
                if (maskWidth <= 0 || maskHeight <= 0 || alphaMask.size != maskWidth * maskHeight || alphaMask.none { it > 0 }) {
                    return@mapIndexedNotNull null
                }
                CropRegion(
                    id = (index + 1).toString(),
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    maskWidth = maskWidth,
                    maskHeight = maskHeight,
                    alphaMask = alphaMask,
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
                totalTimeMs = stats?.get("totalTimeMs")?.asLong() ?: 0L,
                backend = stats?.get("backend")?.asString().orEmpty()
            )
        )
    }

    private fun detectionMode(mode: String?): DetectionMode = when {
        mode?.contains("alpha", ignoreCase = true) == true -> DetectionMode.ALPHA_MASK
        else -> DetectionMode.SOLID_BACKGROUND
    }

    private fun scoreThreshold(config: DetectionConfig): Float {
        return (config.colorDistanceThreshold / 100.0f).coerceIn(0.05f, 0.95f)
    }

    private fun parseAlphaMask(region: JsonValue.JsonObject): List<Int> {
        return region["alphaMask"]
            ?.asArray()
            ?.values
            .orEmpty()
            .mapNotNull { it.asInt()?.coerceIn(0, 255) }
    }
}

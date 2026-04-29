package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.model.maskAlphaAt
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.math.max
import kotlin.math.min

data class ModelEvolutionPreviewResult(
    val beforeOverlayPath: Path?,
    val afterOverlayPath: Path?,
    val beforeRegionCount: Int,
    val afterRegionCount: Int,
    val beforeMaxScore: Float?,
    val afterMaxScore: Float?,
    val visualSummary: String
)

fun generateModelEvolutionPreview(
    sampleImagePath: Path?,
    config: DetectionConfig = DetectionConfig()
): ModelEvolutionPreviewResult? {
    val imagePath = sampleImagePath?.takeIf { it.exists() } ?: return null
    val loaded = runCatching { ImageIO.read(imagePath.toFile()) }.getOrNull() ?: return null
    val modelRoot = AppRuntimeFiles.pythonDir.resolve("model")
    val beforeModel = modelRoot.resolve("instance_segmentation_previous").resolve("model.onnx")
    val afterModel = modelRoot.resolve("instance_segmentation").resolve("model.onnx")
    val before = PythonDetectorBridge.detectWithModel(loaded, config, beforeModel)
    val after = PythonDetectorBridge.detectWithModel(loaded, config, afterModel)
    if (before == null && after == null) return null

    val previewDir = modelRoot.resolve("evolution-previews")
    Files.createDirectories(previewDir)
    val fingerprint = sha256("${imagePath.toAbsolutePath().normalize()}:${imagePath.toFile().lastModified()}:${System.nanoTime()}").take(16)
    val beforePath = before?.let { previewDir.resolve("${fingerprint}_before.png") }
    val afterPath = after?.let { previewDir.resolve("${fingerprint}_after.png") }
    beforePath?.let { ImageIO.write(drawDetectionOverlay(loaded, before, "训练前"), "png", it.toFile()) }
    afterPath?.let { ImageIO.write(drawDetectionOverlay(loaded, after, "训练后"), "png", it.toFile()) }

    val beforeCount = before?.regions?.size ?: 0
    val afterCount = after?.regions?.size ?: 0
    val beforeScore = before?.regions?.mapNotNull { it.score }?.maxOrNull()
    val afterScore = after?.regions?.mapNotNull { it.score }?.maxOrNull()
    return ModelEvolutionPreviewResult(
        beforeOverlayPath = beforePath,
        afterOverlayPath = afterPath,
        beforeRegionCount = beforeCount,
        afterRegionCount = afterCount,
        beforeMaxScore = beforeScore,
        afterMaxScore = afterScore,
        visualSummary = buildVisualSummary(beforeCount, afterCount, beforeScore, afterScore)
    )
}

fun LearningConfig.toDetectionConfig(): DetectionConfig =
    DetectionConfig(colorDistanceThreshold = (scoreThreshold * 160.0).toInt().coerceIn(1, 999))

fun drawDetectionOverlay(image: BufferedImage, result: DetectionResult, title: String): BufferedImage {
    val maxEdge = 360.0
    val scale = min(1.0, maxEdge / max(image.width, image.height).coerceAtLeast(1).toDouble())
    val width = max(1, (image.width * scale).toInt())
    val height = max(1, (image.height * scale).toInt())
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    graphics.drawImage(image, 0, 0, width, height, null)
    graphics.composite = AlphaComposite.SrcOver
    result.regions.forEachIndexed { index, region ->
        drawRegionOverlay(output, region, scale, index)
    }
    graphics.color = Color(15, 20, 28, 190)
    graphics.fillRoundRect(8, 8, 116, 24, 8, 8)
    graphics.color = Color.WHITE
    graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
    graphics.drawString("$title ${result.regions.size}", 18, 25)
    graphics.dispose()
    return output
}

private fun drawRegionOverlay(output: BufferedImage, region: CropRegion, scale: Double, index: Int) {
    val graphics = output.createGraphics()
    graphics.color = Color(68, 151, 255, 72)
    if (region.hasMask()) {
        for (y in region.y until region.bottom) {
            for (x in region.x until region.right) {
                if (region.maskAlphaAt(x, y) <= 0) continue
                val sx = (x * scale).toInt().coerceIn(0, output.width - 1)
                val sy = (y * scale).toInt().coerceIn(0, output.height - 1)
                output.setRGB(sx, sy, blendArgb(output.getRGB(sx, sy), Color(68, 151, 255, 90).rgb))
            }
        }
    } else {
        graphics.fillRect(
            (region.x * scale).toInt(),
            (region.y * scale).toInt(),
            (region.width * scale).toInt().coerceAtLeast(1),
            (region.height * scale).toInt().coerceAtLeast(1)
        )
    }
    graphics.color = Color(82, 180, 255)
    graphics.stroke = BasicStroke(2f)
    graphics.drawRect(
        (region.x * scale).toInt(),
        (region.y * scale).toInt(),
        (region.width * scale).toInt().coerceAtLeast(1),
        (region.height * scale).toInt().coerceAtLeast(1)
    )
    region.score?.let { score ->
        val label = "#${index + 1} %.2f".format(java.util.Locale.US, score)
        val x = (region.x * scale).toInt()
        val y = (region.y * scale).toInt().coerceAtLeast(18)
        graphics.color = Color(15, 20, 28, 190)
        graphics.fillRoundRect(x, y - 16, 54, 18, 6, 6)
        graphics.color = Color.WHITE
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        graphics.drawString(label, x + 5, y - 3)
    }
    graphics.dispose()
}

private fun blendArgb(background: Int, overlay: Int): Int {
    val alpha = overlay ushr 24
    val inverse = 255 - alpha
    val red = (((overlay shr 16) and 0xFF) * alpha + ((background shr 16) and 0xFF) * inverse) / 255
    val green = (((overlay shr 8) and 0xFF) * alpha + ((background shr 8) and 0xFF) * inverse) / 255
    val blue = ((overlay and 0xFF) * alpha + (background and 0xFF) * inverse) / 255
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun buildVisualSummary(beforeCount: Int, afterCount: Int, beforeScore: Float?, afterScore: Float?): String {
    val scoreSummary = if (beforeScore != null || afterScore != null) {
        val before = beforeScore?.let { "%.2f".format(java.util.Locale.US, it) } ?: "-"
        val after = afterScore?.let { "%.2f".format(java.util.Locale.US, it) } ?: "-"
        "，最高置信度 $before -> $after"
    } else {
        ""
    }
    return "识别数 $beforeCount -> $afterCount$scoreSummary"
}

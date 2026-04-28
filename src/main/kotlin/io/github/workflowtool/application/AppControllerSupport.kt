package io.github.workflowtool.application

import androidx.compose.ui.geometry.Offset
import io.github.workflowtool.model.CropRegion
import java.awt.image.BufferedImage
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.sqrt

operator fun Offset.times(scale: Float): Offset = Offset(x * scale, y * scale)

operator fun Offset.div(scale: Float): Offset = Offset(x / scale, y / scale)

data class ProcessResult(val exitCode: Int, val output: String)

data class ContinuousTrainingUpdate(
    val icon: Boolean,
    val background: Boolean
) {
    val hasUpdates: Boolean get() = icon || background
}

val supportedImageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
const val multiImageGap = 64

fun formatArgb(value: Int): String = "#%08X".format(value)

fun combineImages(images: List<BufferedImage>, gap: Int): BufferedImage {
    val columns = ceil(sqrt(images.size.toDouble())).toInt().coerceAtLeast(1)
    val rows = ceil(images.size / columns.toDouble()).toInt().coerceAtLeast(1)
    val cellWidth = images.maxOf { it.width }
    val cellHeight = images.maxOf { it.height }
    val width = columns * cellWidth + (columns - 1).coerceAtLeast(0) * gap
    val height = rows * cellHeight + (rows - 1).coerceAtLeast(0) * gap
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    images.forEachIndexed { index, image ->
        val column = index % columns
        val row = index / columns
        graphics.drawImage(image, column * (cellWidth + gap), row * (cellHeight + gap), null)
    }
    graphics.dispose()
    return output
}

fun appendImagesToCanvas(base: BufferedImage, images: List<BufferedImage>, gap: Int): BufferedImage {
    val columns = ceil(sqrt(images.size.toDouble())).toInt().coerceAtLeast(1)
    val rows = ceil(images.size / columns.toDouble()).toInt().coerceAtLeast(1)
    val cellWidth = images.maxOf { it.width }
    val cellHeight = images.maxOf { it.height }
    val gridWidth = columns * cellWidth + (columns - 1).coerceAtLeast(0) * gap
    val gridHeight = rows * cellHeight + (rows - 1).coerceAtLeast(0) * gap
    val offsetX = base.width + gap
    val output = BufferedImage(offsetX + gridWidth, maxOf(base.height, gridHeight), BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    graphics.drawImage(base, 0, 0, null)
    images.forEachIndexed { index, image ->
        val column = index % columns
        val row = index / columns
        graphics.drawImage(image, offsetX + column * (cellWidth + gap), row * (cellHeight + gap), null)
    }
    graphics.dispose()
    return output
}

fun trainingComparableRegions(regions: List<CropRegion>): List<CropRegion> {
    return regions.mapIndexed { index, region ->
        region.copy(id = (index + 1).toString(), selected = false)
    }
}

fun estimateCornerBackgroundArgb(image: BufferedImage): Int {
    val corner = minOf(image.width, image.height, 24).coerceAtLeast(1)
    var a = 0L
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    fun add(x: Int, y: Int) {
        val argb = image.getRGB(x, y)
        a += argb ushr 24 and 0xFF
        r += argb ushr 16 and 0xFF
        g += argb ushr 8 and 0xFF
        b += argb and 0xFF
        count += 1
    }
    for (y in 0 until corner) {
        for (x in 0 until corner) {
            add(x, y)
            add(image.width - 1 - x, y)
            add(x, image.height - 1 - y)
            add(image.width - 1 - x, image.height - 1 - y)
        }
    }
    if (count == 0L) return 0
    return ((a / count).toInt() shl 24) or
        ((r / count).toInt() shl 16) or
        ((g / count).toInt() shl 8) or
        (b / count).toInt()
}

fun buildTrainingJsonLine(imagePath: String, imageHash: String, regions: List<CropRegion>): String {
    val instances = regions.joinToString(",") { region ->
        val mask = region.alphaMask.joinToString(",")
        """{"bbox":{"x":${region.x},"y":${region.y},"width":${region.width},"height":${region.height}},"maskWidth":${region.maskWidth},"maskHeight":${region.maskHeight},"alphaMask":[$mask],"label":"icon"}"""
    }
    return """{"image":"${escapeJson(imagePath)}","imageHash":"$imageHash","instances":[$instances]}"""
}

fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

fun imagePixelHash(image: BufferedImage): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            digest.update((argb ushr 24 and 0xFF).toByte())
            digest.update((argb ushr 16 and 0xFF).toByte())
            digest.update((argb ushr 8 and 0xFF).toByte())
            digest.update((argb and 0xFF).toByte())
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun escapeJson(value: String): String =
    value.flatMap { char ->
        when (char) {
            '\\' -> listOf('\\', '\\')
            '"' -> listOf('\\', '"')
            '\n' -> listOf('\\', 'n')
            '\r' -> listOf('\\', 'r')
            '\t' -> listOf('\\', 't')
            else -> listOf(char)
        }
    }.joinToString("")

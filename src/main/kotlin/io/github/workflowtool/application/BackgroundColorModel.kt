package io.github.workflowtool.application

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.math.pow
import kotlin.math.sqrt

internal object BackgroundColorModel {
    private var loadedAt: Long = Long.MIN_VALUE
    private var records: List<BackgroundColorRecord> = emptyList()

    fun predict(sampleArgb: Int): Int? {
        val modelRecords = loadRecords()
        if (modelRecords.isEmpty()) return null
        val sample = BackgroundColor.fromArgb(sampleArgb)
        val nearest = modelRecords.minByOrNull { it.edgeColor.distanceTo(sample) } ?: return null
        if (nearest.edgeColor.distanceTo(sample) > 72.0) return null
        return nearest.backgroundArgb
    }

    fun invalidate() {
        loadedAt = Long.MIN_VALUE
        records = emptyList()
    }

    private fun loadRecords(): List<BackgroundColorRecord> {
        val modelFile = AppRuntimeFiles.pythonDir.resolve("model").resolve("background").resolve("model.json")
        if (!modelFile.exists()) return emptyList()
        val modified = Files.getLastModifiedTime(modelFile).toMillis()
        if (modified == loadedAt) return records
        loadedAt = modified
        records = parseModel(modelFile)
        return records
    }

    private fun parseModel(modelFile: Path): List<BackgroundColorRecord> {
        val text = Files.readString(modelFile, Charsets.UTF_8)
        val pattern = Regex(
            """\{\s*"edgeArgb"\s*:\s*(-?\d+)\s*,\s*"backgroundArgb"\s*:\s*(-?\d+)"""
        )
        return pattern.findAll(text).map { match ->
            val (edgeArgb, backgroundArgb) = match.destructured
            BackgroundColorRecord(
                edgeColor = BackgroundColor.fromArgb(edgeArgb.toInt()),
                backgroundArgb = backgroundArgb.toInt()
            )
        }.toList()
    }
}

private data class BackgroundColorRecord(
    val edgeColor: BackgroundColor,
    val backgroundArgb: Int
)

private data class BackgroundColor(val r: Int, val g: Int, val b: Int) {
    fun distanceTo(other: BackgroundColor): Double {
        return sqrt(
            (r - other.r).toDouble().pow(2) +
                (g - other.g).toDouble().pow(2) +
                (b - other.b).toDouble().pow(2)
        )
    }

    companion object {
        fun fromArgb(argb: Int): BackgroundColor {
            return BackgroundColor(
                r = argb ushr 16 and 0xFF,
                g = argb ushr 8 and 0xFF,
                b = argb and 0xFF
            )
        }
    }
}

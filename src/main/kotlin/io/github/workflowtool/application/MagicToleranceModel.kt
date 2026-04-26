package io.github.workflowtool.application

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.math.pow
import kotlin.math.sqrt

internal object MagicToleranceModel {
    private var loadedAt: Long = Long.MIN_VALUE
    private var records: List<MagicToleranceRecord> = emptyList()

    fun predict(seedArgb: Int, fallbackTolerance: Int): Int {
        val modelRecords = loadRecords()
        if (modelRecords.isEmpty()) return fallbackTolerance
        val seed = MagicColor.fromArgb(seedArgb)
        val nearest = modelRecords.minByOrNull { it.color.distanceTo(seed) } ?: return fallbackTolerance
        val distance = nearest.color.distanceTo(seed)
        if (distance > 96.0) return fallbackTolerance
        return nearest.tolerance.coerceIn(1, 255)
    }

    fun invalidate() {
        loadedAt = Long.MIN_VALUE
        records = emptyList()
    }

    private fun loadRecords(): List<MagicToleranceRecord> {
        val modelFile = AppRuntimeFiles.pythonDir.resolve("model").resolve("magic").resolve("model.json")
        if (!modelFile.exists()) return emptyList()
        val modified = Files.getLastModifiedTime(modelFile).toMillis()
        if (modified == loadedAt) return records
        loadedAt = modified
        records = parseModel(modelFile)
        return records
    }

    private fun parseModel(modelFile: Path): List<MagicToleranceRecord> {
        val text = Files.readString(modelFile, Charsets.UTF_8)
        val recordPattern = Regex(
            """\{\s*"r"\s*:\s*(\d+)\s*,\s*"g"\s*:\s*(\d+)\s*,\s*"b"\s*:\s*(\d+)\s*,\s*"tolerance"\s*:\s*(\d+)"""
        )
        return recordPattern.findAll(text).map { match ->
            val (r, g, b, tolerance) = match.destructured
            MagicToleranceRecord(
                color = MagicColor(r.toInt(), g.toInt(), b.toInt()),
                tolerance = tolerance.toInt()
            )
        }.toList()
    }
}

private data class MagicToleranceRecord(
    val color: MagicColor,
    val tolerance: Int
)

private data class MagicColor(
    val r: Int,
    val g: Int,
    val b: Int
) {
    fun distanceTo(other: MagicColor): Double {
        return sqrt(
            (r - other.r).toDouble().pow(2) +
                (g - other.g).toDouble().pow(2) +
                (b - other.b).toDouble().pow(2)
        )
    }

    companion object {
        fun fromArgb(argb: Int): MagicColor {
            return MagicColor(
                r = argb ushr 16 and 0xFF,
                g = argb ushr 8 and 0xFF,
                b = argb and 0xFF
            )
        }
    }
}

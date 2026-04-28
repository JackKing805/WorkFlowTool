package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.SplitSource
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.math.roundToInt

internal data class WorkspaceSnapshotEntry(
    val id: String,
    val title: String,
    val sourcePaths: List<Path>,
    val currentImageIndex: Int,
    val updatedAtEpochMillis: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val splitSource: SplitSource,
    val hasManualEdits: Boolean,
    val baseRegions: List<CropRegion>,
    val regions: List<CropRegion>
) {
    val previewPath: Path
        get() = WorkspaceHistoryStore.previewPathFor(id)

    val primaryPath: Path?
        get() = sourcePaths.getOrNull(currentImageIndex) ?: sourcePaths.firstOrNull()

    val displayTitle: String
        get() {
            val path = primaryPath
            return when {
                title.isNotBlank() -> title
                path != null -> path.fileName.toString()
                else -> "未命名快照"
            }
        }

    val sourcePathLabel: String
        get() = primaryPath?.toString() ?: "-"

    val allFilesAvailable: Boolean
        get() = sourcePaths.isNotEmpty() && sourcePaths.all(Files::isRegularFile)

    val missingFileCount: Int
        get() = sourcePaths.count { !Files.isRegularFile(it) }
}

internal object WorkspaceHistoryStore {
    private const val MaxEntries = 12
    private val historyFile: Path get() = AppRuntimeFiles.runtimeRoot.resolve("workspace-history.json")
    private val previewRoot: Path get() = AppRuntimeFiles.runtimeRoot.resolve("history-previews")

    fun load(): List<WorkspaceSnapshotEntry> {
        val file = historyFile
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = parseJsonObject(Files.readString(file, Charsets.UTF_8)) ?: return@runCatching emptyList()
            root["entries"]?.asArray()?.values
                ?.mapNotNull { readEntry(it.asObject()) }
                ?.sortedByDescending { it.updatedAtEpochMillis }
                ?.take(MaxEntries)
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun upsert(
        existing: List<WorkspaceSnapshotEntry>,
        entry: WorkspaceSnapshotEntry,
        preview: BufferedImage
    ): List<WorkspaceSnapshotEntry> {
        val merged = (listOf(entry) + existing.filterNot { it.id == entry.id })
            .sortedByDescending { it.updatedAtEpochMillis }
            .take(MaxEntries)
        save(merged)
        Files.createDirectories(previewRoot)
        ImageIO.write(preview, "png", previewPathFor(entry.id).toFile())
        cleanupPreviewFiles(merged.mapTo(mutableSetOf()) { it.id })
        return merged
    }

    fun remove(existing: List<WorkspaceSnapshotEntry>, id: String): List<WorkspaceSnapshotEntry> {
        val next = existing.filterNot { it.id == id }
        previewPathFor(id).deleteIfExists()
        save(next)
        cleanupPreviewFiles(next.mapTo(mutableSetOf()) { it.id })
        return next
    }

    fun clear() {
        historyFile.deleteIfExists()
        if (!previewRoot.exists()) return
        Files.walk(previewRoot).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                path.deleteIfExists()
            }
        }
    }

    fun previewPathFor(id: String): Path = previewRoot.resolve("$id.png")

    private fun save(entries: List<WorkspaceSnapshotEntry>) {
        runCatching {
            Files.createDirectories(historyFile.parent)
            Files.writeString(historyFile, entriesToJson(entries), Charsets.UTF_8)
        }
    }

    private fun cleanupPreviewFiles(validIds: Set<String>) {
        if (!previewRoot.exists()) return
        Files.walk(previewRoot).use { stream ->
            stream.filter(Files::isRegularFile).forEach { path ->
                val fileName = path.fileName.toString()
                val id = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
                if (id !in validIds) {
                    path.deleteIfExists()
                }
            }
        }
    }

    private fun entriesToJson(entries: List<WorkspaceSnapshotEntry>): String = buildString {
        append("{\n")
        append("  \"entries\": [\n")
        append(entries.joinToString(",\n") { entry -> "    ${entry.toJson()}" })
        append("\n  ]\n")
        append("}\n")
    }

    private fun WorkspaceSnapshotEntry.toJson(): String = buildString {
        append("{")
        append("\"id\":${jsonString(id)},")
        append("\"title\":${jsonString(title)},")
        append("\"sourcePaths\":[${sourcePaths.joinToString(",") { jsonString(it.toString()) }}],")
        append("\"currentImageIndex\":$currentImageIndex,")
        append("\"updatedAtEpochMillis\":$updatedAtEpochMillis,")
        append("\"imageWidth\":$imageWidth,")
        append("\"imageHeight\":$imageHeight,")
        append("\"splitSource\":${jsonString(splitSource.name)},")
        append("\"hasManualEdits\":$hasManualEdits,")
        append("\"baseRegions\":[${baseRegions.joinToString(",") { it.toJson() }}],")
        append("\"regions\":[${regions.joinToString(",") { it.toJson() }}]")
        append("}")
    }

    private fun CropRegion.toJson(): String = buildString {
        append("{")
        append("\"id\":${jsonString(id)},")
        append("\"x\":$x,")
        append("\"y\":$y,")
        append("\"width\":$width,")
        append("\"height\":$height,")
        append("\"visible\":$visible,")
        append("\"selected\":$selected,")
        append("\"maskWidth\":$maskWidth,")
        append("\"maskHeight\":$maskHeight,")
        append("\"alphaMask\":[${alphaMask.joinToString(",")}],")
        append("\"score\":${score?.toString() ?: "null"}")
        append("}")
    }

    private fun readEntry(obj: JsonValue.JsonObject?): WorkspaceSnapshotEntry? {
        if (obj == null) return null
        val paths = obj["sourcePaths"]?.asArray()?.values
            ?.mapNotNull { it.asString()?.takeIf(String::isNotBlank)?.let(Path::of) }
            .orEmpty()
        val splitSource = obj["splitSource"]?.asString()?.let(::splitSourceOrNull) ?: SplitSource.AutoDetect
        return WorkspaceSnapshotEntry(
            id = obj["id"]?.asString().orEmpty(),
            title = obj["title"]?.asString().orEmpty(),
            sourcePaths = paths,
            currentImageIndex = obj["currentImageIndex"]?.asInt() ?: 0,
            updatedAtEpochMillis = obj["updatedAtEpochMillis"]?.asLong() ?: 0L,
            imageWidth = obj["imageWidth"]?.asInt() ?: 0,
            imageHeight = obj["imageHeight"]?.asInt() ?: 0,
            splitSource = splitSource,
            hasManualEdits = obj["hasManualEdits"]?.asBoolean() ?: false,
            baseRegions = obj["baseRegions"]?.asArray()?.values?.mapNotNull { readRegion(it.asObject()) }.orEmpty(),
            regions = obj["regions"]?.asArray()?.values?.mapNotNull { readRegion(it.asObject()) }.orEmpty()
        ).takeIf { it.id.isNotBlank() && it.sourcePaths.isNotEmpty() }
    }

    private fun readRegion(obj: JsonValue.JsonObject?): CropRegion? {
        if (obj == null) return null
        return CropRegion(
            id = obj["id"]?.asString().orEmpty(),
            x = obj["x"]?.asInt() ?: 0,
            y = obj["y"]?.asInt() ?: 0,
            width = obj["width"]?.asInt() ?: 0,
            height = obj["height"]?.asInt() ?: 0,
            visible = obj["visible"]?.asBoolean() ?: true,
            selected = obj["selected"]?.asBoolean() ?: false,
            maskWidth = obj["maskWidth"]?.asInt() ?: 0,
            maskHeight = obj["maskHeight"]?.asInt() ?: 0,
            alphaMask = obj["alphaMask"]?.asArray()?.values?.mapNotNull(JsonValue::asInt).orEmpty(),
            score = obj["score"]?.asFloat()
        )
    }

    private fun splitSourceOrNull(name: String): SplitSource? =
        enumValues<SplitSource>().firstOrNull { it.name == name }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                append(
                    when (char) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        '\b' -> "\\b"
                        '\u000C' -> "\\f"
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        else -> char
                    }
                )
            }
            append('"')
        }
}

internal fun buildWorkspaceSnapshotId(sourcePaths: List<Path>): String =
    sha256(sourcePaths.joinToString("\n") { it.toAbsolutePath().normalize().toString() }).take(24)

internal fun buildWorkspaceSnapshotPreview(image: BufferedImage, regions: List<CropRegion>): BufferedImage {
    val scale = minOf(320.0 / image.width.coerceAtLeast(1), 192.0 / image.height.coerceAtLeast(1), 1.0)
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.drawImage(image, 0, 0, width, height, null)

    val visibleColor = Color(0x2F, 0x6B, 0xFF, 58)
    val visibleStroke = Color(0x7E, 0xB1, 0xFF, 220)
    val hiddenColor = Color(0x8C, 0x96, 0xA8, 36)
    val hiddenStroke = Color(0xB9, 0xC0, 0xCD, 170)
    val strokeWidth = (1.5f + scale.toFloat()).coerceAtLeast(1.2f)

    regions.take(240).forEach { region ->
        val x = (region.x * scale).roundToInt()
        val y = (region.y * scale).roundToInt()
        val regionWidth = (region.width * scale).roundToInt().coerceAtLeast(1)
        val regionHeight = (region.height * scale).roundToInt().coerceAtLeast(1)
        graphics.color = if (region.visible) visibleColor else hiddenColor
        graphics.fillRect(x, y, regionWidth, regionHeight)
        graphics.color = if (region.visible) visibleStroke else hiddenStroke
        graphics.stroke = BasicStroke(strokeWidth)
        graphics.drawRect(x, y, regionWidth, regionHeight)
    }

    val label = "${regions.size} 个区域"
    graphics.color = Color(0x0E, 0x12, 0x18, 188)
    graphics.fillRoundRect(8, 8, 96, 28, 12, 12)
    graphics.color = Color.WHITE
    graphics.drawString(label, 16, 27)
    graphics.dispose()
    return output
}

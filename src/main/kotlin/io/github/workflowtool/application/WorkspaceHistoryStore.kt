package io.github.workflowtool.application

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.ui.editor.CheckerboardCellPx
import io.github.workflowtool.ui.editor.CheckerboardDarkArgb
import io.github.workflowtool.ui.editor.CheckerboardLightArgb
import io.github.workflowtool.ui.editor.PixelGridStrongThreshold
import io.github.workflowtool.ui.editor.PixelGridThreshold
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
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
            val text = sanitizeLegacyHistoryJson(Files.readString(file, Charsets.UTF_8))
            val root = parseJsonObject(text) ?: return@runCatching emptyList()
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
        deleteDirectory(previewRoot)
    }

    fun previewPathFor(id: String): Path = previewRoot.resolve("$id.png")

    private fun save(entries: List<WorkspaceSnapshotEntry>) {
        Files.createDirectories(historyFile.parent)
        Files.writeString(historyFile, entriesToJson(entries), Charsets.UTF_8)
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

    private fun deleteDirectory(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
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
        append("\"score\":${score?.takeIf { it.isFinite() }?.toString() ?: "null"}")
        append("}")
    }

    private fun readEntry(obj: JsonValue.JsonObject?): WorkspaceSnapshotEntry? {
        if (obj == null) return null
        val paths = obj["sourcePaths"]?.asArray()?.values
            ?.mapNotNull { it.asString()?.takeIf(String::isNotBlank)?.let(Path::of) }
            .orEmpty()
        return WorkspaceSnapshotEntry(
            id = obj["id"]?.asString().orEmpty(),
            title = obj["title"]?.asString().orEmpty(),
            sourcePaths = paths,
            currentImageIndex = obj["currentImageIndex"]?.asInt() ?: 0,
            updatedAtEpochMillis = obj["updatedAtEpochMillis"]?.asLong() ?: 0L,
            imageWidth = obj["imageWidth"]?.asInt() ?: 0,
            imageHeight = obj["imageHeight"]?.asInt() ?: 0,
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

    private fun sanitizeLegacyHistoryJson(text: String): String =
        text.replace(Regex(""""score"\s*:\s*(NaN|Infinity|-Infinity)"""), """"score":null""")
}

internal fun buildWorkspaceSnapshotId(sourcePaths: List<Path>): String =
    sha256(sourcePaths.joinToString("\n") { it.toAbsolutePath().normalize().toString() }).take(24)

internal fun buildWorkspaceSnapshotPreview(
    image: BufferedImage,
    regions: List<CropRegion>,
    zoom: Float,
    viewportOffset: Offset,
    viewportSize: Size,
    showGrid: Boolean
): BufferedImage {
    val width = viewportSize.width.roundToInt().takeIf { it > 0 } ?: 320
    val height = viewportSize.height.roundToInt().takeIf { it > 0 } ?: 192
    val resolvedZoom = zoom.takeIf { it > 0f } ?: minOf(
        width.toFloat() / image.width.coerceAtLeast(1),
        height.toFloat() / image.height.coerceAtLeast(1)
    ).coerceIn(0.1f, 8f)
    val resolvedOffset = if (viewportSize.width > 0f && viewportSize.height > 0f) {
        viewportOffset
    } else {
        Offset(
            x = (width - image.width * resolvedZoom) / 2f,
            y = (height - image.height * resolvedZoom) / 2f
        )
    }
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.color = Color(0xE1, 0xE4, 0xE8)
    graphics.fillRect(0, 0, width, height)
    drawSnapshotCheckerboard(graphics, width, height, resolvedZoom, resolvedOffset)
    if (showGrid) drawSnapshotGrid(graphics, width, height, resolvedZoom, resolvedOffset)
    graphics.drawImage(
        image,
        resolvedOffset.x.roundToInt(),
        resolvedOffset.y.roundToInt(),
        (image.width * resolvedZoom).roundToInt(),
        (image.height * resolvedZoom).roundToInt(),
        null
    )

    regions.filter { it.visible }.take(240).forEach { region ->
        drawSnapshotRegion(graphics, region, resolvedZoom, resolvedOffset)
    }
    graphics.dispose()
    return output
}

private fun drawSnapshotCheckerboard(graphics: java.awt.Graphics2D, width: Int, height: Int, zoom: Float, offset: Offset) {
    val cell = CheckerboardCellPx
    val dark = Color(CheckerboardDarkArgb, true)
    val light = Color(CheckerboardLightArgb, true)
    var y = kotlin.math.floor(-offset.y / cell) * cell + offset.y
    var row = kotlin.math.floor((y - offset.y) / cell).toInt()
    while (y < height) {
        var x = kotlin.math.floor(-offset.x / cell) * cell + offset.x
        var column = kotlin.math.floor((x - offset.x) / cell).toInt()
        while (x < width) {
            graphics.color = if ((row + column) % 2 == 0) dark else light
            graphics.fillRect(x.roundToInt(), y.roundToInt(), cell.roundToInt().coerceAtLeast(1), cell.roundToInt().coerceAtLeast(1))
            x += cell
            column += 1
        }
        y += cell
        row += 1
    }
}

private fun drawSnapshotGrid(graphics: java.awt.Graphics2D, width: Int, height: Int, zoom: Float, offset: Offset) {
    val spacing = 48f * zoom.coerceAtLeast(0.4f)
    graphics.color = Color(255, 255, 255, 31)
    graphics.stroke = BasicStroke(1f)
    var x = offset.x % spacing
    while (x <= width) {
        graphics.drawLine(x.roundToInt(), 0, x.roundToInt(), height)
        x += spacing
    }
    var y = offset.y % spacing
    while (y <= height) {
        graphics.drawLine(0, y.roundToInt(), width, y.roundToInt())
        y += spacing
    }
    drawSnapshotPixelGrid(graphics, width, height, zoom, offset)
}

private fun drawSnapshotPixelGrid(graphics: java.awt.Graphics2D, width: Int, height: Int, zoom: Float, offset: Offset) {
    if (zoom < PixelGridThreshold) return
    val alpha = if (zoom >= PixelGridStrongThreshold) 87 else 46
    graphics.color = Color(0, 0, 0, alpha)
    graphics.stroke = BasicStroke(1f)
    var x = offset.x % zoom
    if (x > 0f) x -= zoom
    while (x <= width) {
        graphics.drawLine(x.roundToInt(), 0, x.roundToInt(), height)
        x += zoom
    }
    var y = offset.y % zoom
    if (y > 0f) y -= zoom
    while (y <= height) {
        graphics.drawLine(0, y.roundToInt(), width, y.roundToInt())
        y += zoom
    }
}

private fun drawSnapshotRegion(graphics: java.awt.Graphics2D, region: CropRegion, zoom: Float, offset: Offset) {
    val fill = if (region.selected) Color(0xF3, 0xA2, 0x3C, 38) else Color(0x1F, 0x4A, 0x82, 8)
    val stroke = if (region.selected) Color(0x11, 0x11, 0x11, 240) else Color(0x6B, 0x7E, 0xA1, 192)
    graphics.color = fill
    if (region.hasMask()) {
        drawSnapshotMask(graphics, region, zoom, offset, fill, stroke)
        return
    }
    val x = (region.x * zoom + offset.x).roundToInt()
    val y = (region.y * zoom + offset.y).roundToInt()
    val w = (region.width * zoom).roundToInt().coerceAtLeast(1)
    val h = (region.height * zoom).roundToInt().coerceAtLeast(1)
    graphics.fillRect(x, y, w, h)
    graphics.color = stroke
    graphics.stroke = BasicStroke(if (region.selected) 2f else 1.25f)
    graphics.drawRect(x, y, w, h)
}

private fun drawSnapshotMask(
    graphics: java.awt.Graphics2D,
    region: CropRegion,
    zoom: Float,
    offset: Offset,
    fill: Color,
    stroke: Color
) {
    for (localY in 0 until region.maskHeight) {
        var localX = 0
        while (localX < region.maskWidth) {
            val alpha = region.alphaMask[localY * region.maskWidth + localX].coerceIn(0, 255)
            if (alpha <= 0) {
                localX += 1
                continue
            }
            val startX = localX
            while (localX + 1 < region.maskWidth && region.alphaMask[localY * region.maskWidth + localX + 1] > 0) {
                localX += 1
            }
            graphics.color = Color(fill.red, fill.green, fill.blue, (fill.alpha * (0.60f + 0.40f * alpha / 255f)).roundToInt().coerceIn(0, 255))
            graphics.fillRect(
                ((region.x + startX) * zoom + offset.x).roundToInt(),
                ((region.y + localY) * zoom + offset.y).roundToInt(),
                ((localX - startX + 1) * zoom).roundToInt().coerceAtLeast(1),
                zoom.roundToInt().coerceAtLeast(1)
            )
            localX += 1
        }
    }
    graphics.color = stroke
    graphics.stroke = BasicStroke(if (region.selected) 2f else 1.25f)
    graphics.drawRect(
        (region.x * zoom + offset.x).roundToInt(),
        (region.y * zoom + offset.y).roundToInt(),
        (region.width * zoom).roundToInt().coerceAtLeast(1),
        (region.height * zoom).roundToInt().coerceAtLeast(1)
    )
}

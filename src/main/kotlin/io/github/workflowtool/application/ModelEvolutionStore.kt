package io.github.workflowtool.application

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

data class ModelEvolutionEntry(
    val id: String,
    val createdAtEpochMillis: Long,
    val source: String,
    val status: ModelEvolutionStatus,
    val sampleCount: Int,
    val trainingType: String,
    val message: String,
    val thumbnailPath: Path?,
    val beforeOverlayPath: Path? = null,
    val afterOverlayPath: Path? = null,
    val beforeRegionCount: Int = 0,
    val afterRegionCount: Int = 0,
    val beforeMaxScore: Float? = null,
    val afterMaxScore: Float? = null,
    val visualSummary: String = "",
    val revisionBefore: Int,
    val revisionAfter: Int,
    val changes: List<ModelEvolutionChange>
)

data class ModelEvolutionChange(
    val label: String,
    val before: String,
    val after: String
)

enum class ModelEvolutionStatus {
    Updated,
    Waiting,
    Failed
}

internal object ModelEvolutionStore {
    const val MaxEntries = 100

    private val historyFile: Path
        get() = AppRuntimeFiles.pythonDir.resolve("model").resolve("evolution-history.json")

    fun load(): List<ModelEvolutionEntry> {
        val file = historyFile
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = parseJsonObject(Files.readString(file, Charsets.UTF_8)) ?: return@runCatching emptyList()
            root["entries"]?.asArray()?.values
                ?.mapNotNull { readEntry(it.asObject()) }
                ?.sortedByDescending { it.createdAtEpochMillis }
                ?.take(MaxEntries)
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun append(entry: ModelEvolutionEntry): List<ModelEvolutionEntry> {
        val updated = (listOf(entry) + load())
            .distinctBy { it.id }
            .sortedByDescending { it.createdAtEpochMillis }
            .take(MaxEntries)
        save(updated)
        return updated
    }

    fun clear() {
        Files.deleteIfExists(historyFile)
    }

    private fun save(entries: List<ModelEvolutionEntry>) {
        Files.createDirectories(historyFile.parent)
        Files.writeString(historyFile, entriesToJson(entries), Charsets.UTF_8)
    }

    private fun entriesToJson(entries: List<ModelEvolutionEntry>): String = buildString {
        append("{\n  \"entries\": [\n")
        append(entries.joinToString(",\n") { "    ${it.toJson()}" })
        append("\n  ]\n}\n")
    }

    private fun ModelEvolutionEntry.toJson(): String = buildString {
        append("{")
        append("\"id\":${jsonString(id)},")
        append("\"createdAtEpochMillis\":$createdAtEpochMillis,")
        append("\"source\":${jsonString(source)},")
        append("\"status\":${jsonString(status.name)},")
        append("\"sampleCount\":$sampleCount,")
        append("\"trainingType\":${jsonString(trainingType)},")
        append("\"message\":${jsonString(message)},")
        append("\"thumbnailPath\":${thumbnailPath?.let { jsonString(it.toString()) } ?: "null"},")
        append("\"beforeOverlayPath\":${beforeOverlayPath?.let { jsonString(it.toString()) } ?: "null"},")
        append("\"afterOverlayPath\":${afterOverlayPath?.let { jsonString(it.toString()) } ?: "null"},")
        append("\"beforeRegionCount\":$beforeRegionCount,")
        append("\"afterRegionCount\":$afterRegionCount,")
        append("\"beforeMaxScore\":${beforeMaxScore ?: "null"},")
        append("\"afterMaxScore\":${afterMaxScore ?: "null"},")
        append("\"visualSummary\":${jsonString(visualSummary)},")
        append("\"revisionBefore\":$revisionBefore,")
        append("\"revisionAfter\":$revisionAfter,")
        append("\"changes\":[${changes.joinToString(",") { it.toJson() }}]")
        append("}")
    }

    private fun ModelEvolutionChange.toJson(): String =
        """{"label":${jsonString(label)},"before":${jsonString(before)},"after":${jsonString(after)}}"""

    private fun readEntry(obj: JsonValue.JsonObject?): ModelEvolutionEntry? {
        if (obj == null) return null
        val id = obj["id"]?.asString().orEmpty()
        if (id.isBlank()) return null
        return ModelEvolutionEntry(
            id = id,
            createdAtEpochMillis = obj["createdAtEpochMillis"]?.asLong() ?: 0L,
            source = obj["source"]?.asString().orEmpty(),
            status = obj["status"]?.asString()?.let { runCatching { ModelEvolutionStatus.valueOf(it) }.getOrNull() }
                ?: ModelEvolutionStatus.Waiting,
            sampleCount = obj["sampleCount"]?.asInt() ?: 0,
            trainingType = obj["trainingType"]?.asString().orEmpty(),
            message = obj["message"]?.asString().orEmpty(),
            thumbnailPath = obj["thumbnailPath"]?.asString()?.takeIf(String::isNotBlank)?.let(Path::of),
            beforeOverlayPath = obj["beforeOverlayPath"]?.asString()?.takeIf(String::isNotBlank)?.let(Path::of),
            afterOverlayPath = obj["afterOverlayPath"]?.asString()?.takeIf(String::isNotBlank)?.let(Path::of),
            beforeRegionCount = obj["beforeRegionCount"]?.asInt() ?: 0,
            afterRegionCount = obj["afterRegionCount"]?.asInt() ?: 0,
            beforeMaxScore = obj["beforeMaxScore"]?.asFloat(),
            afterMaxScore = obj["afterMaxScore"]?.asFloat(),
            visualSummary = obj["visualSummary"]?.asString().orEmpty(),
            revisionBefore = obj["revisionBefore"]?.asInt() ?: 0,
            revisionAfter = obj["revisionAfter"]?.asInt() ?: 0,
            changes = obj["changes"]?.asArray()?.values?.mapNotNull { readChange(it.asObject()) }.orEmpty()
        )
    }

    private fun readChange(obj: JsonValue.JsonObject?): ModelEvolutionChange? {
        if (obj == null) return null
        return ModelEvolutionChange(
            label = obj["label"]?.asString().orEmpty(),
            before = obj["before"]?.asString().orEmpty(),
            after = obj["after"]?.asString().orEmpty()
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
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        else -> char.toString()
                    }
                )
            }
            append('"')
        }
}

fun buildModelEvolutionEntry(
    source: String,
    status: ModelEvolutionStatus,
    sampleCount: Int,
    trainingType: String,
    message: String,
    thumbnailPath: Path?,
    preview: ModelEvolutionPreviewResult? = null,
    before: LearningConfig,
    after: LearningConfig
): ModelEvolutionEntry {
    val createdAt = Instant.now().toEpochMilli()
    val identity = listOf(source, status.name, sampleCount.toString(), trainingType, createdAt.toString()).joinToString("|")
    return ModelEvolutionEntry(
        id = sha256(identity).take(24),
        createdAtEpochMillis = createdAt,
        source = source,
        status = status,
        sampleCount = sampleCount,
        trainingType = trainingType,
        message = message,
        thumbnailPath = thumbnailPath,
        beforeOverlayPath = preview?.beforeOverlayPath,
        afterOverlayPath = preview?.afterOverlayPath,
        beforeRegionCount = preview?.beforeRegionCount ?: 0,
        afterRegionCount = preview?.afterRegionCount ?: 0,
        beforeMaxScore = preview?.beforeMaxScore,
        afterMaxScore = preview?.afterMaxScore,
        visualSummary = preview?.visualSummary.orEmpty(),
        revisionBefore = before.revision,
        revisionAfter = after.revision,
        changes = buildLearningConfigChanges(before, after)
    )
}

fun buildLearningConfigChanges(before: LearningConfig, after: LearningConfig): List<ModelEvolutionChange> = buildList {
    addIfChanged("Mask threshold", before.maskThreshold, after.maskThreshold)
    addIfChanged("Score threshold", before.scoreThreshold, after.scoreThreshold)
    addIfChanged("Recent epochs", before.recentFineTuneEpochs, after.recentFineTuneEpochs)
    addIfChanged("Full epochs", before.fullRetrainEpochs, after.fullRetrainEpochs)
    addIfChanged("Dice weight", before.diceWeight, after.diceWeight)
    addIfChanged("Focal weight", before.focalWeight, after.focalWeight)
    addIfChanged("Min component pixels", before.minComponentPixels, after.minComponentPixels)
    addIfChanged("Feedback samples", before.feedbackSamplesSeen, after.feedbackSamplesSeen)
}

private fun MutableList<ModelEvolutionChange>.addIfChanged(label: String, before: Double, after: Double) {
    if (before != after) {
        add(ModelEvolutionChange(label, formatConfigNumber(before), formatConfigNumber(after)))
    }
}

private fun MutableList<ModelEvolutionChange>.addIfChanged(label: String, before: Int, after: Int) {
    if (before != after) {
        add(ModelEvolutionChange(label, before.toString(), after.toString()))
    }
}

private fun formatConfigNumber(value: Double): String = "%.3f".format(java.util.Locale.US, value)

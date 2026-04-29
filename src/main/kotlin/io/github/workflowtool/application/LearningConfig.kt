package io.github.workflowtool.application

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.math.max
import kotlin.math.min

data class LearningConfig(
    val revision: Int = 1,
    val fullRetrainEpochs: Int = 4,
    val recentFineTuneEpochs: Int = 2,
    val fullBatch: Int = 2,
    val recentBatch: Int = 1,
    val learningRate: Double = 0.002,
    val fineTuneLearningRate: Double = 0.0008,
    val bceWeight: Double = 0.55,
    val diceWeight: Double = 0.30,
    val focalWeight: Double = 0.15,
    val scoreThreshold: Double = 0.18,
    val maskThreshold: Double = 0.28,
    val adaptiveMinThreshold: Double = 0.08,
    val adaptiveQuantile: Double = 0.985,
    val adaptiveMaxProbabilityScale: Double = 0.72,
    val adaptiveFallbackScale: Double = 0.55,
    val minComponentPixels: Int = 12,
    val minAlpha: Int = 180,
    val feedbackSamplesSeen: Int = 0
) {
    fun sanitized(): LearningConfig {
        val totalWeight = bceWeight + diceWeight + focalWeight
        val safeBce = if (totalWeight > 0.0) bceWeight else 0.55
        val safeDice = if (totalWeight > 0.0) diceWeight else 0.30
        val safeFocal = if (totalWeight > 0.0) focalWeight else 0.15
        return copy(
            revision = max(1, revision),
            fullRetrainEpochs = fullRetrainEpochs.coerceIn(2, 10),
            recentFineTuneEpochs = recentFineTuneEpochs.coerceIn(1, 5),
            fullBatch = fullBatch.coerceIn(1, 4),
            recentBatch = recentBatch.coerceIn(1, 2),
            learningRate = learningRate.coerceIn(0.0002, 0.006),
            fineTuneLearningRate = fineTuneLearningRate.coerceIn(0.00005, 0.002),
            bceWeight = safeBce.coerceIn(0.20, 0.75),
            diceWeight = safeDice.coerceIn(0.15, 0.60),
            focalWeight = safeFocal.coerceIn(0.05, 0.35),
            scoreThreshold = scoreThreshold.coerceIn(0.12, 0.24),
            maskThreshold = maskThreshold.coerceIn(0.20, 0.34),
            adaptiveMinThreshold = adaptiveMinThreshold.coerceIn(0.05, 0.14),
            adaptiveQuantile = adaptiveQuantile.coerceIn(0.95, 0.995),
            adaptiveMaxProbabilityScale = adaptiveMaxProbabilityScale.coerceIn(0.60, 0.85),
            adaptiveFallbackScale = adaptiveFallbackScale.coerceIn(0.45, 0.70),
            minComponentPixels = minComponentPixels.coerceIn(6, 24),
            minAlpha = minAlpha.coerceIn(120, 220),
            feedbackSamplesSeen = max(0, feedbackSamplesSeen)
        )
    }
}

data class LearningConfigEvolution(
    val previous: LearningConfig,
    val updated: LearningConfig,
    val sampleCount: Int
) {
    fun summary(): String {
        val changes = buildList {
            if (previous.maskThreshold != updated.maskThreshold) {
                add("maskThreshold ${format(previous.maskThreshold)} -> ${format(updated.maskThreshold)}")
            }
            if (previous.scoreThreshold != updated.scoreThreshold) {
                add("scoreThreshold ${format(previous.scoreThreshold)} -> ${format(updated.scoreThreshold)}")
            }
            if (previous.recentFineTuneEpochs != updated.recentFineTuneEpochs) {
                add("recentEpochs ${previous.recentFineTuneEpochs} -> ${updated.recentFineTuneEpochs}")
            }
            if (previous.fullRetrainEpochs != updated.fullRetrainEpochs) {
                add("fullEpochs ${previous.fullRetrainEpochs} -> ${updated.fullRetrainEpochs}")
            }
            if (previous.diceWeight != updated.diceWeight || previous.focalWeight != updated.focalWeight) {
                add("loss dice/focal ${format(previous.diceWeight)}/${format(previous.focalWeight)} -> ${format(updated.diceWeight)}/${format(updated.focalWeight)}")
            }
        }
        return "样本 $sampleCount，" + changes.joinToString("，")
    }

    private fun format(value: Double): String = "%.3f".format(java.util.Locale.US, value)
}

fun loadLearningConfig(): LearningConfig {
    val file = learningConfigFile()
    if (!file.exists()) {
        val defaults = LearningConfig().sanitized()
        saveLearningConfig(defaults, backup = false)
        return defaults
    }
    val text = runCatching { Files.readString(file, Charsets.UTF_8) }.getOrDefault("")
    return LearningConfig(
        revision = jsonInt(text, "revision", 1),
        fullRetrainEpochs = jsonInt(text, "fullRetrainEpochs", 4),
        recentFineTuneEpochs = jsonInt(text, "recentFineTuneEpochs", 2),
        fullBatch = jsonInt(text, "fullBatch", 2),
        recentBatch = jsonInt(text, "recentBatch", 1),
        learningRate = jsonDouble(text, "learningRate", 0.002),
        fineTuneLearningRate = jsonDouble(text, "fineTuneLearningRate", 0.0008),
        bceWeight = jsonDouble(text, "bceWeight", 0.55),
        diceWeight = jsonDouble(text, "diceWeight", 0.30),
        focalWeight = jsonDouble(text, "focalWeight", 0.15),
        scoreThreshold = jsonDouble(text, "scoreThreshold", 0.18),
        maskThreshold = jsonDouble(text, "maskThreshold", 0.28),
        adaptiveMinThreshold = jsonDouble(text, "adaptiveMinThreshold", 0.08),
        adaptiveQuantile = jsonDouble(text, "adaptiveQuantile", 0.985),
        adaptiveMaxProbabilityScale = jsonDouble(text, "adaptiveMaxProbabilityScale", 0.72),
        adaptiveFallbackScale = jsonDouble(text, "adaptiveFallbackScale", 0.55),
        minComponentPixels = jsonInt(text, "minComponentPixels", 12),
        minAlpha = jsonInt(text, "minAlpha", 180),
        feedbackSamplesSeen = jsonInt(text, "feedbackSamplesSeen", 0)
    ).sanitized()
}

fun evolveLearningConfigAfterFeedback(): LearningConfigEvolution? {
    val sampleCount = userFeedbackSampleCount()
    if (sampleCount <= 0) return null
    val current = loadLearningConfig()
    if (sampleCount <= current.feedbackSamplesSeen) return null
    val evolved = evolveLearningConfig(current, sampleCount)
    if (evolved == current) return null
    saveLearningConfig(evolved, backup = true)
    return LearningConfigEvolution(current, evolved, sampleCount)
}

fun evolveLearningConfig(current: LearningConfig, sampleCount: Int): LearningConfig {
    var next = current.copy(feedbackSamplesSeen = sampleCount, revision = current.revision + 1)
    val unseen = max(1, sampleCount - current.feedbackSamplesSeen)
    if (sampleCount >= 3) {
        val step = min(0.02, 0.004 * unseen)
        next = next.copy(
            maskThreshold = (next.maskThreshold - step).coerceIn(0.20, 0.34),
            adaptiveMaxProbabilityScale = (next.adaptiveMaxProbabilityScale - 0.01).coerceIn(0.60, 0.85),
            adaptiveFallbackScale = (next.adaptiveFallbackScale - 0.005).coerceIn(0.45, 0.70),
            minComponentPixels = (next.minComponentPixels - 1).coerceIn(6, 24),
            diceWeight = (next.diceWeight + 0.01).coerceIn(0.15, 0.60),
            focalWeight = (next.focalWeight + 0.005).coerceIn(0.05, 0.35)
        )
    }
    if (sampleCount >= 6) {
        next = next.copy(scoreThreshold = (next.scoreThreshold - 0.003 * unseen).coerceIn(0.12, 0.24))
    }
    next = next.copy(
        recentFineTuneEpochs = (2 + sampleCount / 6).coerceIn(2, 5),
        fullRetrainEpochs = (4 + sampleCount / 8).coerceIn(4, 10)
    )
    return next.sanitized()
}

fun saveLearningConfig(config: LearningConfig, backup: Boolean = true) {
    val file = learningConfigFile()
    Files.createDirectories(file.parent)
    if (backup && file.exists()) {
        Files.copy(file, file.parent.resolve("learning-config.previous.json"), StandardCopyOption.REPLACE_EXISTING)
    }
    Files.writeString(file, config.toJson(), Charsets.UTF_8)
}

private fun learningConfigFile() = AppRuntimeFiles.pythonDir.resolve("model").resolve("learning-config.json")

private fun userFeedbackSampleCount(): Int {
    val manifest = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("user_feedback").resolve("annotations.jsonl")
    if (!manifest.exists()) return 0
    return runCatching { Files.readAllLines(manifest, Charsets.UTF_8).count { it.isNotBlank() } }.getOrDefault(0)
}

private fun LearningConfig.toJson(): String = """
{
  "revision": $revision,
  "fullRetrainEpochs": $fullRetrainEpochs,
  "recentFineTuneEpochs": $recentFineTuneEpochs,
  "fullBatch": $fullBatch,
  "recentBatch": $recentBatch,
  "learningRate": ${jsonNumber(learningRate)},
  "fineTuneLearningRate": ${jsonNumber(fineTuneLearningRate)},
  "bceWeight": ${jsonNumber(bceWeight)},
  "diceWeight": ${jsonNumber(diceWeight)},
  "focalWeight": ${jsonNumber(focalWeight)},
  "scoreThreshold": ${jsonNumber(scoreThreshold)},
  "maskThreshold": ${jsonNumber(maskThreshold)},
  "adaptiveMinThreshold": ${jsonNumber(adaptiveMinThreshold)},
  "adaptiveQuantile": ${jsonNumber(adaptiveQuantile)},
  "adaptiveMaxProbabilityScale": ${jsonNumber(adaptiveMaxProbabilityScale)},
  "adaptiveFallbackScale": ${jsonNumber(adaptiveFallbackScale)},
  "minComponentPixels": $minComponentPixels,
  "minAlpha": $minAlpha,
  "feedbackSamplesSeen": $feedbackSamplesSeen
}
""".trimIndent() + "\n"

private fun jsonInt(text: String, key: String, default: Int): Int =
    Regex(""""$key"\s*:\s*(-?\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: default

private fun jsonDouble(text: String, key: String, default: Double): Double =
    Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: default

private fun jsonNumber(value: Double): String = "%.6f".format(java.util.Locale.US, value)

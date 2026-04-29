package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppControllerSupportTest {
    @Test
    fun buildTrainingJsonLineWritesOnlyMaskFirstInstances() {
        val line = buildTrainingJsonLine(
            imagePath = "images/sample.png",
            imageHash = "hash-1",
            regions = listOf(
                CropRegion(
                    id = "1",
                    x = 4,
                    y = 6,
                    width = 2,
                    height = 2,
                    maskWidth = 2,
                    maskHeight = 2,
                    alphaMask = listOf(255, 0, 220, 255)
                )
            )
        )

        val root = parseJsonObject(line)
        assertNotNull(root)
        assertEquals(null, root["regions"])
        val instance = root["instances"]?.asArray()?.values?.firstOrNull()?.asObject()
        assertNotNull(instance)
        assertEquals(null, instance["points"])
        assertEquals(null, instance["holes"])
        assertEquals(2, instance["maskWidth"]?.asInt())
        assertEquals(4, instance["alphaMask"]?.asArray()?.values?.size)
        assertEquals("images/sample.png", root["image"]?.asString())
    }

    @Test
    fun buildTrainingJsonLineIncludesLearningMetadataWhenProvided() {
        val line = buildTrainingJsonLine(
            imagePath = "images/sample.png",
            imageHash = "hash-1",
            regions = listOf(CropRegion(id = "1", x = 0, y = 0, width = 1, height = 1)),
            metadata = mapOf("learningMode" to "confirmed_manual_edit", "regionCount" to "1")
        )

        val root = parseJsonObject(line)
        assertNotNull(root)
        val metadata = root["metadata"]?.asObject()
        assertNotNull(metadata)
        assertEquals("confirmed_manual_edit", metadata["learningMode"]?.asString())
        assertEquals("1", metadata["regionCount"]?.asString())
    }

    @Test
    fun candidateModelValidationRequiresFilesAndForegroundStats() {
        val dir = Files.createTempDirectory("workflowtool-candidate-model")
        Files.writeString(dir.resolve("model.pt"), "weights")
        Files.writeString(dir.resolve("model.onnx"), "onnx")
        Files.writeString(
            dir.resolve("model.json"),
            """{"training":{"validation":{"maxProbability":0.24,"predictedForegroundRatio":0.001}}}"""
        )

        assertTrue(candidateModelLooksUsable(dir))

        Files.writeString(
            dir.resolve("model.json"),
            """{"training":{"validation":{"maxProbability":0.04,"predictedForegroundRatio":0.0}}}"""
        )
        assertFalse(candidateModelLooksUsable(dir))
    }

    @Test
    fun learningConfigEvolvesConservativelyAfterFeedback() {
        val base = LearningConfig()

        val evolved = evolveLearningConfig(base, sampleCount = 6)

        assertTrue(evolved.maskThreshold < base.maskThreshold)
        assertTrue(evolved.scoreThreshold < base.scoreThreshold)
        assertTrue(evolved.recentFineTuneEpochs >= base.recentFineTuneEpochs)
        assertTrue(evolved.fullRetrainEpochs >= base.fullRetrainEpochs)
        assertTrue(evolved.minComponentPixels < base.minComponentPixels)
    }

    @Test
    fun learningConfigSanitizesUnsafeValues() {
        val config = LearningConfig(
            fullRetrainEpochs = 100,
            recentFineTuneEpochs = 100,
            learningRate = 1.0,
            scoreThreshold = 0.01,
            maskThreshold = 0.99,
            minComponentPixels = 1,
            minAlpha = 255,
        ).sanitized()

        assertEquals(10, config.fullRetrainEpochs)
        assertEquals(5, config.recentFineTuneEpochs)
        assertEquals(0.006, config.learningRate)
        assertEquals(0.12, config.scoreThreshold)
        assertEquals(0.34, config.maskThreshold)
        assertEquals(6, config.minComponentPixels)
        assertEquals(220, config.minAlpha)
    }

    @Test
    fun bundledPythonAssetsIncludeSeedManifestEntriesWithoutGeneratedOutputs() {
        val assets = AppRuntimeFiles.bundledPythonAssetsForTest()

        assertTrue("seed_image_annotations.py" in assets)
        assertTrue("seed_images/open_icon_samples/png/bell.svg.png" in assets)
        assertTrue("seed_images/open_icon_samples/sheets/open_icon_sheet_1.png" in assets)
        assertFalse(assets.any { it.endsWith(".svg") })
        assertFalse(assets.any { it.startsWith("training_sets/combined/") })
        assertFalse(assets.any { it.startsWith("training_sets/recent_feedback/") })
    }
}

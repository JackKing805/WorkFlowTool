package io.github.workflowtool.application

import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.hasMask
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PythonDetectorBridgeTest {
    @Test
    fun parsesMaskFirstDetectionPayload() {
        val result = PythonDetectorBridge.parseDetectionResult(
            """
            {
              "mode":"solid_background",
              "regions":[
                {
                  "bbox":{"x":8,"y":10,"width":2,"height":2},
                  "maskWidth":2,
                  "maskHeight":2,
                  "alphaMask":[255,0,220,255],
                  "score":0.875
                }
              ],
              "stats":{
                "estimatedBackgroundArgb":-1234567,
                "candidatePixels":128,
                "connectedComponents":1,
                "regionCount":1,
                "backgroundSampleCount":24,
                "totalTimeMs":9,
                "backend":"mask_rcnn_onnx",
                "maxProbability":0.91,
                "meanProbability":0.12,
                "effectiveMaskThreshold":0.28,
                "thresholdStrategy":"adaptive"
              }
            }
            """.trimIndent()
        )

        assertNotNull(result)
        val region = result.regions.first()
        assertTrue(region.hasMask())
        assertEquals(4, region.alphaMask.size)
        assertTrue(region.alphaMask.count { it > 0 } < region.width * region.height)
        assertEquals(0.875f, region.score)
        assertEquals(128, result.stats.candidatePixels)
        assertEquals("mask_rcnn_onnx", result.stats.backend)
    }

    @Test
    fun ignoresLegacyPayloadWithoutMask() {
        val result = PythonDetectorBridge.parseDetectionResult(
            """{"mode":"solid_background","regions":[{"bbox":{"x":8,"y":10,"width":14,"height":16}}]}"""
        )

        assertNotNull(result)
        assertEquals(0, result.regions.size)
    }

    @Test
    fun detectsRegionsThroughPythonScriptWhenAvailable() {
        if (!runPythonDetectorIntegrationTests()) return

        val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF2, 0xF3, 0xF5)
        graphics.fillRect(0, 0, 48, 48)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(8, 8, 14, 14)
        graphics.color = Color(0x14, 0x2D, 0x55)
        graphics.fillRect(30, 10, 10, 18)
        graphics.dispose()

        val result = PythonDetectorBridge.detect(
            image,
            DetectionConfig(
                minWidth = 6,
                minHeight = 6,
                minPixelArea = 16,
                bboxPadding = 0,
                colorDistanceThreshold = 24,
                mergeNearbyRegions = false
            )
        )

        assertNotNull(result)
        assertTrue(result.regions.size >= 2)
        assertTrue(result.regions.all { it.hasMask() })
        assertEquals("mask_rcnn_onnx", result.stats.backend)
    }

    @Test
    fun separatesForegroundObjectsFromStudioLikeBackground() {
        if (!runPythonDetectorIntegrationTests()) return

        val image = BufferedImage(96, 72, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF7, 0xF7, 0xF7)
        graphics.fillRect(0, 0, 96, 72)
        graphics.color = Color(0xE9, 0xEA, 0xEC)
        graphics.fillRect(0, 54, 96, 18)
        graphics.color = Color(0xD4, 0xD7, 0xDB)
        graphics.fillRect(11, 39, 22, 8)
        graphics.fillRect(57, 41, 18, 8)
        graphics.color = Color(0x25, 0x2C, 0x38)
        graphics.fillRect(14, 12, 18, 28)
        graphics.color = Color(0xD7, 0x8F, 0x4D)
        graphics.fillRect(60, 8, 14, 34)
        graphics.dispose()

        val result = PythonDetectorBridge.detect(
            image,
            DetectionConfig(
                minWidth = 8,
                minHeight = 8,
                minPixelArea = 40,
                bboxPadding = 0,
                colorDistanceThreshold = 18,
                backgroundTolerance = 18,
                mergeNearbyRegions = false
            )
        )

        assertNotNull(result)
        assertTrue(result.regions.size >= 2)
        assertTrue(result.regions.none { it.width >= 90 && it.height <= 20 })
        assertTrue(result.regions.any { it.x <= 16 && it.width <= 24 && it.height >= 24 })
        assertTrue(result.regions.any { it.x >= 56 && it.width <= 20 && it.height >= 28 })
    }

    @Test
    fun detectsAllActionFramesInTestSpriteSheet() {
        if (!runPythonDetectorIntegrationTests()) return
        val file = File("test.png")
        if (!file.exists()) return

        val result = PythonDetectorBridge.detect(
            ImageIO.read(file),
            DetectionConfig(
                minWidth = 30,
                minHeight = 30,
                minPixelArea = 500,
                bboxPadding = 4,
                colorDistanceThreshold = 36
            )
        )

        assertNotNull(result)
        assertEquals(18, result.regions.size)
        assertTrue(result.regions.all { it.width >= 100 && it.height >= 100 && it.hasMask() })
    }

    private fun runPythonDetectorIntegrationTests(): Boolean {
        return System.getenv("WORKFLOWTOOL_RUN_PYTHON_DETECTOR_INTEGRATION")
            ?.trim()
            ?.lowercase()
            ?.let { it == "1" || it == "true" || it == "yes" }
            ?: false
    }
}

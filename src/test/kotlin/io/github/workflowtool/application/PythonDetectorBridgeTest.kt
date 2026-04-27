package io.github.workflowtool.application

import java.awt.Color
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.workflowtool.model.DetectionConfig

class PythonDetectorBridgeTest {
    @Test
    fun parsesPolygonAwareDetectionPayload() {
        val result = PythonDetectorBridge.parseDetectionResult(
            """
            {
              "mode":"solid_background",
              "regions":[
                {
                  "bbox":{"x":8,"y":10,"width":14,"height":16},
                  "points":[
                    {"x":8,"y":10},
                    {"x":22,"y":10},
                    {"x":22,"y":26},
                    {"x":8,"y":26}
                  ],
                  "score":0.875
                }
              ],
              "stats":{
                "estimatedBackgroundArgb":-1234567,
                "candidatePixels":128,
                "connectedComponents":1,
                "regionCount":1,
                "backgroundSampleCount":24,
                "totalTimeMs":9
              }
            }
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals(1, result.regions.size)
        assertEquals(8, result.regions.first().x)
        assertEquals(16, result.regions.first().height)
        assertEquals(4, result.regions.first().points.size)
        assertEquals(0.875f, result.regions.first().score)
        assertEquals(128, result.stats.candidatePixels)
    }

    @Test
    fun detectsRegionsThroughPythonScriptWhenAvailable() {
        if (!PythonDetectorBridge.isAvailable) return

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
    }

    @Test
    fun detectsAllActionFramesInTestSpriteSheet() {
        if (!PythonDetectorBridge.isAvailable) return
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
        assertTrue(result.regions.all { it.width >= 100 && it.height >= 100 })
    }
}

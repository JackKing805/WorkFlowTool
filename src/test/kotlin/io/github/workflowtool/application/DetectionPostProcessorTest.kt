package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.resolveRegionGroup
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectionPostProcessorTest {
    @Test
    fun tightensLooseDetectionBoundsAroundForeground() {
        val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF2, 0xF3, 0xF5)
        graphics.fillRect(0, 0, 40, 40)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(10, 10, 10, 10)
        graphics.dispose()

        val result = postProcessDetection(
            image = image,
            config = DetectionConfig(bboxPadding = 1, minPixelArea = 16),
            result = DetectionResult(
                regions = listOf(CropRegion("1", 5, 5, 20, 20)),
                mode = DetectionMode.SOLID_BACKGROUND,
                stats = DetectionStats(0xFFF2F3F5.toInt(), 100, 1, 1, 0, 1)
            )
        )

        assertEquals(1, result.regions.size)
        assertEquals(9, result.regions.first().x)
        assertEquals(9, result.regions.first().y)
        assertEquals(12, result.regions.first().width)
        assertEquals(12, result.regions.first().height)
    }

    @Test
    fun filtersThinLowDensityNoiseRegions() {
        val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF2, 0xF3, 0xF5)
        graphics.fillRect(0, 0, 48, 48)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(8, 8, 18, 1)
        graphics.dispose()

        val result = postProcessDetection(
            image = image,
            config = DetectionConfig(minPixelArea = 24),
            result = DetectionResult(
                regions = listOf(CropRegion("1", 7, 7, 22, 4)),
                mode = DetectionMode.SOLID_BACKGROUND,
                stats = DetectionStats(0xFFF2F3F5.toInt(), 18, 1, 1, 0, 1)
            )
        )

        assertTrue(result.regions.isEmpty())
        assertEquals(0, result.stats.regionCount)
    }

    @Test
    fun detectsInteriorBackgroundCutoutAsHoleRegion() {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val background = Color(0x57, 0xD7, 0x45)
        val frame = Color(0x4A, 0x2B, 0x1E)
        val graphics = image.createGraphics()
        graphics.color = background
        graphics.fillRect(0, 0, 32, 32)
        graphics.color = frame
        graphics.fillRect(6, 6, 20, 3)
        graphics.fillRect(6, 23, 20, 3)
        graphics.fillRect(6, 6, 3, 20)
        graphics.fillRect(23, 6, 3, 20)
        graphics.dispose()

        val result = postProcessDetection(
            image = image,
            config = DetectionConfig(minPixelArea = 24, colorDistanceThreshold = 24, bboxPadding = 0),
            result = DetectionResult(
                regions = listOf(CropRegion("outer", 6, 6, 20, 20)),
                mode = DetectionMode.SOLID_BACKGROUND,
                stats = DetectionStats(background.rgb, 192, 1, 1, 0, 1)
            )
        )

        assertEquals(2, result.regions.size)
        val group = resolveRegionGroup(result.regions, "outer")
        assertEquals("outer", group?.root?.id)
        assertEquals(1, group?.holes?.size)
        val hole = group!!.holes.first()
        assertEquals(9, hole.x)
        assertEquals(9, hole.y)
        assertEquals(14, hole.width)
        assertEquals(14, hole.height)
    }
}

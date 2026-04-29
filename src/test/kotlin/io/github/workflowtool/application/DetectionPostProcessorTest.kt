package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.model.maskAlphaAt
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
    fun keepsInteriorBackgroundCutoutTransparentInMask() {
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

        assertEquals(1, result.regions.size)
        val region = result.regions.first()
        assertEquals("outer", region.id)
        assertTrue(region.hasMask())
        assertEquals(0, region.maskAlphaAt(16, 16))
        assertTrue(region.maskAlphaAt(7, 7) > 0)
    }

    @Test
    fun preservesMaskFirstPayloadWithoutRecomputingFullBoxMask() {
        val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF2, 0xF3, 0xF5)
        graphics.fillRect(0, 0, 24, 24)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(6, 6, 8, 8)
        graphics.dispose()

        val suppliedMask = listOf(
            255, 255, 255, 255,
            255, 0, 0, 255,
            255, 0, 0, 255,
            255, 255, 255, 255
        )
        val result = postProcessDetection(
            image = image,
            config = DetectionConfig(minWidth = 2, minHeight = 2, minPixelArea = 4, bboxPadding = 0),
            result = DetectionResult(
                regions = listOf(
                    CropRegion(
                        id = "mask",
                        x = 7,
                        y = 7,
                        width = 4,
                        height = 4,
                        maskWidth = 4,
                        maskHeight = 4,
                        alphaMask = suppliedMask
                    )
                ),
                mode = DetectionMode.SOLID_BACKGROUND,
                stats = DetectionStats(0xFFF2F3F5.toInt(), 16, 1, 1, 0, 1)
            )
        )

        assertEquals(1, result.regions.size)
        val region = result.regions.first()
        assertTrue(region.hasMask())
        assertEquals(0, region.maskAlphaAt(8, 8))
        assertTrue(region.maskAlphaAt(7, 7) > 0)
        assertTrue(region.maskAlphaAt(10, 10) > 0)
    }

    @Test
    fun constrainedRefineKeepsUserCutoutWhileAllowingSmallOuterExpansion() {
        val image = BufferedImage(48, 24, BufferedImage.TYPE_INT_ARGB)
        val userMask = MutableList(16 * 8) { 0 }
        for (y in 0 until 8) {
            for (x in 0 until 5) userMask[y * 16 + x] = 255
            for (x in 11 until 16) userMask[y * 16 + x] = 255
        }
        val candidateMask = MutableList(20 * 8) { 255 }

        val result = constrainedRefineUserRegion(
            image = image,
            region = CropRegion(
                id = "manual",
                x = 12,
                y = 6,
                width = 16,
                height = 8,
                maskWidth = 16,
                maskHeight = 8,
                alphaMask = userMask
            ),
            candidate = CropRegion(
                id = "candidate",
                x = 10,
                y = 6,
                width = 20,
                height = 8,
                maskWidth = 20,
                maskHeight = 8,
                alphaMask = candidateMask
            ),
            config = DetectionConfig(manualRefineExpansionRadius = 3, manualRefineConflictTolerance = 0.45),
            backgroundArgb = 0,
            mode = DetectionMode.FALLBACK_BACKGROUND
        )

        assertEquals(10, result?.x)
        assertEquals(6, result?.y)
        assertEquals(20, result?.width)
        assertEquals(8, result?.height)
        assertEquals(0, result?.maskAlphaAt(18, 10))
        assertTrue((result?.maskAlphaAt(10, 10) ?: 0) > 0)
        assertTrue((result?.maskAlphaAt(29, 10) ?: 0) > 0)
    }

}

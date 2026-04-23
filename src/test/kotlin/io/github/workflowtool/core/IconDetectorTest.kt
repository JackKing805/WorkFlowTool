package io.github.workflowtool.core

import io.github.workflowtool.model.DetectionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.awt.Color
import java.awt.image.BufferedImage

class IconDetectorTest {
    @Test
    fun detectsSeparateTransparentIcons() {
        val image = BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(5, 5, 12, 12)
        graphics.fillRect(50, 10, 16, 16)
        graphics.dispose()

        val regions = IconDetector().detect(image, DetectionConfig(minWidth = 4, minHeight = 4))

        assertEquals(2, regions.size)
        assertEquals(5, regions[0].x)
        assertEquals(50, regions[1].x)
    }

    @Test
    fun mergesNearbyRegionsWhenGapAllows() {
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(4, 4, 8, 8)
        graphics.fillRect(15, 4, 8, 8)
        graphics.dispose()

        val regions = IconDetector().detect(
            image,
            DetectionConfig(minWidth = 2, minHeight = 2, gapThreshold = 4, mergeNearbyRegions = true)
        )

        assertEquals(1, regions.size)
        assertTrue(regions.single().width >= 19)
    }
}


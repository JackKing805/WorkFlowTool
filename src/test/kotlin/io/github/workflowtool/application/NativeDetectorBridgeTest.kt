package io.github.workflowtool.application

import com.sun.jna.Memory
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.GridConfig
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeDetectorBridgeTest {
    @Test
    fun mapsNativeResultIntoDomainResult() {
        val regionSize = NativeRegion().size()
        val regionMemory = Memory((regionSize * 2).toLong())

        NativeRegion(regionMemory.share(0)).apply {
            x = 4
            y = 6
            width = 12
            height = 14
            visible = 1
            selected = 0
            write()
        }
        NativeRegion(regionMemory.share(regionSize.toLong())).apply {
            x = 30
            y = 10
            width = 16
            height = 18
            visible = 0
            selected = 1
            write()
        }

        val nativeResult = NativeDetectionResult().apply {
            mode = 1
            regionCount = 2
            regions = regionMemory
            stats = NativeDetectionStats().apply {
                estimatedBackgroundArgb = 0xFF111111.toInt()
                candidatePixels = 240
                connectedComponents = 2
                regionCount = 2
                backgroundSampleCount = 40
                totalTimeMs = com.sun.jna.NativeLong(8)
            }
            write()
        }

        val result = nativeResult.toDomainResult()

        assertEquals(DetectionMode.SOLID_BACKGROUND, result.mode)
        assertEquals(2, result.regions.size)
        assertEquals(4, result.regions[0].x)
        assertEquals(30, result.regions[1].x)
        assertFalse(result.regions[1].visible)
        assertTrue(result.regions[1].selected)
        assertEquals(240, result.stats.candidatePixels)
    }

    @Test
    fun mapsUnknownModeToFallback() {
        assertEquals(DetectionMode.FALLBACK_BACKGROUND, detectionModeFromNative(99))
    }

    @Test
    fun cppBridgeDetectsOpaqueIconsWhenLibraryIsPresent() {
        if (!CppDetectorBridge.isLoaded) return

        val image = BufferedImage(72, 44, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF2, 0xF3, 0xF5)
        graphics.fillRect(0, 0, 72, 44)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(6, 6, 18, 18)
        graphics.color = Color(0x14, 0x2D, 0x55)
        graphics.fillRect(40, 8, 20, 22)
        graphics.dispose()

        val result = CppDetectorBridge.detect(
            image,
            DetectionConfig(minWidth = 8, minHeight = 8, bboxPadding = 0)
        )

        assertNotNull(result)
        assertEquals(DetectionMode.SOLID_BACKGROUND, result.mode)
        assertEquals(2, result.regions.size)
        assertTrue(result.regions.any { it.x == 6 && it.y == 6 })
        assertTrue(result.regions.any { it.x == 40 && it.y == 8 })
    }

    @Test
    fun cppBridgeDetectsMagicSelectionWhenLibraryIsPresent() {
        if (!CppDetectorBridge.isLoaded) return

        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF3, 0xF4, 0xF6)
        graphics.fillRect(0, 0, 32, 32)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(5, 5, 10, 10)
        graphics.dispose()

        val result = CppDetectorBridge.detectMagicRegion(
            image,
            7,
            7,
            DetectionConfig(minWidth = 4, minHeight = 4, minPixelArea = 8, bboxPadding = 0)
        )

        assertNotNull(result)
        assertEquals(5, result.region.x)
        assertEquals(5, result.region.y)
        assertEquals(10, result.region.width)
        assertEquals(10, result.region.height)
        assertEquals(32 * 32, result.mask.size)
        assertTrue(result.pixelCount >= 100)
    }

    @Test
    fun cppBridgeUsesManualBackgroundWhenProvided() {
        if (!CppDetectorBridge.isLoaded) return

        val image = BufferedImage(72, 44, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF2, 0xF3, 0xF5)
        graphics.fillRect(0, 0, 72, 44)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(6, 6, 18, 18)
        graphics.dispose()

        val result = CppDetectorBridge.detect(
            image,
            DetectionConfig(
                minWidth = 8,
                minHeight = 8,
                bboxPadding = 0,
                useManualBackground = true,
                manualBackgroundArgb = 0xFFF2F3F5.toInt()
            )
        )

        assertNotNull(result)
        assertEquals(DetectionMode.SOLID_BACKGROUND, result.mode)
        assertEquals(0xFFF2F3F5.toInt(), result.stats.estimatedBackgroundArgb)
        assertEquals(1, result.regions.size)
        assertEquals(6, result.regions.first().x)
    }
}

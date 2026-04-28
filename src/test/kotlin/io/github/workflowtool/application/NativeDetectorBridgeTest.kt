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
    fun ignoresNativePolygonCompatibilityFields() {
        val regionSize = NativeRegion().size()
        val pointSize = NativePoint().size()
        val regionMemory = Memory(regionSize.toLong())
        val pointsMemory = Memory((pointSize * 4).toLong())

        listOf(4 to 6, 16 to 6, 16 to 20, 4 to 20).forEachIndexed { index, (x, y) ->
            NativePoint(pointsMemory.share(index.toLong() * pointSize)).apply {
                this.x = x
                this.y = y
                write()
            }
        }

        NativeRegion(regionMemory.share(0)).apply {
            x = 4
            y = 6
            width = 12
            height = 14
            visible = 1
            selected = 1
            pointCount = 4
            points = pointsMemory
            score = 0.91f
            write()
        }

        val nativeResult = NativeDetectionResult().apply {
            mode = 1
            regionCount = 1
            regions = regionMemory
            stats = NativeDetectionStats().apply { write() }
            write()
        }

        val result = nativeResult.toDomainResult()

        assertEquals(1, result.regions.size)
        assertEquals(4, result.regions.first().x)
        assertEquals(12, result.regions.first().width)
        assertEquals(0.91f, result.regions.first().score)
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

package io.github.workflowtool.application

import androidx.compose.ui.geometry.Size
import io.github.workflowtool.domain.RegionDetector
import io.github.workflowtool.domain.RegionExporter
import io.github.workflowtool.domain.RegionSplitter
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.ExportResult
import io.github.workflowtool.model.GridConfig
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppControllerTest {
    @Test
    fun nativeUnavailableDoesNotBlockInjectedDetectors() {
        val detector = CountingDetector()
        val splitter = CountingSplitter()
        val controller = AppController(
            detector = detector,
            splitter = splitter,
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            nativeEngine = UnavailableNativeImageEngine
        )

        controller.loadFile(createImageFile())
        controller.rebuildFromAuto(logResult = true)
        controller.rebuildFromSmartGrid(logResult = true)

        assertTrue(detector.calls >= 1)
        assertEquals(1, splitter.calls)
    }

    @Test
    fun fitSelectionCentersSelectedRegion() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createImageFile(width = 200, height = 100))
        controller.updateViewportSize(Size(400f, 300f))
        controller.replaceRegions(
            "娴嬭瘯",
            listOf(
                CropRegion("1", 10, 10, 20, 20),
                CropRegion("2", 120, 20, 40, 30, selected = true)
            )
        )

        controller.fitSelectionToViewport()

        assertTrue(controller.zoom > 1f)
        assertEquals("120, 20, 40 x 30", controller.selectedRegionLabel)
    }

    @Test
    fun viewportCanPanBeyondImageBounds() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createImageFile(width = 64, height = 32))
        controller.updateViewportSize(Size(400f, 300f))
        controller.panViewport(androidx.compose.ui.geometry.Offset(1200f, -900f))

        assertEquals(1200f, controller.viewportOffset.x)
        assertEquals(-900f, controller.viewportOffset.y)
    }

    @Test
    fun redoRestoresUndoAvailability() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.replaceRegions("first", listOf(CropRegion("1", 1, 1, 10, 10)))
        controller.undo()
        controller.redo()

        assertTrue(controller.canUndo)
        assertEquals(1, controller.regions.size)
    }

    @Test
    fun magicSelectionCreatesRegionFromClickedArea() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createMagicImageFile())
        controller.applyMagicSelection(androidx.compose.ui.geometry.Offset(7f, 7f))

        val selected = controller.selectedRegion
        assertNotNull(selected)
        assertEquals(4, selected.x)
        assertEquals(4, selected.y)
        assertEquals(12, selected.width)
        assertEquals(12, selected.height)
    }

    @Test
    fun magicToleranceRefreshesCurrentMagicSelection() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createToleranceImageFile())
        controller.enterMagicSelectionMode()
        controller.updateMagicTolerance(8)
        controller.applyMagicSelection(androidx.compose.ui.geometry.Offset(7f, 7f))
        val narrow = controller.selectedRegion
        assertNotNull(narrow)
        assertEquals(12, narrow.width)

        controller.updateMagicTolerance(40)
        val wide = controller.selectedRegion
        assertNotNull(wide)
        assertTrue(wide.width > narrow.width)
        assertEquals(1, controller.regions.size)
    }

    @Test
    fun clickingInsideMagicRegionRefreshesInsteadOfCreatingNewRegion() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createToleranceImageFile())
        controller.enterMagicSelectionMode()
        controller.updateMagicTolerance(8)
        controller.applyMagicSelection(androidx.compose.ui.geometry.Offset(7f, 7f))

        val first = controller.selectedRegion
        assertNotNull(first)
        assertEquals(1, controller.regions.size)

        controller.updateMagicTolerance(40)
        controller.applyMagicSelection(androidx.compose.ui.geometry.Offset(8f, 8f))

        val refreshed = controller.selectedRegion
        assertNotNull(refreshed)
        assertEquals(1, controller.regions.size)
        assertEquals(first.id, refreshed.id)
        assertTrue(refreshed.width > first.width)
    }

    @Test
    fun magicSelectionReplacesExistingHitRegionInsteadOfCreatingNewRegion() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createToleranceImageFile())
        controller.enterMagicSelectionMode()
        controller.updateMagicTolerance(8)
        controller.replaceRegions(
            "test",
            listOf(CropRegion("1", 5, 5, 4, 4, selected = true))
        )

        controller.applyMagicSelection(androidx.compose.ui.geometry.Offset(7f, 7f))

        val refreshed = controller.selectedRegion
        assertNotNull(refreshed)
        assertEquals(1, controller.regions.size)
        assertEquals("1", refreshed.id)
        assertTrue(refreshed.width > 4)
        assertTrue(refreshed.height > 4)
    }

    @Test
    fun draggingMagicSelectionAddsDifferentColorRegionToCurrentSelection() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createToleranceImageFile())
        controller.enterMagicSelectionMode()
        controller.updateMagicTolerance(8)
        controller.applyMagicSelection(androidx.compose.ui.geometry.Offset(7f, 7f))
        val first = controller.selectedRegion
        assertNotNull(first)

        controller.extendMagicSelection(androidx.compose.ui.geometry.Offset(17f, 7f))

        val merged = controller.selectedRegion
        assertNotNull(merged)
        assertEquals(1, controller.regions.size)
        assertEquals(first.id, merged.id)
        assertTrue(merged.width > first.width)
        assertTrue(controller.magicSelectionPreview?.pixelCount ?: 0 > first.width * first.height)
    }

    @Test
    fun magicSelectionIgnoresBackgroundLikeEdgeColor() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createMagicImageFile())
        controller.applyMagicSelection(androidx.compose.ui.geometry.Offset(1f, 1f))

        assertEquals(null, controller.selectedRegion)
    }

    @Test
    fun magicSelectionConnectsDiagonalPixels() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createDiagonalMagicImageFile())
        controller.applyMagicSelection(androidx.compose.ui.geometry.Offset(5f, 5f))

        val selected = controller.selectedRegion
        assertNotNull(selected)
        assertEquals(4, selected.x)
        assertEquals(4, selected.y)
        assertEquals(14, selected.width)
        assertEquals(14, selected.height)
    }

    @Test
    fun exportPreviewRegionWritesSingleRegionImage() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createImageFile(width = 32, height = 24))
        controller.replaceRegions(
            "test",
            listOf(CropRegion("1", 4, 4, 12, 12, selected = true))
        )
        val outputDir = Files.createTempDirectory("workflowtool-preview-export")
        val output = outputDir.resolve("preview.png")

        controller.exportPreviewRegion("1", output)

        assertTrue(Files.exists(output))
        val exported = ImageIO.read(output.toFile())
        assertEquals(12, exported.width)
        assertEquals(12, exported.height)
    }

    @Test
    fun sampledBackgroundColorDrivesAutoDetection() {
        val detector = CountingDetector()
        val controller = AppController(
            detector = detector,
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            nativeEngine = AvailableNativeImageEngine
        )

        controller.loadFile(createManualBackgroundImageFile())
        assertEquals(1, detector.calls)

        controller.sampleBackgroundAt(androidx.compose.ui.geometry.Offset(1f, 1f))

        assertEquals(2, detector.calls)
        assertEquals("#FFF2F3F5", controller.activeBackgroundLabel)
        assertTrue(detector.lastConfig.useManualBackground)
        assertEquals(0xFFF2F3F5.toInt(), detector.lastConfig.manualBackgroundArgb)
    }

    @Test
    fun loadingImageClampsDetectedRegionPointsIntoImageBounds() {
        val controller = AppController(
            detector = StaticDetector(
                listOf(
                    CropRegion(
                        id = "raw",
                        x = -10,
                        y = -12,
                        width = 100,
                        height = 80,
                        points = listOf(
                            io.github.workflowtool.model.RegionPoint(-10, -12),
                            io.github.workflowtool.model.RegionPoint(100, -12),
                            io.github.workflowtool.model.RegionPoint(100, 80),
                            io.github.workflowtool.model.RegionPoint(-10, 80)
                        )
                    )
                )
            ),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createImageFile(width = 32, height = 24))

        val region = controller.regions.single()
        assertEquals(0, region.x)
        assertEquals(0, region.y)
        assertEquals(32, region.width)
        assertEquals(24, region.height)
        assertTrue(region.points.all { it.x in 0..32 && it.y in 0..24 })
    }

    private fun createImageFile(width: Int = 64, height: Int = 32): java.io.File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(4, 4, 12, 12)
        graphics.dispose()
        val file = Files.createTempFile("workflowtool-controller", ".png").toFile()
        ImageIO.write(image, "png", file)
        return file
    }

    private fun createMagicImageFile(): java.io.File {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF3, 0xF4, 0xF6)
        graphics.fillRect(0, 0, 32, 32)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(5, 5, 10, 10)
        graphics.dispose()
        val file = Files.createTempFile("workflowtool-magic", ".png").toFile()
        ImageIO.write(image, "png", file)
        return file
    }

    private fun createToleranceImageFile(): java.io.File {
        val image = BufferedImage(40, 24, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF5, 0xF5, 0xF5)
        graphics.fillRect(0, 0, 40, 24)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(5, 5, 10, 10)
        graphics.color = Color(0x36, 0x8B, 0xFF)
        graphics.fillRect(15, 5, 6, 10)
        graphics.dispose()
        val file = Files.createTempFile("workflowtool-magic-tolerance", ".png").toFile()
        ImageIO.write(image, "png", file)
        return file
    }

    private fun createDiagonalMagicImageFile(): java.io.File {
        val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF4, 0xF4, 0xF4)
        graphics.fillRect(0, 0, 24, 24)
        graphics.color = Color(0x24, 0x7B, 0xFE)
        graphics.fillRect(5, 5, 2, 2)
        graphics.fillRect(7, 7, 2, 2)
        graphics.fillRect(9, 9, 2, 2)
        graphics.fillRect(11, 11, 2, 2)
        graphics.fillRect(13, 13, 2, 2)
        graphics.fillRect(15, 15, 2, 2)
        graphics.dispose()
        val file = Files.createTempFile("workflowtool-magic-diagonal", ".png").toFile()
        ImageIO.write(image, "png", file)
        return file
    }

    private fun createManualBackgroundImageFile(): java.io.File {
        val image = BufferedImage(72, 44, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF2, 0xF3, 0xF5)
        graphics.fillRect(0, 0, 72, 44)
        graphics.color = Color(0x23, 0x7A, 0xFF)
        graphics.fillRect(6, 6, 18, 18)
        graphics.color = Color(0x14, 0x2D, 0x55)
        graphics.fillRect(40, 8, 20, 22)
        graphics.dispose()
        val file = Files.createTempFile("workflowtool-manual-background", ".png").toFile()
        ImageIO.write(image, "png", file)
        return file
    }
}

private class CountingDetector : RegionDetector {
    var calls: Int = 0
    var lastConfig: DetectionConfig = DetectionConfig()

    override fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult {
        calls += 1
        lastConfig = config
        return DetectionResult(emptyList(), DetectionMode.FALLBACK_BACKGROUND, DetectionStats(0, 0, 0, 0, 0, 0))
    }
}

private class StaticDetector(
    private val regions: List<CropRegion> = emptyList()
) : RegionDetector {
    override fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult {
        return DetectionResult(regions, DetectionMode.FALLBACK_BACKGROUND, DetectionStats(0, 0, regions.size, regions.size, 0, 0))
    }
}

private class CountingSplitter : RegionSplitter {
    var calls: Int = 0

    override fun split(image: BufferedImage, config: GridConfig): List<CropRegion> {
        calls += 1
        return emptyList()
    }
}

private class NoopExporter : RegionExporter {
    override fun export(
        image: BufferedImage,
        sourceFileName: String,
        regions: List<CropRegion>,
        config: ExportConfig
    ): ExportResult = ExportResult(0, 0, emptyList())
}

private object AvailableNativeImageEngine : io.github.workflowtool.domain.NativeImageEngine {
    override val isAvailable: Boolean = true
    override val backendName: String = "Test"
    override val detail: String = "available"
}

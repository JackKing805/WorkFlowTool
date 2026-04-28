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
    fun controllerLogsOfflineDependencySelfCheckOnStartup() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        assertTrue(controller.logs.any { it.contains("离线依赖自检") })
    }

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
    fun selectingContainedRegionSelectsOnlyThatRegion() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("outer", 10, 10, 40, 40),
                CropRegion("hole", 20, 20, 10, 10),
                CropRegion("other", 80, 10, 20, 20)
            )
        )

        controller.selectRegion("hole")

        assertEquals(false, controller.regions.first { it.id == "outer" }.selected)
        assertTrue(controller.regions.first { it.id == "hole" }.selected)
        assertEquals(false, controller.regions.first { it.id == "other" }.selected)
        assertEquals("20, 20, 10 x 10", controller.selectedRegionLabel)
    }

    @Test
    fun togglingVisibilityOnContainedRegionTogglesOnlyThatRegion() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("outer", 10, 10, 40, 40),
                CropRegion("hole", 20, 20, 10, 10),
                CropRegion("other", 80, 10, 20, 20)
            )
        )

        controller.toggleVisibility("hole")

        assertEquals(true, controller.regions.first { it.id == "outer" }.visible)
        assertEquals(false, controller.regions.first { it.id == "hole" }.visible)
        assertEquals(true, controller.regions.first { it.id == "other" }.visible)
    }

    @Test
    fun removingContainedRegionRemovesOnlyThatRegion() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("outer", 10, 10, 40, 40),
                CropRegion("hole", 20, 20, 10, 10),
                CropRegion("other", 80, 10, 20, 20)
            )
        )

        controller.removeRegion("hole")

        assertEquals(listOf("outer", "other"), controller.regions.map { it.id })
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
    fun reopeningHistorySnapshotRestoresSavedBaseAndRegions() {
        WorkspaceHistoryStore.clear()
        val file = createImageFile(width = 48, height = 36)
        val originalBase = CropRegion("auto", 3, 4, 11, 9)
        val manual = CropRegion("manual", 18, 12, 14, 10, visible = false)

        val savingController = AppController(
            detector = StaticDetector(listOf(originalBase)),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        savingController.loadFile(file)
        savingController.replaceRegions("manual", listOf(manual))

        val snapshotId = savingController.workspaceHistoryEntries.single().id
        assertTrue(savingController.workspaceHistoryEntries.single().previewPath.toFile().isFile)

        val restoringDetector = CountingDetector()
        val restoringController = AppController(
            detector = restoringDetector,
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        restoringController.reopenHistorySnapshot(snapshotId)

        assertEquals(0, restoringDetector.calls)
        assertEquals(file.absolutePath, restoringController.imageFile?.absolutePath)
        assertEquals(1, restoringController.regions.size)
        assertEquals(18, restoringController.regions.single().x)
        assertEquals(false, restoringController.regions.single().visible)
        assertTrue(restoringController.hasManualEdits)

        restoringController.resetManualEdits()

        assertEquals(1, restoringController.regions.size)
        assertEquals(3, restoringController.regions.single().x)
        assertEquals(4, restoringController.regions.single().y)
        assertEquals(11, restoringController.regions.single().width)
        assertEquals(9, restoringController.regions.single().height)
        WorkspaceHistoryStore.clear()
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

        assertEquals(1, detector.calls)
        assertEquals("#FFF2F3F5", controller.activeBackgroundLabel)
        controller.rebuildFromAuto(logResult = true)
        assertEquals(2, detector.calls)
        assertTrue(detector.lastConfig.useManualBackground)
        assertEquals(0xFFF2F3F5.toInt(), detector.lastConfig.manualBackgroundArgb)
    }

    @Test
    fun samplingBackgroundDoesNotResetEditedRegions() {
        val controller = AppController(
            detector = StaticDetector(listOf(CropRegion("auto", 1, 1, 8, 8))),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createManualBackgroundImageFile())
        controller.replaceRegions("manual", listOf(CropRegion("1", 12, 12, 18, 18)))

        controller.sampleBackgroundAt(androidx.compose.ui.geometry.Offset(1f, 1f))

        assertEquals(1, controller.regions.size)
        assertEquals(12, controller.regions.single().x)
        assertEquals(18, controller.regions.single().width)
        assertTrue(controller.hasManualEdits)
    }

    @Test
    fun configChangesDoNotResetEditedRegions() {
        val detector = CountingDetector()
        val splitter = CountingSplitter()
        val controller = AppController(
            detector = detector,
            splitter = splitter,
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createImageFile())
        controller.replaceRegions("manual", listOf(CropRegion("1", 12, 12, 18, 18)))
        val detectorCalls = detector.calls
        val splitterCalls = splitter.calls

        controller.updateDetectionConfig(controller.detectionConfig.copy(minWidth = controller.detectionConfig.minWidth + 1))
        controller.updateGridConfig(controller.gridConfig.copy(columns = controller.gridConfig.columns + 1))

        assertEquals(detectorCalls, detector.calls)
        assertEquals(splitterCalls, splitter.calls)
        assertEquals(1, controller.regions.size)
        assertEquals(12, controller.regions.single().x)
        assertEquals(18, controller.regions.single().width)
        assertTrue(controller.hasManualEdits)
    }

    @Test
    fun loadingMultipleImagesCreatesSingleSpacedCanvas() {
        val controller = AppController(
            detector = StaticDetector(),
            splitter = CountingSplitter(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFiles(
            listOf(
                createImageFile(width = 64, height = 32),
                createImageFile(width = 32, height = 48)
            )
        )

        assertEquals(2, controller.imageFiles.size)
        assertEquals(192, controller.image?.width)
        assertEquals(48, controller.image?.height)
    }

    @Test
    fun loadingImageClampsDetectedRegionIntoImageBounds() {
        val controller = AppController(
            detector = StaticDetector(
                listOf(
                    CropRegion(
                        id = "raw",
                        x = -10,
                        y = -12,
                        width = 100,
                        height = 80
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

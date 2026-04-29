package io.github.workflowtool.application

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.workflowtool.domain.RegionDetector
import io.github.workflowtool.domain.RegionExporter
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.ExportResult
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.model.maskAlphaAt
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppControllerTest {
    @Test
    fun controllerLogsOfflineDependencySelfCheckOnStartup() {
        val controller = AppController(
            detector = StaticDetector(),
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
        val controller = AppController(
            detector = detector,
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            nativeEngine = UnavailableNativeImageEngine
        )

        controller.loadFile(createImageFile())
        controller.rebuildFromAuto(logResult = true)

        assertTrue(detector.calls >= 1)
    }

    @Test
    fun selectingContainedRegionSelectsOnlyThatRegion() {
        val controller = AppController(
            detector = StaticDetector(),
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
    fun additiveSelectionKeepsExistingSelections() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )
        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("a", 0, 0, 10, 10, selected = true),
                CropRegion("b", 20, 0, 10, 10),
                CropRegion("c", 40, 0, 10, 10)
            )
        )

        controller.selectRegion("b", additive = true)

        assertEquals(listOf("a", "b"), controller.regions.filter { it.selected }.map { it.id })
        assertEquals("已选 2 个", controller.selectedRegionLabel)
    }

    @Test
    fun toggleRegionSelectionCanSelectAndDeselectSingleRegion() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )
        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("a", 0, 0, 10, 10),
                CropRegion("b", 20, 0, 10, 10, selected = true)
            )
        )

        controller.toggleRegionSelection("a")
        assertEquals(listOf("a", "b"), controller.regions.filter { it.selected }.map { it.id })

        controller.toggleRegionSelection("b")
        assertEquals(listOf("a"), controller.regions.filter { it.selected }.map { it.id })
    }

    @Test
    fun refineBrushSizeDefaultsAndClampsToSessionBounds() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        assertEquals(DefaultRefineBrushSizePx, controller.refineBrushSizePx)

        controller.updateRefineBrushSize(1)
        assertEquals(MinRefineBrushSizePx, controller.refineBrushSizePx)

        controller.updateRefineBrushSize(999)
        assertEquals(MaxRefineBrushSizePx, controller.refineBrushSizePx)
    }

    @Test
    fun refineBrushSizeStepsByConfiguredAmount() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.increaseRefineBrushSize()
        assertEquals(DefaultRefineBrushSizePx + RefineBrushSizeStepPx, controller.refineBrushSizePx)

        controller.decreaseRefineBrushSize()
        assertEquals(DefaultRefineBrushSizePx, controller.refineBrushSizePx)
    }

    @Test
    fun rangeSelectionSelectsAnchorTargetAndItemsBetween() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )
        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("a", 0, 0, 10, 10),
                CropRegion("b", 20, 0, 10, 10),
                CropRegion("c", 40, 0, 10, 10),
                CropRegion("d", 60, 0, 10, 10)
            )
        )

        controller.selectRegionRange("b", "d")

        assertEquals(listOf("b", "c", "d"), controller.regions.filter { it.selected }.map { it.id })
    }

    @Test
    fun boundsSelectionCanReplaceOrAddToSelection() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )
        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("a", 0, 0, 10, 10, selected = true),
                CropRegion("b", 20, 0, 10, 10),
                CropRegion("c", 60, 0, 10, 10)
            )
        )

        controller.selectRegionsInBounds(18, -2, 35, 12, additive = false)
        assertEquals(listOf("b"), controller.regions.filter { it.selected }.map { it.id })

        controller.selectRegionsInBounds(-2, -2, 12, 12, additive = true)
        assertEquals(listOf("a", "b"), controller.regions.filter { it.selected }.map { it.id })
    }

    @Test
    fun mergeSelectedRegionsReplacesMultipleRegionsWithSingleMaskRegion() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )
        controller.loadFile(createImageFile(width = 80, height = 80))
        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("a", 5, 6, 10, 8, selected = true),
                CropRegion("b", 25, 20, 12, 10, selected = true),
                CropRegion("c", 60, 60, 5, 5)
            )
        )

        controller.mergeSelectedRegions()

        assertEquals(2, controller.regions.size)
        val merged = controller.regions.first()
        assertEquals(true, merged.selected)
        assertEquals(5, merged.x)
        assertEquals(6, merged.y)
        assertEquals(32, merged.width)
        assertEquals(24, merged.height)
        assertTrue(merged.hasMask())
        assertEquals(listOf("a"), controller.regions.filter { it.selected }.map { it.id })
    }

    @Test
    fun togglingVisibilityOnContainedRegionTogglesOnlyThatRegion() {
        val controller = AppController(
            detector = StaticDetector(),
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
    fun removeSelectedRegionsRemovesSelectionAndClearsPreview() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("a", 0, 0, 10, 10, selected = true),
                CropRegion("b", 20, 0, 10, 10, selected = true),
                CropRegion("c", 40, 0, 10, 10)
            )
        )
        controller.openRegionPreview("b")

        controller.removeSelectedRegions()

        assertEquals(listOf("c"), controller.regions.map { it.id })
        assertEquals(null, controller.previewRegion)
    }

    @Test
    fun toggleSelectedVisibilityHidesVisibleSelectionAndShowsHiddenSelection() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.replaceRegions(
            "group",
            listOf(
                CropRegion("a", 0, 0, 10, 10, selected = true, visible = true),
                CropRegion("b", 20, 0, 10, 10, selected = true, visible = false),
                CropRegion("c", 40, 0, 10, 10, visible = true)
            )
        )

        controller.toggleSelectedVisibility()

        assertEquals(false, controller.regions.first { it.id == "a" }.visible)
        assertEquals(false, controller.regions.first { it.id == "b" }.visible)
        assertEquals(true, controller.regions.first { it.id == "c" }.visible)

        controller.toggleSelectedVisibility()

        assertEquals(true, controller.regions.first { it.id == "a" }.visible)
        assertEquals(true, controller.regions.first { it.id == "b" }.visible)
        assertEquals(true, controller.regions.first { it.id == "c" }.visible)
    }

    @Test
    fun fitSelectionCentersSelectedRegion() {
        val controller = AppController(
            detector = StaticDetector(),
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
    fun zoomAroundImagePointUsesRenderedImageScaleForAnchor() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createImageFile(width = 257, height = 193))
        controller.updateViewportSize(Size(400f, 300f))
        controller.zoom = 1f
        controller.viewportOffset = Offset(0.4f, -0.4f)

        val anchor = Offset(180f, 120f)
        val imagePoint = Offset(88.5f, 41.25f)
        controller.zoomAroundImagePoint(1.337f, anchor, imagePoint)
        val renderedWidth = (257 * controller.zoom).roundToInt()
        val renderedHeight = (193 * controller.zoom).roundToInt()
        val renderZoomX = renderedWidth.toFloat() / 257f
        val renderZoomY = renderedHeight.toFloat() / 193f

        assertEquals(anchor.x - imagePoint.x * renderZoomX, controller.viewportOffset.x)
        assertEquals(anchor.y - imagePoint.y * renderZoomY, controller.viewportOffset.y)
    }

    @Test
    fun redoRestoresUndoAvailability() {
        val controller = AppController(
            detector = StaticDetector(),
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
    fun missingHistorySourceDoesNotRestoreSnapshot() {
        WorkspaceHistoryStore.clear()
        val file = createImageFile(width = 48, height = 36)
        val originalBase = CropRegion("auto", 3, 4, 11, 9)
        val manual = CropRegion("manual", 18, 12, 14, 10, visible = false)

        val savingController = AppController(
            detector = StaticDetector(listOf(originalBase)),
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
        Files.deleteIfExists(file.toPath())

        val restoringDetector = CountingDetector()
        val restoringController = AppController(
            detector = restoringDetector,
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        restoringController.reopenHistorySnapshot(snapshotId)

        assertEquals(0, restoringDetector.calls)
        assertEquals(null, restoringController.imageFile)
        assertTrue(restoringController.logs.any { it.contains("缺少 1 个源文件") })
        WorkspaceHistoryStore.clear()
    }

    @Test
    fun reopeningHistorySnapshotRestoresSavedBaseAndRegions() {
        WorkspaceHistoryStore.clear()
        val file = createImageFile(width = 48, height = 36)
        val originalBase = CropRegion("auto", 3, 4, 11, 9)
        val manual = CropRegion("manual", 18, 12, 14, 10, visible = false)

        val savingController = AppController(
            detector = StaticDetector(listOf(originalBase)),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        savingController.loadFile(file)
        savingController.replaceRegions("manual", listOf(manual))

        val snapshotId = savingController.workspaceHistoryEntries.single().id

        val restoringDetector = CountingDetector()
        val restoringController = AppController(
            detector = restoringDetector,
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
    fun loadingImageStoresHistoryEvenWhenDetectionFindsNoRegions() {
        WorkspaceHistoryStore.clear()
        val file = createImageFile(width = 48, height = 36)
        val controller = AppController(
            detector = StaticDetector(emptyList()),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        controller.loadFile(file)

        assertEquals(1, controller.workspaceHistoryEntries.size)
        assertEquals(file.absolutePath, controller.workspaceHistoryEntries.single().sourcePaths.single().toFile().absolutePath)
        assertTrue(controller.workspaceHistoryEntries.single().previewPath.toFile().isFile)
        WorkspaceHistoryStore.clear()
    }

    @Test
    fun workspaceHistoryKeepsMostRecentHundredSnapshots() {
        WorkspaceHistoryStore.clear()
        val source = createImageFile(width = 12, height = 12).toPath()
        var entries = emptyList<WorkspaceSnapshotEntry>()

        repeat(101) { index ->
            val entry = WorkspaceSnapshotEntry(
                id = "history-$index",
                title = "history $index",
                sourcePaths = listOf(source.resolveSibling("history-$index.png")),
                currentImageIndex = 0,
                updatedAtEpochMillis = index.toLong(),
                imageWidth = 12,
                imageHeight = 12,
                hasManualEdits = false,
                baseRegions = emptyList(),
                regions = emptyList()
            )
            entries = WorkspaceHistoryStore.upsert(
                existing = entries,
                entry = entry,
                preview = BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB)
            )
        }

        val loaded = WorkspaceHistoryStore.load()

        assertEquals(WorkspaceHistoryStore.MaxEntries, loaded.size)
        assertEquals("history-100", loaded.first().id)
        assertFalse(loaded.any { it.id == "history-0" })
        WorkspaceHistoryStore.clear()
    }

    @Test
    fun modelEvolutionStorePersistsEntriesInNewestFirstOrder() {
        ModelEvolutionStore.clear()
        val before = LearningConfig(revision = 1, feedbackSamplesSeen = 1)
        val after = before.copy(revision = 2, maskThreshold = 0.24, feedbackSamplesSeen = 3)
        val older = buildModelEvolutionEntry(
            source = "精修确认",
            status = ModelEvolutionStatus.Waiting,
            sampleCount = 1,
            trainingType = "等待更多样本",
            message = "等待",
            thumbnailPath = null,
            before = before,
            after = before
        ).copy(id = "older", createdAtEpochMillis = 10)
        val newer = buildModelEvolutionEntry(
            source = "导出确认",
            status = ModelEvolutionStatus.Updated,
            sampleCount = 3,
            trainingType = "最近样本微调",
            message = "已更新",
            thumbnailPath = createImageFile(width = 12, height = 12).toPath(),
            before = before,
            after = after
        ).copy(id = "newer", createdAtEpochMillis = 20)

        ModelEvolutionStore.append(older)
        ModelEvolutionStore.append(newer)
        val loaded = ModelEvolutionStore.load()

        assertEquals(listOf("newer", "older"), loaded.map { it.id })
        assertEquals(ModelEvolutionStatus.Updated, loaded.first().status)
        assertTrue(loaded.first().changes.any { it.label == "遮罩阈值" })
        ModelEvolutionStore.clear()
    }

    @Test
    fun learningConfigChangesOnlyIncludesChangedValues() {
        val before = LearningConfig(revision = 1, maskThreshold = 0.28, scoreThreshold = 0.18)
        val after = before.copy(revision = 2, maskThreshold = 0.24)

        val changes = buildLearningConfigChanges(before, after)

        assertEquals(listOf("遮罩阈值"), changes.map { it.label })
        assertEquals("0.280", changes.single().before)
        assertEquals("0.240", changes.single().after)
    }

    @Test
    fun workspaceHistoryUpdatesExistingCanvasInsteadOfDuplicatingIt() {
        WorkspaceHistoryStore.clear()
        val file = createImageFile(width = 48, height = 36)
        val controller = AppController(
            detector = StaticDetector(emptyList()),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        controller.loadFile(file)
        val snapshotId = controller.workspaceHistoryEntries.single().id
        controller.replaceRegions("manual", listOf(CropRegion("manual", 6, 7, 8, 9)))

        val loaded = WorkspaceHistoryStore.load()

        assertEquals(1, loaded.size)
        assertEquals(snapshotId, loaded.single().id)
        assertEquals(6, loaded.single().regions.single().x)
        assertTrue(loaded.single().hasManualEdits)
        WorkspaceHistoryStore.clear()
    }

    @Test
    fun removingHistorySnapshotDeletesEntryAndPreview() {
        WorkspaceHistoryStore.clear()
        val file = createImageFile(width = 48, height = 36)
        val controller = AppController(
            detector = StaticDetector(emptyList()),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        controller.loadFile(file)
        val entry = controller.workspaceHistoryEntries.single()
        assertTrue(Files.exists(entry.previewPath))

        controller.removeHistorySnapshot(entry.id)

        assertEquals(emptyList(), controller.workspaceHistoryEntries)
        assertFalse(Files.exists(entry.previewPath))
        assertEquals(emptyList(), WorkspaceHistoryStore.load())
        WorkspaceHistoryStore.clear()
    }

    @Test
    fun clearingRegionsUpdatesWorkspaceHistorySnapshot() {
        WorkspaceHistoryStore.clear()
        val file = createImageFile(width = 48, height = 36)
        val controller = AppController(
            detector = StaticDetector(listOf(CropRegion("auto", 2, 3, 10, 11))),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        controller.loadFile(file)
        controller.clearRegions()

        val entry = WorkspaceHistoryStore.load().single()
        assertEquals(emptyList(), entry.regions)
        assertTrue(entry.hasManualEdits)
        WorkspaceHistoryStore.clear()
    }

    @Test
    fun reopeningMultiImageHistoryRestoresCanvasSourcesAndRegions() {
        WorkspaceHistoryStore.clear()
        val first = createImageFile(width = 24, height = 20)
        val second = createImageFile(width = 16, height = 18)
        val savingController = AppController(
            detector = StaticDetector(listOf(CropRegion("auto", 1, 2, 6, 6))),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        savingController.loadFiles(listOf(first, second))
        savingController.replaceRegions("manual", listOf(CropRegion("multi", 30, 4, 8, 8, selected = true)))
        val snapshotId = savingController.workspaceHistoryEntries.single().id

        val restoringController = AppController(
            detector = CountingDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            persistenceEnabled = true
        )

        restoringController.reopenHistorySnapshot(snapshotId)

        assertEquals(2, restoringController.imageFiles.size)
        assertEquals(listOf(first.absolutePath, second.absolutePath), restoringController.imageFiles.map { it.absolutePath })
        assertEquals(1, restoringController.regions.size)
        assertEquals("multi", restoringController.regions.single().id)
        assertEquals(30, restoringController.regions.single().x)
        assertTrue(restoringController.hasManualEdits)
        WorkspaceHistoryStore.clear()
    }

    @Test
    fun workspaceHistoryPersistsWhenRegionScoreIsNotFinite() {
        WorkspaceHistoryStore.clear()
        val file = createImageFile(width = 48, height = 36)
        val entry = WorkspaceSnapshotEntry(
            id = "non-finite-score",
            title = "score test",
            sourcePaths = listOf(file.toPath()),
            currentImageIndex = 0,
            updatedAtEpochMillis = 1L,
            imageWidth = 48,
            imageHeight = 36,
            hasManualEdits = true,
            baseRegions = emptyList(),
            regions = listOf(CropRegion("nan", 1, 2, 3, 4, score = Float.NaN))
        )

        WorkspaceHistoryStore.upsert(
            existing = emptyList(),
            entry = entry,
            preview = BufferedImage(12, 8, BufferedImage.TYPE_INT_ARGB)
        )

        val loaded = WorkspaceHistoryStore.load()
        assertEquals(1, loaded.size)
        assertEquals(null, loaded.single().regions.single().score)
        WorkspaceHistoryStore.clear()
    }

    @Test
    fun workspaceHistoryLoadsLegacyFileWithNanScore() {
        WorkspaceHistoryStore.clear()
        val file = createImageFile(width = 48, height = 36)
        val path = file.toPath().toString().replace("\\", "\\\\")
        val historyFile = AppRuntimeFiles.runtimeRoot.resolve("workspace-history.json")
        Files.createDirectories(historyFile.parent)
        Files.writeString(
            historyFile,
            """
            {
              "entries": [
                {"id":"legacy-nan","title":"legacy","sourcePaths":["$path"],"currentImageIndex":0,"updatedAtEpochMillis":1,"imageWidth":48,"imageHeight":36,"hasManualEdits":false,"baseRegions":[],"regions":[{"id":"1","x":1,"y":2,"width":3,"height":4,"visible":true,"selected":false,"maskWidth":0,"maskHeight":0,"alphaMask":[],"score":NaN}]}
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        val loaded = WorkspaceHistoryStore.load()

        assertEquals(1, loaded.size)
        assertEquals("legacy-nan", loaded.single().id)
        assertEquals(null, loaded.single().regions.single().score)
        WorkspaceHistoryStore.clear()
    }

    @Test
    fun exportPreviewRegionWritesSingleRegionImage() {
        val controller = AppController(
            detector = StaticDetector(),
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
        val controller = AppController(
            detector = detector,
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createImageFile())
        controller.replaceRegions("manual", listOf(CropRegion("1", 12, 12, 18, 18)))
        val detectorCalls = detector.calls

        controller.updateDetectionConfig(controller.detectionConfig.copy(minWidth = controller.detectionConfig.minWidth + 1))

        assertEquals(detectorCalls, detector.calls)
        assertEquals(1, controller.regions.size)
        assertEquals(12, controller.regions.single().x)
        assertEquals(18, controller.regions.single().width)
        assertTrue(controller.hasManualEdits)
    }

    @Test
    fun loadingMultipleImagesCreatesSingleSpacedCanvas() {
        val controller = AppController(
            detector = StaticDetector(),
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

    @Test
    fun detectInsideRegionPromotesDraftBoxWhenDetectionFails() {
        val controller = AppController(
            detector = StaticDetector(emptyList()),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )
        val existing = CropRegion("existing", 2, 3, 8, 9, selected = true)

        controller.loadFile(createImageFile(width = 64, height = 48))
        controller.replaceRegions("existing", listOf(existing))
        controller.detectInsideRegion(CropRegion("draft", 10, 10, 20, 20, selected = true))

        assertEquals(2, controller.regions.size)
        assertEquals(false, controller.regions[0].selected)
        val promoted = controller.regions[1]
        assertEquals(10, promoted.x)
        assertEquals(10, promoted.y)
        assertEquals(6, promoted.width)
        assertEquals(6, promoted.height)
        assertTrue(promoted.selected)
        assertTrue(promoted.hasMask())
        assertTrue(controller.logs.any { it.contains("已将框选范围自动贴合图标边缘") })
    }

    @Test
    fun detectInsideRegionReplacesDraftBoxWithDetectedRegionsOnSuccess() {
        val controller = AppController(
            detector = StaticDetector(listOf(CropRegion("detected", 3, 4, 6, 7))),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createImageFile(width = 64, height = 48))
        controller.clearRegions()
        controller.detectInsideRegion(CropRegion("draft", 10, 12, 20, 20, selected = true))

        val region = controller.regions.single()
        assertEquals(13, region.x)
        assertEquals(16, region.y)
        assertEquals(6, region.width)
        assertEquals(7, region.height)
        assertTrue(region.selected)
    }

    @Test
    fun addBrushCommitSnapsSelectedRegionToForeground() {
        val controller = AppController(
            detector = StaticDetector(emptyList()),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createImageFile(width = 64, height = 48))
        controller.replaceRegions(
            "贴合新增区域",
            listOf(CropRegion("brush", 8, 8, 20, 20, selected = true))
        )

        val region = controller.regions.single()
        assertEquals(8, region.x)
        assertEquals(8, region.y)
        assertEquals(8, region.width)
        assertEquals(8, region.height)
        assertTrue(region.hasMask())
        assertTrue(controller.logs.any { it.contains("新增区域已自动贴合图标边缘") })
    }

    @Test
    fun addBrushCommitSnapsSelectedRegionAsOneWholeIcon() {
        val controller = AppController(
            detector = StaticDetector(emptyList()),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )
        val maskWidth = 36
        val maskHeight = 12
        val mask = MutableList(maskWidth * maskHeight) { 0 }
        for (y in 0 until maskHeight) {
            for (x in 0 until 12) mask[y * maskWidth + x] = 255
            for (x in 24 until 36) mask[y * maskWidth + x] = 255
        }

        controller.loadFile(createTwoPartIconFile())
        controller.replaceRegions(
            "整体贴合选区",
            listOf(
                CropRegion(
                    id = "whole",
                    x = 4,
                    y = 4,
                    width = maskWidth,
                    height = maskHeight,
                    selected = true,
                    maskWidth = maskWidth,
                    maskHeight = maskHeight,
                    alphaMask = mask
                )
            )
        )

        val region = controller.regions.single()
        assertEquals("whole", region.id)
        assertEquals(4, region.x)
        assertEquals(4, region.y)
        assertEquals(36, region.width)
        assertEquals(12, region.height)
        assertTrue(region.selected)
        assertTrue(region.hasMask())
        assertEquals(0, region.maskAlphaAt(22, 8))
        assertTrue(region.maskAlphaAt(8, 8) > 0)
        assertTrue(region.maskAlphaAt(34, 8) > 0)
        assertTrue(controller.logs.any { it.contains("选区已按用户精修结果补全并贴合") })
        assertTrue(controller.logs.any { it.contains("未形成有效模型区域，已回退颜色贴边") })
    }

    @Test
    fun addBrushCommitUsesRedetectionResultWhenAvailable() {
        val controller = AppController(
            detector = StaticDetector(listOf(CropRegion("detected", 3, 4, 20, 10))),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )

        controller.loadFile(createTwoPartIconFile())
        controller.replaceRegions(
            "整体贴合选区",
            listOf(CropRegion("whole", 4, 4, 36, 12, selected = true))
        )

        val region = controller.regions.single()
        assertEquals("whole", region.id)
        assertEquals(3, region.x)
        assertEquals(4, region.y)
        assertEquals(20, region.width)
        assertEquals(10, region.height)
        assertTrue(region.selected)
        assertTrue(region.hasMask())
        assertTrue(controller.logs.any { it.contains("精修贴合：模型命中 1 个候选") })
    }

    @Test
    fun fineRefineTrainingFingerprintChangesWhenMaskChanges() {
        val controller = AppController(
            detector = StaticDetector(),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )
        controller.loadFile(createTwoPartIconFile())
        val mask = MutableList(16) { 255 }
        controller.replaceRegions(
            "manual",
            listOf(CropRegion("whole", 4, 4, 4, 4, selected = true, maskWidth = 4, maskHeight = 4, alphaMask = mask))
        )
        val first = controller.buildFineRefineTrainingFingerprint()

        val changedMask = mask.toMutableList().also { it[5] = 0 }
        controller.replaceRegions(
            "manual",
            listOf(CropRegion("whole", 4, 4, 4, 4, selected = true, maskWidth = 4, maskHeight = 4, alphaMask = changedMask))
        )
        val second = controller.buildFineRefineTrainingFingerprint()

        assertFalse(first == second)
    }

    @Test
    fun addBrushCommitKeepsMaskedCutoutWhenRedetectionTriesToFillItBack() {
        val controller = AppController(
            detector = StaticDetector(listOf(CropRegion("detected", 0, 0, 36, 12))),
            exporter = NoopExporter(),
            layoutSpec = LayoutSpec(),
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy()
        )
        val maskWidth = 36
        val maskHeight = 12
        val mask = MutableList(maskWidth * maskHeight) { 0 }
        for (y in 0 until maskHeight) {
            for (x in 0 until 12) mask[y * maskWidth + x] = 255
            for (x in 24 until 36) mask[y * maskWidth + x] = 255
        }

        controller.loadFile(createTwoPartIconFile())
        controller.replaceRegions(
            "整体贴合选区",
            listOf(
                CropRegion(
                    id = "whole",
                    x = 4,
                    y = 4,
                    width = maskWidth,
                    height = maskHeight,
                    selected = true,
                    maskWidth = maskWidth,
                    maskHeight = maskHeight,
                    alphaMask = mask
                )
            )
        )

        val region = controller.regions.single()
        assertEquals(4, region.x)
        assertEquals(4, region.y)
        assertEquals(36, region.width)
        assertEquals(12, region.height)
        assertEquals(0, region.maskAlphaAt(22, 8))
        assertTrue(region.maskAlphaAt(8, 8) > 0)
        assertTrue(region.maskAlphaAt(34, 8) > 0)
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

    private fun createTwoPartIconFile(): java.io.File {
        val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(4, 4, 12, 12)
        graphics.fillRect(28, 4, 12, 12)
        graphics.dispose()
        val file = Files.createTempFile("workflowtool-two-part-icon", ".png").toFile()
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

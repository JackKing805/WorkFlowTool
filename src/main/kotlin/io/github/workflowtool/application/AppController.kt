package io.github.workflowtool.application

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import io.github.workflowtool.core.IconExporter
import io.github.workflowtool.domain.LayoutBounds
import io.github.workflowtool.domain.LayoutConstraintPolicy
import io.github.workflowtool.domain.LayoutState
import io.github.workflowtool.domain.LocalizationProvider
import io.github.workflowtool.domain.NativeImageEngine
import io.github.workflowtool.domain.RegionDetector
import io.github.workflowtool.domain.RegionExporter
import io.github.workflowtool.domain.RegionSplitter
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.GridConfig
import io.github.workflowtool.model.ImageFormat
import io.github.workflowtool.model.NamingMode
import io.github.workflowtool.model.SplitSource
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.platform.DesktopPlatform
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.io.path.exists
import kotlin.math.ceil
import kotlin.math.sqrt

class AppController(
    private val detector: RegionDetector,
    private val splitter: RegionSplitter,
    private val exporter: RegionExporter,
    val layoutSpec: LayoutSpec,
    val localization: LocalizationProvider,
    private val layoutPolicy: LayoutConstraintPolicy,
    private val nativeEngine: NativeImageEngine = PythonImageEngine,
    private val previewExporter: IconExporter = IconExporter(),
    private val persistenceEnabled: Boolean = splitter is CppRegionSplitter && exporter is JvmRegionExporter
) {
    internal val history = EditorHistory()
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var baseRegions: List<CropRegion> = emptyList()
    private var lastDetectionResult by mutableStateOf(emptyDetectionResult())
    var busyMessage by mutableStateOf<String?>(null)
        private set

    var imageFile by mutableStateOf<File?>(null)
        internal set
    var imageFiles by mutableStateOf<List<File>>(emptyList())
        internal set
    var recentFiles by mutableStateOf<List<File>>(emptyList())
        internal set
    var currentImageIndex by mutableStateOf(-1)
        internal set
    var image by mutableStateOf<BufferedImage?>(null)
        internal set
    var splitSource by mutableStateOf(SplitSource.AutoDetect)
        private set
    var toolMode by mutableStateOf(ToolMode.Select)
        private set
    var zoom by mutableStateOf(1.0f)
        internal set
    var viewportOffset by mutableStateOf(Offset.Zero)
        internal set
    var viewportSize by mutableStateOf(Size.Zero)
        internal set
    var hoveredImagePoint by mutableStateOf<Offset?>(null)
        internal set
    var showGrid by mutableStateOf(true)
        internal set
    var detectionConfig by mutableStateOf(DetectionConfig())
        internal set
    var sampledBackgroundArgb by mutableStateOf<Int?>(null)
        private set
    var backgroundPickArmed by mutableStateOf(false)
        internal set
    var gridConfig by mutableStateOf(GridConfig())
        internal set
    var outputDirectory by mutableStateOf(DesktopPlatform.defaultOutputDirectory())
        internal set
    var outputFormat by mutableStateOf(ImageFormat.PNG)
        internal set
    var namingMode by mutableStateOf(NamingMode.Sequence)
        internal set
    var customPrefix by mutableStateOf("icon")
        internal set
    var keepOriginalSize by mutableStateOf(true)
        internal set
    var trimTransparent by mutableStateOf(false)
        internal set
    var removeBackgroundToTransparent by mutableStateOf(false)
        internal set
    var backgroundRemovalTolerance by mutableStateOf(20)
        internal set
    var padToSquare by mutableStateOf(false)
        internal set
    var fixedSizeText by mutableStateOf("")
        internal set
    var overwriteExisting by mutableStateOf(false)
        internal set
    var continuousTrainingEnabled by mutableStateOf(false)
        internal set
    var runtimePreparationStage by mutableStateOf(RuntimePreparationStage.NotChecked)
        private set
    var runtimePreparationMessage by mutableStateOf("尚未检查 Python 运行环境")
        private set
    var runtimePreparationFailed by mutableStateOf(false)
        private set
    var showAdvancedSettings by mutableStateOf(false)
        private set
    var historyDialogVisible by mutableStateOf(false)
        private set
    var previewRegionId by mutableStateOf<String?>(null)
        internal set
    var hasManualEdits by mutableStateOf(false)
        internal set
    internal var workspaceHistoryEntries by mutableStateOf<List<WorkspaceSnapshotEntry>>(emptyList())
        private set
    var layoutState by mutableStateOf(
        LayoutState(
            leftPanelWidth = layoutSpec.initialLeftWidth,
            rightPanelWidth = layoutSpec.initialRightWidth,
            previewHeight = layoutSpec.initialPreviewHeight
        )
    )
        private set

    val logs = mutableStateListOf<String>()

    init {
        if (persistenceEnabled) applyPersistedSettings(AppSettingsStore.load())
        if (persistenceEnabled) workspaceHistoryEntries = WorkspaceHistoryStore.load()
        logOfflineDependencyReport()
    }

    val regions: List<CropRegion> get() = history.state.regions
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val isBusy: Boolean get() = busyMessage != null
    val isAutoDetectAvailable: Boolean get() = nativeEngine.isAvailable
    val isGridSplitAvailable: Boolean get() = CppDetectorBridge.isLoaded
    val canRegenerateBase: Boolean
        get() = when (splitSource) {
            SplitSource.AutoDetect -> isAutoDetectAvailable
            SplitSource.SmartGrid -> isGridSplitAvailable
        }
    val selectedRegion: CropRegion?
        get() = regions.lastOrNull { it.selected }
    val previewRegion: CropRegion?
        get() = previewRegionId?.let { id -> regions.lastOrNull { it.id == id } }
    val baseSourceLabel: String
        get() = when (splitSource) {
            SplitSource.AutoDetect -> "自动识别"
            SplitSource.SmartGrid -> "智能网格"
        }
    val manualStatusLabel: String
        get() = if (hasManualEdits) "已修改" else "未修改"
    val currentRegionSourceLabel: String
        get() = when {
            regions.isEmpty() -> "-"
            hasManualEdits -> "手动修正（基于$baseSourceLabel）"
            else -> baseSourceLabel
        }
    val detectionModeLabel: String
        get() = when (lastDetectionResult.mode) {
            DetectionMode.ALPHA_MASK -> "透明前景"
            DetectionMode.SOLID_BACKGROUND -> "纯色背景"
            DetectionMode.FALLBACK_BACKGROUND -> "保守回退"
        }
    val detectionTimeLabel: String
        get() = "${lastDetectionResult.stats.totalTimeMs} ms"
    val candidatePixelsLabel: String
        get() = lastDetectionResult.stats.candidatePixels.toString()
    val backgroundEstimateLabel: String
        get() = formatArgb(lastDetectionResult.stats.estimatedBackgroundArgb)
    val activeBackgroundLabel: String
        get() = sampledBackgroundArgb?.let(::formatArgb) ?: "自动"
    val exportBackgroundLabel: String
        get() = sampledBackgroundArgb?.let { "${formatArgb(it)}（吸色）" } ?: "${formatArgb(lastDetectionResult.stats.estimatedBackgroundArgb)}（自动）"
    val detectionBackendLabel: String
        get() = "${nativeEngine.backendName} (${nativeEngine.detail})"
    val hoverPositionLabel: String
        get() = hoveredImagePoint?.let { "${it.x.toInt()}, ${it.y.toInt()}" } ?: "-"
    val selectedRegionLabel: String
        get() = selectedRegion?.let { "${it.x}, ${it.y}, ${it.width} x ${it.height}" } ?: "-"
    val runtimeStatusLabel: String
        get() = "${runtimePreparationStage.label}：$runtimePreparationMessage"

    fun log(message: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logs += "[$time] $message"
    }

    private fun logOfflineDependencyReport() {
        val report = AppRuntimeFiles.offlineDependencyReport()
        log(report.summary())
        report.statuses
            .filter { !it.ok }
            .forEach { status ->
                log("${status.name} 缺失：${status.detail}")
            }
        val pythonRuntimeReady = report.statuses
            .filter { it.name == "Python venv" || it.name == "runtime model" }
            .all { it.ok }
        if (report.statuses.all { it.ok }) {
            log("离线运行依赖已就绪")
        }
        if (pythonRuntimeReady) {
            preparePythonRuntimeAsync()
        } else if (persistenceEnabled && report.needsPythonRuntimePreparation) {
            preparePythonRuntimeAsync()
        }
    }

    fun preparePythonRuntimeAsync() {
        runBusy("正在准备 Python 运行环境并生成首次运行模型...") {
            PythonEnvironmentManager.invalidate()
            runtimePreparationFailed = false
            log("开始准备 Python 运行环境：创建虚拟环境、安装缺失依赖、训练运行时模型")
            val status = PythonEnvironmentManager.ensureReady { stage ->
                runtimePreparationStage = stage
                runtimePreparationMessage = stage.label
            }
            if (status.ready) {
                runtimePreparationFailed = false
                runtimePreparationMessage = status.message
                log("Python 运行环境已就绪：${status.message}")
            } else {
                runtimePreparationFailed = true
                runtimePreparationMessage = status.message
                log("Python 运行环境准备失败：${status.message}")
            }
        }
    }

    fun applyLayoutBounds(bounds: LayoutBounds) {
        updateLayoutState(layoutState, bounds)
    }

    fun resizeLeftPanel(delta: Dp, bounds: LayoutBounds) {
        if (delta.value == 0f) return
        updateLayoutState(layoutState.copy(leftPanelWidth = layoutState.leftPanelWidth + delta), bounds)
    }

    fun resizeRightPanel(delta: Dp, bounds: LayoutBounds) {
        if (delta.value == 0f) return
        updateLayoutState(layoutState.copy(rightPanelWidth = layoutState.rightPanelWidth - delta), bounds)
    }

    fun resizePreview(delta: Dp, bounds: LayoutBounds) {
        if (delta.value == 0f) return
        updateLayoutState(layoutState.copy(previewHeight = layoutState.previewHeight + delta), bounds)
    }


    fun rebuildFromAuto(logResult: Boolean = false) {
        val loaded = image ?: return
        val detected = detector.detect(loaded, effectiveDetectionConfig())
        lastDetectionResult = detected
        applyBaseGeneration(SplitSource.AutoDetect, detected.regions, logResult, "自动识别")
    }

    fun rebuildFromAutoAsync(logResult: Boolean = false) {
        runBusy("正在自动识别区域...") {
            rebuildFromAuto(logResult)
        }
    }

    fun rebuildFromSmartGrid(logResult: Boolean = false) {
        val loaded = image ?: return
        val generated = splitter.split(loaded, gridConfig)
        lastDetectionResult = DetectionResult(
            regions = generated,
            mode = DetectionMode.FALLBACK_BACKGROUND,
            stats = DetectionStats(0, 0, generated.size, generated.size, 0, 0)
        )
        applyBaseGeneration(SplitSource.SmartGrid, generated, logResult, "智能网格")
    }

    fun rebuildFromSmartGridAsync(logResult: Boolean = false) {
        runBusy("正在智能拆分区域...") {
            rebuildFromSmartGrid(logResult)
        }
    }

    fun regenerateBase() {
        regenerateBaseSafely(logResult = true)
    }

    fun regenerateBaseAsync() {
        runBusy("正在重新生成区域...") {
            regenerateBase()
        }
    }

    fun armBackgroundPicker() {
        backgroundPickArmed = image != null
        if (backgroundPickArmed) {
            updateToolMode(ToolMode.Eyedropper)
            log("已进入吸色工具")
        }
    }

    fun clearSampledBackground() {
        if (sampledBackgroundArgb == null) return
        sampledBackgroundArgb = null
        backgroundPickArmed = false
        if (toolMode == ToolMode.Eyedropper) toolMode = ToolMode.Select
        log("已清除手动背景色")
    }

    fun sampleBackgroundAt(point: Offset) {
        val loaded = image ?: return
        val x = point.x.toInt().coerceIn(0, loaded.width - 1)
        val y = point.y.toInt().coerceIn(0, loaded.height - 1)
        sampledBackgroundArgb = loaded.getRGB(x, y)
        backgroundPickArmed = false
        toolMode = ToolMode.Select
        log("已取背景色 ($x, $y)：${activeBackgroundLabel}")
    }

    fun resetManualEdits() {
        if (!hasManualEdits) return
        history.reset(baseRegions)
        hasManualEdits = false
        toolMode = ToolMode.Select
        rememberWorkspaceSnapshot()
        log("已重置手工修正，当前选框来源：$currentRegionSourceLabel")
    }

    private fun applyBaseGeneration(source: SplitSource, generated: List<CropRegion>, logResult: Boolean, sourceLabel: String) {
        val normalized = normalizeRegionIds(generated).mapNotNull { clampRegionToImage(it) }
        if (source == splitSource && normalized == baseRegions && !hasManualEdits) return
        splitSource = source
        baseRegions = normalized
        history.reset(normalized)
        hasManualEdits = false
        toolMode = ToolMode.Select
        rememberWorkspaceSnapshot()
        if (logResult) {
            log("$sourceLabel 生成 ${normalized.size} 个区域，当前选框来源：$currentRegionSourceLabel")
        }
    }

    fun exportRegions() {
        val loaded = image
        val file = imageFile
        if (loaded == null || file == null) {
            log("请先打开图片")
            return
        }
        if (regions.none { it.visible }) {
            log("没有可导出的区域")
            return
        }
        val config = buildExportConfig()
        log("当前选框来源：$currentRegionSourceLabel")
        log("开始裁剪...")
        val result = exporter.export(loaded, file.name, regions, config)
        log("裁剪完成：成功 ${result.successCount} 个，失败 ${result.failureCount} 个")
        result.failures.take(3).forEach { log("失败：$it") }
        log("输出目录：$outputDirectory")
        if (continuousTrainingEnabled && result.successCount > 0 && hasManualEdits) {
            val trainingFingerprint = buildTrainingFingerprint(config)
            if (hasTrainingFingerprint(trainingFingerprint)) {
                log("持续学习未更新：当前选框和参数已训练过")
                return
            }
            val trainingUpdate = ContinuousTrainingUpdate(
                icon = appendTrainingSample("手动修正后导出确认"),
                background = appendBackgroundTrainingSample("手动修正后背景确认")
            )
            if (!trainingUpdate.hasUpdates) {
                log("持续学习未更新：没有可用的用户确认训练样本")
                return
            }
            if (retrainContinuousModels(trainingUpdate)) {
                rememberTrainingFingerprint(trainingFingerprint)
            }
        } else if (continuousTrainingEnabled && result.successCount > 0) {
            log("持续学习未更新：当前结果没有手动修正")
        }
    }

    fun exportPreviewRegion(regionId: String, targetPath: Path? = null) {
        val loaded = image
        val file = imageFile
        if (loaded == null || file == null) {
            log("请先打开图片")
            return
        }
        val region = regions.lastOrNull { it.id == regionId }
        if (region == null) {
            log("未找到要导出的区域")
            return
        }
        val destination = targetPath ?: DesktopPlatform.chooseSaveFile(
            title = "Export preview",
            suggestedFileName = suggestedPreviewFileName(file, region),
            initialDirectory = outputDirectory
        )?.toPath()
        if (destination == null) {
            log("已取消预览导出")
            return
        }
        if (destination.exists() && !overwriteExisting) {
            log("文件已存在，请启用覆盖或更换名称")
            return
        }

        runCatching {
            val config = buildExportConfig()
            val written = previewExporter.exportSingle(loaded, region, regions, config, destination)
            check(written) { "No writer available for ${config.outputFormat.extension}" }
        }.onSuccess {
            log("预览区域已导出：${destination.fileName}")
        }.onFailure {
            log("预览区域导出失败：${it.message}")
        }
    }

    fun updateToolMode(mode: ToolMode) {
        if (toolMode == mode) return
        toolMode = mode
        if (mode != ToolMode.Eyedropper) {
            backgroundPickArmed = false
        }
    }

    fun enterManualDrawMode() {
        updateToolMode(ToolMode.Draw)
    }

    fun exportRegionsAsync() {
        runBusy("正在裁剪导出...") {
            exportRegions()
        }
    }

    fun detectInsideRegionAsync(region: CropRegion) {
        runBusy("正在识别框选区域...") {
            detectInsideRegion(region)
        }
    }

    fun clearRuntimeGeneratedFiles() {
        val result = AppRuntimeFiles.clearCreatedFiles()
        log("已清理应用运行时文件：删除 ${result.deletedCount} 项，目录：${result.root}")
        result.failures.take(5).forEach { log("清理失败：$it") }
        if (result.failures.isNotEmpty()) {
            log("部分文件可能正在被应用占用，重启后可再次清理")
        }
    }

    fun showAdvancedSettings(show: Boolean) {
        if (showAdvancedSettings == show) return
        showAdvancedSettings = show
    }

    fun showHistoryDialog(show: Boolean) {
        if (historyDialogVisible == show) return
        if (show && persistenceEnabled) {
            workspaceHistoryEntries = WorkspaceHistoryStore.load()
        }
        historyDialogVisible = show
    }

    fun reopenHistorySnapshotAsync(snapshotId: String) {
        runBusy("正在重开历史快照...") {
            reopenHistorySnapshot(snapshotId)
        }
    }

    fun removeHistorySnapshot(snapshotId: String) {
        if (!persistenceEnabled) return
        workspaceHistoryEntries = WorkspaceHistoryStore.remove(workspaceHistoryEntries, snapshotId)
        log("已删除历史记录")
    }

    fun openRegionPreview(regionId: String) {
        if (regions.none { it.id == regionId }) return
        previewRegionId = regionId
    }

    fun closeRegionPreview() {
        previewRegionId = null
    }

    private fun buildExportConfig(): ExportConfig {
        return ExportConfig(
            outputFormat = outputFormat,
            outputDirectory = outputDirectory,
            namingMode = namingMode,
            customPrefix = customPrefix,
            keepOriginalSize = keepOriginalSize,
            trimTransparentPadding = trimTransparent,
            removeBackgroundToTransparent = removeBackgroundToTransparent,
            backgroundArgb = sampledBackgroundArgb ?: lastDetectionResult.stats.estimatedBackgroundArgb,
            backgroundTolerance = backgroundRemovalTolerance,
            padToSquare = padToSquare,
            fixedSize = fixedSizeText.toIntOrNull(),
            overwriteExisting = overwriteExisting
        )
    }

    internal fun runBusy(message: String, action: () -> Unit) {
        if (isBusy) {
            log("已有任务执行中：$message")
            return
        }
        busyMessage = message
        workerScope.launch {
            try {
                action()
            } catch (error: Throwable) {
                log("任务失败：${error.message ?: error::class.simpleName}")
            } finally {
                busyMessage = null
            }
        }
    }

    internal fun setTrainingBusyMessage(message: String?) {
        busyMessage = message
    }

    internal fun syncManualEdits() {
        hasManualEdits = trainingComparableRegions(regions) != trainingComparableRegions(baseRegions)
    }

    internal fun rememberWorkspaceSnapshot() {
        if (!persistenceEnabled) return
        val loaded = image ?: return
        val sourcePaths = currentWorkspaceSourcePaths()
        if (sourcePaths.isEmpty()) return
        val entry = WorkspaceSnapshotEntry(
            id = buildWorkspaceSnapshotId(sourcePaths),
            title = buildWorkspaceTitle(),
            sourcePaths = sourcePaths,
            currentImageIndex = currentImageIndex.coerceAtLeast(0),
            updatedAtEpochMillis = System.currentTimeMillis(),
            imageWidth = loaded.width,
            imageHeight = loaded.height,
            splitSource = splitSource,
            hasManualEdits = hasManualEdits,
            baseRegions = snapshotRegions(baseRegions),
            regions = snapshotRegions(regions)
        )
        workspaceHistoryEntries = WorkspaceHistoryStore.upsert(
            existing = workspaceHistoryEntries,
            entry = entry,
            preview = buildWorkspaceSnapshotPreview(loaded, entry.regions)
        )
    }

    internal fun persistSettings() {
        if (!persistenceEnabled) return
        AppSettingsStore.save(
            AppSettings(
                detectionConfig = detectionConfig,
                gridConfig = gridConfig,
                outputDirectory = outputDirectory,
                outputFormat = outputFormat,
                namingMode = namingMode,
                customPrefix = customPrefix,
                keepOriginalSize = keepOriginalSize,
                trimTransparent = trimTransparent,
                removeBackgroundToTransparent = removeBackgroundToTransparent,
                backgroundRemovalTolerance = backgroundRemovalTolerance,
                padToSquare = padToSquare,
                fixedSizeText = fixedSizeText,
                overwriteExisting = overwriteExisting,
                continuousTrainingEnabled = continuousTrainingEnabled,
                showGrid = showGrid,
                recentFiles = recentFiles.map { it.toPath() }
            )
        )
    }

    private fun applyPersistedSettings(settings: AppSettings) {
        detectionConfig = settings.detectionConfig
        gridConfig = settings.gridConfig
        settings.outputDirectory?.let { outputDirectory = it }
        outputFormat = settings.outputFormat
        namingMode = settings.namingMode
        customPrefix = settings.customPrefix
        keepOriginalSize = settings.keepOriginalSize
        trimTransparent = settings.trimTransparent
        removeBackgroundToTransparent = settings.removeBackgroundToTransparent
        backgroundRemovalTolerance = settings.backgroundRemovalTolerance
        padToSquare = settings.padToSquare
        fixedSizeText = settings.fixedSizeText
        overwriteExisting = settings.overwriteExisting
        continuousTrainingEnabled = settings.continuousTrainingEnabled
        showGrid = settings.showGrid
        recentFiles = AppSettingsStore.sanitizedRecentFiles(settings.recentFiles).map(Path::toFile)
    }

    internal fun rememberRecentFile(file: File) {
        val path = file.toPath().toAbsolutePath().normalize()
        recentFiles = AppSettingsStore.sanitizedRecentFiles(listOf(path) + recentFiles.map { it.toPath() }).map(Path::toFile)
        persistSettings()
    }

    internal fun normalizeRegionIds(input: List<CropRegion>): List<CropRegion> {
        val usedIds = mutableSetOf<String>()
        var nextId = 1
        fun nextAvailableId(): String {
            while (nextId.toString() in usedIds) nextId++
            return nextId.toString().also {
                usedIds += it
                nextId++
            }
        }
        return input.map { region ->
            val id = region.id.takeIf { it.isNotBlank() && usedIds.add(it) } ?: nextAvailableId()
            region.copy(id = id)
        }
    }

    internal fun regenerateBaseSafely(logResult: Boolean) {
        runCatching {
            when (splitSource) {
                SplitSource.AutoDetect -> rebuildFromAuto(logResult = logResult)
                SplitSource.SmartGrid -> rebuildFromSmartGrid(logResult = logResult)
            }
        }.onFailure {
            lastDetectionResult = emptyDetectionResult()
            applyBaseGeneration(splitSource, emptyList(), false, baseSourceLabel)
            log("图片已导入，但自动识别失败：${it.message ?: it::class.simpleName}")
        }
    }

    private fun updateLayoutState(nextState: LayoutState, bounds: LayoutBounds) {
        val next = layoutPolicy.clamp(nextState, bounds)
        if (next != layoutState) layoutState = next
    }

    private fun clampRegionToImage(region: CropRegion): CropRegion? {
        val loaded = image ?: return region
        val x = region.x.coerceIn(0, (loaded.width - 1).coerceAtLeast(0))
        val y = region.y.coerceIn(0, (loaded.height - 1).coerceAtLeast(0))
        val right = region.right.coerceIn(x + 1, loaded.width)
        val bottom = region.bottom.coerceIn(y + 1, loaded.height)
        val width = right - x
        val height = bottom - y
        if (width <= 0 || height <= 0) return null
        return region.copy(
            x = x,
            y = y,
            width = width,
            height = height
        )
    }

    private fun effectiveDetectionConfig(): DetectionConfig {
        val background = sampledBackgroundArgb
        val predictedBackground = if (background == null) {
            image?.let(::estimateCornerBackgroundArgb)?.let(BackgroundColorModel::predict)
        } else {
            null
        }
        return detectionConfig.copy(
            useManualBackground = background != null || predictedBackground != null,
            manualBackgroundArgb = background ?: predictedBackground ?: 0
        )
    }

    internal fun reopenHistorySnapshot(snapshotId: String) {
        val snapshot = workspaceHistoryEntries.firstOrNull { it.id == snapshotId } ?: run {
            log("未找到历史快照")
            return
        }
        if (!snapshot.allFilesAvailable) {
            log("无法恢复历史快照：缺少 ${snapshot.missingFileCount} 个源文件")
            workspaceHistoryEntries = WorkspaceHistoryStore.load()
            return
        }
        val files = snapshot.sourcePaths.map(Path::toFile)
        if (!loadSnapshotFiles(files, snapshot.currentImageIndex)) return
        restoreHistorySnapshot(snapshot)
        rememberWorkspaceSnapshot()
        historyDialogVisible = false
        log("已重开历史记录：${snapshot.displayTitle}，恢复 ${regions.size} 个区域")
    }

    private fun restoreHistorySnapshot(snapshot: WorkspaceSnapshotEntry) {
        val restoredBase = normalizeSnapshotRegions(snapshot.baseRegions)
        val restoredRegions = normalizeSnapshotRegions(snapshot.regions).ifEmpty { restoredBase }
        splitSource = snapshot.splitSource
        baseRegions = restoredBase.ifEmpty { restoredRegions }
        history.reset(restoredRegions)
        lastDetectionResult = DetectionResult(
            regions = restoredBase.ifEmpty { restoredRegions },
            mode = snapshot.splitSource.toDetectionMode(),
            stats = DetectionStats(0, 0, restoredRegions.size, restoredBase.size, 0, 0)
        )
        previewRegionId = null
        backgroundPickArmed = false
        toolMode = ToolMode.Select
        syncManualEdits()
    }

    private fun currentWorkspaceSourcePaths(): List<Path> {
        val paths = when {
            imageFiles.isNotEmpty() -> imageFiles.map { it.toPath() }
            imageFile != null -> listOf(imageFile!!.toPath())
            else -> emptyList()
        }
        return paths.map { it.toAbsolutePath().normalize() }.distinct()
    }

    private fun buildWorkspaceTitle(): String {
        val files = imageFiles.takeIf { it.isNotEmpty() } ?: imageFile?.let(::listOf).orEmpty()
        if (files.isEmpty()) return "未命名快照"
        if (files.size == 1) return files.first().name
        return "${files.first().name} 等 ${files.size} 张"
    }

    private fun snapshotRegions(input: List<CropRegion>): List<CropRegion> =
        input.map { region -> region.copy(selected = false) }

    private fun normalizeSnapshotRegions(input: List<CropRegion>): List<CropRegion> =
        normalizeRegionIds(snapshotRegions(input)).mapNotNull { clampRegionToImage(it) }

    private fun SplitSource.toDetectionMode(): DetectionMode =
        when (this) {
            SplitSource.AutoDetect -> DetectionMode.FALLBACK_BACKGROUND
            SplitSource.SmartGrid -> DetectionMode.FALLBACK_BACKGROUND
        }

    internal fun openDirectorySafely(path: Path, label: String) {
        runCatching {
            Files.createDirectories(path)
            DesktopPlatform.openDirectory(path)
            log("已打开$label：$path")
        }.onFailure {
            log("打开$label 失败：${it.message}")
        }
    }

    private fun suggestedPreviewFileName(file: File, region: CropRegion): String {
        val base = file.nameWithoutExtension.ifBlank { "preview" }
        return "${base}_region_${region.id}.${outputFormat.extension}"
    }

    private fun detectInsideRegion(region: CropRegion) {
        val loaded = image ?: return
        val clamped = clampRegionToImage(region) ?: return
        val crop = BufferedImage(clamped.width, clamped.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = crop.createGraphics()
        graphics.drawImage(
            loaded,
            0,
            0,
            clamped.width,
            clamped.height,
            clamped.x,
            clamped.y,
            clamped.right,
            clamped.bottom,
            null
        )
        graphics.dispose()
        val detected = detector.detect(crop, effectiveDetectionConfig())
        val shifted = detected.regions.map { detectedRegion ->
            detectedRegion.copy(
                x = detectedRegion.x + clamped.x,
                y = detectedRegion.y + clamped.y,
                selected = true
            )
        }.mapNotNull { clampRegionToImage(it) }
        lastDetectionResult = detected.copy(regions = shifted)
        val additions = shifted.ifEmpty { listOf(clamped.copy(selected = true)) }
        replaceRegions(
            "框选自动识别",
            regions.map { it.copy(selected = false) } + additions
        )
        splitSource = SplitSource.AutoDetect
        log("框选自动识别生成 ${shifted.size} 个区域")
    }

}

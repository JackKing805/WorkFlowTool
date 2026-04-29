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
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.ImageFormat
import io.github.workflowtool.model.NamingMode
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.platform.DesktopPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists

class AppController(
    private val detector: RegionDetector,
    private val exporter: RegionExporter,
    val layoutSpec: LayoutSpec,
    val localization: LocalizationProvider,
    private val layoutPolicy: LayoutConstraintPolicy,
    private val nativeEngine: NativeImageEngine = PythonImageEngine,
    private val previewExporter: IconExporter = IconExporter(),
    private val persistenceEnabled: Boolean = exporter is JvmRegionExporter
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
    var refineBrushSizePx by mutableStateOf(DefaultRefineBrushSizePx)
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
    val canRegenerateBase: Boolean get() = isAutoDetectAvailable
    val selectedRegion: CropRegion?
        get() = regions.lastOrNull { it.selected }
    val previewRegion: CropRegion?
        get() = previewRegionId?.let { id -> regions.lastOrNull { it.id == id } }
    val baseSourceLabel: String get() = "自动识别"
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
        get() {
            val selectedCount = regions.count { it.selected }
            return when {
                selectedCount > 1 -> "已选 $selectedCount 个"
                else -> selectedRegion?.let { "${it.x}, ${it.y}, ${it.width} x ${it.height}" } ?: "-"
            }
        }
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
        applyBaseGeneration(detected.regions, logResult, "自动识别")
    }

    fun rebuildFromAutoAsync(logResult: Boolean = false) {
        runBusy("正在自动识别区域...") {
            rebuildFromAuto(logResult)
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

    private fun applyBaseGeneration(generated: List<CropRegion>, logResult: Boolean, sourceLabel: String) {
        val normalized = normalizeRegionIds(generated).mapNotNull { clampRegionToImage(it) }
        if (normalized == baseRegions && !hasManualEdits) return
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
            val iconSample = appendTrainingSampleResult("手动修正后导出确认")
            val trainingUpdate = ContinuousTrainingUpdate(
                icon = iconSample.success,
                background = appendBackgroundTrainingSample("手动修正后背景确认")
            )
            if (!trainingUpdate.hasUpdates) {
                log("持续学习未更新：没有可用的用户确认训练样本")
                return
            }
            if (retrainContinuousModels(trainingUpdate, sourceLabel = "导出确认", thumbnailPath = iconSample.imagePath)) {
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

    fun exportPreviewRegionAsync(regionId: String) {
        runBusy("正在导出预览区域...") {
            exportPreviewRegion(regionId)
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
            hasManualEdits = hasManualEdits,
            baseRegions = snapshotRegions(baseRegions),
            regions = snapshotRegions(regions)
        )
        runCatching {
            WorkspaceHistoryStore.upsert(
                existing = workspaceHistoryEntries,
                entry = entry,
                preview = buildWorkspaceSnapshotPreview(
                    image = loaded,
                    regions = regions,
                    zoom = zoom,
                    viewportOffset = viewportOffset,
                    viewportSize = viewportSize,
                    showGrid = showGrid
                )
            )
        }.onSuccess {
            workspaceHistoryEntries = it
        }.onFailure {
            log("历史记录保存失败：${it.message ?: it::class.simpleName}")
        }
    }

    internal fun persistSettings() {
        if (!persistenceEnabled) return
        AppSettingsStore.save(
            AppSettings(
                detectionConfig = detectionConfig,
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
            rebuildFromAuto(logResult = logResult)
        }.onFailure {
            lastDetectionResult = emptyDetectionResult()
            applyBaseGeneration(emptyList(), false, baseSourceLabel)
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
        baseRegions = restoredBase.ifEmpty { restoredRegions }
        history.reset(restoredRegions)
        lastDetectionResult = DetectionResult(
            regions = restoredBase.ifEmpty { restoredRegions },
            mode = DetectionMode.FALLBACK_BACKGROUND,
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

    internal fun detectInsideRegion(region: CropRegion) {
        val loaded = image ?: return
        val clamped = clampRegionToImage(region) ?: return
        val crop = cropImageRegion(loaded, clamped)
        val detected = detector.detect(crop, effectiveDetectionConfig())
        val shifted = detected.regions.map { detectedRegion ->
            detectedRegion.copy(
                x = detectedRegion.x + clamped.x,
                y = detectedRegion.y + clamped.y,
                selected = true
            )
        }.mapNotNull { clampRegionToImage(it) }
        lastDetectionResult = detected.copy(regions = shifted)
        val addition = if (shifted.isNotEmpty()) {
            mergeRegionsToOuterMask(loaded.width, loaded.height, shifted, clamped.id)
                ?.copy(selected = true)
                ?: shifted.first().copy(selected = true)
        } else {
            snapUserRegionToForeground(clamped)
                ?: clamped.copy(selected = true)
        }
        replaceRegions(
            "框选自动识别",
            regions.map { it.copy(selected = false) } + addition
        )
        if (shifted.isEmpty()) {
            if (addition.hasMask()) {
                log("框选自动识别未生成区域，已将框选范围自动贴合图标边缘")
            } else {
                log("框选自动识别未生成区域，已将框选范围作为图标区域")
            }
        } else {
            log("框选自动识别生成 ${shifted.size} 个区域，已合并贴合为单个区域")
        }
    }

    internal fun redetectUserRegionAsWhole(region: CropRegion): CropRegion? {
        val loaded = image ?: return null
        val clamped = clampRegionToImage(region) ?: return null
        val config = effectiveDetectionConfig()
        val backgroundArgb = foregroundSnapBackgroundArgb(loaded)
        val detectionBounds = expandManualRefineDetectionBounds(loaded, clamped, config)
        val crop = cropImageRegion(loaded, detectionBounds)
        val detected = detector.detect(crop, config)
        val shifted = detected.regions.map { detectedRegion ->
            detectedRegion.copy(
                x = detectedRegion.x + detectionBounds.x,
                y = detectedRegion.y + detectionBounds.y,
                selected = true
            )
        }.mapNotNull { clampRegionToImage(it) }
        lastDetectionResult = detected.copy(regions = shifted)
        val maxScore = shifted.mapNotNull { it.score }.maxOrNull()
        val modelSummary = "模型命中 ${shifted.size} 个候选" +
            (maxScore?.let { "，最高置信度 %.2f".format(it) } ?: "")
        val candidate = buildWholeRegionCandidate(
            loaded = loaded,
            shifted = shifted,
            config = config,
            backgroundArgb = backgroundArgb,
            mode = lastDetectionResult.mode,
            constrainToUserMask = clamped.hasMask() && clamped.alphaMask.any { it <= 0 }
        )
        val overlap = candidate?.let { manualRefineOverlapRatio(clamped, it) }
        val hasLockedNegativeMask = clamped.hasMask() && clamped.alphaMask.any { it <= 0 }
        val modelUsable = candidate != null &&
            (!hasLockedNegativeMask || (overlap ?: 0.0) >= (1.0 - config.manualRefineConflictTolerance).coerceIn(0.05, 0.95))
        when {
            candidate == null -> log("精修贴合：$modelSummary，未形成有效模型区域，已回退颜色贴边")
            modelUsable -> log(
                "精修贴合：$modelSummary，采用模型候选" +
                    (overlap?.let { "，用户遮罩重叠 %.0f%%".format(it * 100.0) } ?: "")
            )
            else -> log(
                "精修贴合：$modelSummary，模型候选与用户删减区域冲突，已回退颜色贴边" +
                    (overlap?.let { "，重叠 %.0f%%".format(it * 100.0) } ?: "")
            )
        }
        val refined = constrainedRefineUserRegion(
            image = loaded,
            region = clamped.copy(selected = true),
            candidate = candidate,
            config = config,
            backgroundArgb = backgroundArgb,
            mode = lastDetectionResult.mode
        )?.copy(id = clamped.id, visible = clamped.visible, selected = true, score = clamped.score)
        if (refined == null) {
            log("精修贴合失败：模型与颜色贴边都没有生成有效区域")
        }
        return refined
    }

    internal fun snapUserRegionToForeground(region: CropRegion): CropRegion? {
        val loaded = image ?: return null
        val clamped = clampRegionToImage(region) ?: return null
        return snapRegionToForeground(
            image = loaded,
            region = clamped.copy(selected = true),
            config = effectiveDetectionConfig(),
            backgroundArgb = foregroundSnapBackgroundArgb(loaded),
            mode = lastDetectionResult.mode
        )?.copy(id = clamped.id, visible = clamped.visible, selected = true, score = clamped.score)
    }

    internal fun snapUserRegionToForegroundWhole(region: CropRegion): CropRegion? {
        val loaded = image ?: return null
        val clamped = clampRegionToImage(region) ?: return null
        return snapRegionToForegroundWhole(
            image = loaded,
            region = clamped.copy(selected = true),
            config = effectiveDetectionConfig(),
            backgroundArgb = foregroundSnapBackgroundArgb(loaded),
            mode = lastDetectionResult.mode
        )?.copy(id = clamped.id, visible = clamped.visible, selected = true, score = clamped.score)
    }

    private fun buildWholeRegionCandidate(
        loaded: BufferedImage,
        shifted: List<CropRegion>,
        config: DetectionConfig,
        backgroundArgb: Int,
        mode: DetectionMode,
        constrainToUserMask: Boolean
    ): CropRegion? {
        if (shifted.isEmpty()) return null
        val prepared = if (constrainToUserMask) {
            shifted.map { detectedRegion ->
                if (detectedRegion.hasMask()) {
                    detectedRegion
                } else {
                    snapRegionToForeground(loaded, detectedRegion, config, backgroundArgb, mode) ?: detectedRegion
                }
            }
        } else {
            shifted
        }
        return mergeRegionsToOuterMask(loaded.width, loaded.height, prepared, shifted.first().id)
    }

    private fun foregroundSnapBackgroundArgb(loaded: BufferedImage): Int =
        sampledBackgroundArgb
            ?: lastDetectionResult.stats.estimatedBackgroundArgb.takeIf { it != 0 }
            ?: estimateCornerBackgroundArgb(loaded)

    private fun expandManualRefineDetectionBounds(
        loaded: BufferedImage,
        region: CropRegion,
        config: DetectionConfig
    ): CropRegion {
        val adaptivePadding = (maxOf(region.width, region.height) * 0.25f).toInt()
        val padding = maxOf(8, config.manualRefineExpansionRadius, adaptivePadding).coerceAtMost(96)
        val left = (region.x - padding).coerceAtLeast(0)
        val top = (region.y - padding).coerceAtLeast(0)
        val right = (region.right + padding).coerceAtMost(loaded.width)
        val bottom = (region.bottom + padding).coerceAtMost(loaded.height)
        return region.copy(
            x = left,
            y = top,
            width = (right - left).coerceAtLeast(1),
            height = (bottom - top).coerceAtLeast(1),
            alphaMask = emptyList(),
            maskWidth = 0,
            maskHeight = 0
        )
    }

    private fun cropImageRegion(loaded: BufferedImage, region: CropRegion): BufferedImage {
        val crop = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = crop.createGraphics()
        graphics.drawImage(
            loaded,
            0,
            0,
            region.width,
            region.height,
            region.x,
            region.y,
            region.right,
            region.bottom,
            null
        )
        graphics.dispose()
        return crop
    }

}

const val MinRefineBrushSizePx = 4
const val MaxRefineBrushSizePx = 64
const val DefaultRefineBrushSizePx = 18
const val RefineBrushSizeStepPx = 2

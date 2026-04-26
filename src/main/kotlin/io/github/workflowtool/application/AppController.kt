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
import io.github.workflowtool.model.RegionPoint
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
    private val nativeEngine: NativeImageEngine = CppOnlyNativeImageEngine,
    private val previewExporter: IconExporter = IconExporter()
) {
    private val history = EditorHistory()
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var baseRegions: List<CropRegion> = emptyList()
    private var lastDetectionResult by mutableStateOf(emptyDetectionResult())
    var busyMessage by mutableStateOf<String?>(null)
        private set

    var imageFile by mutableStateOf<File?>(null)
        private set
    var imageFiles by mutableStateOf<List<File>>(emptyList())
        private set
    var currentImageIndex by mutableStateOf(-1)
        private set
    var image by mutableStateOf<BufferedImage?>(null)
        private set
    var splitSource by mutableStateOf(SplitSource.AutoDetect)
        private set
    var toolMode by mutableStateOf(ToolMode.Select)
        private set
    var zoom by mutableStateOf(1.0f)
        private set
    var viewportOffset by mutableStateOf(Offset.Zero)
        private set
    var viewportSize by mutableStateOf(Size.Zero)
        private set
    var hoveredImagePoint by mutableStateOf<Offset?>(null)
        private set
    var showGrid by mutableStateOf(true)
        private set
    var detectionConfig by mutableStateOf(DetectionConfig())
        private set
    var sampledBackgroundArgb by mutableStateOf<Int?>(null)
        private set
    var backgroundPickArmed by mutableStateOf(false)
        private set
    var magicTolerance by mutableStateOf(detectionConfig.colorDistanceThreshold)
        private set
    var gridConfig by mutableStateOf(GridConfig())
        private set
    var outputDirectory by mutableStateOf(DesktopPlatform.defaultOutputDirectory())
        private set
    var outputFormat by mutableStateOf(ImageFormat.PNG)
        private set
    var namingMode by mutableStateOf(NamingMode.Sequence)
        private set
    var customPrefix by mutableStateOf("icon")
        private set
    var keepOriginalSize by mutableStateOf(true)
        private set
    var trimTransparent by mutableStateOf(false)
        private set
    var removeBackgroundToTransparent by mutableStateOf(false)
        private set
    var backgroundRemovalTolerance by mutableStateOf(20)
        private set
    var padToSquare by mutableStateOf(false)
        private set
    var fixedSizeText by mutableStateOf("")
        private set
    var overwriteExisting by mutableStateOf(false)
        private set
    var continuousTrainingEnabled by mutableStateOf(false)
        private set
    var showAdvancedSettings by mutableStateOf(false)
        private set
    var previewRegionId by mutableStateOf<String?>(null)
        private set
    var hasManualEdits by mutableStateOf(false)
        private set
    var magicSelectionPreview by mutableStateOf<MagicSelectionPreview?>(null)
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

    val regions: List<CropRegion> get() = history.state.regions
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val isBusy: Boolean get() = busyMessage != null
    val isNativeSplitAvailable: Boolean get() = nativeEngine.isAvailable
    val selectedRegion: CropRegion? get() = regions.lastOrNull { it.selected }
    val previewRegion: CropRegion? get() = previewRegionId?.let { id -> regions.lastOrNull { it.id == id } }
    val baseSourceLabel: String
        get() = when (splitSource) {
            SplitSource.AutoDetect -> "自动识别"
            SplitSource.SmartGrid -> "智能网格"
        }
    val manualStatusLabel: String
        get() = if (hasManualEdits) "已修改" else "未修改"
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

    fun log(message: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logs += "[$time] $message"
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

    fun chooseImageFile() {
        DesktopPlatform.chooseImageFile()?.let(::loadFileAsync)
    }

    fun chooseOutputDirectory() {
        DesktopPlatform.chooseDirectory()?.let { outputDirectory = it.toPath() }
    }

    fun openOutputDirectory() {
        openDirectorySafely(outputDirectory, "输出目录")
    }

    fun openRuntimeDirectory() {
        openDirectorySafely(AppRuntimeFiles.runtimeRoot, "应用运行目录")
    }

    fun openPythonRuntimeDirectory() {
        openDirectorySafely(AppRuntimeFiles.pythonDir, "Python 运行目录")
    }

    fun openTrainingSetDirectory() {
        openDirectorySafely(AppRuntimeFiles.pythonDir.resolve("training_sets"), "训练集目录")
    }

    fun openModelDirectory() {
        openDirectorySafely(AppRuntimeFiles.pythonDir.resolve("model"), "模型目录")
    }

    fun openNativeRuntimeDirectory() {
        openDirectorySafely(AppRuntimeFiles.runtimeRoot.resolve("native"), "Native 运行目录")
    }

    fun loadFile(file: File) {
        imageFiles = listOf(file)
        currentImageIndex = 0
        loadFileContent(file)
    }

    private fun loadFileContent(file: File) {
        runCatching {
            val loaded = ImageIO.read(file) ?: error("不支持的图片格式")
            imageFile = file
            image = loaded
            zoom = 1.0f
            viewportOffset = Offset.Zero
            hoveredImagePoint = null
            backgroundPickArmed = false
            magicSelectionPreview = null
            log("图片加载成功：${file.name}，${loaded.width} x ${loaded.height}")
            regenerateBaseSafely(logResult = true)
        }.onFailure {
            log("图片加载失败：${it.message}")
        }
    }

    fun loadFileAsync(file: File) {
        runBusy("正在导入并识别图片...") {
            loadFile(file)
        }
    }

    fun loadFilesAsync(files: List<File>) {
        val images = files.filter { it.isFile && it.extension.lowercase() in supportedImageExtensions }
        if (images.isEmpty()) {
            log("未找到可导入的图片文件")
            return
        }
        runBusy("正在拖入 ${images.size} 张图片...") {
            appendDroppedFiles(images)
        }
    }

    fun loadFiles(files: List<File>) {
        val images = files.filter { it.isFile && it.extension.lowercase() in supportedImageExtensions }
        if (images.isEmpty()) {
            log("未找到可导入的图片文件")
            return
        }
        if (images.size == 1) {
            loadFile(images.first())
            return
        }
        runCatching {
            val loaded = images.mapNotNull { file ->
                ImageIO.read(file)?.let { file to it }
            }
            if (loaded.isEmpty()) error("没有可读取的图片")
            val combined = combineImages(loaded.map { it.second }, multiImageGap)
            imageFiles = loaded.map { it.first }
            currentImageIndex = 0
            imageFile = loaded.first().first
            image = combined
            zoom = 1.0f
            viewportOffset = Offset.Zero
            hoveredImagePoint = null
            backgroundPickArmed = false
            magicSelectionPreview = null
            log("多图画布加载成功：${loaded.size} 张，${combined.width} x ${combined.height}，间隔 ${multiImageGap}px")
            regenerateBaseSafely(logResult = true)
        }.onFailure {
            log("多图导入失败：${it.message}")
        }
    }

    private fun appendDroppedFiles(files: List<File>) {
        val current = image
        if (current == null) {
            loadFiles(files)
            return
        }
        runCatching {
            val loaded = files.mapNotNull { file ->
                ImageIO.read(file)?.let { file to it }
            }
            if (loaded.isEmpty()) error("没有可读取的图片")
            val combined = appendImagesToCanvas(current, loaded.map { it.second }, multiImageGap)
            imageFiles = imageFiles + loaded.map { it.first }
            image = combined
            hoveredImagePoint = null
            backgroundPickArmed = false
            magicSelectionPreview = null
            log("已拖入追加 ${loaded.size} 张图片：画布 ${combined.width} x ${combined.height}，原有选框已保留")
        }.onFailure {
            log("拖入图片失败：${it.message}")
        }
    }

    fun openPreviousImage() {
        val files = imageFiles
        if (files.size <= 1 || currentImageIndex <= 0) return
        runBusy("正在切换图片...") {
            currentImageIndex -= 1
            loadFileContent(files[currentImageIndex])
        }
    }

    fun openNextImage() {
        val files = imageFiles
        if (files.size <= 1 || currentImageIndex !in 0 until files.lastIndex) return
        runBusy("正在切换图片...") {
            currentImageIndex += 1
            loadFileContent(files[currentImageIndex])
        }
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
        magicSelectionPreview = null
        log("已重置手工修正")
    }

    private fun applyBaseGeneration(source: SplitSource, generated: List<CropRegion>, logResult: Boolean, sourceLabel: String) {
        val normalized = normalizeRegionIds(generated).mapNotNull { clampRegionToImage(it) }
        if (source == splitSource && normalized == baseRegions && !hasManualEdits) return
        splitSource = source
        baseRegions = normalized
        history.reset(normalized)
        hasManualEdits = false
        toolMode = ToolMode.Select
        magicSelectionPreview = null
        if (logResult) {
            log("$sourceLabel 生成 ${normalized.size} 个区域")
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
                magic = appendMagicTrainingSample("手动修正后魔棒确认"),
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
            val written = previewExporter.exportSingle(loaded, region, config, destination)
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
        if (mode != ToolMode.Magic) {
            magicSelectionPreview = null
        }
    }

    fun enterManualDrawMode() {
        updateToolMode(ToolMode.Draw)
    }

    fun enterMagicSelectionMode() {
        updateToolMode(ToolMode.Magic)
    }

    fun applyMagicSelection(point: Offset) {
        val loaded = image ?: return
        val x = point.x.toInt()
        val y = point.y.toInt()
        val result = detectMagicAt(loaded, x, y)
        if (result == null) {
            magicSelectionPreview = null
            log("Magic selection found no usable region at ($x, $y)")
            return
        }
        val targetRegion = findMagicReplaceTarget(x, y)
        val created = result.region
        val regionId = replaceOrAppendMagicRegion("Magic selection", created, targetRegion)
        magicSelectionPreview = result.toPreview(regionId)
        if (targetRegion != null) {
            log("Magic selection refreshed ${created.width} x ${created.height} at ($x, $y), tolerance=$magicTolerance")
        } else {
            log("Magic selection created ${created.width} x ${created.height} at ($x, $y), tolerance=$magicTolerance")
        }
    }

    fun exportRegionsAsync() {
        runBusy("正在裁剪导出...") {
            exportRegions()
        }
    }

    fun extendMagicSelection(point: Offset) {
        val loaded = image ?: return
        val preview = magicSelectionPreview ?: run {
            applyMagicSelection(point)
            return
        }
        val x = point.x.toInt()
        val y = point.y.toInt()
        if (x !in 0 until loaded.width || y !in 0 until loaded.height) return
        if (magicMaskContains(preview, x, y)) return

        val result = detectMagicAt(loaded, x, y) ?: return
        val merged = mergeMagicMasks(preview, result, detectionConfig.bboxPadding) ?: return
        val existing = preview.regionId?.let { targetId -> regions.firstOrNull { it.id == targetId } }
        val regionId = replaceOrAppendMagicRegion("Magic selection extend", merged.region, existing, trackHistory = false)
        magicSelectionPreview = merged.toPreview(x, y, regionId, preview)
    }

    fun updateMagicTolerance(next: Int) {
        val sanitized = next.coerceIn(1, 255)
        if (magicTolerance == sanitized) return
        magicTolerance = sanitized
        refreshMagicSelectionPreview()
    }

    fun adjustZoom(delta: Float) {
        if (delta == 0f) return
        val next = (zoom + delta).coerceIn(0.1f, 8f)
        if (next != zoom) {
            zoom = next
            clampViewport()
        }
    }

    fun updateZoom(value: Float) {
        val next = value.coerceIn(0.1f, 8f)
        if (next != zoom) {
            zoom = next
            clampViewport()
        }
    }

    fun zoomAround(factor: Float, anchor: Offset) {
        if (factor == 0f) return
        val currentZoom = zoom
        val nextZoom = (currentZoom * factor).coerceIn(0.1f, 8f)
        if (nextZoom == currentZoom) return
        val imagePoint = (anchor - viewportOffset) / currentZoom
        zoom = nextZoom
        viewportOffset = anchor - imagePoint * nextZoom
        clampViewport()
    }

    fun panViewport(delta: Offset) {
        if (delta == Offset.Zero) return
        viewportOffset += delta
        clampViewport()
    }

    fun updateViewportSize(viewport: Size) {
        if (viewport == viewportSize) return
        viewportSize = viewport
        clampViewport()
    }

    fun updatePointerHover(point: Offset?) {
        val loaded = image
        hoveredImagePoint = point?.takeIf {
            loaded != null &&
                it.x >= 0f &&
                it.y >= 0f &&
                it.x <= loaded.width.toFloat() &&
                it.y <= loaded.height.toFloat()
        }
    }

    fun fitToViewport() {
        val loaded = image ?: run {
            updateZoom(1.0f)
            viewportOffset = Offset.Zero
            return
        }
        val viewport = viewportSize
        if (viewport.width <= 0f || viewport.height <= 0f) return
        val nextZoom = minOf(viewport.width / loaded.width, viewport.height / loaded.height).coerceIn(0.1f, 8f)
        zoom = nextZoom
        viewportOffset = Offset(
            x = (viewport.width - loaded.width * nextZoom) / 2f,
            y = (viewport.height - loaded.height * nextZoom) / 2f
        )
        clampViewport()
    }

    fun fitSelectionToViewport() {
        focusRegion(selectedRegion ?: run {
            fitToViewport()
            return
        }, fit = true)
    }

    fun focusRegion(regionId: String, fit: Boolean = false) {
        val region = regions.lastOrNull { it.id == regionId } ?: return
        focusRegion(region, fit)
    }

    fun toggleGrid() {
        showGrid = !showGrid
    }

    fun clearRegions() {
        replaceRegions("清空区域", emptyList())
        previewRegionId = null
        magicSelectionPreview = null
        log("已清空区域")
    }

    fun undo() {
        val before = regions
        history.undo()
        if (before != regions) syncManualEdits()
    }

    fun redo() {
        val before = regions
        history.redo()
        if (before != regions) syncManualEdits()
    }

    fun replaceRegions(label: String, updated: List<CropRegion>, trackHistory: Boolean = true) {
        val normalized = normalizeRegionIds(updated)
        history.replaceRegions(label, normalized, trackHistory)
        syncManualEdits()
    }

    fun selectRegion(regionId: String, additive: Boolean = false) {
        if (regions.none { it.id == regionId }) return
        val updated = regions.map { region ->
            when {
                region.id == regionId -> region.copy(selected = true)
                additive -> region
                else -> region.copy(selected = false)
            }
        }
        replaceRegions("选择区域", updated, trackHistory = false)
    }

    fun selectAndFocusRegion(regionId: String, fit: Boolean = false) {
        selectRegion(regionId)
        focusRegion(regionId, fit)
    }

    fun selectAll() = replaceRegions("全选区域", regions.map { it.copy(selected = true) }, trackHistory = false)

    fun invertSelection() =
        replaceRegions("反选区域", regions.map { it.copy(selected = !it.selected) }, trackHistory = false)

    fun clearSelection() =
        replaceRegions("取消选择", regions.map { it.copy(selected = false) }, trackHistory = false)

    fun removeRegion(regionId: String) {
        if (regions.none { it.id == regionId }) return
        replaceRegions("删除区域", regions.filterNot { it.id == regionId })
        if (previewRegionId == regionId) {
            previewRegionId = null
        }
        if (magicSelectionPreview?.regionId == regionId) {
            magicSelectionPreview = null
        }
    }

    fun toggleVisibility(regionId: String) {
        if (regions.none { it.id == regionId }) return
        replaceRegions("切换区域显示", regions.map { if (it.id == regionId) it.copy(visible = !it.visible) else it })
    }

    fun updateDetectionConfig(next: DetectionConfig) {
        if (detectionConfig == next) return
        detectionConfig = next
    }

    fun updateGridConfig(next: GridConfig) {
        if (gridConfig == next) return
        gridConfig = next
    }

    fun updateOutputFormat(next: ImageFormat) {
        if (outputFormat == next) return
        outputFormat = next
    }

    fun updateNamingMode(next: NamingMode) {
        if (namingMode == next) return
        namingMode = next
    }

    fun updateCustomPrefix(next: String) {
        if (customPrefix == next) return
        customPrefix = next
    }

    fun updateKeepOriginalSize(next: Boolean) {
        if (keepOriginalSize == next && (!next || fixedSizeText.isEmpty())) return
        keepOriginalSize = next
        if (next) fixedSizeText = ""
    }

    fun updateTrimTransparent(next: Boolean) {
        if (trimTransparent == next) return
        trimTransparent = next
    }

    fun updateRemoveBackgroundToTransparent(next: Boolean) {
        if (removeBackgroundToTransparent == next) return
        removeBackgroundToTransparent = next
    }

    fun updateBackgroundRemovalTolerance(next: Int) {
        val sanitized = next.coerceIn(0, 255)
        if (backgroundRemovalTolerance == sanitized) return
        backgroundRemovalTolerance = sanitized
    }

    fun updatePadToSquare(next: Boolean) {
        if (padToSquare == next) return
        padToSquare = next
    }

    fun updateFixedSizeText(next: String) {
        val sanitized = next.filter(Char::isDigit)
        if (fixedSizeText == sanitized && (!keepOriginalSize || sanitized.isBlank())) return
        fixedSizeText = sanitized
        if (fixedSizeText.isNotBlank()) keepOriginalSize = false
    }

    fun updateOverwriteExisting(next: Boolean) {
        if (overwriteExisting == next) return
        overwriteExisting = next
    }

    fun updateContinuousTrainingEnabled(next: Boolean) {
        if (continuousTrainingEnabled == next) return
        continuousTrainingEnabled = next
        log(if (next) "持续学习已开启：只有手动修正后导出确认的区域会更新训练集并重训模型" else "持续学习已关闭")
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

    fun openRegionPreview(regionId: String) {
        if (regions.none { it.id == regionId }) return
        previewRegionId = regionId
    }

    fun closeRegionPreview() {
        previewRegionId = null
    }

    fun clearMagicSelectionPreview() {
        magicSelectionPreview = null
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

    private fun runBusy(message: String, action: () -> Unit) {
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

    private fun syncManualEdits() {
        hasManualEdits = trainingComparableRegions(regions) != trainingComparableRegions(baseRegions)
    }

    private fun normalizeRegionIds(input: List<CropRegion>): List<CropRegion> {
        return input.mapIndexed { index, region -> region.copy(id = (index + 1).toString()) }
    }

    private fun regenerateBaseSafely(logResult: Boolean) {
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
            height = height,
            points = region.points.map {
                RegionPoint(
                    x = it.x.coerceIn(0, loaded.width),
                    y = it.y.coerceIn(0, loaded.height)
                )
            }
        )
    }

    private fun refreshMagicSelectionPreview() {
        if (toolMode != ToolMode.Magic) return
        val preview = magicSelectionPreview ?: return
        val loaded = image ?: return
        val result = detectMagicAt(loaded, preview.seedX, preview.seedY) ?: run {
            magicSelectionPreview = null
            return
        }

        val existing = preview.regionId?.let { targetId -> regions.firstOrNull { it.id == targetId } }
        val regionId = replaceOrAppendMagicRegion("Magic selection preview", result.region, existing, trackHistory = false)
        magicSelectionPreview = result.toPreview(regionId)
    }

    private fun detectMagicAt(loaded: BufferedImage, x: Int, y: Int): MagicSelectionResult? {
        if (x !in 0 until loaded.width || y !in 0 until loaded.height) return null
        val tolerance = MagicToleranceModel.predict(loaded.getRGB(x, y), magicTolerance)
        return detectMagicRegion(
            loaded,
            x,
            y,
            detectionConfig.copy(colorDistanceThreshold = tolerance)
        )
    }

    private fun replaceOrAppendMagicRegion(
        label: String,
        region: CropRegion,
        existing: CropRegion?,
        trackHistory: Boolean = true
    ): String {
        val regionId = existing?.id ?: (regions.size + 1).toString()
        val nextRegion = region.copy(
            id = regionId,
            visible = existing?.visible ?: true,
            selected = true
        )
        val updatedRegions = if (existing != null) {
            regions.map { current ->
                when {
                    current.id == existing.id -> nextRegion
                    current.selected -> current.copy(selected = false)
                    else -> current
                }
            }
        } else {
            regions.map { it.copy(selected = false) } + nextRegion
        }
        replaceRegions(label, updatedRegions, trackHistory = trackHistory)
        return regionId
    }

    private fun focusRegion(region: CropRegion, fit: Boolean) {
        if (viewportSize.width <= 0f || viewportSize.height <= 0f) return
        val nextZoom = if (fit) {
            val padding = 40f
            minOf(
                viewportSize.width / (region.width + padding),
                viewportSize.height / (region.height + padding)
            ).coerceIn(0.1f, 8f)
        } else {
            zoom
        }
        zoom = nextZoom
        viewportOffset = Offset(
            x = viewportSize.width / 2f - (region.x + region.width / 2f) * nextZoom,
            y = viewportSize.height / 2f - (region.y + region.height / 2f) * nextZoom
        )
        clampViewport()
    }

    private fun clampViewport() {
        val viewport = viewportSize
        if (viewport.width <= 0f || viewport.height <= 0f) return
    }

    private fun findMagicReplaceTarget(x: Int, y: Int): CropRegion? {
        return regions.lastOrNull { region ->
            region.visible &&
                x in region.x until region.right &&
                y in region.y until region.bottom
        }
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

    private fun appendTrainingSample(sourceLabel: String): Boolean {
        val loaded = image ?: return false
        val visibleRegions = regions.filter { it.visible }
        if (visibleRegions.isEmpty()) return false
        return runCatching {
            val datasetRoot = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("user_feedback")
            val imagesDir = datasetRoot.resolve("images")
            Files.createDirectories(imagesDir)
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
            val sourceName = imageFile?.nameWithoutExtension.orEmpty().ifBlank { "canvas" }
            val safeName = sourceName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "canvas" }
            val targetName = "${timestamp}_${safeName}.png"
            val target = imagesDir.resolve(targetName)
            check(ImageIO.write(loaded, "png", target.toFile())) { "训练样本图片写入失败" }
            val line = buildTrainingJsonLine("images/$targetName", imagePixelHash(loaded), visibleRegions)
            Files.writeString(
                datasetRoot.resolve("annotations.jsonl"),
                line + System.lineSeparator(),
                Charsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            )
            log("持续学习样本已记录：$sourceLabel，${visibleRegions.size} 个区域")
            true
        }.onFailure {
            log("持续学习样本记录失败：${it.message}")
        }.getOrDefault(false)
    }

    private fun appendMagicTrainingSample(sourceLabel: String): Boolean {
        val loaded = image ?: return false
        val preview = magicSelectionPreview ?: return false
        val acceptedRegion = preview.regionId?.let { id -> regions.firstOrNull { it.id == id } } ?: return false
        return runCatching {
            val datasetRoot = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("magic_feedback")
            val imagesDir = datasetRoot.resolve("images")
            Files.createDirectories(imagesDir)
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
            val sourceName = imageFile?.nameWithoutExtension.orEmpty().ifBlank { "canvas" }
            val safeName = sourceName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "canvas" }
            val targetName = "${timestamp}_${safeName}_magic.png"
            val target = imagesDir.resolve(targetName)
            check(ImageIO.write(loaded, "png", target.toFile())) { "魔棒训练样本图片写入失败" }
            val line = buildMagicTrainingJsonLine(
                imagePath = "images/$targetName",
                seedX = preview.seedX,
                seedY = preview.seedY,
                tolerance = magicTolerance,
                region = acceptedRegion
            )
            Files.writeString(
                datasetRoot.resolve("annotations.jsonl"),
                line + System.lineSeparator(),
                Charsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            )
            log("魔棒训练样本已记录：$sourceLabel")
            true
        }.onFailure {
            log("魔棒训练样本记录失败：${it.message}")
        }.getOrDefault(false)
    }

    private fun appendBackgroundTrainingSample(sourceLabel: String): Boolean {
        val loaded = image ?: return false
        val background = sampledBackgroundArgb ?: return false
        return runCatching {
            val datasetRoot = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("background_feedback")
            Files.createDirectories(datasetRoot)
            val edgeArgb = estimateCornerBackgroundArgb(loaded)
            val line = """{"edgeArgb":$edgeArgb,"backgroundArgb":$background}"""
            Files.writeString(
                datasetRoot.resolve("annotations.jsonl"),
                line + System.lineSeparator(),
                Charsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            )
            log("背景训练样本已记录：$sourceLabel")
            true
        }.onFailure {
            log("背景训练样本记录失败：${it.message}")
        }.getOrDefault(false)
    }

    private fun retrainContinuousModels(update: ContinuousTrainingUpdate): Boolean {
        return runCatching {
            busyMessage = "正在用本次结果更新模型..."
            if (update.icon) {
                val makeDataset = runPythonCommand("make_training_set.py")
                check(makeDataset.exitCode == 0) { makeDataset.output.ifBlank { "训练集生成失败" } }
                val recentManifest = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("recent_feedback").resolve("annotations.jsonl")
                if (shouldRunFullIconRetrain()) {
                    val trainCombined = runPythonCommand(
                        "train_icon_detector.py",
                        "--dataset",
                        "training_sets/combined",
                        "--out",
                        "model/combined",
                        "--epochs",
                        "4",
                        "--imgsz",
                        "512",
                        "--batch",
                        "2"
                    )
                    check(trainCombined.exitCode == 0) { trainCombined.output.ifBlank { "模型训练失败" } }
                    log("图标模型已执行全量稳态训练")
                }
                if (recentManifest.exists()) {
                    val trainRecent = runPythonCommand(
                        "train_icon_detector.py",
                        "--dataset",
                        "training_sets/recent_feedback",
                        "--out",
                        "model/combined",
                        "--epochs",
                        "2",
                        "--imgsz",
                        "512",
                        "--batch",
                        "1"
                    )
                    check(trainRecent.exitCode == 0) { trainRecent.output.ifBlank { "最近样本微调失败" } }
                    log("图标模型已执行最近样本快速微调")
                }
            }
            if (update.magic) trainMagicModelIfNeeded()
            if (update.background) trainBackgroundModelIfNeeded()
            log("持续学习模型已更新，下次识别会使用新模型")
            true
        }.onFailure {
            log("持续学习模型更新失败：${it.message}")
        }.getOrDefault(false)
    }

    private fun trainMagicModelIfNeeded() {
        val samples = AppRuntimeFiles.pythonDir
            .resolve("training_sets")
            .resolve("magic_feedback")
            .resolve("annotations.jsonl")
        if (!samples.exists()) return
        val train = runPythonCommand("train_magic_model.py")
        check(train.exitCode == 0) { train.output.ifBlank { "魔棒模型训练失败" } }
        MagicToleranceModel.invalidate()
    }

    private fun trainBackgroundModelIfNeeded() {
        val samples = AppRuntimeFiles.pythonDir
            .resolve("training_sets")
            .resolve("background_feedback")
            .resolve("annotations.jsonl")
        if (!samples.exists()) return
        val train = runPythonCommand("train_background_model.py")
        check(train.exitCode == 0) { train.output.ifBlank { "背景模型训练失败" } }
        BackgroundColorModel.invalidate()
    }

    private fun shouldRunFullIconRetrain(): Boolean {
        val best = AppRuntimeFiles.pythonDir.resolve("model").resolve("combined").resolve("runs").resolve("weights").resolve("best.pt")
        if (!best.exists()) return true
        val feedbackManifest = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("user_feedback").resolve("annotations.jsonl")
        if (!feedbackManifest.exists()) return false
        val sampleCount = runCatching {
            Files.readAllLines(feedbackManifest, Charsets.UTF_8).count { it.isNotBlank() }
        }.getOrDefault(0)
        return sampleCount <= 2 || sampleCount % 5 == 0
    }

    private fun buildTrainingFingerprint(config: ExportConfig): String {
        val source = buildString {
            append("image=").append(imageIdentity()).append('\n')
            append("regions=").append(trainingComparableRegions(regions).filter { it.visible }.joinToString("|") { region ->
                buildString {
                    append(region.x).append(',').append(region.y).append(',')
                        .append(region.width).append(',').append(region.height).append(',')
                        .append(region.visible)
                    append(':')
                    append(region.points.joinToString(";") { "${it.x},${it.y}" })
                }
            }).append('\n')
            append("sampledBackground=").append(sampledBackgroundArgb ?: "auto").append('\n')
            append("magic=").append(magicSelectionPreview?.let { "${it.seedX},${it.seedY},$magicTolerance,${it.regionId}" } ?: "none").append('\n')
            append("format=").append(config.outputFormat).append('\n')
            append("keepOriginalSize=").append(config.keepOriginalSize).append('\n')
            append("trimTransparentPadding=").append(config.trimTransparentPadding).append('\n')
            append("removeBackgroundToTransparent=").append(config.removeBackgroundToTransparent).append('\n')
            append("backgroundArgb=").append(config.backgroundArgb).append('\n')
            append("backgroundTolerance=").append(config.backgroundTolerance).append('\n')
            append("padToSquare=").append(config.padToSquare).append('\n')
            append("fixedSize=").append(config.fixedSize ?: "none")
        }
        return sha256(source)
    }

    private fun imageIdentity(): String {
        val loaded = image ?: return "none"
        val files = imageFiles.takeIf { it.isNotEmpty() } ?: imageFile?.let(::listOf).orEmpty()
        val fileIdentity = files.joinToString("|") { file ->
            "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        }
        return "${loaded.width}x${loaded.height}:$fileIdentity"
    }

    private fun hasTrainingFingerprint(fingerprint: String): Boolean {
        val file = trainingFingerprintFile()
        if (!file.exists()) return false
        return runCatching {
            Files.readAllLines(file, Charsets.UTF_8).any { it == fingerprint }
        }.getOrDefault(false)
    }

    private fun rememberTrainingFingerprint(fingerprint: String) {
        runCatching {
            val file = trainingFingerprintFile()
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                fingerprint + System.lineSeparator(),
                Charsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            )
        }.onFailure {
            log("训练指纹记录失败：${it.message}")
        }
    }

    private fun trainingFingerprintFile(): Path {
        return AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("trained_fingerprints.txt")
    }

    private fun runPythonCommand(vararg args: String): ProcessResult {
        val process = ProcessBuilder(listOf("python") + args)
            .directory(AppRuntimeFiles.pythonDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        return ProcessResult(process.waitFor(), output)
    }

    private fun openDirectorySafely(path: Path, label: String) {
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

}

private operator fun Offset.times(scale: Float): Offset = Offset(x * scale, y * scale)

private operator fun Offset.div(scale: Float): Offset = Offset(x / scale, y / scale)

private data class ProcessResult(val exitCode: Int, val output: String)

private data class ContinuousTrainingUpdate(
    val icon: Boolean,
    val magic: Boolean,
    val background: Boolean
) {
    val hasUpdates: Boolean get() = icon || magic || background
}

private val supportedImageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
private const val multiImageGap = 64

private fun formatArgb(value: Int): String = "#%08X".format(value)

private fun combineImages(images: List<BufferedImage>, gap: Int): BufferedImage {
    val columns = ceil(sqrt(images.size.toDouble())).toInt().coerceAtLeast(1)
    val rows = ceil(images.size / columns.toDouble()).toInt().coerceAtLeast(1)
    val cellWidth = images.maxOf { it.width }
    val cellHeight = images.maxOf { it.height }
    val width = columns * cellWidth + (columns - 1).coerceAtLeast(0) * gap
    val height = rows * cellHeight + (rows - 1).coerceAtLeast(0) * gap
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    images.forEachIndexed { index, image ->
        val column = index % columns
        val row = index / columns
        graphics.drawImage(image, column * (cellWidth + gap), row * (cellHeight + gap), null)
    }
    graphics.dispose()
    return output
}

private fun appendImagesToCanvas(base: BufferedImage, images: List<BufferedImage>, gap: Int): BufferedImage {
    val columns = ceil(sqrt(images.size.toDouble())).toInt().coerceAtLeast(1)
    val rows = ceil(images.size / columns.toDouble()).toInt().coerceAtLeast(1)
    val cellWidth = images.maxOf { it.width }
    val cellHeight = images.maxOf { it.height }
    val gridWidth = columns * cellWidth + (columns - 1).coerceAtLeast(0) * gap
    val gridHeight = rows * cellHeight + (rows - 1).coerceAtLeast(0) * gap
    val offsetX = base.width + gap
    val output = BufferedImage(offsetX + gridWidth, maxOf(base.height, gridHeight), BufferedImage.TYPE_INT_ARGB)
    val graphics = output.createGraphics()
    graphics.drawImage(base, 0, 0, null)
    images.forEachIndexed { index, image ->
        val column = index % columns
        val row = index / columns
        graphics.drawImage(image, offsetX + column * (cellWidth + gap), row * (cellHeight + gap), null)
    }
    graphics.dispose()
    return output
}

private fun trainingComparableRegions(regions: List<CropRegion>): List<CropRegion> {
    return regions.mapIndexed { index, region ->
        region.copy(id = (index + 1).toString(), selected = false)
    }
}

private fun estimateCornerBackgroundArgb(image: BufferedImage): Int {
    val corner = minOf(image.width, image.height, 24).coerceAtLeast(1)
    var a = 0L
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    fun add(x: Int, y: Int) {
        val argb = image.getRGB(x, y)
        a += argb ushr 24 and 0xFF
        r += argb ushr 16 and 0xFF
        g += argb ushr 8 and 0xFF
        b += argb and 0xFF
        count += 1
    }
    for (y in 0 until corner) {
        for (x in 0 until corner) {
            add(x, y)
            add(image.width - 1 - x, y)
            add(x, image.height - 1 - y)
            add(image.width - 1 - x, image.height - 1 - y)
        }
    }
    if (count == 0L) return 0
    return ((a / count).toInt() shl 24) or
        ((r / count).toInt() shl 16) or
        ((g / count).toInt() shl 8) or
        (b / count).toInt()
}

private fun buildTrainingJsonLine(imagePath: String, imageHash: String, regions: List<CropRegion>): String {
    val boxes = regions.joinToString(",") { region ->
        """{"x":${region.x},"y":${region.y},"width":${region.width},"height":${region.height}}"""
    }
    return """{"image":"${escapeJson(imagePath)}","imageHash":"$imageHash","regions":[$boxes]}"""
}

private fun buildMagicTrainingJsonLine(
    imagePath: String,
    seedX: Int,
    seedY: Int,
    tolerance: Int,
    region: CropRegion
): String {
    return """{"image":"${escapeJson(imagePath)}","seedX":$seedX,"seedY":$seedY,"tolerance":$tolerance,"region":{"x":${region.x},"y":${region.y},"width":${region.width},"height":${region.height}}}"""
}

private fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun imagePixelHash(image: BufferedImage): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            digest.update((argb ushr 24 and 0xFF).toByte())
            digest.update((argb ushr 16 and 0xFF).toByte())
            digest.update((argb ushr 8 and 0xFF).toByte())
            digest.update((argb and 0xFF).toByte())
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun escapeJson(value: String): String =
    value.flatMap { char ->
        when (char) {
            '\\' -> listOf('\\', '\\')
            '"' -> listOf('\\', '"')
            '\n' -> listOf('\\', 'n')
            '\r' -> listOf('\\', 'r')
            '\t' -> listOf('\\', 't')
            else -> listOf(char)
        }
    }.joinToString("")

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
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.io.path.exists

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
        runCatching { DesktopPlatform.openDirectory(outputDirectory) }
            .onFailure { log("打开输出目录失败：${it.message}") }
    }

    fun loadFile(file: File) {
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

    fun rebuildFromAuto(logResult: Boolean = false) {
        val loaded = image ?: return
        val file = imageFile
        val detected = detector.detect(loaded, effectiveDetectionConfig())
        lastDetectionResult = detected
        applyBaseGeneration(SplitSource.AutoDetect, detected.regions, logResult, "自动识别")
        if (continuousTrainingEnabled && file != null && regions.any { it.visible }) {
            appendTrainingSample(file, "自动识别")
            retrainContinuousModel()
        }
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
            log("已进入背景取色模式")
        }
    }

    fun clearSampledBackground() {
        if (sampledBackgroundArgb == null) return
        sampledBackgroundArgb = null
        backgroundPickArmed = false
        log("已清除手动背景色")
        if (splitSource == SplitSource.AutoDetect && image != null) {
            rebuildFromAuto(logResult = true)
        }
    }

    fun sampleBackgroundAt(point: Offset) {
        val loaded = image ?: return
        val x = point.x.toInt().coerceIn(0, loaded.width - 1)
        val y = point.y.toInt().coerceIn(0, loaded.height - 1)
        sampledBackgroundArgb = loaded.getRGB(x, y)
        backgroundPickArmed = false
        log("已取背景色 ($x, $y)：${activeBackgroundLabel}")
        if (splitSource == SplitSource.AutoDetect) {
            rebuildFromAuto(logResult = true)
        }
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
        if (continuousTrainingEnabled && result.successCount > 0) {
            appendTrainingSample(file, "导出确认")
            retrainContinuousModel()
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
        if (splitSource == SplitSource.AutoDetect && image != null) rebuildFromAuto(logResult = true)
    }

    fun updateGridConfig(next: GridConfig) {
        if (gridConfig == next) return
        gridConfig = next
        if (splitSource == SplitSource.SmartGrid && image != null) rebuildFromSmartGrid(logResult = true)
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
        log(if (next) "持续学习已开启：识别和导出确认后的区域会更新训练集并重训模型" else "持续学习已关闭")
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
        hasManualEdits = normalizeRegionIds(regions) != baseRegions
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
        return detectMagicRegion(
            loaded,
            x,
            y,
            detectionConfig.copy(colorDistanceThreshold = magicTolerance)
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
        return detectionConfig.copy(
            useManualBackground = background != null,
            manualBackgroundArgb = background ?: 0
        )
    }

    private fun appendTrainingSample(sourceFile: File, sourceLabel: String) {
        val visibleRegions = regions.filter { it.visible }
        if (visibleRegions.isEmpty()) return
        runCatching {
            val datasetRoot = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("user_feedback")
            val imagesDir = datasetRoot.resolve("images")
            Files.createDirectories(imagesDir)
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
            val extension = sourceFile.extension.ifBlank { "png" }
            val targetName = "${timestamp}_${sourceFile.nameWithoutExtension}.${extension}"
            val target = imagesDir.resolve(targetName)
            Files.copy(sourceFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            val line = buildTrainingJsonLine("images/$targetName", visibleRegions)
            Files.writeString(
                datasetRoot.resolve("annotations.jsonl"),
                line + System.lineSeparator(),
                Charsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            )
            log("持续学习样本已记录：$sourceLabel，${visibleRegions.size} 个区域")
        }.onFailure {
            log("持续学习样本记录失败：${it.message}")
        }
    }

    private fun retrainContinuousModel() {
        runCatching {
            busyMessage = "正在用本次结果更新模型..."
            val makeDataset = runPythonCommand("make_training_set.py")
            check(makeDataset.exitCode == 0) { makeDataset.output.ifBlank { "训练集生成失败" } }
            val train = runPythonCommand(
                "train_icon_detector.py",
                "--dataset",
                "training_sets/combined",
                "--out",
                "model/combined",
                "--epochs",
                "3",
                "--imgsz",
                "640",
                "--batch",
                "2"
            )
            check(train.exitCode == 0) { train.output.ifBlank { "模型训练失败" } }
            log("持续学习模型已更新，下次识别会使用新模型")
        }.onFailure {
            log("持续学习模型更新失败：${it.message}")
        }
    }

    private fun runPythonCommand(vararg args: String): ProcessResult {
        val process = ProcessBuilder(listOf("python") + args)
            .directory(AppRuntimeFiles.pythonDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        return ProcessResult(process.waitFor(), output)
    }

    private fun suggestedPreviewFileName(file: File, region: CropRegion): String {
        val base = file.nameWithoutExtension.ifBlank { "preview" }
        return "${base}_region_${region.id}.${outputFormat.extension}"
    }

}

private operator fun Offset.times(scale: Float): Offset = Offset(x * scale, y * scale)

private operator fun Offset.div(scale: Float): Offset = Offset(x / scale, y / scale)

private data class ProcessResult(val exitCode: Int, val output: String)

private fun formatArgb(value: Int): String = "#%08X".format(value)

private fun buildTrainingJsonLine(imagePath: String, regions: List<CropRegion>): String {
    val boxes = regions.joinToString(",") { region ->
        """{"x":${region.x},"y":${region.y},"width":${region.width},"height":${region.height}}"""
    }
    return """{"image":"${escapeJson(imagePath)}","regions":[$boxes]}"""
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

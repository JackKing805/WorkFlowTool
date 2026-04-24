package io.github.workflowtool.application

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
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
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.io.path.exists

data class MagicSelectionPreview(
    val seedX: Int,
    val seedY: Int,
    val regionId: String?,
    val mask: BooleanArray,
    val imageWidth: Int,
    val imageHeight: Int,
    val pixelCount: Int
)

class AppController(
    private val detector: RegionDetector,
    private val splitter: RegionSplitter,
    private val exporter: RegionExporter,
    val layoutSpec: LayoutSpec,
    val localization: LocalizationProvider,
    private val layoutPolicy: LayoutConstraintPolicy,
    private val nativeEngine: NativeImageEngine = CppOnlyNativeImageEngine
) {
    private val history = EditorHistory()

    private var baseRegions: List<CropRegion> = emptyList()
    private var lastDetectionResult by mutableStateOf(
        DetectionResult(
            regions = baseRegions,
            mode = DetectionMode.FALLBACK_BACKGROUND,
            stats = DetectionStats(0, 0, 0, 0, 0, 0)
        )
    )

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
        get() = "#%08X".format(lastDetectionResult.stats.estimatedBackgroundArgb)
    val activeBackgroundLabel: String
        get() = sampledBackgroundArgb?.let { "#%08X".format(it) } ?: "自动"
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
        val next = layoutPolicy.clamp(layoutState, bounds)
        if (next != layoutState) layoutState = next
    }

    fun resizeLeftPanel(delta: Dp, bounds: LayoutBounds) {
        if (delta.value == 0f) return
        val next = layoutPolicy.clamp(layoutState.copy(leftPanelWidth = layoutState.leftPanelWidth + delta), bounds)
        if (next != layoutState) layoutState = next
    }

    fun resizeRightPanel(delta: Dp, bounds: LayoutBounds) {
        if (delta.value == 0f) return
        val next = layoutPolicy.clamp(layoutState.copy(rightPanelWidth = layoutState.rightPanelWidth - delta), bounds)
        if (next != layoutState) layoutState = next
    }

    fun resizePreview(delta: Dp, bounds: LayoutBounds) {
        if (delta.value == 0f) return
        val next = layoutPolicy.clamp(layoutState.copy(previewHeight = layoutState.previewHeight + delta), bounds)
        if (next != layoutState) layoutState = next
    }

    fun chooseImageFile() {
        DesktopPlatform.chooseImageFile()?.let(::loadFile)
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
            rebuildFromAuto(logResult = true)
        }.onFailure {
            log("图片加载失败：${it.message}")
        }
    }

    fun rebuildFromAuto(logResult: Boolean = false) {
        val loaded = image ?: return
        val detected = detector.detect(loaded, effectiveDetectionConfig())
        lastDetectionResult = detected
        applyBaseGeneration(SplitSource.AutoDetect, detected.regions, logResult, "自动识别")
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

    fun regenerateBase() {
        when (splitSource) {
            SplitSource.AutoDetect -> rebuildFromAuto(logResult = true)
            SplitSource.SmartGrid -> rebuildFromSmartGrid(logResult = true)
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
        val normalized = normalizeRegionIds(generated)
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
            destination.parent?.let(Files::createDirectories)
            val config = buildExportConfig()
            val cropped = cropRegionImage(loaded, region)
            val processed = processPreviewImage(cropped, config)
            val writable = prepareImageForFormat(processed, config.outputFormat)
            val written = ImageIO.write(writable, config.outputFormat.imageIoName, destination.toFile())
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
        val result = detectMagicRegion(loaded, x, y, detectionConfig.copy(colorDistanceThreshold = magicTolerance))
        if (result == null) {
            magicSelectionPreview = null
            log("Magic selection found no usable region at ($x, $y)")
            return
        }
        val targetRegion = findMagicReplaceTarget(x, y)
        val created = result.region
        val updatedRegions = if (targetRegion != null) {
            regions.map { region ->
                when {
                    region.id == targetRegion.id -> created.copy(
                        id = targetRegion.id,
                        visible = targetRegion.visible,
                        selected = true
                    )
                    region.selected -> region.copy(selected = false)
                    else -> region
                }
            }
        } else {
            regions.map { it.copy(selected = false) } + created
        }
        replaceRegions("Magic selection", updatedRegions)
        val regionId = targetRegion?.id ?: regions.lastOrNull()?.id
        magicSelectionPreview = MagicSelectionPreview(
            seedX = result.seedX,
            seedY = result.seedY,
            regionId = regionId,
            mask = result.mask,
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight,
            pixelCount = result.pixelCount
        )
        if (targetRegion != null) {
            log("Magic selection refreshed ${created.width} x ${created.height} at ($x, $y), tolerance=$magicTolerance")
        } else {
            log("Magic selection created ${created.width} x ${created.height} at ($x, $y), tolerance=$magicTolerance")
        }
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

    private fun syncManualEdits() {
        hasManualEdits = normalizeRegionIds(regions) != baseRegions
    }

    private fun normalizeRegionIds(input: List<CropRegion>): List<CropRegion> {
        return input.mapIndexed { index, region -> region.copy(id = (index + 1).toString()) }
    }

    private fun refreshMagicSelectionPreview() {
        if (toolMode != ToolMode.Magic) return
        val preview = magicSelectionPreview ?: return
        val loaded = image ?: return
        val result = detectMagicRegion(
            loaded,
            preview.seedX,
            preview.seedY,
            detectionConfig.copy(colorDistanceThreshold = magicTolerance)
        ) ?: run {
            magicSelectionPreview = null
            return
        }

        val existing = preview.regionId?.let { targetId -> regions.firstOrNull { it.id == targetId } }
        val nextRegion = result.region.copy(
            id = existing?.id ?: result.region.id,
            visible = existing?.visible ?: true,
            selected = true
        )
        val updatedRegions = if (existing != null) {
            regions.map { region ->
                when {
                    region.id == existing.id -> nextRegion
                    region.selected -> region.copy(selected = false)
                    else -> region
                }
            }
        } else {
            regions.map { it.copy(selected = false) } + nextRegion
        }
        replaceRegions("Magic selection preview", updatedRegions, trackHistory = false)
        magicSelectionPreview = MagicSelectionPreview(
            seedX = result.seedX,
            seedY = result.seedY,
            regionId = nextRegion.id,
            mask = result.mask,
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight,
            pixelCount = result.pixelCount
        )
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

    private fun ensureNativeSplitAvailable(): Boolean {
        if (nativeEngine.isAvailable) return true
        log("C++ 检测后端不可用：${nativeEngine.detail}")
        return false
    }

    private fun findMagicReplaceTarget(x: Int, y: Int): CropRegion? {
        val previewRegionId = magicSelectionPreview?.regionId ?: return null
        return regions.lastOrNull { region ->
            region.id == previewRegionId &&
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
    private fun suggestedPreviewFileName(file: File, region: CropRegion): String {
        val base = file.nameWithoutExtension.ifBlank { "preview" }
        return "${base}_region_${region.id}.${outputFormat.extension}"
    }

    private fun cropRegionImage(image: BufferedImage, region: CropRegion): BufferedImage {
        val x = region.x.coerceIn(0, image.width - 1)
        val y = region.y.coerceIn(0, image.height - 1)
        val width = region.width.coerceAtMost(image.width - x).coerceAtLeast(1)
        val height = region.height.coerceAtMost(image.height - y).coerceAtLeast(1)
        return image.getSubimage(x, y, width, height)
    }

    private fun processPreviewImage(input: BufferedImage, config: ExportConfig): BufferedImage {
        var current = if (config.trimTransparentPadding) trimTransparentImage(input) else copyImage(input)
        if (config.padToSquare) current = padImageToSquare(current)
        config.fixedSize?.takeIf { it > 0 && !config.keepOriginalSize }?.let { size ->
            current = resizeImage(current, size, size)
        }
        return current
    }

    private fun copyImage(input: BufferedImage): BufferedImage {
        val output = BufferedImage(input.width, input.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.drawImage(input, 0, 0, null)
        graphics.dispose()
        return output
    }

    private fun trimTransparentImage(input: BufferedImage): BufferedImage {
        var minX = input.width
        var minY = input.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                val alpha = input.getRGB(x, y) ushr 24
                if (alpha > 0) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        if (maxX < minX || maxY < minY) return copyImage(input)
        return copyImage(input.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1))
    }

    private fun padImageToSquare(input: BufferedImage): BufferedImage {
        val size = maxOf(input.width, input.height)
        val output = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.drawImage(input, (size - input.width) / 2, (size - input.height) / 2, null)
        graphics.dispose()
        return output
    }

    private fun resizeImage(input: BufferedImage, width: Int, height: Int): BufferedImage {
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(input, 0, 0, width, height, null)
        graphics.dispose()
        return output
    }

    private fun prepareImageForFormat(input: BufferedImage, format: ImageFormat): BufferedImage {
        if (format == ImageFormat.PNG || format == ImageFormat.WEBP) return input
        val output = BufferedImage(input.width, input.height, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, output.width, output.height)
        graphics.drawImage(input, 0, 0, null)
        graphics.dispose()
        return output
    }
}

private operator fun Offset.times(scale: Float): Offset = Offset(x * scale, y * scale)

private operator fun Offset.div(scale: Float): Offset = Offset(x / scale, y / scale)

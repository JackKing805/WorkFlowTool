package io.github.workflowtool.application

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.workflowtool.core.IconExporter
import io.github.workflowtool.domain.LayoutBounds
import io.github.workflowtool.domain.LayoutConstraintPolicy
import io.github.workflowtool.domain.LayoutState
import io.github.workflowtool.domain.LocalizationProvider
import io.github.workflowtool.domain.NativeImageEngine
import io.github.workflowtool.domain.RegionDetector
import io.github.workflowtool.domain.RegionExporter
import io.github.workflowtool.domain.RegionSplitter
import io.github.workflowtool.domain.StringKey
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.ExportResult
import io.github.workflowtool.model.GridConfig
import java.awt.image.BufferedImage

abstract class BaseRegionDetector : RegionDetector {
    final override fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult {
        if (image.width <= 0 || image.height <= 0) {
            return DetectionResult(
                regions = emptyList(),
                mode = DetectionMode.FALLBACK_BACKGROUND,
                stats = DetectionStats(0, 0, 0, 0, 0, 0)
            )
        }
        return doDetect(image, config)
    }

    protected abstract fun doDetect(image: BufferedImage, config: DetectionConfig): DetectionResult
}

abstract class BaseRegionExporter : RegionExporter {
    final override fun export(
        image: BufferedImage,
        sourceFileName: String,
        regions: List<CropRegion>,
        config: ExportConfig
    ): ExportResult {
        if (regions.none { it.visible }) {
            return ExportResult(successCount = 0, failureCount = 1, failures = listOf("No visible regions"))
        }
        return doExport(image, sourceFileName, regions, config)
    }

    protected abstract fun doExport(
        image: BufferedImage,
        sourceFileName: String,
        regions: List<CropRegion>,
        config: ExportConfig
    ): ExportResult
}

class CppRegionDetector(
    private val bridge: NativeDetectorBridge = CppDetectorBridge
) : BaseRegionDetector() {
    override fun doDetect(image: BufferedImage, config: DetectionConfig): DetectionResult {
        return bridge.detect(image, config) ?: DetectionResult(
            regions = emptyList(),
            mode = DetectionMode.FALLBACK_BACKGROUND,
            stats = DetectionStats(0, 0, 0, 0, 0, 0)
        )
    }
}

class CppNativeImageEngine : NativeImageEngine {
    override val isAvailable: Boolean = CppDetectorBridge.isLoaded
    override val backendName: String = "C++"
    override val detail: String = CppDetectorBridge.status
}

class CppRegionSplitter(
    private val bridge: NativeDetectorBridge = CppDetectorBridge
) : RegionSplitter {
    override fun split(image: BufferedImage, config: GridConfig): List<CropRegion> =
        bridge.splitGrid(image, config).orEmpty()
}

class JvmRegionExporter(
    private val delegate: IconExporter = IconExporter()
) : BaseRegionExporter() {
    override fun doExport(
        image: BufferedImage,
        sourceFileName: String,
        regions: List<CropRegion>,
        config: ExportConfig
    ): ExportResult = delegate.export(image, sourceFileName, regions, config)
}

object UnavailableNativeImageEngine : NativeImageEngine {
    override val isAvailable: Boolean = false
    override val backendName: String = "C++"
    override val detail: String = "native unavailable"
}

object CppOnlyNativeImageEngine : NativeImageEngine {
    override val isAvailable: Boolean
        get() = CppDetectorBridge.isLoaded
    override val backendName: String = "C++"
    override val detail: String
        get() = CppDetectorBridge.status
}

object DefaultLocalizationProvider : LocalizationProvider {
    private val zhCn = mapOf(
        StringKey.AppTitle to "图标自动裁剪工具 v1.1.0",
        StringKey.PreviewTitle to "预览（自定义区域）",
        StringKey.LogTitle to "处理日志",
        StringKey.SelectImageTitle to "1. 选择图片",
        StringKey.SplitSettingsTitle to "2. 拆分设置",
        StringKey.OutputSettingsTitle to "3. 输出设置",
        StringKey.RegionsTitle to "区域列表",
        StringKey.ProcessTitle to "4. 处理",
        StringKey.OpenImage to "打开图片",
        StringKey.AutoMode to "自动识别",
        StringKey.GridMode to "网格拆分",
        StringKey.BaseGeneration to "基础生成",
        StringKey.ManualAdjustments to "手工修正",
        StringKey.CurrentBaseSource to "当前基础来源",
        StringKey.ManualEditsActive to "手工修正状态",
        StringKey.Regenerate to "重新生成",
        StringKey.ResetManualEdits to "重置手工修正",
        StringKey.SmartGridSettings to "智能网格参数",
        StringKey.GridColumns to "列数",
        StringKey.GridRows to "行数",
        StringKey.GridCellWidth to "单元宽",
        StringKey.GridCellHeight to "单元高",
        StringKey.SearchPadding to "搜索外扩",
        StringKey.SnapToContent to "吸附内容边界",
        StringKey.IgnoreEmptyCells to "忽略空白格",
        StringKey.TrimCellToContent to "按内容收缩单元格",
        StringKey.DrawRectangle to "绘制矩形",
        StringKey.MagicTool to "魔棒",
        StringKey.MergeNearby to "合并相邻区域",
        StringKey.RemoveSmall to "移除小区域",
        StringKey.MinSize to "最小尺寸",
        StringKey.GapThreshold to "间距阈值",
        StringKey.OutputFormat to "输出格式",
        StringKey.OutputDirectory to "输出目录",
        StringKey.NamingMode to "图标命名方式",
        StringKey.KeepOriginalSize to "保持原始大小",
        StringKey.TrimTransparent to "去除透明边距",
        StringKey.PadToSquare to "补齐为正方形",
        StringKey.SelectTool to "选择",
        StringKey.MoveTool to "移动",
        StringKey.Undo to "撤销",
        StringKey.Redo to "重做",
        StringKey.ClearRegions to "清空区域",
        StringKey.FitWindow to "适应窗口",
        StringKey.FitSelection to "适应选区",
        StringKey.GridToggle to "网格",
        StringKey.DetectionMode to "检测模式",
        StringKey.DetectionTime to "检测耗时",
        StringKey.CandidatePixels to "候选像素",
        StringKey.BackgroundEstimate to "背景估计",
        StringKey.DetectionBackend to "检测后端",
        StringKey.SelectAll to "全选",
        StringKey.InvertSelection to "反选",
        StringKey.Clear to "清空",
        StringKey.StartCrop to "开始裁剪",
        StringKey.AdvancedSettings to "高级设置",
        StringKey.OpenOutputDirectory to "打开输出目录",
        StringKey.FixedSize to "固定尺寸",
        StringKey.OverwriteExisting to "覆盖已存在文件",
        StringKey.Save to "保存",
        StringKey.Cancel to "取消"
    )

    override fun text(key: StringKey): String = zhCn.getValue(key)
}

class DefaultLayoutConstraintPolicy : LayoutConstraintPolicy {
    override fun clamp(state: LayoutState, bounds: LayoutBounds): LayoutState {
        val maxLeft = (bounds.totalWidth - bounds.rightPanelMinWidth - bounds.centerMinWidth).coerceAtLeast(bounds.leftPanelMinWidth)
        val left = state.leftPanelWidth.coerceIn(bounds.leftPanelMinWidth, maxLeft)

        val maxRight = (bounds.totalWidth - left - bounds.centerMinWidth).coerceAtLeast(bounds.rightPanelMinWidth)
        val right = state.rightPanelWidth.coerceIn(bounds.rightPanelMinWidth, maxRight)

        val maxPreview = (bounds.totalHeight - bounds.logMinHeight).coerceAtLeast(bounds.previewMinHeight)
        val preview = state.previewHeight.coerceIn(bounds.previewMinHeight, maxPreview)

        return LayoutState(leftPanelWidth = left, rightPanelWidth = right, previewHeight = preview)
    }
}

interface EditorCommand {
    val label: String
    fun execute(state: EditorState): EditorState
    fun undo(state: EditorState): EditorState
}

data class EditorState(
    val regions: List<CropRegion> = emptyList()
)

class ReplaceRegionsCommand(
    override val label: String,
    private val before: List<CropRegion>,
    private val after: List<CropRegion>
) : EditorCommand {
    override fun execute(state: EditorState): EditorState = state.copy(regions = after)
    override fun undo(state: EditorState): EditorState = state.copy(regions = before)
}

class EditorHistory(initialRegions: List<CropRegion> = emptyList()) {
    var state by mutableStateOf(EditorState(initialRegions))
        private set

    private val undoStack = ArrayDeque<EditorCommand>()
    private val redoStack = ArrayDeque<EditorCommand>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun reset(regions: List<CropRegion>) {
        state = EditorState(regions)
        undoStack.clear()
        redoStack.clear()
    }

    fun replaceRegions(label: String, nextRegions: List<CropRegion>, trackHistory: Boolean = true) {
        val currentRegions = state.regions
        if (currentRegions == nextRegions) return
        if (!trackHistory) {
            state = state.copy(regions = nextRegions)
            return
        }
        val command = ReplaceRegionsCommand(label, currentRegions, nextRegions)
        state = command.execute(state)
        undoStack.addLast(command)
        redoStack.clear()
    }

    fun undo() {
        val command = undoStack.removeLastOrNull() ?: return
        state = command.undo(state)
        redoStack.addLast(command)
    }

    fun redo() {
        val command = redoStack.removeLastOrNull() ?: return
        state = command.execute(state)
        redoStack.addLast(command)
    }
}

data class LayoutSpec(
    val minWindowWidth: Dp = 1280.dp,
    val minWindowHeight: Dp = 860.dp,
    val minLeftWidth: Dp = 250.dp,
    val minCenterWidth: Dp = 560.dp,
    val minRightWidth: Dp = 240.dp,
    val minPreviewHeight: Dp = 360.dp,
    val minLogHeight: Dp = 180.dp,
    val initialLeftWidth: Dp = 270.dp,
    val initialRightWidth: Dp = 268.dp,
    val initialPreviewHeight: Dp = 670.dp
)

object ServiceFactory {
    fun detector(nativeEngine: NativeImageEngine = CppNativeImageEngine()): RegionDetector = CppRegionDetector()

    fun splitter(): RegionSplitter = CppRegionSplitter()

    fun exporter(nativeEngine: NativeImageEngine = UnavailableNativeImageEngine): RegionExporter = JvmRegionExporter()
}

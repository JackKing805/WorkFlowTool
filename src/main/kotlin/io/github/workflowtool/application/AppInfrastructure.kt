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
import io.github.workflowtool.domain.NativeImageEngine
import io.github.workflowtool.domain.RegionDetector
import io.github.workflowtool.domain.RegionExporter
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.ExportResult
import java.awt.image.BufferedImage

abstract class BaseRegionDetector : RegionDetector {
    final override fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult {
        if (image.width <= 0 || image.height <= 0) {
            return emptyDetectionResult()
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

class PythonRegionDetector : BaseRegionDetector() {
    override fun doDetect(image: BufferedImage, config: DetectionConfig): DetectionResult {
        return PythonDetectorBridge.detect(image, config)
            ?.let { postProcessDetection(image, config, it) }
            ?: emptyDetectionResult()
    }
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

object PythonImageEngine : NativeImageEngine {
    override val isAvailable: Boolean
        get() = PythonDetectorBridge.isAvailable
    override val backendName: String = "Python"
    override val detail: String
        get() = "${PythonDetectorBridge.status}; ${PythonEnvironmentManager.statusSummary()}"
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
        undoStack.addLast(command)
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
    fun detector(): RegionDetector = PythonRegionDetector()

    fun exporter(): RegionExporter = JvmRegionExporter()
}

internal fun emptyDetectionResult(): DetectionResult =
    DetectionResult(
        regions = emptyList(),
        mode = DetectionMode.FALLBACK_BACKGROUND,
        stats = DetectionStats(0, 0, 0, 0, 0, 0)
    )

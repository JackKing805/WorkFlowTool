package io.github.workflowtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workflowtool.application.AppController
import io.github.workflowtool.application.clearRegions
import io.github.workflowtool.application.clearSelection
import io.github.workflowtool.application.decreaseRefineBrushSize
import io.github.workflowtool.application.fitToViewport
import io.github.workflowtool.application.increaseRefineBrushSize
import io.github.workflowtool.application.mergeSelectedRegions
import io.github.workflowtool.application.panViewport
import io.github.workflowtool.application.removeRegion
import io.github.workflowtool.application.removeSelectedRegions
import io.github.workflowtool.application.replaceRegions
import io.github.workflowtool.application.selectAndFocusRegion
import io.github.workflowtool.application.selectRegion
import io.github.workflowtool.application.selectRegionsInBounds
import io.github.workflowtool.application.toggleSelectedVisibility
import io.github.workflowtool.application.toggleVisibility
import io.github.workflowtool.application.updatePointerHover
import io.github.workflowtool.application.updateViewportSize
import io.github.workflowtool.application.zoomAroundImagePoint
import io.github.workflowtool.domain.LayoutBounds
import io.github.workflowtool.domain.StringKey
import io.github.workflowtool.domain.WindowController
import io.github.workflowtool.ui.components.PanelCard
import io.github.workflowtool.ui.editor.EditorCanvas
import io.github.workflowtool.ui.panels.AdvancedSettingsDialog
import io.github.workflowtool.ui.panels.HistoryDialog
import io.github.workflowtool.ui.panels.LeftPanel
import io.github.workflowtool.ui.panels.LogPanel
import io.github.workflowtool.ui.panels.RegionPreviewDialog
import io.github.workflowtool.ui.panels.RightPanel
import io.github.workflowtool.ui.panels.Toolbar
import io.github.workflowtool.ui.theme.TextMuted
import io.github.workflowtool.ui.window.HorizontalSplitter
import io.github.workflowtool.ui.window.TopBar
import io.github.workflowtool.ui.window.VerticalSplitter

@Composable
fun IconCropperApp(controller: AppController, windowController: WindowController) {
    val strings = controller.localization
    val bitmap = remember(controller.image) { controller.image?.toComposeImageBitmap() }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = strings.text(StringKey.AppTitle),
            onMinimize = windowController::minimize,
            onMaximize = windowController::toggleMaximize,
            onClose = windowController::close
        )
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)
        ) {
            val bounds = LayoutBounds(
                totalWidth = maxWidth,
                totalHeight = maxHeight,
                leftPanelMinWidth = controller.layoutSpec.minLeftWidth,
                centerMinWidth = controller.layoutSpec.minCenterWidth,
                rightPanelMinWidth = controller.layoutSpec.minRightWidth,
                previewMinHeight = controller.layoutSpec.minPreviewHeight,
                logMinHeight = controller.layoutSpec.minLogHeight
            )
            LaunchedEffect(maxWidth, maxHeight) {
                controller.applyLayoutBounds(bounds)
            }
            val layout = controller.layoutState

            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    LeftPanel(controller = controller, modifier = Modifier.width(layout.leftPanelWidth).fillMaxHeight())
                    VerticalSplitter { controller.resizeLeftPanel(it, bounds) }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        PanelCard(strings.text(StringKey.PreviewTitle), Modifier.fillMaxWidth().height(layout.previewHeight)) {
                            Toolbar(
                                controller = controller,
                                canUndo = controller.canUndo,
                                canRedo = controller.canRedo
                            )
                            Spacer(Modifier.height(10.dp))
                            EditorCanvas(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                bitmap = bitmap,
                                imageWidth = controller.image?.width,
                                imageHeight = controller.image?.height,
                                regions = controller.regions,
                                zoom = controller.zoom,
                                viewportOffset = controller.viewportOffset,
                                showGrid = controller.showGrid,
                                toolMode = controller.toolMode,
                                backgroundPickArmed = controller.backgroundPickArmed,
                                refineBrushSizePx = controller.refineBrushSizePx,
                                onViewport = controller::updateViewportSize,
                                onPan = controller::panViewport,
                                onZoom = controller::zoomAroundImagePoint,
                                onCommit = controller::replaceRegions,
                                onSelect = controller::selectRegion,
                                onSelectBounds = controller::selectRegionsInBounds,
                                onDetectInsideRegion = controller::detectInsideRegionAsync,
                                onBackgroundPick = controller::sampleBackgroundAt,
                                onHover = controller::updatePointerHover,
                                onClearSelection = controller::clearSelection,
                                onDeleteRegion = controller::removeRegion,
                                onDeleteSelectedRegions = controller::removeSelectedRegions,
                                onToggleRegionVisibility = controller::toggleVisibility,
                                onToggleSelectedVisibility = controller::toggleSelectedVisibility,
                                onFocusRegion = controller::selectAndFocusRegion,
                                onOpenRegionPreview = controller::openRegionPreview,
                                onMergeSelectedRegions = controller::mergeSelectedRegions,
                                onFitToViewport = controller::fitToViewport,
                                onClearRegions = controller::clearRegions,
                                onIncreaseRefineBrushSize = controller::increaseRefineBrushSize,
                                onDecreaseRefineBrushSize = controller::decreaseRefineBrushSize
                            )
                            Spacer(Modifier.height(12.dp))
                            PreviewStatusBar(controller)
                        }
                        HorizontalSplitter { controller.resizePreview(it, bounds) }
                        LogPanel(strings.text(StringKey.LogTitle), controller.logs, Modifier.fillMaxWidth().weight(1f))
                    }
                    VerticalSplitter { controller.resizeRightPanel(it, bounds) }
                    RightPanel(controller = controller, modifier = Modifier.width(layout.rightPanelWidth).fillMaxHeight())
                }
                controller.busyMessage?.let { message ->
                    LoadingOverlay(message)
                }
            }
        }
    }

    if (controller.showAdvancedSettings) {
        AdvancedSettingsDialog(controller)
    }
    if (controller.historyDialogVisible) {
        HistoryDialog(controller)
    }
    controller.previewRegion?.let { region ->
        RegionPreviewDialog(controller = controller, region = region)
    }
}

@Composable
private fun LoadingOverlay(message: String) {
    Box(
        Modifier.fillMaxSize()
            .background(Color(0x99070A0F))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = Color(0xFF65A7FF))
            Text(message, color = Color.White, fontSize = 14.sp)
            Text("请稍候", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PreviewStatusBar(controller: AppController) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PreviewStatusText("缩放 ${kotlin.math.round(controller.zoom * 100)}%", Modifier.widthIn(min = 72.dp, max = 96.dp))
        PreviewStatusText("坐标 ${controller.hoverPositionLabel}", Modifier.weight(1f))
        PreviewStatusText("选区 ${controller.selectedRegionLabel}", Modifier.weight(1f))
    }
}

@Composable
private fun PreviewStatusText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = TextMuted,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

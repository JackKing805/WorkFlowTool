package io.github.workflowtool.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import io.github.workflowtool.application.AppController
import io.github.workflowtool.core.IconExporter
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.ui.components.GhostButton
import io.github.workflowtool.ui.components.SquareButton
import io.github.workflowtool.ui.editor.EditorViewportTransform
import io.github.workflowtool.ui.editor.drawCheckerboard
import io.github.workflowtool.ui.editor.drawPixelGrid
import io.github.workflowtool.ui.theme.SoftBorder
import io.github.workflowtool.ui.theme.TextMuted
import kotlin.math.roundToInt


@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RegionPreviewDialog(controller: AppController, region: CropRegion) {
    val bitmap = remember(controller.image, region.x, region.y, region.width, region.height, region.maskWidth, region.maskHeight, region.alphaMask) {
        controller.image?.let { previewCropper.cropPreview(it, region, controller.regions).toComposeImageBitmap() }
    }
    if (bitmap == null) return

    val dialogState = rememberDialogState(width = 960.dp, height = 720.dp)

    DialogWindow(
        onCloseRequest = controller::closeRegionPreview,
        title = "区域预览",
        state = dialogState,
        resizable = true
    ) {
        var zoom by remember(region.id) { mutableStateOf(1f) }
        var viewportOffset by remember(region.id) { mutableStateOf(Offset.Zero) }
        var viewportSize by remember(region.id) { mutableStateOf(Size.Zero) }
        val viewportTransform = remember(viewportOffset, zoom, bitmap) {
            EditorViewportTransform(viewportOffset, zoom, bitmap.width, bitmap.height)
        }

        fun fitToViewport() {
            if (viewportSize.width <= 0f || viewportSize.height <= 0f) return
            val nextZoom = minOf(
                viewportSize.width / bitmap.width.toFloat(),
                viewportSize.height / bitmap.height.toFloat()
            ).coerceIn(0.1f, 12f)
            zoom = nextZoom
            viewportOffset = Offset(
                x = (viewportSize.width - bitmap.width * nextZoom) / 2f,
                y = (viewportSize.height - bitmap.height * nextZoom) / 2f
            )
        }

        LaunchedEffect(region.id, viewportSize) {
            fitToViewport()
        }

        Column(
            Modifier.fillMaxSize().background(Color(0xFF0D1015)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "区域 ${region.id} · ${region.width} x ${region.height}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SquareButton("-", onClick = { zoom = (zoom / 1.1f).coerceIn(0.1f, 12f) })
                Box(Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                    Text("${(zoom * 100).roundToInt()}%", color = TextMuted, fontSize = 13.sp)
                }
                SquareButton("+", onClick = { zoom = (zoom * 1.1f).coerceIn(0.1f, 12f) })
                GhostButton(
                    "导出当前",
                    onClick = { controller.exportPreviewRegionAsync(region.id) },
                    modifier = Modifier.width(112.dp).height(38.dp),
                    enabled = !controller.isBusy
                )
                GhostButton("适应窗口", onClick = ::fitToViewport, modifier = Modifier.width(112.dp).height(38.dp))
                GhostButton("关闭预览", onClick = controller::closeRegionPreview, modifier = Modifier.width(112.dp).height(38.dp))
            }

            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF121821))
                    .border(1.dp, SoftBorder, RoundedCornerShape(8.dp))
                    .onSizeChanged { viewportSize = it.toSize() }
            ) {
                Canvas(
                    Modifier.fillMaxSize()
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val change = event.changes.firstOrNull() ?: return@onPointerEvent
                            val factor = if (change.scrollDelta.y < 0f) 1.1f else 1f / 1.1f
                            val currentZoom = zoom
                            val nextZoom = (currentZoom * factor).coerceIn(0.1f, 12f)
                            if (nextZoom == currentZoom) return@onPointerEvent
                            val imagePoint = viewportTransform.screenToImage(change.position)
                            zoom = nextZoom
                            viewportOffset = change.position - Offset(imagePoint.x * nextZoom, imagePoint.y * nextZoom)
                        }
                        .pointerInput(region.id) {
                            detectTapGestures(
                                onDoubleTap = {
                                    fitToViewport()
                                }
                            )
                        }
                        .pointerInput(region.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                viewportOffset += dragAmount
                            }
                        }
                ) {
                    drawRect(Color(0xFF10151D), size = size)
                    drawCheckerboard(size, zoom, viewportTransform.renderOffset)
                    drawPixelGrid(
                        size,
                        viewportTransform.renderZoomX,
                        viewportTransform.renderZoomY,
                        viewportTransform.renderOffset
                    )
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(
                            viewportTransform.renderOffset.x.roundToInt(),
                            viewportTransform.renderOffset.y.roundToInt()
                        ),
                        dstSize = IntSize(
                            viewportTransform.renderWidth ?: (bitmap.width * zoom).roundToInt(),
                            viewportTransform.renderHeight ?: (bitmap.height * zoom).roundToInt()
                        ),
                        filterQuality = FilterQuality.None
                    )
                }
            }
        }
    }
}

internal val previewCropper = IconExporter()

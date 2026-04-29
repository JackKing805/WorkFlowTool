package io.github.workflowtool.ui.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import io.github.workflowtool.application.AppController
import io.github.workflowtool.application.*
import io.github.workflowtool.application.openModelDirectory
import io.github.workflowtool.application.openOutputDirectory
import io.github.workflowtool.application.openPythonRuntimeDirectory
import io.github.workflowtool.application.openRuntimeDirectory
import io.github.workflowtool.application.openTrainingSetDirectory
import io.github.workflowtool.core.IconExporter
import io.github.workflowtool.domain.LocalizationProvider
import io.github.workflowtool.domain.StringKey
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.ui.components.CompactNumber
import io.github.workflowtool.ui.components.CompactStepper
import io.github.workflowtool.ui.components.CompactTextField
import io.github.workflowtool.ui.components.GhostButton
import io.github.workflowtool.ui.components.PanelCard
import io.github.workflowtool.ui.components.PrimaryButton
import io.github.workflowtool.ui.components.SelectField
import io.github.workflowtool.ui.components.SettingSwitch
import io.github.workflowtool.ui.components.SmallCheck
import io.github.workflowtool.ui.components.SquareButton
import io.github.workflowtool.ui.components.StatusLine
import io.github.workflowtool.ui.components.ThumbnailBox
import io.github.workflowtool.ui.components.ToolButton
import io.github.workflowtool.ui.components.namingModeLabel
import io.github.workflowtool.ui.components.nextFormat
import io.github.workflowtool.ui.components.nextNamingMode
import io.github.workflowtool.ui.theme.Panel
import io.github.workflowtool.ui.theme.TextDim

@Composable
fun AdvancedSettingsDialog(controller: AppController) {
    val strings = controller.localization
    var showShortcutDialog by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { controller.showAdvancedSettings(false) },
        title = { Text(strings.text(StringKey.AdvancedSettings), color = Color.White) },
        backgroundColor = Panel,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallCheck(strings.text(StringKey.KeepOriginalSize), controller.keepOriginalSize, controller::updateKeepOriginalSize)
                Text(strings.text(StringKey.FixedSize), color = Color.White, fontSize = 13.sp)
                CompactTextField(controller.fixedSizeText, controller::updateFixedSizeText, suffix = "px")
                SmallCheck(strings.text(StringKey.OverwriteExisting), controller.overwriteExisting, controller::updateOverwriteExisting)
                GhostButton(
                    "快捷键映射",
                    { showShortcutDialog = true },
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )
                SmallCheck("持续学习训练集", controller.continuousTrainingEnabled, controller::updateContinuousTrainingEnabled)
                Text("开启后，仅从用户手动修正并导出确认的结果学习；训练会先生成候选模型，验证通过后才替换当前模型。", color = TextDim, fontSize = 12.sp)
                GhostButton(
                    "重建图标模型（本地训练样本）",
                    controller::retrainSeedAndUserFeedbackModelAsync,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    enabled = !controller.isBusy
                )
                Text(
                    "会合并本地训练样本和用户确认过的 user_feedback；候选模型通过验证后才会成为运行时模型。",
                    color = TextDim,
                    fontSize = 12.sp
                )
                Text("目录", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("运行目录", controller::openRuntimeDirectory, modifier = Modifier.weight(1f).height(36.dp))
                    GhostButton("输出目录", controller::openOutputDirectory, modifier = Modifier.weight(1f).height(36.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("训练集", controller::openTrainingSetDirectory, modifier = Modifier.weight(1f).height(36.dp))
                    GhostButton("模型", controller::openModelDirectory, modifier = Modifier.weight(1f).height(36.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Python", controller::openPythonRuntimeDirectory, modifier = Modifier.weight(1f).height(36.dp))
                }
                GhostButton("清理内置运行文件", controller::clearRuntimeGeneratedFiles, modifier = Modifier.fillMaxWidth().height(36.dp))
                Text("删除应用释放出的脚本、模型和训练样本；内置资源仍保留在安装包内。", color = TextDim, fontSize = 12.sp)
            }
        },
        confirmButton = {
            GhostButton(strings.text(StringKey.Save), { controller.showAdvancedSettings(false) }, modifier = Modifier.width(96.dp))
        },
        dismissButton = {
            GhostButton(strings.text(StringKey.Cancel), { controller.showAdvancedSettings(false) }, modifier = Modifier.width(96.dp))
        }
    )
    if (showShortcutDialog) {
        ShortcutMappingDialog(onDismiss = { showShortcutDialog = false })
    }
}

@Composable
private fun ShortcutMappingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快捷键映射", color = Color.White) },
        backgroundColor = Panel,
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShortcutSection(
                    "预览区",
                    listOf(
                        ShortcutItem("鼠标滚轮", "缩放预览"),
                        ShortcutItem("拖动画布空白处", "平移画布"),
                        ShortcutItem("点击选框", "选中区域"),
                        ShortcutItem("拖动选框", "移动区域"),
                        ShortcutItem("右键选框", "打开区域菜单"),
                        ShortcutItem("双击取色模式下的图片", "取背景色")
                    )
                )
                ShortcutSection(
                    "框选和精修",
                    listOf(
                        ShortcutItem("框选工具拖拽", "框选范围并自动识别/贴合图标"),
                        ShortcutItem("选中区域 + Shift 拖动", "把新增范围内的图标并入当前选区整体并贴合边缘"),
                        ShortcutItem("选中区域 + Alt/Option 拖动", "减少选区，可先按鼠标或先按快捷键"),
                        ShortcutItem("普通拖动选框", "移动区域，不触发画笔精修")
                    )
                )
                ShortcutSection(
                    "工具栏操作",
                    listOf(
                        ShortcutItem("选择", "进入选择/移动选框操作"),
                        ShortcutItem("移动", "拖动画布平移视图"),
                        ShortcutItem("绘制矩形", "框选新增区域"),
                        ShortcutItem("吸色", "从图片中取背景色"),
                        ShortcutItem("撤销 / 重做", "恢复或重放区域编辑历史"),
                        ShortcutItem("适应窗口 / 适应选区", "调整预览缩放和视口")
                    )
                )
            }
        },
        confirmButton = {
            GhostButton("关闭", onDismiss, modifier = Modifier.width(96.dp))
        }
    )
}

private data class ShortcutItem(
    val keys: String,
    val action: String
)

@Composable
private fun ShortcutSection(title: String, items: List<ShortcutItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        items.forEach { item ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.keys,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.width(156.dp)
                )
                Text(
                    item.action,
                    color = TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

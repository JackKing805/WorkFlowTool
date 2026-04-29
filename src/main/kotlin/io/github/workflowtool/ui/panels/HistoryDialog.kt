package io.github.workflowtool.ui.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import io.github.workflowtool.application.AppController
import io.github.workflowtool.application.WorkspaceHistoryStore
import io.github.workflowtool.application.WorkspaceSnapshotEntry
import io.github.workflowtool.ui.components.GhostButton
import io.github.workflowtool.ui.theme.Border
import io.github.workflowtool.ui.theme.ControlBg
import io.github.workflowtool.ui.theme.Danger
import io.github.workflowtool.ui.theme.Panel
import io.github.workflowtool.ui.theme.SoftBorder
import io.github.workflowtool.ui.theme.TextDim
import io.github.workflowtool.ui.theme.TextMuted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

@Composable
fun HistoryDialog(controller: AppController) {
    val dialogState = rememberDialogState(width = 1120.dp, height = 760.dp)
    DialogWindow(
        onCloseRequest = { controller.showHistoryDialog(false) },
        title = "历史记录",
        state = dialogState,
        resizable = true
    ) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF0D1015)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("历史记录", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "保留最近 ${WorkspaceHistoryStore.MaxEntries} 条画布快照，当前 ${controller.workspaceHistoryEntries.size} 条。可直接恢复最新区域状态。",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                GhostButton(
                    "关闭",
                    onClick = { controller.showHistoryDialog(false) },
                    modifier = Modifier.width(96.dp).height(38.dp)
                )
            }

            if (controller.workspaceHistoryEntries.isEmpty()) {
                EmptyHistoryState()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF121720))
                        .border(1.dp, SoftBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(controller.workspaceHistoryEntries, key = { it.id }) { entry ->
                        HistoryEntryCard(entry = entry, controller = controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryState() {
    Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF121720))
            .border(1.dp, SoftBorder, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂无历史快照", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("打开图片并编辑区域后，这里会自动保存可恢复的画布快照。", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HistoryEntryCard(entry: WorkspaceSnapshotEntry, controller: AppController) {
    val bitmap = remember(entry.id, entry.updatedAtEpochMillis) {
        runCatching {
            entry.previewPath.toFile().takeIf { it.isFile }?.let(ImageIO::read)?.toComposeImageBitmap()
        }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, SoftBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(224.dp).height(136.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F141B))
                .border(1.dp, Border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("无预览", color = TextMuted, fontSize = 13.sp)
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.displayTitle,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(280.dp)
                )
                HistoryTag(if (entry.hasManualEdits) "手动修正" else "自动识别")
                HistoryTag("${entry.sourcePaths.size} 张源图")
            }
            Text(formatHistoryTime(entry.updatedAtEpochMillis), color = TextMuted, fontSize = 12.sp)
            Text(entry.sourcePathLabel, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HistoryInfo("${entry.imageWidth} x ${entry.imageHeight}")
                HistoryInfo("${entry.regions.size} 个区域")
                HistoryInfo(if (entry.hasManualEdits) "可恢复手工状态" else "基础快照")
            }
            if (!entry.allFilesAvailable) {
                Text("缺失 ${entry.missingFileCount} 个源文件，当前无法恢复。", color = Danger, fontSize = 12.sp)
            }
        }

        Column(Modifier.width(128.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                "恢复快照",
                onClick = { controller.reopenHistorySnapshotAsync(entry.id) },
                modifier = Modifier.fillMaxWidth().height(38.dp),
                active = true,
                enabled = entry.allFilesAvailable
            )
            GhostButton(
                "删除记录",
                onClick = { controller.removeHistorySnapshot(entry.id) },
                modifier = Modifier.fillMaxWidth().height(38.dp)
            )
        }
    }
}

@Composable
private fun HistoryTag(label: String) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(Color(0x1F2F6BFF))
            .border(1.dp, Color(0x663F88FF), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = Color(0xFFDDE9FF), fontSize = 11.sp)
    }
}

@Composable
private fun HistoryInfo(label: String) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(ControlBg)
            .border(1.dp, Border, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

private fun formatHistoryTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "时间未知"
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

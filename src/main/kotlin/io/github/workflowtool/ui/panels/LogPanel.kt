package io.github.workflowtool.ui.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.workflowtool.ui.components.PanelCard
import io.github.workflowtool.ui.theme.TextMuted


@Composable
fun LogPanel(title: String, logs: List<String>, modifier: Modifier = Modifier) {
    PanelCard(title, modifier) {
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无日志", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            val visibleLogs = logs.takeLast(200)
            val listState = rememberLazyListState()
            LaunchedEffect(visibleLogs.size) {
                listState.animateScrollToItem((visibleLogs.size - 1).coerceAtLeast(0))
            }
            LazyColumn(state = listState) {
                itemsIndexed(visibleLogs) { _, line ->
                    SelectionContainer {
                        Text(line, color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

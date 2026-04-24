package io.github.workflowtool.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workflowtool.ui.theme.Accent
import io.github.workflowtool.ui.theme.SoftBorder
import io.github.workflowtool.ui.theme.TopBg
import java.awt.MouseInfo

@Composable
fun TopBar(
    title: String,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
    onStartWindowDrag: (Int, Int) -> Unit,
    onDragWindow: (Int, Int) -> Unit,
    onEndWindowDrag: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).background(TopBg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            MouseInfo.getPointerInfo()?.location?.let { point ->
                                onStartWindowDrag(point.x, point.y)
                            }
                        },
                        onDragEnd = onEndWindowDrag,
                        onDragCancel = onEndWindowDrag
                    ) { change, _ ->
                        change.consume()
                        MouseInfo.getPointerInfo()?.location?.let { point ->
                            onDragWindow(point.x, point.y)
                        }
                    }
                }
                .padding(start = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(31.dp).clip(RoundedCornerShape(7.dp)).background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Text("W", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
        WindowControl("−", onMinimize)
        WindowControl("□", onMaximize)
        WindowControl("×", onClose)
    }
}

@Composable
fun WindowControl(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 58.dp, height = 62.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = androidx.compose.ui.graphics.Color.White, fontSize = 21.sp)
    }
}

@Composable
fun VerticalSplitter(onDrag: (Dp) -> Unit) {
    Box(
        Modifier.fillMaxHeight().width(10.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(with(this) { dragAmount.x.toDp() })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(SoftBorder))
    }
}

@Composable
fun HorizontalSplitter(onDrag: (Dp) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(10.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(with(this) { dragAmount.y.toDp() })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(SoftBorder))
    }
}

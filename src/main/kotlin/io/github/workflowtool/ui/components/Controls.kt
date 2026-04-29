package io.github.workflowtool.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workflowtool.model.ImageFormat
import io.github.workflowtool.model.NamingMode
import io.github.workflowtool.ui.editor.drawCheckerboard
import io.github.workflowtool.ui.theme.Accent
import io.github.workflowtool.ui.theme.Border
import io.github.workflowtool.ui.theme.CardShape
import io.github.workflowtool.ui.theme.ControlActive
import io.github.workflowtool.ui.theme.ControlBg
import io.github.workflowtool.ui.theme.ControlShape
import io.github.workflowtool.ui.theme.Panel
import io.github.workflowtool.ui.theme.SoftBorder
import io.github.workflowtool.ui.theme.TextDim
import io.github.workflowtool.ui.theme.TextMuted

@Composable
fun PanelCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(CardShape).background(Panel).border(1.dp, SoftBorder, CardShape).padding(14.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun StatusLine(label: String, value: String, multilineValue: Boolean = false) {
    if (multilineValue) {
        Column(
            Modifier.fillMaxWidth().clip(ControlShape).background(ControlBg).border(1.dp, Border, ControlShape).padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(label, color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                value,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().clip(ControlShape).background(ControlBg).border(1.dp, Border, ControlShape).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 180.dp)
        )
    }
}

@Composable
fun ToolButton(label: String, active: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    GhostButton(if (active) "✓ $label" else label, onClick, active = active, enabled = enabled)
}

@Composable
fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        SwitchPill(checked, onChecked)
    }
}

@Composable
fun SwitchPill(checked: Boolean, onChecked: (Boolean) -> Unit) {
    Box(
        Modifier.width(36.dp).height(20.dp).clip(RoundedCornerShape(20.dp))
            .background(if (checked) Accent else Color(0xFF3A414D))
            .clickable { onChecked(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun CompactNumber(title: String, value: Int, suffix: String, onValue: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        SquareButton("-", onClick = { onValue((value - 1).coerceAtLeast(0)) })
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.width(56.dp).height(30.dp).clip(ControlShape)
                .background(Color(0xFF171C23))
                .border(1.dp, Border, ControlShape),
            contentAlignment = Alignment.Center
        ) {
            Text(value.toString(), color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        SquareButton("+", onClick = { onValue((value + 1).coerceAtMost(999)) })
        if (suffix.isNotBlank()) {
            Text(suffix, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
        }
    }
}

@Composable
fun CompactStepper(title: String, value: Int, suffix: String, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        SquareButton("-", onClick = onDecrease)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.width(56.dp).height(30.dp).clip(ControlShape)
                .background(Color(0xFF171C23))
                .border(1.dp, Border, ControlShape),
            contentAlignment = Alignment.Center
        ) {
            Text(value.toString(), color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        SquareButton("+", onClick = onIncrease)
        if (suffix.isNotBlank()) {
            Text(suffix, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
        }
    }
}

@Composable
fun CompactTextField(value: String, onValue: (String) -> Unit, suffix: String? = null) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(ControlShape).background(Color(0xFF171C23)).border(1.dp, Border, ControlShape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp)
        )
        suffix?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
fun SelectField(title: String, value: String, onClick: () -> Unit) {
    Text(title, color = Color.White, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().height(34.dp)
            .clip(ControlShape)
            .background(Color(0xFF171C23))
            .border(1.dp, Border, ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(value, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("›", color = TextMuted, fontSize = 14.sp)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun <T> DropdownSelectField(
    title: String,
    value: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Text(title, color = Color.White, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(34.dp)
                .clip(ControlShape)
                .background(Color(0xFF171C23))
                .border(1.dp, if (expanded) Accent else Border, ControlShape)
                .alpha(if (enabled) 1f else 0.45f)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                optionLabel(value),
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "⌃" else "⌄", color = TextMuted, fontSize = 14.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(ControlBg).border(1.dp, Border)
        ) {
            options.forEach { option ->
                DropdownMenuItem(onClick = {
                    onSelect(option)
                    expanded = false
                }) {
                    Text(
                        optionLabel(option),
                        color = if (option == value) Accent else Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun SmallCheck(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChecked)
        Text(label, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
fun SquareButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.size(36.dp).clip(ControlShape).background(ControlBg).border(1.dp, Border, ControlShape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    dashed: Boolean = false,
    enabled: Boolean = true
) {
    Box(
        modifier
            .clip(ControlShape)
            .background(if (active) ControlActive else ControlBg)
            .border(1.dp, if (active) Accent else Border, ControlShape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (dashed) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = Color(0xFF485264),
                    style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
            }
        }
        Text(label, color = if (active) Color(0xFFDBE5FF) else Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).clip(ControlShape).background(Accent)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ThumbnailBox(bitmap: ImageBitmap? = null) {
    Box(
        Modifier.fillMaxWidth().height(102.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, Border, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) { drawCheckerboard(size) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("未选择图片", color = TextMuted, fontSize = 13.sp)
        }
    }
}

fun nextFormat(current: ImageFormat): ImageFormat = when (current) {
    ImageFormat.PNG -> ImageFormat.JPG
    ImageFormat.JPG -> ImageFormat.WEBP
    ImageFormat.WEBP -> ImageFormat.PNG
}

fun nextNamingMode(current: NamingMode): NamingMode = when (current) {
    NamingMode.Sequence -> NamingMode.SourceNameSequence
    NamingMode.SourceNameSequence -> NamingMode.CustomPrefixSequence
    NamingMode.CustomPrefixSequence -> NamingMode.Sequence
}

fun namingModeLabel(mode: NamingMode): String = when (mode) {
    NamingMode.Sequence -> "序号命名（001.png）"
    NamingMode.SourceNameSequence -> "原图名称_序号"
    NamingMode.CustomPrefixSequence -> "自定义前缀"
}

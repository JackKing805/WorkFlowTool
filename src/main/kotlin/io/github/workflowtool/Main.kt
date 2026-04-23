package io.github.workflowtool

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.workflowtool.core.GridSplitter
import io.github.workflowtool.core.IconDetector
import io.github.workflowtool.core.IconExporter
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.GridConfig
import io.github.workflowtool.model.ImageFormat
import io.github.workflowtool.model.NamingMode
import io.github.workflowtool.model.SplitMode
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.platform.DesktopPlatform
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.math.roundToInt

private val Panel = Color(0xFF171A1F)
private val PanelAlt = Color(0xFF20242B)
private val Border = Color(0xFF343A45)
private val Accent = Color(0xFF2F6BFF)
private val TextMuted = Color(0xFFABB2C0)
private val TextDim = Color(0xFF6F7988)
private val AppBg = Color(0xFF0F1217)
private val TopBg = Color(0xFF111419)
private val SoftBorder = Color(0xFF262D36)
private val ControlBg = Color(0xFF1E232B)
private val ControlActive = Color(0xFF203D83)
private val CardShape = RoundedCornerShape(6.dp)
private val ControlShape = RoundedCornerShape(4.dp)
private val Danger = Color(0xFFFF554C)

private enum class ResizeCorner {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight
}

private fun demoRegions(): List<CropRegion> {
    return List(24) { index ->
        val column = index % 8
        val row = index / 8
        CropRegion(
            id = (index + 1).toString(),
            x = 24 + column * 110,
            y = 94 + row * 106,
            width = 96,
            height = 96,
            selected = index == 0
        )
    }
}

private val demoIcons = listOf(
    "⌂", "●", "⚙", "✉", "⌕", "♥", "★", "▣",
    "◔", "➤", "♦", "☎", "▥", "♟", "♬", "▣",
    "✎", "⇩", "⇧", "▰", "🔗", "◉", "▦", "☰"
)

fun main() = application {
    val windowState = rememberWindowState(width = 1536.dp, height = 1024.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "图标自动裁剪工具 v1.1.0",
        undecorated = true,
        state = windowState
    ) {
        MaterialTheme(colors = darkColors(primary = Accent, background = AppBg, surface = Panel)) {
            Surface(modifier = Modifier.fillMaxSize(), color = AppBg) {
                IconCropperApp(
                    onMinimize = { windowState.isMinimized = true },
                    onMaximize = {
                        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                    },
                    onClose = ::exitApplication
                )
            }
        }
    }
}

@Composable
private fun IconCropperApp(onMinimize: () -> Unit, onMaximize: () -> Unit, onClose: () -> Unit) {
    val detector = remember { IconDetector() }
    val gridSplitter = remember { GridSplitter() }
    val exporter = remember { IconExporter() }
    val logs = remember {
        mutableStateListOf(
            "[10:30:15]   图片加载成功： icons.png (1024 x 1024)",
            "[10:30:16]   检测到 24 个区域",
            "[10:30:18]   开始裁剪...",
            "[10:30:19]   裁剪完成： 成功 24 个， 失败 0 个",
            "[10:30:19]   输出目录： C:\\Users\\Desktop\\icons_out"
        )
    }
    var imageFile by remember { mutableStateOf<File?>(null) }
    var image by remember { mutableStateOf<BufferedImage?>(null) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var regions by remember { mutableStateOf(demoRegions()) }
    var splitMode by remember { mutableStateOf(SplitMode.Custom) }
    var toolMode by remember { mutableStateOf(ToolMode.Select) }
    var zoom by remember { mutableStateOf(1.0f) }
    var showGrid by remember { mutableStateOf(true) }
    var detectionConfig by remember { mutableStateOf(DetectionConfig()) }
    var gridConfig by remember { mutableStateOf(GridConfig()) }
    var outputDirectory by remember { mutableStateOf(DesktopPlatform.defaultOutputDirectory()) }
    var outputFormat by remember { mutableStateOf(ImageFormat.PNG) }
    var namingMode by remember { mutableStateOf(NamingMode.Sequence) }
    var customPrefix by remember { mutableStateOf("icon") }
    var trimTransparent by remember { mutableStateOf(false) }
    var padToSquare by remember { mutableStateOf(false) }
    var fixedSizeText by remember { mutableStateOf("") }
    var overwriteExisting by remember { mutableStateOf(false) }

    fun log(message: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logs += "[$time] $message"
    }

    fun loadFile(file: File) {
        runCatching {
            val loaded = ImageIO.read(file) ?: error("不支持的图片格式")
            imageFile = file
            image = loaded
            bitmap = loaded.toComposeImageBitmap()
            regions = emptyList()
            zoom = 1.0f
            log("图片加载成功：${file.name}（${loaded.width} x ${loaded.height}）")
            val detected = detector.detect(loaded, detectionConfig)
            regions = detected
            splitMode = SplitMode.AutoDetect
            log("检测到 ${detected.size} 个区域")
        }.onFailure {
            log("图片加载失败：${it.message}")
        }
    }

    fun refreshRegions() {
        val loaded = image ?: return
        regions = when (splitMode) {
            SplitMode.AutoDetect -> detector.detect(loaded, detectionConfig)
            SplitMode.Grid -> gridSplitter.split(loaded.width, loaded.height, gridConfig)
            SplitMode.Custom -> regions
        }
        if (splitMode != SplitMode.Custom) log("生成 ${regions.size} 个区域")
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
        val fixedSize = fixedSizeText.toIntOrNull()
        val config = ExportConfig(
            outputFormat = outputFormat,
            outputDirectory = outputDirectory,
            namingMode = namingMode,
            customPrefix = customPrefix,
            trimTransparentPadding = trimTransparent,
            padToSquare = padToSquare,
            fixedSize = fixedSize,
            overwriteExisting = overwriteExisting
        )
        log("开始裁剪...")
        val result = exporter.export(loaded, file.name, regions, config)
        log("裁剪完成：成功 ${result.successCount} 个，失败 ${result.failureCount} 个")
        result.failures.take(3).forEach { log("失败：$it") }
        log("输出目录：$outputDirectory")
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(onMinimize, onMaximize, onClose)
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LeftPanel(
                imageFile = imageFile,
                image = image,
                splitMode = splitMode,
                onSplitMode = {
                    splitMode = it
                    if (it != SplitMode.Custom) refreshRegions()
                },
                detectionConfig = detectionConfig,
                onDetectionConfig = { detectionConfig = it },
                gridConfig = gridConfig,
                onGridConfig = { gridConfig = it },
                outputDirectory = outputDirectory.toString(),
                outputFormat = outputFormat,
                namingMode = namingMode,
                customPrefix = customPrefix,
                trimTransparent = trimTransparent,
                padToSquare = padToSquare,
                fixedSizeText = fixedSizeText,
                overwriteExisting = overwriteExisting,
                onOpenImage = { DesktopPlatform.chooseImageFile()?.let(::loadFile) },
                onRefreshRegions = ::refreshRegions,
                onChooseOutput = { DesktopPlatform.chooseDirectory()?.let { outputDirectory = it.toPath() } },
                onOutputFormat = { outputFormat = it },
                onNamingMode = { namingMode = it },
                onCustomPrefix = { customPrefix = it },
                onTrimTransparent = { trimTransparent = it },
                onPadToSquare = { padToSquare = it },
                onFixedSizeText = { fixedSizeText = it.filter(Char::isDigit) },
                onOverwriteExisting = { overwriteExisting = it }
            )
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PanelCard("预览（自定义区域）", Modifier.height(670.dp)) {
                    Toolbar(
                        toolMode = toolMode,
                        zoom = zoom,
                        showGrid = showGrid,
                        onToolMode = { toolMode = it },
                        onZoom = { zoom = it.coerceIn(0.1f, 8f) },
                        onFit = { zoom = 1.0f },
                        onGrid = { showGrid = it },
                        onClear = {
                            regions = emptyList()
                            log("已清空区域")
                        }
                    )
                    Spacer(Modifier.height(22.dp))
                    EditorCanvas(
                        bitmap = bitmap,
                        image = image,
                        regions = regions,
                        zoom = zoom,
                        showGrid = showGrid,
                        toolMode = toolMode,
                        onRegions = { regions = it }
                    )
                }
                LogPanel(logs)
            }
            RightPanel(
                regions = regions,
                onRegions = { regions = it },
                onExport = ::exportRegions,
                onOpenOutput = {
                    runCatching { DesktopPlatform.openDirectory(outputDirectory) }
                        .onFailure { log("打开输出目录失败：${it.message}") }
                }
            )
        }
    }
}

@Composable
private fun TopBar(onMinimize: () -> Unit, onMaximize: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).background(TopBg).padding(start = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(31.dp).clip(RoundedCornerShape(7.dp)).background(Accent), contentAlignment = Alignment.Center) {
            Text("↗", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text("图标自动裁剪工具 v1.1.0", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        WindowControl("─", onMinimize)
        WindowControl("□", onMaximize)
        WindowControl("×", onClose)
    }
}

@Composable
private fun WindowControl(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 58.dp, height = 62.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 21.sp)
    }
}

@Composable
private fun LeftPanel(
    imageFile: File?,
    image: BufferedImage?,
    splitMode: SplitMode,
    onSplitMode: (SplitMode) -> Unit,
    detectionConfig: DetectionConfig,
    onDetectionConfig: (DetectionConfig) -> Unit,
    gridConfig: GridConfig,
    onGridConfig: (GridConfig) -> Unit,
    outputDirectory: String,
    outputFormat: ImageFormat,
    namingMode: NamingMode,
    customPrefix: String,
    trimTransparent: Boolean,
    padToSquare: Boolean,
    fixedSizeText: String,
    overwriteExisting: Boolean,
    onOpenImage: () -> Unit,
    onRefreshRegions: () -> Unit,
    onChooseOutput: () -> Unit,
    onOutputFormat: (ImageFormat) -> Unit,
    onNamingMode: (NamingMode) -> Unit,
    onCustomPrefix: (String) -> Unit,
    onTrimTransparent: (Boolean) -> Unit,
    onPadToSquare: (Boolean) -> Unit,
    onFixedSizeText: (String) -> Unit,
    onOverwriteExisting: (Boolean) -> Unit
) {
    Column(Modifier.width(270.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelCard("1. 选择图片", Modifier.height(258.dp)) {
            GhostButton("▣  打开图片", onOpenImage, dashed = true, modifier = Modifier.fillMaxWidth().height(35.dp))
            Spacer(Modifier.height(8.dp))
            ThumbnailBox()
            Spacer(Modifier.height(8.dp))
            Text(imageFile?.name ?: "icons.png", color = Color.White, fontSize = 14.sp)
            Text(image?.let { "${it.width} × ${it.height}" } ?: "1024 × 1024", color = TextMuted, fontSize = 14.sp)
        }
        PanelCard("2. 拆分设置", Modifier.height(402.dp)) {
            Text("拆分模式", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            SegmentedMode(splitMode, onSplitMode)
            Spacer(Modifier.height(14.dp))
            Text("添加区域", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("绘制矩形", { onSplitMode(SplitMode.Custom) }, active = true, modifier = Modifier.weight(1f).height(31.dp))
                GhostButton("绘制多边形", {}, modifier = Modifier.weight(1f).height(31.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("在预览区拖拽创建区域，右键删除区域", color = TextDim, fontSize = 12.sp)
            SettingSwitch("合并相邻区域", "合并间距小于阈值的相邻区域", detectionConfig.mergeNearbyRegions) {
                onDetectionConfig(detectionConfig.copy(mergeNearbyRegions = it))
            }
            SettingSwitch("去除小区域", "过滤掉面积小于阈值的区域", detectionConfig.removeSmallRegions) {
                onDetectionConfig(detectionConfig.copy(removeSmallRegions = it))
            }
            CompactNumber("最小尺寸", detectionConfig.minWidth, "px") {
                onDetectionConfig(detectionConfig.copy(minWidth = it, minHeight = it))
            }
            CompactNumber("间距阈值", detectionConfig.gapThreshold, "px") {
                onDetectionConfig(detectionConfig.copy(gapThreshold = it))
            }
        }
        PanelCard("3. 输出设置", Modifier.weight(1f)) {
            SelectField("输出格式", outputFormat.name) {
                onOutputFormat(if (outputFormat == ImageFormat.PNG) ImageFormat.JPG else if (outputFormat == ImageFormat.JPG) ImageFormat.WEBP else ImageFormat.PNG)
            }
            SelectField("输出目录", outputDirectory, onChooseOutput)
            SelectField("图标命名方式", when (namingMode) {
                NamingMode.Sequence -> "序号命名（001.png）"
                NamingMode.SourceNameSequence -> "原图名称_序号"
                NamingMode.CustomPrefixSequence -> "自定义前缀"
            }) {
                onNamingMode(
                    when (namingMode) {
                        NamingMode.Sequence -> NamingMode.SourceNameSequence
                        NamingMode.SourceNameSequence -> NamingMode.CustomPrefixSequence
                        NamingMode.CustomPrefixSequence -> NamingMode.Sequence
                    }
                )
            }
            SmallCheck("保持原始大小", true) {}
            SmallCheck("去除透明边距", trimTransparent, onTrimTransparent)
            SmallCheck("补齐为正方形", padToSquare, onPadToSquare)
        }
    }
}

@Composable
private fun Toolbar(
    toolMode: ToolMode,
    zoom: Float,
    showGrid: Boolean,
    onToolMode: (ToolMode) -> Unit,
    onZoom: (Float) -> Unit,
    onFit: () -> Unit,
    onGrid: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ToolButton("⌖  选择", toolMode == ToolMode.Select) { onToolMode(ToolMode.Select) }
        ToolButton("✥  移动", toolMode == ToolMode.Move) { onToolMode(ToolMode.Move) }
        Spacer(Modifier.width(8.dp))
        ToolButton("↶  撤销", false) {}
        ToolButton("↷  重做", false) {}
        ToolButton("⌫  清空区域", false, onClear)
        Spacer(Modifier.weight(1f))
        SquareButton("−") { onZoom(zoom - 0.1f) }
        Box(Modifier.width(58.dp), contentAlignment = Alignment.Center) {
            Text("${(zoom * 100).roundToInt()}%", color = TextMuted, fontSize = 14.sp)
        }
        SquareButton("+") { onZoom(zoom + 0.1f) }
        ToolButton("◉  适应窗口", false, onFit)
        ToolButton("▦  网格", showGrid) { onGrid(!showGrid) }
    }
}

@Composable
private fun EditorCanvas(
    bitmap: ImageBitmap?,
    image: BufferedImage?,
    regions: List<CropRegion>,
    zoom: Float,
    showGrid: Boolean,
    toolMode: ToolMode,
    onRegions: (List<CropRegion>) -> Unit
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragRegionId by remember { mutableStateOf<String?>(null) }
    var resizeCorner by remember { mutableStateOf<ResizeCorner?>(null) }
    var resizeOriginal by remember { mutableStateOf<CropRegion?>(null) }
    var draftRegion by remember { mutableStateOf<CropRegion?>(null) }
    val canvasBg = Color(0xFF14181E)

    Box(
        Modifier.fillMaxWidth().height(548.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Panel)
            .border(1.dp, SoftBorder, RoundedCornerShape(5.dp))
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(bitmap, regions, zoom, toolMode) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val imagePoint = offset / zoom
                        dragStart = imagePoint
                        val handleHit = findHandleHit(regions, imagePoint, zoom)
                        val hit = regions.lastOrNull { it.visible && imagePoint.x in it.x.toFloat()..it.right.toFloat() && imagePoint.y in it.y.toFloat()..it.bottom.toFloat() }
                        if (toolMode == ToolMode.Draw || hit == null) {
                            draftRegion = CropRegion(UUID.randomUUID().toString(), imagePoint.x.roundToInt(), imagePoint.y.roundToInt(), 1, 1, selected = true)
                            dragRegionId = null
                            resizeCorner = null
                            resizeOriginal = null
                        } else {
                            val active = handleHit?.first ?: hit
                            dragRegionId = active.id
                            resizeCorner = handleHit?.second
                            resizeOriginal = active
                            onRegions(regions.map { it.copy(selected = it.id == active.id || it.selected) })
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (bitmap == null || image == null) return@detectDragGestures
                        val currentStart = dragStart ?: return@detectDragGestures
                        val regionId = dragRegionId
                        if (regionId != null && resizeCorner != null && resizeOriginal != null && toolMode != ToolMode.Draw) {
                            val current = change.position / zoom
                            val original = resizeOriginal ?: return@detectDragGestures
                            val resized = resizeRegion(original, resizeCorner ?: return@detectDragGestures, current, image.width, image.height)
                            onRegions(regions.map { if (it.id == regionId) resized else it })
                        } else if (regionId != null && toolMode != ToolMode.Draw) {
                            val dx = (dragAmount.x / zoom).roundToInt()
                            val dy = (dragAmount.y / zoom).roundToInt()
                            onRegions(regions.map {
                                if (it.selected) it.copy(
                                    x = (it.x + dx).coerceIn(0, image.width - it.width),
                                    y = (it.y + dy).coerceIn(0, image.height - it.height)
                                ) else it
                            })
                        } else {
                            val current = change.position / zoom
                            val x = minOf(currentStart.x, current.x).roundToInt().coerceAtLeast(0)
                            val y = minOf(currentStart.y, current.y).roundToInt().coerceAtLeast(0)
                            val right = maxOf(currentStart.x, current.x).roundToInt().coerceAtMost(image.width)
                            val bottom = maxOf(currentStart.y, current.y).roundToInt().coerceAtMost(image.height)
                            draftRegion = CropRegion(
                                id = draftRegion?.id ?: UUID.randomUUID().toString(),
                                x = x,
                                y = y,
                                width = (right - x).coerceAtLeast(1),
                                height = (bottom - y).coerceAtLeast(1),
                                selected = true
                            )
                        }
                    },
                    onDragEnd = {
                        draftRegion?.takeIf { it.width >= 2 && it.height >= 2 }?.let { created ->
                            onRegions(regions.map { it.copy(selected = false) } + created.copy(id = (regions.size + 1).toString()))
                        }
                        dragStart = null
                        dragRegionId = null
                        resizeCorner = null
                        resizeOriginal = null
                        draftRegion = null
                    }
                )
            }
        ) {
            drawRect(canvasBg, size = size)
            drawCheckerboard(size)
            if (showGrid) drawGrid(size, zoom)
            bitmap?.let {
                drawImage(it, dstSize = IntSize((it.width * zoom).roundToInt(), (it.height * zoom).roundToInt()))
            }
            (regions + listOfNotNull(draftRegion)).filter { it.visible }.forEachIndexed { index, region ->
                val rect = Rect(
                    offset = Offset(region.x * zoom, region.y * zoom),
                    size = Size(region.width * zoom, region.height * zoom)
                )
                val color = if (region.selected) Color(0xFF74A5FF) else Accent
                drawRect(color, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = if (region.selected) 3f else 2f))
                drawRect(color.copy(alpha = 0.16f), topLeft = rect.topLeft, size = rect.size)
                drawHandle(rect.topLeft, color)
                drawHandle(Offset(rect.right, rect.top), color)
                drawHandle(Offset(rect.left, rect.bottom), color)
                drawHandle(Offset(rect.right, rect.bottom), color)
                drawHandle(Offset(rect.left + rect.width / 2f, rect.top), color, 5f)
                drawHandle(Offset(rect.left + rect.width / 2f, rect.bottom), color, 5f)
                drawHandle(Offset(rect.left, rect.top + rect.height / 2f), color, 5f)
                drawHandle(Offset(rect.right, rect.top + rect.height / 2f), color, 5f)
            }
        }
        (regions + listOfNotNull(draftRegion)).filter { it.visible }.forEachIndexed { index, region ->
            Box(
                Modifier.offset((region.x * zoom + 1).roundToInt().dp, (region.y * zoom + 1).roundToInt().dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Text((index + 1).toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (bitmap == null && index < demoIcons.size) {
                Box(
                    Modifier.offset((region.x * zoom + region.width * zoom / 2f - 16).roundToInt().dp, (region.y * zoom + region.height * zoom / 2f - 18).roundToInt().dp)
                        .size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(demoIcons[index], color = Color.White, fontSize = 35.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (bitmap == null) {
            Text("", color = TextMuted, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>) {
    PanelCard("处理日志", modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        LazyColumn {
            itemsIndexed(logs.takeLast(200)) { _, line ->
                Text(line, color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun RightPanel(
    regions: List<CropRegion>,
    onRegions: (List<CropRegion>) -> Unit,
    onExport: () -> Unit,
    onOpenOutput: () -> Unit
) {
    Column(Modifier.width(268.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelCard("区域列表（${regions.size}）", modifier = Modifier.height(575.dp).fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolButton("全选", false) { onRegions(regions.map { it.copy(selected = true) }) }
                ToolButton("反选", false) { onRegions(regions.map { it.copy(selected = !it.selected) }) }
                ToolButton("清空", false) { onRegions(emptyList()) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SquareTab("▦", true) {}
                SquareTab("☷", false) {}
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).border(1.dp, SoftBorder, RoundedCornerShape(6.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(regions, key = { _, item -> item.id }) { index, region ->
                    RegionRow(index, region, regions, onRegions)
                }
                item {
                    Text("...", color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        PanelCard("4. 处理", Modifier.weight(1f).fillMaxWidth()) {
            PrimaryButton("开始裁剪", onExport)
            Spacer(Modifier.height(16.dp))
            GhostButton("⚙  高级设置", {}, modifier = Modifier.fillMaxWidth().height(38.dp))
            Spacer(Modifier.weight(1f))
            GhostButton("▣  打开输出目录", onOpenOutput, modifier = Modifier.fillMaxWidth().height(36.dp))
        }
    }
}

@Composable
private fun RegionRow(index: Int, region: CropRegion, regions: List<CropRegion>, onRegions: (List<CropRegion>) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .height(33.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (region.selected) Color(0xFF1E315F) else Color.Transparent)
            .clickable { onRegions(regions.map { it.copy(selected = it.id == region.id) }) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${index + 1}", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(28.dp))
        Text("${region.width} × ${region.height}", color = TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(if (region.visible) "◉" else "○", color = TextMuted, fontSize = 15.sp, modifier = Modifier.clickable {
            onRegions(regions.map { if (it.id == region.id) it.copy(visible = !it.visible) else it })
        })
        Spacer(Modifier.width(12.dp))
        Text("⌫", color = Color(0xFFFF554C), fontSize = 16.sp, modifier = Modifier.clickable {
            onRegions(regions.filterNot { it.id == region.id })
        })
    }
}

@Composable
private fun PanelCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(CardShape).background(Panel).border(1.dp, SoftBorder, CardShape).padding(14.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SegmentedMode(value: SplitMode, onValue: (SplitMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ModeChoice("自动", SplitMode.AutoDetect, value, onValue)
        ModeChoice("网格", SplitMode.Grid, value, onValue)
        ModeChoice("自定义", SplitMode.Custom, value, onValue)
    }
}

@Composable
private fun ModeChoice(label: String, mode: SplitMode, value: SplitMode, onValue: (SplitMode) -> Unit) {
    Row(
        Modifier.height(31.dp)
            .clip(ControlShape)
            .background(if (value == mode) Color(0xFF1F315F) else ControlBg)
            .border(1.dp, if (value == mode) Accent else Border, ControlShape)
            .clickable { onValue(mode) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (value == mode) Color(0xFFBFD0FF) else TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun ToolButton(label: String, active: Boolean, onClick: () -> Unit) {
    GhostButton(if (active) "✓ $label" else label, onClick, active = active)
}

@Composable
private fun CheckRow(label: String, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onValue)
        Text(label, color = Color.White)
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        SwitchPill(checked, onChecked)
    }
}

@Composable
private fun SwitchPill(checked: Boolean, onChecked: (Boolean) -> Unit) {
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
private fun CompactNumber(title: String, value: Int, suffix: String, onValue: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(64.dp).height(30.dp).clip(ControlShape)
                .background(Color(0xFF171C23))
                .border(1.dp, Border, ControlShape)
                .clickable { onValue((value + 1).coerceAtMost(999)) },
            contentAlignment = Alignment.Center
        ) {
            Text(value.toString(), color = Color.White, fontSize = 13.sp)
        }
        Text(suffix, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
private fun SelectField(title: String, value: String, onClick: () -> Unit) {
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
        Text("⌄", color = TextMuted, fontSize = 14.sp)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SmallCheck(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChecked)
        Text(label, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun SquareButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(ControlShape).background(ControlBg).border(1.dp, Border, ControlShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun SquareTab(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 56.dp, height = 30.dp).clip(ControlShape)
            .background(if (active) ControlActive else ControlBg)
            .border(1.dp, if (active) Accent else Border, ControlShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (active) Color(0xFFAEC5FF) else TextMuted, fontSize = 16.sp)
    }
}

@Composable
private fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    dashed: Boolean = false
) {
    Box(
        modifier
            .clip(ControlShape)
            .background(if (active) ControlActive else ControlBg)
            .border(1.dp, if (active) Accent else Border, ControlShape)
            .clickable(onClick = onClick)
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
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).clip(ControlShape).background(Accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ThumbnailBox() {
    Box(
        Modifier.fillMaxWidth().height(102.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, Border, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) { drawCheckerboard(size) }
        val icons = listOf("⌂", "●", "⚙", "✉", "⌕", "◔", "➤", "◆", "☎", "▣", "✎", "⇩", "⇧", "▰", "↗")
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            icons.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { Text(it, color = Color.White, fontSize = 17.sp) }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValue: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.filter(Char::isDigit).toIntOrNull()?.let(onValue) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun <T> EnumDropdown(label: String, value: T, values: List<T>, onValue: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：$value") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach {
                DropdownMenuItem(onClick = {
                    onValue(it)
                    expanded = false
                }) { Text(it.toString()) }
            }
        }
    }
}

private fun Offset.div(value: Float) = Offset(x / value, y / value)

private fun Modifier.weightOrHeight(): Modifier = this.fillMaxHeight()

private fun hitResizeCorner(region: CropRegion, point: Offset, zoom: Float): ResizeCorner? {
    if (!region.visible) return null
    val radius = (10f / zoom).coerceAtLeast(4f)
    fun near(x: Int, y: Int) = kotlin.math.abs(point.x - x) <= radius && kotlin.math.abs(point.y - y) <= radius
    return when {
        near(region.x, region.y) -> ResizeCorner.TopLeft
        near(region.right, region.y) -> ResizeCorner.TopRight
        near(region.x, region.bottom) -> ResizeCorner.BottomLeft
        near(region.right, region.bottom) -> ResizeCorner.BottomRight
        else -> null
    }
}

private fun findHandleHit(regions: List<CropRegion>, point: Offset, zoom: Float): Pair<CropRegion, ResizeCorner>? {
    for (index in regions.indices.reversed()) {
        val region = regions[index]
        val corner = hitResizeCorner(region, point, zoom)
        if (corner != null) return region to corner
    }
    return null
}

private fun resizeRegion(region: CropRegion, corner: ResizeCorner, point: Offset, imageWidth: Int, imageHeight: Int): CropRegion {
    val minSize = 2
    return when (corner) {
        ResizeCorner.TopLeft -> {
            val x = point.x.roundToInt().coerceIn(0, region.right - minSize)
            val y = point.y.roundToInt().coerceIn(0, region.bottom - minSize)
            region.copy(x = x, y = y, width = region.right - x, height = region.bottom - y)
        }
        ResizeCorner.TopRight -> {
            val right = point.x.roundToInt().coerceIn(region.x + minSize, imageWidth)
            val y = point.y.roundToInt().coerceIn(0, region.bottom - minSize)
            region.copy(y = y, width = right - region.x, height = region.bottom - y)
        }
        ResizeCorner.BottomLeft -> {
            val x = point.x.roundToInt().coerceIn(0, region.right - minSize)
            val bottom = point.y.roundToInt().coerceIn(region.y + minSize, imageHeight)
            region.copy(x = x, width = region.right - x, height = bottom - region.y)
        }
        ResizeCorner.BottomRight -> {
            val right = point.x.roundToInt().coerceIn(region.x + minSize, imageWidth)
            val bottom = point.y.roundToInt().coerceIn(region.y + minSize, imageHeight)
            region.copy(width = right - region.x, height = bottom - region.y)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckerboard(size: Size) {
    val cell = 16f
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = 0f
        var column = 0
        while (x < size.width) {
            drawRect(
                color = if ((row + column) % 2 == 0) Color(0xFF1E232B) else Color(0xFF252B34),
                topLeft = Offset(x, y),
                size = Size(cell, cell)
            )
            x += cell
            column++
        }
        y += cell
        row++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(size: Size, zoom: Float) {
    val step = 96f * zoom
    if (step < 8f) return
    var x = 0f
    while (x <= size.width) {
        drawLine(Color.White.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, size.height), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        y += step
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(center: Offset, color: Color, handleSize: Float = 8f) {
    drawCircle(Color(0xFFBFD2FF), radius = handleSize / 2f + 1.5f, center = center)
    drawCircle(color, radius = handleSize / 2f, center = center)
}

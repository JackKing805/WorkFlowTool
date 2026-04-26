package io.github.workflowtool.application

import androidx.compose.ui.geometry.Offset
import io.github.workflowtool.platform.DesktopPlatform
import java.io.File
import javax.imageio.ImageIO

fun AppController.chooseImageFile() {
    DesktopPlatform.chooseImageFile()?.let(::loadFileAsync)
}

fun AppController.chooseOutputDirectory() {
    DesktopPlatform.chooseDirectory()?.let { outputDirectory = it.toPath() }
}

fun AppController.openOutputDirectory() {
    openDirectorySafely(outputDirectory, "输出目录")
}

fun AppController.openRuntimeDirectory() {
    openDirectorySafely(AppRuntimeFiles.runtimeRoot, "应用运行目录")
}

fun AppController.openPythonRuntimeDirectory() {
    openDirectorySafely(AppRuntimeFiles.pythonDir, "Python 运行目录")
}

fun AppController.openTrainingSetDirectory() {
    openDirectorySafely(AppRuntimeFiles.pythonDir.resolve("training_sets"), "训练集目录")
}

fun AppController.openModelDirectory() {
    openDirectorySafely(AppRuntimeFiles.pythonDir.resolve("model"), "模型目录")
}

fun AppController.openNativeRuntimeDirectory() {
    openDirectorySafely(AppRuntimeFiles.runtimeRoot.resolve("native"), "Native 运行目录")
}

fun AppController.loadFile(file: File) {
    imageFiles = listOf(file)
    currentImageIndex = 0
    loadFileContent(file)
}

private fun AppController.loadFileContent(file: File) {
    runCatching {
        val loaded = ImageIO.read(file) ?: error("不支持的图片格式")
        imageFile = file
        image = loaded
        zoom = 1.0f
        viewportOffset = Offset.Zero
        hoveredImagePoint = null
        backgroundPickArmed = false
        magicSelectionPreview = null
        log("图片加载成功：${file.name}，${loaded.width} x ${loaded.height}")
        regenerateBaseSafely(logResult = true)
    }.onFailure {
        log("图片加载失败：${it.message}")
    }
}

fun AppController.loadFileAsync(file: File) {
    runBusy("正在导入并识别图片...") {
        loadFile(file)
    }
}

fun AppController.loadFilesAsync(files: List<File>) {
    val images = files.filter { it.isFile && it.extension.lowercase() in supportedImageExtensions }
    if (images.isEmpty()) {
        log("未找到可导入的图片文件")
        return
    }
    runBusy("正在拖入 ${images.size} 张图片...") {
        appendDroppedFiles(images)
    }
}

fun AppController.loadFiles(files: List<File>) {
    val images = files.filter { it.isFile && it.extension.lowercase() in supportedImageExtensions }
    if (images.isEmpty()) {
        log("未找到可导入的图片文件")
        return
    }
    if (images.size == 1) {
        loadFile(images.first())
        return
    }
    runCatching {
        val loaded = images.mapNotNull { file ->
            ImageIO.read(file)?.let { file to it }
        }
        if (loaded.isEmpty()) error("没有可读取的图片")
        val combined = combineImages(loaded.map { it.second }, multiImageGap)
        imageFiles = loaded.map { it.first }
        currentImageIndex = 0
        imageFile = loaded.first().first
        image = combined
        zoom = 1.0f
        viewportOffset = Offset.Zero
        hoveredImagePoint = null
        backgroundPickArmed = false
        magicSelectionPreview = null
        log("多图画布加载成功：${loaded.size} 张，${combined.width} x ${combined.height}，间隔 ${multiImageGap}px")
        regenerateBaseSafely(logResult = true)
    }.onFailure {
        log("多图导入失败：${it.message}")
    }
}

private fun AppController.appendDroppedFiles(files: List<File>) {
    val current = image
    if (current == null) {
        loadFiles(files)
        return
    }
    runCatching {
        val loaded = files.mapNotNull { file ->
            ImageIO.read(file)?.let { file to it }
        }
        if (loaded.isEmpty()) error("没有可读取的图片")
        val combined = appendImagesToCanvas(current, loaded.map { it.second }, multiImageGap)
        imageFiles = imageFiles + loaded.map { it.first }
        image = combined
        hoveredImagePoint = null
        backgroundPickArmed = false
        magicSelectionPreview = null
        log("已拖入追加 ${loaded.size} 张图片：画布 ${combined.width} x ${combined.height}，原有选框已保留")
    }.onFailure {
        log("拖入图片失败：${it.message}")
    }
}

fun AppController.openPreviousImage() {
    val files = imageFiles
    if (files.size <= 1 || currentImageIndex <= 0) return
    runBusy("正在切换图片...") {
        currentImageIndex -= 1
        loadFileContent(files[currentImageIndex])
    }
}

fun AppController.openNextImage() {
    val files = imageFiles
    if (files.size <= 1 || currentImageIndex !in 0 until files.lastIndex) return
    runBusy("正在切换图片...") {
        currentImageIndex += 1
        loadFileContent(files[currentImageIndex])
    }
}

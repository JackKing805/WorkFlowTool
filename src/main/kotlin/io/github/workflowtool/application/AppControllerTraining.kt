package io.github.workflowtool.application

import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.platform.DesktopPlatform
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.io.path.exists

fun AppController.appendTrainingSample(sourceLabel: String): Boolean {
    val loaded = image ?: return false
    val visibleRegions = regions.filter { it.visible }
    if (visibleRegions.isEmpty()) return false
    return runCatching {
        val datasetRoot = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("user_feedback")
        val imagesDir = datasetRoot.resolve("images")
        Files.createDirectories(imagesDir)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        val sourceName = imageFile?.nameWithoutExtension.orEmpty().ifBlank { "canvas" }
        val safeName = sourceName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "canvas" }
        val targetName = "${timestamp}_${safeName}.png"
        val target = imagesDir.resolve(targetName)
        check(ImageIO.write(loaded, "png", target.toFile())) { "训练样本图片写入失败" }
        val line = buildTrainingJsonLine("images/$targetName", imagePixelHash(loaded), visibleRegions)
        Files.writeString(
            datasetRoot.resolve("annotations.jsonl"),
            line + System.lineSeparator(),
            Charsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
        )
        log("持续学习样本已记录：$sourceLabel，${visibleRegions.size} 个区域")
        true
    }.onFailure {
        log("持续学习样本记录失败：${it.message}")
    }.getOrDefault(false)
}

fun AppController.appendMagicTrainingSample(sourceLabel: String): Boolean {
    val loaded = image ?: return false
    val preview = magicSelectionPreview ?: return false
    val acceptedRegion = preview.regionId?.let { id -> regions.firstOrNull { it.id == id } } ?: return false
    return runCatching {
        val datasetRoot = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("magic_feedback")
        val imagesDir = datasetRoot.resolve("images")
        Files.createDirectories(imagesDir)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        val sourceName = imageFile?.nameWithoutExtension.orEmpty().ifBlank { "canvas" }
        val safeName = sourceName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "canvas" }
        val targetName = "${timestamp}_${safeName}_magic.png"
        val target = imagesDir.resolve(targetName)
        check(ImageIO.write(loaded, "png", target.toFile())) { "魔棒训练样本图片写入失败" }
        val line = buildMagicTrainingJsonLine(
            imagePath = "images/$targetName",
            seedX = preview.seedX,
            seedY = preview.seedY,
            tolerance = magicTolerance,
            region = acceptedRegion
        )
        Files.writeString(
            datasetRoot.resolve("annotations.jsonl"),
            line + System.lineSeparator(),
            Charsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
        )
        log("魔棒训练样本已记录：$sourceLabel")
        true
    }.onFailure {
        log("魔棒训练样本记录失败：${it.message}")
    }.getOrDefault(false)
}

fun AppController.appendBackgroundTrainingSample(sourceLabel: String): Boolean {
    val loaded = image ?: return false
    val background = sampledBackgroundArgb ?: return false
    return runCatching {
        val datasetRoot = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("background_feedback")
        Files.createDirectories(datasetRoot)
        val edgeArgb = estimateCornerBackgroundArgb(loaded)
        val line = """{"edgeArgb":$edgeArgb,"backgroundArgb":$background}"""
        Files.writeString(
            datasetRoot.resolve("annotations.jsonl"),
            line + System.lineSeparator(),
            Charsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
        )
        log("背景训练样本已记录：$sourceLabel")
        true
    }.onFailure {
        log("背景训练样本记录失败：${it.message}")
    }.getOrDefault(false)
}

fun AppController.retrainContinuousModels(update: ContinuousTrainingUpdate): Boolean {
    return runCatching {
        setTrainingBusyMessage("正在用本次结果更新模型...")
        if (update.icon) {
            val makeDataset = runPythonCommand("make_training_set.py")
            check(makeDataset.exitCode == 0) { makeDataset.output.ifBlank { "训练集生成失败" } }
            val recentManifest = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("recent_feedback").resolve("annotations.jsonl")
            if (shouldRunFullIconRetrain()) {
                val trainCombined = runPythonCommand(
                    "train_icon_detector.py",
                    "--dataset",
                    "training_sets/combined",
                    "--out",
                    "model/combined",
                    "--epochs",
                    "4",
                    "--imgsz",
                    "512",
                    "--batch",
                    "2"
                )
                check(trainCombined.exitCode == 0) { trainCombined.output.ifBlank { "模型训练失败" } }
                log("图标模型已执行全量稳态训练")
            }
            if (recentManifest.exists()) {
                val trainRecent = runPythonCommand(
                    "train_icon_detector.py",
                    "--dataset",
                    "training_sets/recent_feedback",
                    "--out",
                    "model/combined",
                    "--epochs",
                    "2",
                    "--imgsz",
                    "512",
                    "--batch",
                    "1"
                )
                check(trainRecent.exitCode == 0) { trainRecent.output.ifBlank { "最近样本微调失败" } }
                log("图标模型已执行最近样本快速微调")
            }
        }
        if (update.magic) trainMagicModelIfNeeded()
        if (update.background) trainBackgroundModelIfNeeded()
        log("持续学习模型已更新，下次识别会使用新模型")
        true
    }.onFailure {
        log("持续学习模型更新失败：${it.message}")
    }.getOrDefault(false)
}

fun AppController.trainMagicModelIfNeeded() {
    val samples = AppRuntimeFiles.pythonDir
        .resolve("training_sets")
        .resolve("magic_feedback")
        .resolve("annotations.jsonl")
    if (!samples.exists()) return
    val train = runPythonCommand("train_magic_model.py")
    check(train.exitCode == 0) { train.output.ifBlank { "魔棒模型训练失败" } }
    MagicToleranceModel.invalidate()
}

fun AppController.trainBackgroundModelIfNeeded() {
    val samples = AppRuntimeFiles.pythonDir
        .resolve("training_sets")
        .resolve("background_feedback")
        .resolve("annotations.jsonl")
    if (!samples.exists()) return
    val train = runPythonCommand("train_background_model.py")
    check(train.exitCode == 0) { train.output.ifBlank { "背景模型训练失败" } }
    BackgroundColorModel.invalidate()
}

fun shouldRunFullIconRetrain(): Boolean {
    val best = AppRuntimeFiles.pythonDir.resolve("model").resolve("combined").resolve("runs").resolve("weights").resolve("best.pt")
    if (!best.exists()) return true
    val feedbackManifest = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("user_feedback").resolve("annotations.jsonl")
    if (!feedbackManifest.exists()) return false
    val sampleCount = runCatching {
        Files.readAllLines(feedbackManifest, Charsets.UTF_8).count { it.isNotBlank() }
    }.getOrDefault(0)
    return sampleCount <= 2 || sampleCount % 5 == 0
}

fun AppController.buildTrainingFingerprint(config: ExportConfig): String {
    val source = buildString {
        append("image=").append(imageIdentity()).append('\n')
        append("regions=").append(trainingComparableRegions(regions).filter { it.visible }.joinToString("|") { region ->
            buildString {
                append(region.x).append(',').append(region.y).append(',')
                    .append(region.width).append(',').append(region.height).append(',')
                    .append(region.visible)
                append(':')
                append(region.points.joinToString(";") { "${it.x},${it.y}" })
            }
        }).append('\n')
        append("sampledBackground=").append(sampledBackgroundArgb ?: "auto").append('\n')
        append("magic=").append(magicSelectionPreview?.let { "${it.seedX},${it.seedY},$magicTolerance,${it.regionId}" } ?: "none").append('\n')
        append("format=").append(config.outputFormat).append('\n')
        append("keepOriginalSize=").append(config.keepOriginalSize).append('\n')
        append("trimTransparentPadding=").append(config.trimTransparentPadding).append('\n')
        append("removeBackgroundToTransparent=").append(config.removeBackgroundToTransparent).append('\n')
        append("backgroundArgb=").append(config.backgroundArgb).append('\n')
        append("backgroundTolerance=").append(config.backgroundTolerance).append('\n')
        append("padToSquare=").append(config.padToSquare).append('\n')
        append("fixedSize=").append(config.fixedSize ?: "none")
    }
    return sha256(source)
}

fun AppController.imageIdentity(): String {
    val loaded = image ?: return "none"
    val files = imageFiles.takeIf { it.isNotEmpty() } ?: imageFile?.let(::listOf).orEmpty()
    val fileIdentity = files.joinToString("|") { file ->
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }
    return "${loaded.width}x${loaded.height}:$fileIdentity"
}

fun hasTrainingFingerprint(fingerprint: String): Boolean {
    val file = trainingFingerprintFile()
    if (!file.exists()) return false
    return runCatching {
        Files.readAllLines(file, Charsets.UTF_8).any { it == fingerprint }
    }.getOrDefault(false)
}

fun AppController.rememberTrainingFingerprint(fingerprint: String) {
    runCatching {
        val file = trainingFingerprintFile()
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            fingerprint + System.lineSeparator(),
            Charsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
        )
    }.onFailure {
        log("训练指纹记录失败：${it.message}")
    }
}

fun trainingFingerprintFile(): Path {
    return AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("trained_fingerprints.txt")
}

fun runPythonCommand(vararg args: String): ProcessResult {
    val process = ProcessBuilder(listOf("python") + args)
        .directory(AppRuntimeFiles.pythonDir.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
    return ProcessResult(process.waitFor(), output)
}

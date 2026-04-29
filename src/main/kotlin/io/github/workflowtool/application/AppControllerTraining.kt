package io.github.workflowtool.application

import io.github.workflowtool.model.ExportConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
        val line = buildTrainingJsonLine(
            "images/$targetName",
            imagePixelHash(loaded),
            visibleRegions,
            mapOf(
                "source" to sourceLabel,
                "learningMode" to "confirmed_manual_edit",
                "createdAt" to LocalDateTime.now().toString(),
                "regionCount" to visibleRegions.size.toString()
            )
        )
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

fun AppController.retrainSeedAndUserFeedbackModelAsync() {
    runBusy("正在根据本地训练样本重建图标模型...") {
        retrainSeedAndUserFeedbackModel()
    }
}

fun AppController.retrainSeedAndUserFeedbackModel(): Boolean {
    return runCatching {
        setTrainingBusyMessage("正在合并本地训练样本...")
        val makeDataset = runPythonCommand("make_training_set.py")
        check(makeDataset.exitCode == 0) { makeDataset.output.ifBlank { "训练集生成失败" } }
        val learningConfig = loadLearningConfig()

        trainAndPromoteIconModel(
            dataset = "training_sets/combined",
            epochs = learningConfig.fullRetrainEpochs.toString(),
            batch = learningConfig.fullBatch.toString()
        )
        log("图标模型已根据本地训练样本重建")
        true
    }.onFailure {
        log("图标模型重建失败：${it.message}")
    }.getOrDefault(false)
}

fun AppController.retrainContinuousModels(update: ContinuousTrainingUpdate): Boolean {
    return runCatching {
        setTrainingBusyMessage("正在用本次结果更新模型...")
        if (update.icon) {
            val learningConfig = loadLearningConfig()
            val makeDataset = runPythonCommand("make_training_set.py")
            check(makeDataset.exitCode == 0) { makeDataset.output.ifBlank { "训练集生成失败" } }
            val recentManifest = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("recent_feedback").resolve("annotations.jsonl")
            var iconModelUpdated = false
            if (shouldRunFullIconRetrain()) {
                iconModelUpdated = trainCandidateAndPromoteIconModel(
                    dataset = "training_sets/combined",
                    epochs = learningConfig.fullRetrainEpochs.toString(),
                    batch = learningConfig.fullBatch.toString(),
                    resume = false
                )
            }
            if (recentManifest.exists() && shouldRunRecentIconFineTune()) {
                iconModelUpdated = trainCandidateAndPromoteIconModel(
                    dataset = "training_sets/recent_feedback",
                    epochs = learningConfig.recentFineTuneEpochs.toString(),
                    batch = learningConfig.recentBatch.toString(),
                    resume = true
                )
            }
            if (iconModelUpdated) {
                AppRuntimeFiles.markUserModelUpdated()
                evolveLearningConfigAfterFeedback()?.let { evolution ->
                    log("模型参数已自适应更新：${evolution.summary()}")
                }
            }
            if (!iconModelUpdated) {
                log("持续学习样本已记录：等待更多确认样本后训练")
            }
        }
        if (update.background) trainBackgroundModelIfNeeded()
        log("持续学习处理完成")
        true
    }.onFailure {
        log("持续学习模型更新失败：${it.message}")
    }.getOrDefault(false)
}

fun AppController.trainAndPromoteIconModel(
    dataset: String,
    epochs: String,
    batch: String
): Boolean {
    return trainCandidateAndPromoteIconModel(dataset, epochs, batch, resume = false)
}

fun AppController.trainCandidateAndPromoteIconModel(
    dataset: String,
    epochs: String,
    batch: String,
    resume: Boolean
): Boolean {
    setTrainingBusyMessage("正在训练图标模型...")
    val pythonDir = AppRuntimeFiles.pythonDir
    val learningConfig = loadLearningConfig()
    val activeDir = pythonDir.resolve("model").resolve("instance_segmentation")
    val candidateDir = pythonDir.resolve("model").resolve("instance_segmentation_candidate")
    deleteDirectory(candidateDir)
    val train = runPythonCommand(
        "train_icon_detector.py",
        "--dataset",
        dataset,
        "--out",
        "model/instance_segmentation_candidate",
        "--epochs",
        epochs,
        "--imgsz",
        "512",
        "--batch",
        batch,
        "--lr",
        (if (resume) learningConfig.fineTuneLearningRate else learningConfig.learningRate).toString(),
        "--bce-weight",
        learningConfig.bceWeight.toString(),
        "--dice-weight",
        learningConfig.diceWeight.toString(),
        "--focal-weight",
        learningConfig.focalWeight.toString(),
        "--score-threshold",
        learningConfig.scoreThreshold.toString(),
        "--mask-threshold",
        learningConfig.maskThreshold.toString(),
        *resumeArgs(activeDir, resume).toTypedArray()
    )
    check(train.exitCode == 0) { train.output.ifBlank { "图标模型训练失败" } }
    check(candidateModelLooksUsable(candidateDir)) { "候选图标模型验证失败，已保留当前模型" }

    promoteCandidateModel(activeDir, candidateDir)
    AppRuntimeFiles.markUserModelUpdated()
    log("图标模型已更新：$dataset")
    return true
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
    val modelManifest = AppRuntimeFiles.pythonDir.resolve("model").resolve("instance_segmentation").resolve("model.json")
    if (!modelManifest.exists()) return true
    val feedbackManifest = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("user_feedback").resolve("annotations.jsonl")
    if (!feedbackManifest.exists()) return false
    val sampleCount = runCatching {
        Files.readAllLines(feedbackManifest, Charsets.UTF_8).count { it.isNotBlank() }
    }.getOrDefault(0)
    return sampleCount <= 2 || sampleCount % 5 == 0
}

fun shouldRunRecentIconFineTune(): Boolean {
    val feedbackManifest = AppRuntimeFiles.pythonDir.resolve("training_sets").resolve("user_feedback").resolve("annotations.jsonl")
    if (!feedbackManifest.exists()) return false
    val sampleCount = runCatching {
        Files.readAllLines(feedbackManifest, Charsets.UTF_8).count { it.isNotBlank() }
    }.getOrDefault(0)
    return sampleCount <= 2 || sampleCount % 3 == 0
}

fun resumeArgs(activeDir: Path, resume: Boolean): List<String> {
    val weights = activeDir.resolve("model.pt")
    return if (resume && weights.exists()) listOf("--resume", "model/instance_segmentation/model.pt") else emptyList()
}

fun candidateModelLooksUsable(candidateDir: Path): Boolean {
    val weights = candidateDir.resolve("model.pt")
    val onnx = candidateDir.resolve("model.onnx")
    val manifest = candidateDir.resolve("model.json")
    if (!weights.exists() || !onnx.exists() || !manifest.exists()) return false
    val learningConfig = loadLearningConfig()
    val text = runCatching { Files.readString(manifest, Charsets.UTF_8) }.getOrDefault("")
    val maxProbability = Regex(""""maxProbability"\s*:\s*([0-9.]+)""").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    val foregroundRatio = Regex(""""predictedForegroundRatio"\s*:\s*([0-9.]+)""").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    return maxProbability != null &&
        foregroundRatio != null &&
        maxProbability >= learningConfig.scoreThreshold &&
        foregroundRatio > 0.00001
}

fun promoteCandidateModel(activeDir: Path, candidateDir: Path) {
    val backupDir = activeDir.parent.resolve("instance_segmentation_previous")
    deleteDirectory(backupDir)
    if (activeDir.exists()) {
        Files.createDirectories(backupDir.parent)
        Files.move(activeDir, backupDir, StandardCopyOption.REPLACE_EXISTING)
    }
    Files.move(candidateDir, activeDir, StandardCopyOption.REPLACE_EXISTING)
}

fun deleteDirectory(path: Path) {
    if (!path.exists()) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
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
                append(region.maskWidth).append('x').append(region.maskHeight).append(':')
                append(region.alphaMask.joinToString(","))
            }
        }).append('\n')
        append("sampledBackground=").append(sampledBackgroundArgb ?: "auto").append('\n')
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
    val environment = PythonEnvironmentManager.ensureReady()
    if (!environment.ready) {
        return ProcessResult(127, environment.message)
    }
    val command = PythonRuntime.buildCommand(args.toList())
        ?: return ProcessResult(127, "Python virtual environment is not ready.")
    val process = PythonRuntime.configureProcess(
        ProcessBuilder(command)
            .directory(AppRuntimeFiles.pythonDir.toFile())
            .redirectErrorStream(true)
    ).start()
    val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
    return ProcessResult(process.waitFor(), output)
}

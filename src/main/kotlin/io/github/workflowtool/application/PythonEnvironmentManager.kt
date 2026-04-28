package io.github.workflowtool.application

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

internal object PythonEnvironmentManager {
    private val requiredModules = listOf("torch", "torchvision", "onnxruntime", "numpy", "PIL")
    private val modelDir: Path
        get() = AppRuntimeFiles.pythonDir.resolve("model").resolve("instance_segmentation")
    private val modelFile: Path get() = modelDir.resolve("model.onnx")
    private val metadataFile: Path get() = modelDir.resolve("model.json")
    private val weightsFile: Path get() = modelDir.resolve("model.pt")

    private var cachedStatus: EnvironmentStatus? = null

    fun ensureReady(onStage: (RuntimePreparationStage) -> Unit = {}): EnvironmentStatus {
        cachedStatus?.takeIf { it.ready }?.let { return it }
        onStage(RuntimePreparationStage.Checking)
        val status = createVenv(onStage)
            .then { ensureDependencies(onStage) }
            .then { ensureModel(onStage) }
        cachedStatus = status
        onStage(if (status.ready) RuntimePreparationStage.Ready else RuntimePreparationStage.Failed)
        return status
    }

    fun invalidate() {
        cachedStatus = null
    }

    fun statusSummary(): String {
        return cachedStatus?.message ?: "python environment not prepared; venv: ${PythonRuntime.venvDir}; model: $modelFile"
    }

    private fun createVenv(onStage: (RuntimePreparationStage) -> Unit): EnvironmentStatus {
        if (PythonRuntime.isVenvAvailable) {
            return EnvironmentStatus(true, "venv ready: ${PythonRuntime.venvPython}")
        }
        onStage(RuntimePreparationStage.CreatingVenv)
        val command = PythonRuntime.buildSystemCommand(listOf("-m", "venv", PythonRuntime.venvDir.toString()))
            ?: return EnvironmentStatus(false, "system python missing; cannot create venv at ${PythonRuntime.venvDir}")
        return runProcess(command, AppRuntimeFiles.runtimeRoot, "create python venv")
    }

    private fun ensureDependencies(onStage: (RuntimePreparationStage) -> Unit): EnvironmentStatus {
        onStage(RuntimePreparationStage.CheckingDependencies)
        if (dependenciesAvailable()) {
            return EnvironmentStatus(true, "python dependencies ready")
        }
        if (skipDependencyInstall()) {
            return EnvironmentStatus(false, "python dependencies missing and WORKFLOWTOOL_SKIP_PYTHON_DEP_INSTALL=true")
        }
        val requirements = AppRuntimeFiles.pythonDir.resolve("requirements.txt")
        if (!requirements.exists()) {
            return EnvironmentStatus(false, "python requirements missing ($requirements)")
        }
        onStage(RuntimePreparationStage.InstallingDependencies)
        val pipUpgrade = PythonRuntime.buildCommand(listOf("-m", "pip", "install", "--upgrade", "pip"))
            ?: return EnvironmentStatus(false, "venv python missing after creation")
        runProcess(pipUpgrade, AppRuntimeFiles.runtimeRoot, "upgrade pip").takeUnless { it.ready }?.let { return it }
        val install = PythonRuntime.buildCommand(listOf("-m", "pip", "install", "-r", requirements.toString()))
            ?: return EnvironmentStatus(false, "venv python missing after creation")
        return runProcess(install, AppRuntimeFiles.runtimeRoot, "install python dependencies")
            .then {
                if (dependenciesAvailable()) {
                    EnvironmentStatus(true, "python dependencies installed")
                } else {
                    EnvironmentStatus(false, "python dependencies still missing after pip install")
                }
            }
    }

    private fun ensureModel(onStage: (RuntimePreparationStage) -> Unit): EnvironmentStatus {
        if (modelFile.exists() && metadataFile.exists()) {
            return EnvironmentStatus(true, "model ready: $modelFile")
        }
        Files.createDirectories(modelDir)
        onStage(RuntimePreparationStage.PreparingTrainingSet)
        val makeTrainingSet = PythonRuntime.buildCommand(listOf(AppRuntimeFiles.pythonDir.resolve("make_training_set.py").toString()))
            ?: return EnvironmentStatus(false, "venv python unavailable for training-set preparation")
        runProcess(makeTrainingSet, AppRuntimeFiles.pythonDir, "prepare training set").takeUnless { it.ready }?.let { return it }

        val combinedManifest = AppRuntimeFiles.pythonDir
            .resolve("training_sets")
            .resolve("combined")
            .resolve("annotations.jsonl")
        val sampleCount = countTrainingRecords(combinedManifest)
        if (sampleCount <= 0) {
            return EnvironmentStatus(false, "training set is empty after preparation ($combinedManifest); check seed_pretrain annotations")
        }

        onStage(RuntimePreparationStage.TrainingModel)
        val train = PythonRuntime.buildCommand(
            listOf(
                AppRuntimeFiles.pythonDir.resolve("train_icon_detector.py").toString(),
                "--dataset",
                "training_sets/combined",
                "--out",
                "model/instance_segmentation",
                "--epochs",
                System.getenv("WORKFLOWTOOL_TRAIN_EPOCHS")?.takeIf { it.isNotBlank() } ?: "2"
            )
        ) ?: return EnvironmentStatus(false, "venv python unavailable for model training")
        return runProcess(train, AppRuntimeFiles.pythonDir, "train first-run icon model")
            .then {
                if (modelFile.exists() && metadataFile.exists() && weightsFile.exists()) {
                    AppRuntimeFiles.markUserModelUpdated()
                    EnvironmentStatus(true, "model trained: $modelFile")
                } else {
                    EnvironmentStatus(false, "training completed but model files are missing in $modelDir")
                }
            }
    }

    private fun dependenciesAvailable(): Boolean {
        val command = PythonRuntime.buildCommand(
            listOf("-c", requiredModules.joinToString(";") { "import $it" })
        ) ?: return false
        return runProcess(command, AppRuntimeFiles.runtimeRoot, "probe python dependencies", captureLimit = 512).ready
    }

    private fun countTrainingRecords(path: Path): Int {
        if (!path.exists()) return 0
        return runCatching {
            Files.readAllLines(path, Charsets.UTF_8).count { it.isNotBlank() }
        }.getOrDefault(0)
    }

    private fun runProcess(
        command: List<String>,
        workingDir: Path,
        label: String,
        captureLimit: Int = 2000
    ): EnvironmentStatus {
        return runCatching {
            val process = PythonRuntime.configureProcess(
                ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
            ).start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            val exit = process.waitFor()
            if (exit == 0) {
                EnvironmentStatus(true, "$label succeeded")
            } else {
                EnvironmentStatus(false, "$label failed ($exit): ${output.takeLast(captureLimit)}")
            }
        }.getOrElse {
            EnvironmentStatus(false, "$label failed (${it.message ?: it::class.simpleName})")
        }
    }

    private fun skipDependencyInstall(): Boolean {
        return System.getenv("WORKFLOWTOOL_SKIP_PYTHON_DEP_INSTALL")
            ?.trim()
            ?.lowercase()
            ?.let { it == "1" || it == "true" || it == "yes" }
            ?: false
    }

    private fun EnvironmentStatus.then(next: () -> EnvironmentStatus): EnvironmentStatus {
        return if (ready) next() else this
    }
}

internal data class EnvironmentStatus(
    val ready: Boolean,
    val message: String
)

enum class RuntimePreparationStage(val label: String) {
    NotChecked("未检查"),
    Checking("检查运行环境"),
    CreatingVenv("创建虚拟环境"),
    CheckingDependencies("检查依赖"),
    InstallingDependencies("安装依赖"),
    PreparingTrainingSet("准备训练集"),
    TrainingModel("训练模型"),
    Ready("可用"),
    Failed("失败")
}

package io.github.workflowtool.application

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.exists

internal object AppRuntimeFiles {
    private val appDataRoot: Path by lazy {
        writableAppDataRoot()
    }

    val runtimeRoot: Path get() = appDataRoot

    val pythonDir: Path
        get() {
            val target = appDataRoot.resolve("python_detector")
            installPythonAssets(target)
            return target
        }

    private fun installPythonAssets(target: Path) {
        Files.createDirectories(target)
        pythonAssets.forEach { relative ->
            when (pythonAssetPolicy(relative)) {
                PythonAssetPolicy.Overwrite -> copyResource("python_detector/$relative", target.resolve(relative), overwrite = true)
                PythonAssetPolicy.CopyIfMissing -> copyResource(
                    "python_detector/$relative",
                    target.resolve(relative),
                    overwrite = false
                )
            }
        }
    }

    fun markUserModelUpdated() {
        val target = pythonDir.resolve("model").resolve("runtime-model-state.json")
        runCatching {
            Files.createDirectories(target.parent)
            Files.writeString(
                target,
                """{"userTrained":true,"updatedAt":"${Instant.now()}"}""",
                Charsets.UTF_8
            )
        }
    }

    fun clearCreatedFiles(): RuntimeCleanupResult {
        val targets = listOf(
            appDataRoot.resolve("python-venv"),
            appDataRoot.resolve("python_detector").resolve("training_sets").resolve("combined"),
            appDataRoot.resolve("python_detector").resolve("training_sets").resolve("recent_feedback"),
            appDataRoot.resolve("python_detector").resolve("training_sets").resolve("magic_seed"),
            appDataRoot.resolve("python_detector").resolve("training_sets").resolve("test_actions"),
            appDataRoot.resolve("python_detector").resolve("model").resolve("instance_segmentation"),
            appDataRoot.resolve("python_detector").resolve("model").resolve("instance_segmentation_candidate"),
            appDataRoot.resolve("python_detector").resolve("model").resolve("instance_segmentation_previous"),
            appDataRoot.resolve("python_detector").resolve("model").resolve("combined"),
            appDataRoot.resolve("python_detector").resolve("model").resolve("magic"),
            appDataRoot.resolve("python_detector").resolve("model").resolve("evolution-previews"),
            appDataRoot.resolve("python_detector").resolve("model").resolve("runtime-model-state.json"),
            appDataRoot.resolve("python_detector").resolve("model").resolve("evolution-history.json")
        )
        var deleted = 0
        val failures = mutableListOf<String>()
        targets.forEach { target ->
            if (!isSafeRuntimeTarget(target) || !target.exists()) return@forEach
            Files.walk(target).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    runCatching {
                        Files.deleteIfExists(path)
                        deleted += 1
                    }.onFailure {
                        failures += "${path.fileName}: ${it.message ?: it::class.simpleName}"
                    }
                }
            }
        }
        return RuntimeCleanupResult(appDataRoot, deleted, failures)
    }

    private fun isSafeRuntimeTarget(path: Path): Boolean {
        val root = appDataRoot.toAbsolutePath().normalize()
        val candidate = path.toAbsolutePath().normalize()
        return candidate.startsWith(root) && candidate != root
    }

    private fun copyResource(resourcePath: String, target: Path, overwrite: Boolean): File? {
        val stream = AppRuntimeFiles::class.java.classLoader.getResourceAsStream(resourcePath)
        Files.createDirectories(target.parent)
        if (stream == null) return null
        stream.use { input ->
            if (overwrite || !target.exists()) {
                val bytes = input.readBytes()
                copyBytesIfChanged(bytes, target) ?: return existingTargetOrNull(target)
                applyExecutableBit(target)
            }
        }
        return target.toFile()
    }

    private fun copyBytesIfChanged(bytes: ByteArray, target: Path): File? {
        if (target.exists() && runCatching { Files.readAllBytes(target).contentEquals(bytes) }.getOrDefault(false)) {
            return target.toFile()
        }
        return runCatching {
            Files.write(target, bytes)
            target.toFile()
        }.getOrNull()
    }

    private fun existingTargetOrNull(target: Path): File? {
        return if (target.exists()) target.toFile() else null
    }

    private fun applyExecutableBit(target: Path) {
        val name = target.fileName.toString()
        if (name == "python" || name == "python3" || name == "python.exe") {
            runCatching { target.toFile().setExecutable(true, false) }
        }
    }

    fun offlineDependencyReport(): OfflineDependencyReport {
        val requirementsFile = pythonDir.resolve("requirements.txt")
        val modelFile = pythonDir.resolve("model").resolve("instance_segmentation").resolve("model.onnx")
        val statuses = listOf(
            OfflineDependencyStatus(
                name = "Python venv",
                ok = PythonRuntime.isVenvAvailable,
                detail = if (PythonRuntime.isVenvAvailable) {
                    PythonRuntime.venvPython.toString()
                } else {
                    "venv not ready: ${PythonRuntime.venvDir}; system python available=${PythonRuntime.isSystemPythonAvailable}"
                }
            ),
            OfflineDependencyStatus(
                name = "PyPI requirements",
                ok = requirementsFile.exists(),
                detail = requirementsFile.toString()
            ),
            OfflineDependencyStatus(
                name = "runtime model",
                ok = modelFile.exists(),
                detail = modelFile.toString()
            )
        )
        return OfflineDependencyReport(statuses)
    }

    private fun pythonAssetPolicy(relative: String): PythonAssetPolicy {
        return when {
            relative.endsWith(".py") || relative == "README.md" || relative == "requirements.txt" -> PythonAssetPolicy.Overwrite
            relative.startsWith("training_sets/seed_pretrain/") -> PythonAssetPolicy.Overwrite
            relative.startsWith("training_sets/background_seed/") -> PythonAssetPolicy.Overwrite
            else -> PythonAssetPolicy.CopyIfMissing
        }
    }

    private fun bundledSeedAssets(): List<String> {
        val stream = AppRuntimeFiles::class.java.classLoader.getResourceAsStream("python_detector/seed_images_manifest.txt")
            ?: return emptyList()
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .filterNot { it.contains("/__pycache__/") }
                .filterNot { it.startsWith("training_sets/combined/") || it.startsWith("training_sets/recent_feedback/") }
                .filterNot { it.endsWith(".svg", ignoreCase = true) }
                .toList()
        }
    }

    internal fun bundledPythonAssetsForTest(): List<String> = pythonAssets

    private val pythonAssets: List<String>
        get() = corePythonAssets + bundledSeedAssets()

    private val corePythonAssets = listOf(
        "offline_common.py",
        "requirements.txt",
        "detect_icons.py",
        "make_training_set.py",
        "seed_image_annotations.py",
        "train_icon_detector.py",
        "train_background_model.py",
        "README.md",
        "seed_images_manifest.txt",
        "training_sets/seed_pretrain/annotations.jsonl",
        "training_sets/background_seed/annotations.jsonl"
    )


    private fun writableAppDataRoot(): Path {
        val candidates = listOfNotNull(
            System.getenv("APPDATA")?.let { Path(it, "WorkFlowTool") },
            System.getProperty("user.home")?.let { Path(it, ".workflowtool", "WorkFlowTool") },
            Path(System.getProperty("java.io.tmpdir"), "WorkFlowTool")
        )
        for (candidate in candidates) {
            if (runCatching {
                    Files.createDirectories(candidate)
                    Files.createTempFile(candidate, ".write-test", ".tmp").also { Files.deleteIfExists(it) }
                }.isSuccess) {
                return candidate
            }
        }
        return Path("WorkFlowTool")
    }
}

internal data class RuntimeCleanupResult(
    val root: Path,
    val deletedCount: Int,
    val failures: List<String>
)

internal data class OfflineDependencyStatus(
    val name: String,
    val ok: Boolean,
    val detail: String
)

internal data class OfflineDependencyReport(
    val statuses: List<OfflineDependencyStatus>
) {
    val okCount: Int get() = statuses.count { it.ok }
    val totalCount: Int get() = statuses.size
    val isHealthy: Boolean get() = statuses.all { it.ok }
    val needsPythonRuntimePreparation: Boolean
        get() = statuses.any { status ->
            (status.name == "Python venv" || status.name == "runtime model") && !status.ok
        }

    fun summary(): String = "离线依赖自检：$okCount/$totalCount 通过"
}

private enum class PythonAssetPolicy {
    Overwrite,
    CopyIfMissing
}

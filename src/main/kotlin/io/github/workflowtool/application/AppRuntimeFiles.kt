package io.github.workflowtool.application

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.exists

internal object AppRuntimeFiles {
    private val projectRoot: Path
        get() = Path(System.getProperty("user.dir"))

    private val appDataRoot: Path by lazy {
        writableAppDataRoot()
    }

    val runtimeRoot: Path get() = appDataRoot

    val thirdPartyRoot: Path
        get() {
            val target = appDataRoot.resolve("third_party")
            installThirdPartyAssets(target)
            return target
        }

    val pythonDir: Path
        get() {
            prepareBundledDependencies()
            val target = appDataRoot.resolve("python_detector")
            installPythonAssets(target)
            return target
        }

    val nativeLibraryFile: File? by lazy {
        installNativeLibrary()
    }

    fun prepareBundledDependencies() {
        installThirdPartyAssets(appDataRoot.resolve("third_party"))
    }

    private fun installPythonAssets(target: Path) {
        Files.createDirectories(target)
        val preserveUserModels = hasUserModelMarker(target)
        pythonAssets.forEach { relative ->
            when (pythonAssetPolicy(relative)) {
                PythonAssetPolicy.Overwrite -> copyResource("python_detector/$relative", target.resolve(relative), overwrite = true)
                PythonAssetPolicy.CopyIfMissing -> copyResource(
                    "python_detector/$relative",
                    target.resolve(relative),
                    overwrite = false
                )
                PythonAssetPolicy.SeedModel -> {
                    if (!preserveUserModels) {
                        copyResource("python_detector/$relative", target.resolve(relative), overwrite = false)
                    }
                }
            }
        }
    }

    private fun installThirdPartyAssets(target: Path) {
        Files.createDirectories(target)
        thirdPartyAssetList().forEach { relative ->
            copyResource("third_party/$relative", target.resolve(relative), overwrite = false)
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

    private fun hasUserModelMarker(target: Path): Boolean {
        return target.resolve("model").resolve("runtime-model-state.json").exists()
    }

    fun clearCreatedFiles(): RuntimeCleanupResult {
        val targets = listOf(
            appDataRoot.resolve("python_detector"),
            appDataRoot.resolve("native")
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

    private fun installNativeLibrary(): File? {
        val libraryName = nativeLibraryName()
        val target = appDataRoot.resolve("native").resolve(libraryName)
        return copyResource("native/$libraryName", target, overwrite = true)
    }

    private fun copyResource(resourcePath: String, target: Path, overwrite: Boolean): File? {
        val stream = AppRuntimeFiles::class.java.classLoader.getResourceAsStream(resourcePath)
        Files.createDirectories(target.parent)
        if (stream != null) {
            if (overwrite || !target.exists()) {
                stream.use {
                    Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                }
                applyExecutableBit(target)
            } else {
                stream.close()
            }
            return target.toFile()
        }

        val projectFile = Path(System.getProperty("user.dir")).resolve(resourcePath)
        if (!projectFile.exists()) return null
        if (overwrite || !target.exists()) {
            Files.copy(projectFile, target, StandardCopyOption.REPLACE_EXISTING)
            applyExecutableBit(target)
        }
        return target.toFile()
    }

    private fun applyExecutableBit(target: Path) {
        val name = target.fileName.toString()
        if (name == "python" || name == "python3" || name == "python.exe") {
            runCatching { target.toFile().setExecutable(true, false) }
        }
    }

    fun offlineDependencyReport(): OfflineDependencyReport {
        prepareBundledDependencies()
        val pythonExecutables = PythonRuntime.bundledExecutableCandidates()
        val wheelRoots = bundledWheelRoots()
        val wheelFiles = wheelRoots.flatMap(::wheelArtifacts)
        val modelFile = pythonDir.resolve("model").resolve("combined").resolve("model.json")
        val nativeProjectFile = projectRoot.resolve("native").resolve(nativeLibraryName())
        val nativeRuntimeFile = appDataRoot.resolve("native").resolve(nativeLibraryName())
        val nativePresent = nativeRuntimeFile.exists() || nativeProjectFile.exists() || nativeLibraryFile?.exists() == true

        val statuses = listOf(
            OfflineDependencyStatus(
                name = "本地 Python",
                ok = pythonExecutables.isNotEmpty(),
                detail = if (pythonExecutables.isNotEmpty()) {
                    pythonExecutables.joinToString(", ") { it.toString() }
                } else {
                    "未找到项目内 Python，可选目录：${bundledPythonRootsForReport().joinToString(", ")}"
                }
            ),
            OfflineDependencyStatus(
                name = "离线 wheels/venv",
                ok = wheelFiles.isNotEmpty(),
                detail = if (wheelFiles.isNotEmpty()) {
                    "${wheelFiles.size} 个离线包/环境文件，目录：${wheelRoots.filter { it.exists() }.joinToString(", ")}"
                } else {
                    "未找到 .whl 或 pyvenv.cfg，候选目录：${wheelRoots.joinToString(", ")}"
                }
            ),
            OfflineDependencyStatus(
                name = "本地模型",
                ok = modelFile.exists(),
                detail = modelFile.toString()
            ),
            OfflineDependencyStatus(
                name = "本地原生库",
                ok = nativePresent,
                detail = listOf(nativeRuntimeFile, nativeProjectFile).joinToString(", ")
            )
        )
        return OfflineDependencyReport(statuses)
    }

    private fun bundledPythonRootsForReport(): List<Path> {
        val osDir = platformDirectoryName()
        val generic = platformDirectoryAlias()
        return listOf(
            thirdPartyRoot.resolve("python"),
            projectRoot.resolve("third_party").resolve("python"),
            projectRoot.resolve("third_party").resolve("python").resolve(osDir),
            projectRoot.resolve("third_party").resolve("python").resolve(generic),
            appDataRoot.resolve("python-runtime")
        ).distinct()
    }

    private fun bundledWheelRoots(): List<Path> {
        val osDir = platformDirectoryName()
        val generic = platformDirectoryAlias()
        return listOf(
            thirdPartyRoot.resolve("wheels"),
            thirdPartyRoot.resolve("wheels").resolve(osDir),
            thirdPartyRoot.resolve("wheels").resolve(generic),
            projectRoot.resolve("third_party").resolve("wheels"),
            projectRoot.resolve("third_party").resolve("wheels").resolve(osDir),
            projectRoot.resolve("third_party").resolve("wheels").resolve(generic)
        ).distinct()
    }

    private fun wheelArtifacts(root: Path): List<Path> {
        if (!root.exists()) return emptyList()
        return runCatching {
            Files.walk(root).use { stream ->
                stream.filter {
                    Files.isRegularFile(it) && (
                        it.fileName.toString().endsWith(".whl", ignoreCase = true) ||
                            it.fileName.toString().equals("pyvenv.cfg", ignoreCase = true)
                        )
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun thirdPartyAssetList(): List<String> {
        val manifestPath = "third_party/offline-assets.txt"
        val projectManifest = projectRoot.resolve(manifestPath)
        val manifestText = when {
            projectManifest.exists() -> Files.readString(projectManifest, Charsets.UTF_8)
            else -> AppRuntimeFiles::class.java.classLoader.getResourceAsStream(manifestPath)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
        }
        return manifestText
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()
            .toList()
    }

    private fun pythonAssetPolicy(relative: String): PythonAssetPolicy {
        return when {
            relative.endsWith(".py") || relative == "README.md" -> PythonAssetPolicy.Overwrite
            relative.startsWith("model/") -> PythonAssetPolicy.SeedModel
            else -> PythonAssetPolicy.CopyIfMissing
        }
    }

    private fun nativeLibraryName(): String {
        val os = System.getProperty("os.name")
        return when {
            os.startsWith("Mac", ignoreCase = true) -> "libcpp_detector.dylib"
            os.startsWith("Windows", ignoreCase = true) -> "cpp_detector.dll"
            else -> "libcpp_detector.so"
        }
    }

    private fun platformDirectoryName(): String {
        val os = System.getProperty("os.name")
        return when {
            os.startsWith("Mac", ignoreCase = true) -> "macos"
            os.startsWith("Windows", ignoreCase = true) -> "windows"
            else -> "linux"
        }
    }

    private fun platformDirectoryAlias(): String {
        val os = System.getProperty("os.name")
        return when {
            os.startsWith("Mac", ignoreCase = true) -> "mac"
            os.startsWith("Windows", ignoreCase = true) -> "win"
            else -> "linux"
        }
    }

    private val pythonAssets = listOf(
        "offline_common.py",
        "detect_icons.py",
        "make_training_set.py",
        "bootstrap_seed_models.py",
        "train_icon_detector.py",
        "train_magic_model.py",
        "train_background_model.py",
        "README.md",
        "model/combined/model.json",
        "model/background/model.json",
        "model/magic/model.json",
        "training_sets/test_actions/annotations.jsonl",
        "training_sets/combined/annotations.jsonl",
        "training_sets/seed_pretrain/annotations.jsonl",
        "training_sets/magic_seed/annotations.jsonl",
        "training_sets/background_seed/annotations.jsonl",
        "seed_images/test.png",
        "seed_images/icons.png",
        "seed_images/icons2.png"
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

    fun summary(): String = "离线依赖自检：$okCount/$totalCount 通过"
}

private enum class PythonAssetPolicy {
    Overwrite,
    CopyIfMissing,
    SeedModel
}

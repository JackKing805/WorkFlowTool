package io.github.workflowtool.application

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
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

    val nativeLibraryFile: File? by lazy {
        installNativeLibrary()
    }

    private fun installPythonAssets(target: Path) {
        Files.createDirectories(target)
        pythonAssets.forEach { relative ->
            copyResource("python_detector/$relative", target.resolve(relative), overwrite = shouldOverwritePythonAsset(relative))
        }
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
        return copyResource("native/$libraryName", target, overwrite = false)
    }

    private fun copyResource(resourcePath: String, target: Path, overwrite: Boolean): File? {
        val stream = AppRuntimeFiles::class.java.classLoader.getResourceAsStream(resourcePath) ?: return null
        Files.createDirectories(target.parent)
        if (overwrite || !target.exists()) {
            stream.use {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } else {
            stream.close()
        }
        return target.toFile()
    }

    private fun shouldOverwritePythonAsset(relative: String): Boolean {
        return relative.endsWith(".py") || relative == "README.md"
    }

    private fun nativeLibraryName(): String {
        val os = System.getProperty("os.name")
        return when {
            os.startsWith("Mac", ignoreCase = true) -> "libcpp_detector.dylib"
            os.startsWith("Windows", ignoreCase = true) -> "cpp_detector.dll"
            else -> "libcpp_detector.so"
        }
    }

    private val pythonAssets = listOf(
        "detect_icons.py",
        "make_training_set.py",
        "train_icon_detector.py",
        "train_magic_model.py",
        "train_background_model.py",
        "README.md",
        "model/combined/runs/weights/best.pt",
        "training_sets/test_actions/annotations.jsonl",
        "training_sets/combined/annotations.jsonl",
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

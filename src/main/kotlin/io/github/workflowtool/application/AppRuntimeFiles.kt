package io.github.workflowtool.application

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists

internal object AppRuntimeFiles {
    private val appDataRoot: Path by lazy {
        val base = System.getenv("APPDATA")
            ?: System.getProperty("user.home")?.let { Path(it, ".workflowtool") }?.toString()
            ?: ".workflowtool"
        Path(base, "WorkFlowTool")
    }

    val pythonDir: Path by lazy {
        val target = appDataRoot.resolve("python_detector")
        installPythonAssets(target)
        target
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

    private fun installNativeLibrary(): File? {
        val libraryName = nativeLibraryName()
        val target = appDataRoot.resolve("native").resolve(libraryName)
        val copied = copyResource("native/$libraryName", target, overwrite = true)
        return copied ?: devNativeLibrary(libraryName).takeIf { it.exists() }
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

    private fun devNativeLibrary(libraryName: String): File =
        File("native/cpp_detector/build/release/$libraryName")

    private val pythonAssets = listOf(
        "detect_icons.py",
        "make_training_set.py",
        "train_icon_detector.py",
        "README.md",
        "model/combined/runs/weights/best.pt",
        "training_sets/test_actions/annotations.jsonl",
        "training_sets/combined/annotations.jsonl",
        "seed_images/test.png",
        "seed_images/icons.png",
        "seed_images/icons2.png"
    )
}

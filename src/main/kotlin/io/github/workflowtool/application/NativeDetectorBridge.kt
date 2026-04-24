package io.github.workflowtool.application

import com.sun.jna.Native
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.GridConfig
import java.awt.image.BufferedImage
import java.io.File

interface NativeDetectorBridge {
    val isLoaded: Boolean
    val status: String
    fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult?
    fun splitGrid(image: BufferedImage, config: GridConfig): List<CropRegion>?
}

internal interface NativeMagicBridge {
    val isLoaded: Boolean
    val status: String
    fun detectMagicRegion(image: BufferedImage, seedX: Int, seedY: Int, config: DetectionConfig): MagicSelectionResult?
}

internal object CppDetectorBridge : NativeDetectorBridge, NativeMagicBridge {
    private val libraryName = when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "libcpp_detector.dylib"
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "cpp_detector.dll"
        else -> "libcpp_detector.so"
    }

    private val libraryFile = File("native/cpp_detector/build/release/$libraryName")

    private val nativeLibrary: CppDetectorLibrary? by lazy {
        runCatching {
            if (!libraryFile.exists()) return@runCatching null
            Native.load(libraryFile.absolutePath, CppDetectorLibrary::class.java)
        }.getOrNull()
    }

    private val backendName: String? by lazy {
        runCatching { nativeLibrary?.detector_backend_name() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    override val isLoaded: Boolean by lazy {
        nativeLibrary?.detect_icons_stub() == 0
    }

    override val status: String by lazy {
        when {
            libraryFile.exists() && isLoaded -> "loaded: ${libraryFile.name}${backendName?.let { " [$it]" } ?: ""}"
            libraryFile.exists() -> "present but failed to initialize"
            else -> "native library missing (${libraryFile.path})"
        }
    }

    override fun detect(image: BufferedImage, config: DetectionConfig): DetectionResult? {
        val library = nativeLibrary ?: return null
        if (!isLoaded) return null

        val (nativeImage, nativePixels) = image.toNativeImageBuffer()
        val nativeConfig = NativeDetectionConfig(config).apply { write() }
        val nativeResult = NativeDetectionResult().apply { write() }
        nativePixels.size()

        val exitCode = runCatching {
            library.detect_icons(nativeImage, nativeConfig, nativeResult)
        }.getOrElse { return null }
        if (exitCode != 0) return null

        return runCatching {
            nativeResult.toDomainResult()
        }.also {
            library.free_detection_result(nativeResult)
        }.getOrNull()
    }

    override fun splitGrid(image: BufferedImage, config: GridConfig): List<CropRegion>? {
        val library = nativeLibrary ?: return null
        if (!isLoaded) return null

        val (nativeImage, nativePixels) = image.toNativeImageBuffer()
        val nativeConfig = NativeGridConfig(config).apply { write() }
        val nativeResult = NativeDetectionResult().apply { write() }
        nativePixels.size()

        val exitCode = runCatching {
            library.split_grid(nativeImage, nativeConfig, nativeResult)
        }.getOrElse { return null }
        if (exitCode != 0) return null

        return runCatching {
            nativeResult.toDomainResult().regions
        }.also {
            library.free_detection_result(nativeResult)
        }.getOrNull()
    }

    override fun detectMagicRegion(image: BufferedImage, seedX: Int, seedY: Int, config: DetectionConfig): MagicSelectionResult? {
        val library = nativeLibrary ?: return null
        if (!isLoaded) return null

        val (nativeImage, nativePixels) = image.toNativeImageBuffer()
        val nativeConfig = NativeDetectionConfig(config).apply { write() }
        val nativeResult = NativeMagicResult().apply { write() }
        nativePixels.size()

        val exitCode = runCatching {
            library.detect_magic_region(nativeImage, seedX, seedY, nativeConfig, nativeResult)
        }.getOrElse { return null }
        if (exitCode != 0) return null

        return runCatching {
            nativeResult.toMagicSelectionResult()
        }.also {
            library.free_magic_result(nativeResult)
        }.getOrNull()
    }
}

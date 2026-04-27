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
    fun mergeMagicMasks(current: MagicSelectionPreview, added: MagicSelectionResult, bboxPadding: Int): MergedMagicMask?
    fun magicMaskContains(mask: BooleanArray, imageWidth: Int, imageHeight: Int, x: Int, y: Int): Boolean
}

internal object CppDetectorBridge : NativeDetectorBridge, NativeMagicBridge {
    private val libraryName = when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "libcpp_detector.dylib"
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "cpp_detector.dll"
        else -> "libcpp_detector.so"
    }

    private val libraryFile: File by lazy {
        listOfNotNull(
            AppRuntimeFiles.nativeLibraryFile,
            File("native/cpp_detector/build/release/$libraryName"),
            File("native/cpp_detector/build/release/Release/$libraryName"),
            File("native/cpp_detector/build/Release/$libraryName")
        ).firstOrNull { it.exists() } ?: File("native/cpp_detector/build/release/$libraryName")
    }

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

    override fun mergeMagicMasks(current: MagicSelectionPreview, added: MagicSelectionResult, bboxPadding: Int): MergedMagicMask? {
        val library = nativeLibrary ?: return null
        if (!isLoaded) return null

        val currentMask = current.mask.toNativeMask()
        val addedMask = added.mask.toNativeMask()
        val nativeResult = NativeMagicResult().apply { write() }
        currentMask.size()
        addedMask.size()

        val exitCode = runCatching {
            library.merge_magic_masks(
                currentMask,
                current.mask.size,
                addedMask,
                added.mask.size,
                current.imageWidth,
                current.imageHeight,
                bboxPadding,
                nativeResult
            )
        }.getOrElse { return null }
        if (exitCode != 0) return null

        return runCatching {
            nativeResult.toMagicSelectionResult()?.let {
                MergedMagicMask(mask = it.mask, region = it.region, pixelCount = it.pixelCount)
            }
        }.also {
            library.free_magic_result(nativeResult)
        }.getOrNull()
    }

    override fun magicMaskContains(mask: BooleanArray, imageWidth: Int, imageHeight: Int, x: Int, y: Int): Boolean {
        val library = nativeLibrary ?: return false
        if (!isLoaded) return false
        val nativeMask = mask.toNativeMask()
        nativeMask.size()
        return runCatching {
            library.magic_mask_contains(nativeMask, mask.size, imageWidth, imageHeight, x, y) == 1
        }.getOrDefault(false)
    }
}

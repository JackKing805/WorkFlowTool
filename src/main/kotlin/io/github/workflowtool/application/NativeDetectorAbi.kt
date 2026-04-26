package io.github.workflowtool.application

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.DetectionMode
import io.github.workflowtool.model.DetectionResult
import io.github.workflowtool.model.DetectionStats
import io.github.workflowtool.model.GridConfig
import io.github.workflowtool.model.RegionPoint
import java.awt.image.BufferedImage

internal interface CppDetectorLibrary : Library {
    fun detect_icons_stub(): Int
    fun detector_backend_name(): String?
    fun detect_icons(
        image: NativeImageBuffer,
        config: NativeDetectionConfig,
        result: NativeDetectionResult
    ): Int
    fun split_grid(
        image: NativeImageBuffer,
        config: NativeGridConfig,
        result: NativeDetectionResult
    ): Int
    fun detect_magic_region(
        image: NativeImageBuffer,
        seedX: Int,
        seedY: Int,
        config: NativeDetectionConfig,
        result: NativeMagicResult
    ): Int
    fun merge_magic_masks(
        currentMask: Pointer,
        currentLength: Int,
        addedMask: Pointer,
        addedLength: Int,
        width: Int,
        height: Int,
        bboxPadding: Int,
        result: NativeMagicResult
    ): Int
    fun magic_mask_contains(
        mask: Pointer,
        maskLength: Int,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): Int

    fun free_detection_result(result: NativeDetectionResult)
    fun free_magic_result(result: NativeMagicResult)
}

internal fun BufferedImage.toNativeImageBuffer(): Pair<NativeImageBuffer, Memory> {
    val pixels = IntArray(width * height)
    getRGB(0, 0, width, height, pixels, 0, width)
    val memory = Memory(pixels.size.toLong() * Int.SIZE_BYTES)
    memory.write(0, pixels, 0, pixels.size)
    val buffer = NativeImageBuffer(width, height, memory)
    buffer.write()
    return buffer to memory
}

internal fun BooleanArray.toNativeMask(): Memory {
    val memory = Memory(size.toLong())
    val bytes = ByteArray(size) { index -> if (this[index]) 1 else 0 }
    memory.write(0, bytes, 0, bytes.size)
    return memory
}


package io.github.workflowtool.application

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.Structure
import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.GridConfig
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
    fun free_detection_result(result: NativeDetectionResult)
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

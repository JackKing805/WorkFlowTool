#include "detector_backend.h"

extern "C" {

WF_EXPORT int detect_icons_stub() {
    return 0;
}

WF_EXPORT const char* detector_backend_name() {
    return workflowtool::detector_backend_name_builtin();
}

WF_EXPORT int detect_icons(
    NativeImageBuffer* image,
    NativeDetectionConfig* config,
    NativeDetectionResult* result
) {
    return workflowtool::detect_icons_builtin(image, config, result);
}

WF_EXPORT int split_grid(
    NativeImageBuffer* image,
    NativeGridConfig* config,
    NativeDetectionResult* result
) {
    return workflowtool::split_grid_builtin(image, config, result);
}

WF_EXPORT int detect_magic_region(
    NativeImageBuffer* image,
    int seedX,
    int seedY,
    NativeDetectionConfig* config,
    NativeMagicResult* result
) {
    return workflowtool::detect_magic_region_builtin(image, seedX, seedY, config, result);
}

WF_EXPORT int merge_magic_masks(
    const std::uint8_t* currentMask,
    int currentLength,
    const std::uint8_t* addedMask,
    int addedLength,
    int width,
    int height,
    int bboxPadding,
    NativeMagicResult* result
) {
    return workflowtool::merge_magic_masks_builtin(
        currentMask,
        currentLength,
        addedMask,
        addedLength,
        width,
        height,
        bboxPadding,
        result
    );
}

WF_EXPORT int magic_mask_contains(
    const std::uint8_t* mask,
    int maskLength,
    int width,
    int height,
    int x,
    int y
) {
    return workflowtool::magic_mask_contains_builtin(mask, maskLength, width, height, x, y);
}

WF_EXPORT void free_detection_result(NativeDetectionResult* result) {
    workflowtool::free_detection_result_builtin(result);
}

WF_EXPORT void free_magic_result(NativeMagicResult* result) {
    workflowtool::free_magic_result_builtin(result);
}

}  // extern "C"

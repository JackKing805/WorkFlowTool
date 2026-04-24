#include "detector_backend.h"

API int32_t detect_icons_stub() {
    return 0;
}

API const char* detector_backend_name() {
#ifdef CPP_DETECTOR_USE_OPENCV
    return "opencv";
#else
    return "builtin";
#endif
}

API int32_t detect_icons(const NativeImageBuffer* image, const NativeDetectionConfig* config, NativeDetectionResult* result) {
#ifdef CPP_DETECTOR_USE_OPENCV
    return opencv_detect_icons(image, config, result);
#else
    return builtin_detect_icons(image, config, result);
#endif
}

API int32_t split_grid(const NativeImageBuffer* image, const NativeGridConfig* config, NativeDetectionResult* result) {
#ifdef CPP_DETECTOR_USE_OPENCV
    return opencv_split_grid(image, config, result);
#else
    return builtin_split_grid(image, config, result);
#endif
}

API int32_t detect_magic_region(const NativeImageBuffer* image, int32_t seed_x, int32_t seed_y, const NativeDetectionConfig* config, NativeMagicResult* result) {
#ifdef CPP_DETECTOR_USE_OPENCV
    return opencv_detect_magic_region(image, seed_x, seed_y, config, result);
#else
    return builtin_detect_magic_region(image, seed_x, seed_y, config, result);
#endif
}

API void free_detection_result(NativeDetectionResult* result) {
    free_detection_result_impl(result);
}

API void free_magic_result(NativeMagicResult* result) {
    free_magic_result_impl(result);
}

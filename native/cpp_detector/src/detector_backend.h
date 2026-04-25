#pragma once

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <queue>
#include <unordered_map>
#include <vector>

#ifdef _WIN32
#define API extern "C" __declspec(dllexport)
#else
#define API extern "C" __attribute__((visibility("default")))
#endif

struct NativeImageBuffer {
    int32_t width;
    int32_t height;
    const int32_t* pixels;
};

struct NativeDetectionConfig {
    int32_t min_width;
    int32_t min_height;
    int32_t gap_threshold;
    int32_t alpha_threshold;
    int32_t background_tolerance;
    int32_t edge_sample_width;
    int32_t min_pixel_area;
    int32_t color_distance_threshold;
    int32_t dilate_iterations;
    int32_t erode_iterations;
    uint8_t enable_hole_fill;
    int32_t bbox_padding;
    uint8_t merge_nearby_regions;
    uint8_t remove_small_regions;
    uint8_t use_manual_background;
    int32_t manual_background_argb;
};

struct NativeGridConfig {
    int32_t cell_width;
    int32_t cell_height;
    int32_t columns;
    int32_t rows;
    int32_t offset_x;
    int32_t offset_y;
    int32_t gap_x;
    int32_t gap_y;
    uint8_t snap_to_content;
    int32_t search_padding;
    uint8_t ignore_empty_cells;
    uint8_t trim_cell_to_content;
    int32_t alpha_threshold;
    int32_t background_tolerance;
};

struct NativeRegion {
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
    uint8_t visible;
    uint8_t selected;
};

struct NativeDetectionStats {
    int32_t estimated_background_argb;
    int32_t candidate_pixels;
    int32_t connected_components;
    int32_t region_count;
    int32_t background_sample_count;
    long long total_time_ms;
};

struct NativeDetectionResult {
    int32_t mode;
    int32_t region_count;
    NativeRegion* regions;
    NativeDetectionStats stats;
};

struct NativeMagicResult {
    NativeRegion region;
    uint8_t* mask;
    int32_t mask_length;
    int32_t image_width;
    int32_t image_height;
    int32_t seed_x;
    int32_t seed_y;
    int32_t pixel_count;
    uint8_t found;
};

struct RegionWork {
    int x;
    int y;
    int width;
    int height;
    int pixels;
};

constexpr int MODE_ALPHA_MASK = 0;
constexpr int MODE_SOLID_BACKGROUND = 1;
constexpr int MODE_FALLBACK_BACKGROUND = 2;
constexpr int ERR_INVALID_ARGUMENT = 1;
constexpr int ERR_PANIC = 2;

inline int alpha(int32_t argb) { return (static_cast<uint32_t>(argb) >> 24) & 0xff; }
inline int red(int32_t argb) { return (static_cast<uint32_t>(argb) >> 16) & 0xff; }
inline int green(int32_t argb) { return (static_cast<uint32_t>(argb) >> 8) & 0xff; }
inline int blue(int32_t argb) { return static_cast<uint32_t>(argb) & 0xff; }
inline int idx(int x, int y, int width) { return y * width + x; }

float weighted_distance(int32_t a, int32_t b);
float magic_distance(int32_t a, int32_t b);
bool matches_background(int32_t argb, int32_t background, const NativeDetectionConfig& config);
int32_t estimate_edge_background(const NativeImageBuffer& image, const NativeDetectionConfig& config, int* sample_count);
std::vector<RegionWork> trace_regions(const std::vector<uint8_t>& mask, int width, int height, const NativeDetectionConfig& config, int* connected);
bool should_merge(const RegionWork& a, const RegionWork& b, int gap);
std::vector<RegionWork> merge_regions(std::vector<RegionWork> regions, int gap);
void write_regions(NativeDetectionResult* result, const std::vector<RegionWork>& regions, int mode, int32_t background, int candidate_pixels, int connected, int background_samples, long long elapsed_ms);
long long elapsed_ms(std::chrono::steady_clock::time_point start);

int32_t builtin_detect_icons(const NativeImageBuffer* image, const NativeDetectionConfig* config, NativeDetectionResult* result);
int32_t builtin_split_grid(const NativeImageBuffer* image, const NativeGridConfig* config, NativeDetectionResult* result);
int32_t builtin_detect_magic_region(const NativeImageBuffer* image, int32_t seed_x, int32_t seed_y, const NativeDetectionConfig* config, NativeMagicResult* result);
int32_t merge_magic_masks_impl(const uint8_t* current_mask, int32_t current_length, const uint8_t* added_mask, int32_t added_length, int32_t width, int32_t height, int32_t bbox_padding, NativeMagicResult* result);
int32_t magic_mask_contains_impl(const uint8_t* mask, int32_t mask_length, int32_t width, int32_t height, int32_t x, int32_t y);

#ifdef CPP_DETECTOR_USE_OPENCV
int32_t opencv_detect_icons(const NativeImageBuffer* image, const NativeDetectionConfig* config, NativeDetectionResult* result);
int32_t opencv_split_grid(const NativeImageBuffer* image, const NativeGridConfig* config, NativeDetectionResult* result);
int32_t opencv_detect_magic_region(const NativeImageBuffer* image, int32_t seed_x, int32_t seed_y, const NativeDetectionConfig* config, NativeMagicResult* result);
#endif

inline void free_detection_result_impl(NativeDetectionResult* result) {
    if (!result) return;
    std::free(result->regions);
    result->regions = nullptr;
    result->region_count = 0;
}

inline void free_magic_result_impl(NativeMagicResult* result) {
    if (!result) return;
    std::free(result->mask);
    result->mask = nullptr;
    result->mask_length = 0;
    result->found = 0;
}

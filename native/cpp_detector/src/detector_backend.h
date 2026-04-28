#pragma once

#include <cstdint>

#if defined(_WIN32)
#define WF_EXPORT __declspec(dllexport)
#else
#define WF_EXPORT __attribute__((visibility("default")))
#endif

extern "C" {

struct NativeImageBuffer {
    int width;
    int height;
    const std::int32_t* pixels;
};

struct NativeDetectionConfig {
    int minWidth;
    int minHeight;
    int gapThreshold;
    int alphaThreshold;
    int backgroundTolerance;
    int edgeSampleWidth;
    int minPixelArea;
    int colorDistanceThreshold;
    int dilateIterations;
    int erodeIterations;
    std::uint8_t enableHoleFill;
    int bboxPadding;
    std::uint8_t mergeNearbyRegions;
    std::uint8_t removeSmallRegions;
    std::uint8_t useManualBackground;
    std::int32_t manualBackgroundArgb;
};

struct NativeGridConfig {
    int cellWidth;
    int cellHeight;
    int columns;
    int rows;
    int offsetX;
    int offsetY;
    int gapX;
    int gapY;
    std::uint8_t snapToContent;
    int searchPadding;
    std::uint8_t ignoreEmptyCells;
    std::uint8_t trimCellToContent;
    int alphaThreshold;
    int backgroundTolerance;
};

struct NativePoint {
    int x;
    int y;
};

struct NativeContour {
    int pointCount;
    NativePoint* points;
};

struct NativeRegion {
    int x;
    int y;
    int width;
    int height;
    std::uint8_t visible;
    std::uint8_t selected;
    int pointCount;
    NativePoint* points;
    int holeCount;
    NativeContour* holes;
    float score;
};

struct NativeDetectionStats {
    std::int32_t estimatedBackgroundArgb;
    int candidatePixels;
    int connectedComponents;
    int regionCount;
    int backgroundSampleCount;
    long totalTimeMs;
};

struct NativeDetectionResult {
    int mode;
    int regionCount;
    NativeRegion* regions;
    NativeDetectionStats stats;
};

struct NativeMagicResult {
    NativeRegion region;
    std::uint8_t* mask;
    int maskLength;
    int imageWidth;
    int imageHeight;
    int seedX;
    int seedY;
    int pixelCount;
    std::uint8_t found;
};

}

namespace workflowtool {

const char* detector_backend_name_builtin();
int detect_icons_builtin(const NativeImageBuffer* image, const NativeDetectionConfig* config, NativeDetectionResult* result);
int split_grid_builtin(const NativeImageBuffer* image, const NativeGridConfig* config, NativeDetectionResult* result);
int detect_magic_region_builtin(
    const NativeImageBuffer* image,
    int seedX,
    int seedY,
    const NativeDetectionConfig* config,
    NativeMagicResult* result
);
int merge_magic_masks_builtin(
    const std::uint8_t* currentMask,
    int currentLength,
    const std::uint8_t* addedMask,
    int addedLength,
    int width,
    int height,
    int bboxPadding,
    NativeMagicResult* result
);
int magic_mask_contains_builtin(const std::uint8_t* mask, int maskLength, int width, int height, int x, int y);
void free_detection_result_builtin(NativeDetectionResult* result);
void free_magic_result_builtin(NativeMagicResult* result);

}  // namespace workflowtool

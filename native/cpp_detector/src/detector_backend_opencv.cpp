#include "detector_backend.h"

#ifdef CPP_DETECTOR_USE_OPENCV

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <cmath>

static cv::Mat build_bgra_image(const NativeImageBuffer& image) {
    cv::Mat bgra(image.height, image.width, CV_8UC4);
    for (int y = 0; y < image.height; ++y) {
        auto* row = bgra.ptr<cv::Vec4b>(y);
        for (int x = 0; x < image.width; ++x) {
            const int32_t argb = image.pixels[idx(x, y, image.width)];
            row[x] = cv::Vec4b(
                static_cast<uint8_t>(blue(argb)),
                static_cast<uint8_t>(green(argb)),
                static_cast<uint8_t>(red(argb)),
                static_cast<uint8_t>(alpha(argb))
            );
        }
    }
    return bgra;
}

static cv::Mat build_bgr_image(const NativeImageBuffer& image) {
    cv::Mat bgr(image.height, image.width, CV_8UC3);
    for (int y = 0; y < image.height; ++y) {
        auto* row = bgr.ptr<cv::Vec3b>(y);
        for (int x = 0; x < image.width; ++x) {
            const int32_t argb = image.pixels[idx(x, y, image.width)];
            row[x] = cv::Vec3b(
                static_cast<uint8_t>(blue(argb)),
                static_cast<uint8_t>(green(argb)),
                static_cast<uint8_t>(red(argb))
            );
        }
    }
    return bgr;
}

static cv::Mat build_alpha_mask(const NativeImageBuffer& image, int alpha_threshold, int& candidates) {
    cv::Mat mask(image.height, image.width, CV_8UC1, cv::Scalar(0));
    candidates = 0;
    for (int y = 0; y < image.height; ++y) {
        auto* row = mask.ptr<uint8_t>(y);
        for (int x = 0; x < image.width; ++x) {
            const uint8_t value = alpha(image.pixels[idx(x, y, image.width)]) > alpha_threshold ? 255 : 0;
            row[x] = value;
            candidates += value != 0 ? 1 : 0;
        }
    }
    return mask;
}

static cv::Mat build_background_mask(const NativeImageBuffer& image, const NativeDetectionConfig& config, int32_t background, int& candidates) {
    cv::Mat mask(image.height, image.width, CV_8UC1, cv::Scalar(0));
    candidates = 0;
    for (int y = 0; y < image.height; ++y) {
        auto* row = mask.ptr<uint8_t>(y);
        for (int x = 0; x < image.width; ++x) {
            const uint8_t value = !matches_background(image.pixels[idx(x, y, image.width)], background, config) ? 255 : 0;
            row[x] = value;
            candidates += value != 0 ? 1 : 0;
        }
    }
    return mask;
}

static cv::Mat refine_mask(const cv::Mat& input, const NativeDetectionConfig& config) {
    cv::Mat mask = input.clone();
    const int openingRadius = std::max(0, config.erode_iterations);
    const int closingRadius = std::max(0, config.dilate_iterations + (config.enable_hole_fill ? 1 : 0));

    if (openingRadius > 0) {
        const int kernelSize = openingRadius * 2 + 1;
        cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(kernelSize, kernelSize));
        cv::morphologyEx(mask, mask, cv::MORPH_OPEN, kernel);
    }
    if (closingRadius > 0) {
        const int kernelSize = closingRadius * 2 + 1;
        cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(kernelSize, kernelSize));
        cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, kernel);
    }
    return mask;
}

static cv::Rect clamp_rect(const cv::Rect& rect, int width, int height) {
    const int left = std::max(0, rect.x);
    const int top = std::max(0, rect.y);
    const int right = std::min(width, rect.x + rect.width);
    const int bottom = std::min(height, rect.y + rect.height);
    return cv::Rect(left, top, std::max(0, right - left), std::max(0, bottom - top));
}

static std::vector<RegionWork> regions_from_components(const cv::Mat& mask, const NativeDetectionConfig& config, int* connected) {
    cv::Mat labels;
    cv::Mat stats;
    cv::Mat centroids;
    const int components = cv::connectedComponentsWithStats(mask, labels, stats, centroids, 8, CV_32S);
    std::vector<RegionWork> regions;
    for (int i = 1; i < components; ++i) {
        const int left = stats.at<int>(i, cv::CC_STAT_LEFT);
        const int top = stats.at<int>(i, cv::CC_STAT_TOP);
        const int width = stats.at<int>(i, cv::CC_STAT_WIDTH);
        const int height = stats.at<int>(i, cv::CC_STAT_HEIGHT);
        const int area = stats.at<int>(i, cv::CC_STAT_AREA);
        if (config.remove_small_regions) {
            if (width < config.min_width || height < config.min_height || area < config.min_pixel_area) continue;
        }
        const int pad = std::max(0, config.bbox_padding);
        cv::Rect padded = clamp_rect(
            cv::Rect(left - pad, top - pad, width + pad * 2, height + pad * 2),
            mask.cols,
            mask.rows
        );
        if (padded.width <= 0 || padded.height <= 0) continue;
        regions.push_back({padded.x, padded.y, padded.width, padded.height, area});
    }
    if (connected) *connected = std::max(0, components - 1);
    return regions;
}

static cv::Rect extract_content_bounds(
    const NativeImageBuffer& image,
    const NativeGridConfig& config,
    int left,
    int top,
    int right,
    int bottom
) {
    const int width = image.width;
    cv::Mat roiMask(bottom - top, right - left, CV_8UC1, cv::Scalar(0));
    const int32_t background = image.pixels[idx(left, top, width)];

    for (int y = top; y < bottom; ++y) {
        auto* row = roiMask.ptr<uint8_t>(y - top);
        for (int x = left; x < right; ++x) {
            const int32_t argb = image.pixels[idx(x, y, width)];
            if (alpha(argb) <= config.alpha_threshold) continue;
            const int delta = std::max({
                std::abs(red(argb) - red(background)),
                std::abs(green(argb) - green(background)),
                std::abs(blue(argb) - blue(background))
            });
            if (delta <= std::max(1, config.background_tolerance)) continue;
            row[x - left] = 255;
        }
    }

    if (config.trim_cell_to_content || config.snap_to_content) {
        cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));
        cv::morphologyEx(roiMask, roiMask, cv::MORPH_CLOSE, kernel);
    }

    std::vector<cv::Point> points;
    cv::findNonZero(roiMask, points);
    if (points.empty()) return cv::Rect();
    cv::Rect bounds = cv::boundingRect(points);
    bounds.x += left;
    bounds.y += top;
    return bounds;
}

int32_t opencv_detect_icons(const NativeImageBuffer* image, const NativeDetectionConfig* config, NativeDetectionResult* result) {
    try {
        if (!image || !config || !result || !image->pixels || image->width <= 0 || image->height <= 0) return ERR_INVALID_ARGUMENT;
        const auto start = std::chrono::steady_clock::now();
        int alpha_candidates = 0;
        cv::Mat alpha_mask = refine_mask(build_alpha_mask(*image, config->alpha_threshold, alpha_candidates), *config);

        int connected = 0;
        auto regions = regions_from_components(alpha_mask, *config, &connected);
        if (alpha_candidates > 0 && alpha_candidates < static_cast<int>(image->width * image->height * 0.92f) && !regions.empty()) {
            if (config->merge_nearby_regions) regions = merge_regions(std::move(regions), std::max(0, config->gap_threshold));
            write_regions(result, regions, MODE_ALPHA_MASK, 0, alpha_candidates, connected, 0, elapsed_ms(start));
            return 0;
        }

        int background_samples = 0;
        const int32_t background = config->use_manual_background
            ? config->manual_background_argb
            : estimate_edge_background(*image, *config, &background_samples);
        int color_candidates = 0;
        cv::Mat color_mask = refine_mask(build_background_mask(*image, *config, background, color_candidates), *config);
        regions = regions_from_components(color_mask, *config, &connected);
        if (config->merge_nearby_regions) regions = merge_regions(std::move(regions), std::max(0, config->gap_threshold));
        write_regions(result, regions, MODE_SOLID_BACKGROUND, background, color_candidates, connected, background_samples, elapsed_ms(start));
        return 0;
    } catch (...) {
        return ERR_PANIC;
    }
}

int32_t opencv_split_grid(const NativeImageBuffer* image, const NativeGridConfig* config, NativeDetectionResult* result) {
    try {
        if (!image || !config || !result || !image->pixels || image->width <= 0 || image->height <= 0) return ERR_INVALID_ARGUMENT;
        const auto start = std::chrono::steady_clock::now();
        std::vector<RegionWork> regions;
        const int columns = std::max(1, config->columns);
        const int rows = std::max(1, config->rows);
        const int cell_width = std::max(1, config->cell_width);
        const int cell_height = std::max(1, config->cell_height);
        const int gap_x = std::max(0, config->gap_x);
        const int gap_y = std::max(0, config->gap_y);

        for (int row = 0; row < rows; ++row) {
            for (int column = 0; column < columns; ++column) {
                const int cell_x = config->offset_x + column * (cell_width + gap_x);
                const int cell_y = config->offset_y + row * (cell_height + gap_y);
                const int left = std::max(0, cell_x - config->search_padding);
                const int top = std::max(0, cell_y - config->search_padding);
                const int right = std::min(image->width, cell_x + cell_width + config->search_padding);
                const int bottom = std::min(image->height, cell_y + cell_height + config->search_padding);
                if (left >= right || top >= bottom) continue;

                cv::Rect bounds = extract_content_bounds(*image, *config, left, top, right, bottom);
                if (bounds.width <= 0 || bounds.height <= 0) {
                    if (!config->ignore_empty_cells) {
                        regions.push_back({
                            std::max(0, cell_x),
                            std::max(0, cell_y),
                            std::max(1, std::min(cell_width, image->width - std::max(0, cell_x))),
                            std::max(1, std::min(cell_height, image->height - std::max(0, cell_y))),
                            0
                        });
                    }
                    continue;
                }

                cv::Rect clamped = clamp_rect(bounds, image->width, image->height);
                regions.push_back({clamped.x, clamped.y, clamped.width, clamped.height, clamped.width * clamped.height});
            }
        }

        write_regions(result, regions, MODE_FALLBACK_BACKGROUND, 0, 0, static_cast<int>(regions.size()), 0, elapsed_ms(start));
        return 0;
    } catch (...) {
        return ERR_PANIC;
    }
}

int32_t opencv_detect_magic_region(const NativeImageBuffer* image, int32_t seed_x, int32_t seed_y, const NativeDetectionConfig* config, NativeMagicResult* result) {
    try {
        if (!image || !config || !result || !image->pixels || image->width <= 0 || image->height <= 0) return ERR_INVALID_ARGUMENT;
        std::memset(result, 0, sizeof(NativeMagicResult));
        if (seed_x < 0 || seed_y < 0 || seed_x >= image->width || seed_y >= image->height) return 0;

        int background_samples = 0;
        const int32_t background = config->use_manual_background
            ? config->manual_background_argb
            : estimate_edge_background(*image, *config, &background_samples);
        const int32_t seed_argb = image->pixels[idx(seed_x, seed_y, image->width)];
        if (matches_background(seed_argb, background, *config)) return 0;

        cv::Mat bgr = build_bgr_image(*image);
        cv::Mat floodMask(image->height + 2, image->width + 2, CV_8UC1, cv::Scalar(0));
        const cv::Scalar seedColor = bgr.at<cv::Vec3b>(seed_y, seed_x);
        const int tol = std::max(1, config->color_distance_threshold);
        const cv::Scalar loDiff(tol, tol, tol);
        const cv::Scalar upDiff(tol, tol, tol);
        cv::Rect rect;
        const int flags = 8 | cv::FLOODFILL_MASK_ONLY | cv::FLOODFILL_FIXED_RANGE | (255 << 8);
        cv::floodFill(bgr, floodMask, cv::Point(seed_x, seed_y), seedColor, &rect, loDiff, upDiff, flags);

        cv::Mat regionMask = floodMask(cv::Rect(1, 1, image->width, image->height)).clone();
        regionMask = refine_mask(regionMask, *config);

        const int pixelCount = cv::countNonZero(regionMask);
        if (pixelCount < std::max(1, config->min_pixel_area)) return 0;

        std::vector<cv::Point> points;
        cv::findNonZero(regionMask, points);
        if (points.empty()) return 0;

        cv::Rect bounds = cv::boundingRect(points);
        bounds = clamp_rect(cv::Rect(
            bounds.x - std::max(0, config->bbox_padding),
            bounds.y - std::max(0, config->bbox_padding),
            bounds.width + std::max(0, config->bbox_padding) * 2,
            bounds.height + std::max(0, config->bbox_padding) * 2
        ), image->width, image->height);

        result->region = {bounds.x, bounds.y, bounds.width, bounds.height, 1, 1};
        result->mask_length = image->width * image->height;
        result->mask = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(result->mask_length)));
        if (!result->mask) return ERR_INVALID_ARGUMENT;

        for (int y = 0; y < image->height; ++y) {
            const auto* row = regionMask.ptr<uint8_t>(y);
            for (int x = 0; x < image->width; ++x) {
                result->mask[idx(x, y, image->width)] = row[x] > 0 ? 1 : 0;
            }
        }

        result->image_width = image->width;
        result->image_height = image->height;
        result->seed_x = seed_x;
        result->seed_y = seed_y;
        result->pixel_count = pixelCount;
        result->found = 1;
        return 0;
    } catch (...) {
        return ERR_PANIC;
    }
}

#endif

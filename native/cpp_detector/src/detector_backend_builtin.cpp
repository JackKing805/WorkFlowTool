#include "detector_backend.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <queue>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace workflowtool {
namespace {

constexpr int kModeAlphaMask = 0;
constexpr int kModeSolidBackground = 1;
constexpr int kModeFallbackBackground = 2;

struct Rgba {
    int r = 0;
    int g = 0;
    int b = 0;
    int a = 0;
};

struct RowSpan {
    int y = 0;
    int left = 0;
    int right = 0;
};

struct Component {
    int minX = std::numeric_limits<int>::max();
    int minY = std::numeric_limits<int>::max();
    int maxX = std::numeric_limits<int>::min();
    int maxY = std::numeric_limits<int>::min();
    int pixelCount = 0;
    long long sumR = 0;
    long long sumG = 0;
    long long sumB = 0;
    long long sumA = 0;
    std::vector<RowSpan> rows;
};

struct DetectionContext {
    Rgba background;
    int mode = kModeSolidBackground;
    int sampleCount = 0;
    int candidatePixels = 0;
    std::vector<std::uint8_t> mask;
};

struct RegionModel {
    int x = 0;
    int y = 0;
    int width = 0;
    int height = 0;
    float score = 0.0f;
    std::vector<NativePoint> points;
};

struct BucketStats {
    int count = 0;
    long long sumR = 0;
    long long sumG = 0;
    long long sumB = 0;
    long long sumA = 0;
};

bool is_inside(int x, int y, int width, int height) {
    return x >= 0 && y >= 0 && x < width && y < height;
}

Rgba decode_argb(std::int32_t argb) {
    const std::uint32_t bits = static_cast<std::uint32_t>(argb);
    return Rgba{
        static_cast<int>((bits >> 16U) & 0xFFU),
        static_cast<int>((bits >> 8U) & 0xFFU),
        static_cast<int>(bits & 0xFFU),
        static_cast<int>((bits >> 24U) & 0xFFU),
    };
}

std::int32_t encode_argb(const Rgba& rgba) {
    const std::uint32_t bits =
        (static_cast<std::uint32_t>(rgba.a) << 24U) |
        (static_cast<std::uint32_t>(rgba.r) << 16U) |
        (static_cast<std::uint32_t>(rgba.g) << 8U) |
        static_cast<std::uint32_t>(rgba.b);
    return static_cast<std::int32_t>(bits);
}

float weighted_color_distance(const Rgba& left, const Rgba& right) {
    return std::abs(left.r - right.r) * 0.35f +
        std::abs(left.g - right.g) * 0.50f +
        std::abs(left.b - right.b) * 0.15f +
        std::abs(left.a - right.a) * 0.45f;
}

Rgba average_rgba(const BucketStats& bucket) {
    if (bucket.count <= 0) return {};
    return Rgba{
        static_cast<int>(std::lround(static_cast<double>(bucket.sumR) / bucket.count)),
        static_cast<int>(std::lround(static_cast<double>(bucket.sumG) / bucket.count)),
        static_cast<int>(std::lround(static_cast<double>(bucket.sumB) / bucket.count)),
        static_cast<int>(std::lround(static_cast<double>(bucket.sumA) / bucket.count)),
    };
}

DetectionContext build_detection_context(const NativeImageBuffer& image, const NativeDetectionConfig& config) {
    DetectionContext context;
    if (image.width <= 0 || image.height <= 0 || image.pixels == nullptr) {
        context.mode = kModeFallbackBackground;
        return context;
    }

    if (config.useManualBackground == 1U) {
        context.background = decode_argb(config.manualBackgroundArgb);
        context.mode = kModeSolidBackground;
        context.sampleCount = 0;
    } else {
        const int edge = std::max(1, std::min({config.edgeSampleWidth, image.width, image.height}));
        int transparentSamples = 0;
        std::unordered_map<std::uint32_t, BucketStats> buckets;
        for (int y = 0; y < image.height; ++y) {
            for (int x = 0; x < image.width; ++x) {
                if (!(x < edge || y < edge || x >= image.width - edge || y >= image.height - edge)) {
                    continue;
                }
                const Rgba rgba = decode_argb(image.pixels[y * image.width + x]);
                ++context.sampleCount;
                if (rgba.a <= config.alphaThreshold) {
                    ++transparentSamples;
                }
                const std::uint32_t key =
                    (static_cast<std::uint32_t>((rgba.r / 16) * 16) << 24U) |
                    (static_cast<std::uint32_t>((rgba.g / 16) * 16) << 16U) |
                    (static_cast<std::uint32_t>((rgba.b / 16) * 16) << 8U) |
                    static_cast<std::uint32_t>((rgba.a / 32) * 32);
                BucketStats& bucket = buckets[key];
                bucket.count += 1;
                bucket.sumR += rgba.r;
                bucket.sumG += rgba.g;
                bucket.sumB += rgba.b;
                bucket.sumA += rgba.a;
            }
        }
        const float transparentRatio = context.sampleCount == 0 ? 0.0f :
            static_cast<float>(transparentSamples) / static_cast<float>(context.sampleCount);
        if (transparentRatio >= 0.60f) {
            context.mode = kModeAlphaMask;
            context.background = Rgba{0, 0, 0, 0};
        } else {
            context.mode = kModeSolidBackground;
            BucketStats bestBucket;
            for (const auto& entry : buckets) {
                if (entry.second.count > bestBucket.count) bestBucket = entry.second;
            }
            context.background = average_rgba(bestBucket);
        }
    }

    const float threshold = std::max(
        12.0f,
        static_cast<float>(config.colorDistanceThreshold) - static_cast<float>(config.backgroundTolerance) * 0.25f
    );
    context.mask.assign(static_cast<std::size_t>(image.width * image.height), 0U);
    for (int index = 0; index < image.width * image.height; ++index) {
        const Rgba rgba = decode_argb(image.pixels[index]);
        bool foreground = false;
        if (rgba.a > config.alphaThreshold) {
            if (context.mode == kModeAlphaMask && rgba.a < 250) {
                foreground = true;
            } else if (context.background.a <= config.alphaThreshold && rgba.a >= 250) {
                foreground = true;
            } else if (weighted_color_distance(rgba, context.background) > threshold) {
                foreground = true;
            }
        }
        context.mask[static_cast<std::size_t>(index)] = foreground ? 1U : 0U;
        if (foreground) ++context.candidatePixels;
    }
    return context;
}

std::vector<std::pair<int, int>> neighbors4(int x, int y) {
    return {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}};
}

std::vector<std::pair<int, int>> neighbors8(int x, int y) {
    return {
        {x - 1, y - 1}, {x, y - 1}, {x + 1, y - 1},
        {x - 1, y},                 {x + 1, y},
        {x - 1, y + 1}, {x, y + 1}, {x + 1, y + 1},
    };
}

void dilate(std::vector<std::uint8_t>& mask, int width, int height) {
    std::vector<std::uint8_t> output(mask.size(), 0U);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const int index = y * width + x;
            if (mask[static_cast<std::size_t>(index)] == 0U) continue;
            output[static_cast<std::size_t>(index)] = 1U;
            for (const auto& [nx, ny] : neighbors8(x, y)) {
                if (is_inside(nx, ny, width, height)) {
                    output[static_cast<std::size_t>(ny * width + nx)] = 1U;
                }
            }
        }
    }
    mask.swap(output);
}

void erode(std::vector<std::uint8_t>& mask, int width, int height) {
    std::vector<std::uint8_t> output(mask.size(), 0U);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const int index = y * width + x;
            if (mask[static_cast<std::size_t>(index)] == 0U) continue;
            bool keep = true;
            for (const auto& [nx, ny] : neighbors8(x, y)) {
                if (!is_inside(nx, ny, width, height) || mask[static_cast<std::size_t>(ny * width + nx)] == 0U) {
                    keep = false;
                    break;
                }
            }
            output[static_cast<std::size_t>(index)] = keep ? 1U : 0U;
        }
    }
    mask.swap(output);
}

void fill_holes(std::vector<std::uint8_t>& mask, int width, int height) {
    std::vector<std::uint8_t> visited(mask.size(), 0U);
    std::queue<int> queue;
    auto enqueue = [&](int x, int y) {
        if (!is_inside(x, y, width, height)) return;
        const int index = y * width + x;
        if (visited[static_cast<std::size_t>(index)] != 0U || mask[static_cast<std::size_t>(index)] != 0U) return;
        visited[static_cast<std::size_t>(index)] = 1U;
        queue.push(index);
    };

    for (int x = 0; x < width; ++x) {
        enqueue(x, 0);
        enqueue(x, height - 1);
    }
    for (int y = 0; y < height; ++y) {
        enqueue(0, y);
        enqueue(width - 1, y);
    }
    while (!queue.empty()) {
        const int current = queue.front();
        queue.pop();
        const int x = current % width;
        const int y = current / width;
        for (const auto& [nx, ny] : neighbors4(x, y)) {
            if (!is_inside(nx, ny, width, height)) continue;
            const int nextIndex = ny * width + nx;
            if (visited[static_cast<std::size_t>(nextIndex)] == 0U &&
                mask[static_cast<std::size_t>(nextIndex)] == 0U) {
                visited[static_cast<std::size_t>(nextIndex)] = 1U;
                queue.push(nextIndex);
            }
        }
    }
    for (std::size_t index = 0; index < mask.size(); ++index) {
        if (mask[index] == 0U && visited[index] == 0U) {
            mask[index] = 1U;
        }
    }
}

std::vector<Component> extract_components(const NativeImageBuffer& image, const std::vector<std::uint8_t>& mask, bool eightConnected) {
    std::vector<Component> components;
    std::vector<std::uint8_t> visited(mask.size(), 0U);
    std::vector<RowSpan> scratchRows;
    for (int start = 0; start < image.width * image.height; ++start) {
        if (mask[static_cast<std::size_t>(start)] == 0U || visited[static_cast<std::size_t>(start)] != 0U) continue;
        std::queue<int> queue;
        queue.push(start);
        visited[static_cast<std::size_t>(start)] = 1U;
        Component component;
        std::unordered_map<int, std::pair<int, int>> rowMap;

        while (!queue.empty()) {
            const int current = queue.front();
            queue.pop();
            const int x = current % image.width;
            const int y = current / image.width;
            const Rgba rgba = decode_argb(image.pixels[current]);
            component.minX = std::min(component.minX, x);
            component.minY = std::min(component.minY, y);
            component.maxX = std::max(component.maxX, x);
            component.maxY = std::max(component.maxY, y);
            component.pixelCount += 1;
            component.sumR += rgba.r;
            component.sumG += rgba.g;
            component.sumB += rgba.b;
            component.sumA += rgba.a;
            auto existing = rowMap.find(y);
            if (existing == rowMap.end()) {
                rowMap.emplace(y, std::make_pair(x, x));
            } else {
                auto& span = existing->second;
                if (span.first == 0 && span.second == 0 && y == 0) span = {x, x};
                span.first = std::min(span.first, x);
                span.second = std::max(span.second, x);
            }

            const auto neighbors = eightConnected ? neighbors8(x, y) : neighbors4(x, y);
            for (const auto& [nx, ny] : neighbors) {
                if (!is_inside(nx, ny, image.width, image.height)) continue;
                const int nextIndex = ny * image.width + nx;
                if (mask[static_cast<std::size_t>(nextIndex)] == 0U || visited[static_cast<std::size_t>(nextIndex)] != 0U) {
                    continue;
                }
                visited[static_cast<std::size_t>(nextIndex)] = 1U;
                queue.push(nextIndex);
            }
        }

        component.rows.reserve(rowMap.size());
        for (const auto& entry : rowMap) {
            component.rows.push_back(RowSpan{entry.first, entry.second.first, entry.second.second});
        }
        std::sort(component.rows.begin(), component.rows.end(), [](const RowSpan& left, const RowSpan& right) {
            return left.y < right.y;
        });
        components.push_back(std::move(component));
    }
    return components;
}

void merge_masks_or(std::vector<std::uint8_t>& target, const std::vector<std::uint8_t>& added) {
    const std::size_t count = std::min(target.size(), added.size());
    for (std::size_t index = 0; index < count; ++index) {
        target[index] = (target[index] != 0U || added[index] != 0U) ? 1U : 0U;
    }
}

bool should_merge_bbox(const RegionModel& left, const RegionModel& right, int gap) {
    const int leftRight = left.x + left.width;
    const int leftBottom = left.y + left.height;
    const int rightRight = right.x + right.width;
    const int rightBottom = right.y + right.height;
    const int horizontalGap = std::max(0, std::max(left.x, right.x) - std::min(leftRight, rightRight));
    const int verticalGap = std::max(0, std::max(left.y, right.y) - std::min(leftBottom, rightBottom));
    return horizontalGap <= gap && verticalGap <= gap;
}

std::vector<NativePoint> dedupe_adjacent(std::vector<NativePoint> points) {
    std::vector<NativePoint> output;
    output.reserve(points.size());
    for (const NativePoint& point : points) {
        if (!output.empty() && output.back().x == point.x && output.back().y == point.y) continue;
        output.push_back(point);
    }
    if (output.size() > 1U && output.front().x == output.back().x && output.front().y == output.back().y) {
        output.pop_back();
    }
    return output;
}

bool is_collinear(const NativePoint& previous, const NativePoint& current, const NativePoint& next, int tolerance) {
    const int leftX = current.x - previous.x;
    const int leftY = current.y - previous.y;
    const int rightX = next.x - current.x;
    const int rightY = next.y - current.y;
    return std::abs(leftX * rightY - leftY * rightX) <= tolerance;
}

std::vector<NativePoint> simplify_polygon(std::vector<NativePoint> points, int tolerance) {
    if (points.size() <= 4U || tolerance <= 0) return points;
    std::vector<NativePoint> simplified;
    simplified.reserve(points.size());
    simplified.push_back(points.front());
    for (std::size_t index = 1; index + 1 < points.size(); ++index) {
        if (is_collinear(simplified.back(), points[index], points[index + 1], tolerance)) continue;
        simplified.push_back(points[index]);
    }
    simplified.push_back(points.back());
    return dedupe_adjacent(std::move(simplified));
}

std::vector<NativePoint> rect_points(int x, int y, int width, int height) {
    return {
        NativePoint{x, y},
        NativePoint{x + width, y},
        NativePoint{x + width, y + height},
        NativePoint{x, y + height},
    };
}

std::vector<NativePoint> polygon_from_rows(const std::vector<RowSpan>& rows, int sampleTarget, int tolerance) {
    if (rows.empty()) return {};
    const int step = std::max(1, static_cast<int>(rows.size()) / std::max(12, sampleTarget));
    std::vector<RowSpan> sampled;
    for (std::size_t index = 0; index < rows.size(); index += static_cast<std::size_t>(step)) {
        sampled.push_back(rows[index]);
    }
    if (sampled.back().y != rows.back().y || sampled.back().left != rows.back().left || sampled.back().right != rows.back().right) {
        sampled.push_back(rows.back());
    }
    std::vector<NativePoint> points;
    points.reserve(sampled.size() * 2U);
    for (const RowSpan& row : sampled) {
        points.push_back(NativePoint{row.left, row.y});
    }
    for (auto it = sampled.rbegin(); it != sampled.rend(); ++it) {
        points.push_back(NativePoint{it->right + 1, it->y + 1});
    }
    points = dedupe_adjacent(std::move(points));
    if (points.size() < 4U) {
        const RowSpan& first = rows.front();
        const RowSpan& last = rows.back();
        const int minLeft = std::min(first.left, last.left);
        const int maxRight = std::max(first.right, last.right);
        return rect_points(minLeft, first.y, maxRight - minLeft + 1, last.y - first.y + 1);
    }
    return simplify_polygon(std::move(points), tolerance);
}

void recompute_bbox_from_points(RegionModel& region) {
    if (region.points.empty()) return;
    int minX = std::numeric_limits<int>::max();
    int minY = std::numeric_limits<int>::max();
    int maxX = std::numeric_limits<int>::min();
    int maxY = std::numeric_limits<int>::min();
    for (const NativePoint& point : region.points) {
        minX = std::min(minX, point.x);
        minY = std::min(minY, point.y);
        maxX = std::max(maxX, point.x);
        maxY = std::max(maxY, point.y);
    }
    region.x = minX;
    region.y = minY;
    region.width = std::max(1, maxX - minX);
    region.height = std::max(1, maxY - minY);
}

RegionModel component_to_region(const Component& component, const Rgba& background, const NativeDetectionConfig& config) {
    RegionModel region;
    const int width = component.maxX - component.minX + 1;
    const int height = component.maxY - component.minY + 1;
    const int pixelCount = component.pixelCount;
    if (width < config.minWidth || height < config.minHeight || pixelCount < config.minPixelArea) {
        return region;
    }

    const float density = static_cast<float>(pixelCount) / static_cast<float>(std::max(1, width * height));
    const Rgba mean{
        static_cast<int>(std::lround(static_cast<double>(component.sumR) / pixelCount)),
        static_cast<int>(std::lround(static_cast<double>(component.sumG) / pixelCount)),
        static_cast<int>(std::lround(static_cast<double>(component.sumB) / pixelCount)),
        static_cast<int>(std::lround(static_cast<double>(component.sumA) / pixelCount)),
    };
    const float contrast = weighted_color_distance(mean, background) / std::max(1.0f, static_cast<float>(config.colorDistanceThreshold) * 2.2f);
    const float sizeRatio = std::min(1.0f, static_cast<float>(pixelCount) / static_cast<float>(std::max(config.minPixelArea, 1) * 8));
    const float alphaBoost = static_cast<float>(mean.a) / 255.0f;
    const float score = std::clamp(density * 0.45f + contrast * 0.35f + sizeRatio * 0.10f + alphaBoost * 0.10f, 0.0f, 0.99f);
    if (config.removeSmallRegions == 1U && score < 0.12f) {
        return region;
    }

    region.points = polygon_from_rows(component.rows, 48, 1);
    if (region.points.empty()) {
        region.points = rect_points(component.minX, component.minY, width, height);
    }
    region.score = score;
    recompute_bbox_from_points(region);
    return region;
}

RegionModel merge_region_models(const RegionModel& left, const RegionModel& right) {
    RegionModel merged;
    merged.score = std::max(left.score, right.score);
    merged.points = left.points;
    merged.points.insert(merged.points.end(), right.points.begin(), right.points.end());
    if (merged.points.empty()) {
        merged.points = rect_points(
            std::min(left.x, right.x),
            std::min(left.y, right.y),
            std::max(left.x + left.width, right.x + right.width) - std::min(left.x, right.x),
            std::max(left.y + left.height, right.y + right.height) - std::min(left.y, right.y)
        );
    } else {
        recompute_bbox_from_points(merged);
    }
    return merged;
}

std::vector<RegionModel> merge_nearby(std::vector<RegionModel> regions, int gap) {
    if (regions.size() < 2U) return regions;
    bool changed = true;
    while (changed) {
        changed = false;
        for (std::size_t left = 0; left < regions.size() && !changed; ++left) {
            for (std::size_t right = left + 1; right < regions.size(); ++right) {
                if (!should_merge_bbox(regions[left], regions[right], gap)) continue;
                regions[left] = merge_region_models(regions[left], regions[right]);
                regions.erase(regions.begin() + static_cast<std::ptrdiff_t>(right));
                changed = true;
                break;
            }
        }
    }
    return regions;
}

void assign_region_points(NativeRegion& target, const std::vector<NativePoint>& points) {
    target.pointCount = static_cast<int>(points.size());
    if (points.empty()) {
        target.points = nullptr;
        return;
    }
    auto* allocated = new NativePoint[points.size()];
    for (std::size_t index = 0; index < points.size(); ++index) {
        allocated[index] = points[index];
    }
    target.points = allocated;
}

void populate_region(NativeRegion& target, const RegionModel& source, bool selected = false) {
    target.x = source.x;
    target.y = source.y;
    target.width = source.width;
    target.height = source.height;
    target.visible = 1U;
    target.selected = selected ? 1U : 0U;
    target.score = source.score;
    assign_region_points(target, source.points);
}

std::vector<NativePoint> outline_from_mask(const std::vector<std::uint8_t>& mask, int width, int height) {
    std::vector<RowSpan> rows;
    rows.reserve(height);
    for (int y = 0; y < height; ++y) {
        int left = -1;
        int right = -1;
        for (int x = 0; x < width; ++x) {
            if (mask[static_cast<std::size_t>(y * width + x)] == 0U) continue;
            if (left < 0) left = x;
            right = x;
        }
        if (left >= 0) rows.push_back(RowSpan{y, left, right});
    }
    return polygon_from_rows(rows, 96, 1);
}

RegionModel region_from_mask(const std::vector<std::uint8_t>& mask, int width, int height, int bboxPadding = 0) {
    RegionModel region;
    int minX = std::numeric_limits<int>::max();
    int minY = std::numeric_limits<int>::max();
    int maxX = std::numeric_limits<int>::min();
    int maxY = std::numeric_limits<int>::min();
    int pixelCount = 0;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            if (mask[static_cast<std::size_t>(y * width + x)] == 0U) continue;
            ++pixelCount;
            minX = std::min(minX, x);
            minY = std::min(minY, y);
            maxX = std::max(maxX, x);
            maxY = std::max(maxY, y);
        }
    }
    if (pixelCount == 0) return region;
    region.points = outline_from_mask(mask, width, height);
    if (region.points.empty()) {
        region.points = rect_points(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }
    if (bboxPadding > 0) {
        for (NativePoint& point : region.points) {
            point.x = std::clamp(point.x, 0, width);
            point.y = std::clamp(point.y, 0, height);
        }
    }
    region.score = 1.0f;
    recompute_bbox_from_points(region);
    if (bboxPadding > 0) {
        region.x = std::max(0, region.x - bboxPadding);
        region.y = std::max(0, region.y - bboxPadding);
        region.width = std::min(width, region.x + region.width + bboxPadding * 2) - region.x;
        region.height = std::min(height, region.y + region.height + bboxPadding * 2) - region.y;
    }
    return region;
}

int flood_magic_region(const NativeImageBuffer& image, int seedX, int seedY, const NativeDetectionConfig& config, std::vector<std::uint8_t>& mask) {
    if (!is_inside(seedX, seedY, image.width, image.height) || image.pixels == nullptr) return 0;
    const NativeDetectionConfig backgroundConfig = config;
    const DetectionContext context = build_detection_context(image, backgroundConfig);
    const int seedIndex = seedY * image.width + seedX;
    const Rgba seedColor = decode_argb(image.pixels[seedIndex]);
    const float backgroundDistance = weighted_color_distance(seedColor, context.background);
    const float backgroundThreshold = std::max(
        8.0f,
        static_cast<float>(config.colorDistanceThreshold) - static_cast<float>(config.backgroundTolerance) * 0.25f
    );
    if (seedColor.a <= config.alphaThreshold) return 0;
    if (context.mode != kModeAlphaMask && backgroundDistance <= backgroundThreshold) return 0;

    mask.assign(static_cast<std::size_t>(image.width * image.height), 0U);
    std::vector<std::uint8_t> visited(mask.size(), 0U);
    std::queue<int> queue;
    queue.push(seedIndex);
    visited[static_cast<std::size_t>(seedIndex)] = 1U;
    int pixelCount = 0;

    while (!queue.empty()) {
        const int current = queue.front();
        queue.pop();
        const int x = current % image.width;
        const int y = current / image.width;
        const Rgba rgba = decode_argb(image.pixels[current]);
        const float seedDistance = weighted_color_distance(rgba, seedColor);
        if (rgba.a <= config.alphaThreshold || seedDistance > static_cast<float>(config.colorDistanceThreshold)) {
            continue;
        }
        mask[static_cast<std::size_t>(current)] = 1U;
        ++pixelCount;
        for (const auto& [nx, ny] : neighbors8(x, y)) {
            if (!is_inside(nx, ny, image.width, image.height)) continue;
            const int nextIndex = ny * image.width + nx;
            if (visited[static_cast<std::size_t>(nextIndex)] != 0U) continue;
            visited[static_cast<std::size_t>(nextIndex)] = 1U;
            queue.push(nextIndex);
        }
    }
    return pixelCount;
}

std::vector<RegionModel> build_grid_regions(const NativeImageBuffer& image, const NativeGridConfig& config) {
    std::vector<RegionModel> regions;
    if (image.width <= 0 || image.height <= 0 || image.pixels == nullptr) return regions;

    const NativeDetectionConfig detectionConfig{
        1,
        1,
        0,
        config.alphaThreshold,
        config.backgroundTolerance,
        2,
        1,
        std::max(24, config.backgroundTolerance * 3),
        0,
        0,
        1U,
        0,
        0U,
        0U,
        0U,
        0
    };
    const DetectionContext context = build_detection_context(image, detectionConfig);

    for (int row = 0; row < std::max(0, config.rows); ++row) {
        for (int col = 0; col < std::max(0, config.columns); ++col) {
            int x = config.offsetX + col * (config.cellWidth + config.gapX);
            int y = config.offsetY + row * (config.cellHeight + config.gapY);
            int width = config.cellWidth;
            int height = config.cellHeight;
            if (width <= 0 || height <= 0) continue;
            if (x >= image.width || y >= image.height) continue;
            width = std::min(width, image.width - x);
            height = std::min(height, image.height - y);
            if (width <= 0 || height <= 0) continue;

            int searchLeft = x;
            int searchTop = y;
            int searchRight = x + width;
            int searchBottom = y + height;
            if (config.snapToContent == 1U) {
                searchLeft = std::max(0, x - config.searchPadding);
                searchTop = std::max(0, y - config.searchPadding);
                searchRight = std::min(image.width, x + width + config.searchPadding);
                searchBottom = std::min(image.height, y + height + config.searchPadding);
            }

            int minX = std::numeric_limits<int>::max();
            int minY = std::numeric_limits<int>::max();
            int maxX = std::numeric_limits<int>::min();
            int maxY = std::numeric_limits<int>::min();
            bool found = false;
            for (int py = searchTop; py < searchBottom; ++py) {
                for (int px = searchLeft; px < searchRight; ++px) {
                    if (context.mask[static_cast<std::size_t>(py * image.width + px)] == 0U) continue;
                    found = true;
                    minX = std::min(minX, px);
                    minY = std::min(minY, py);
                    maxX = std::max(maxX, px);
                    maxY = std::max(maxY, py);
                }
            }

            if (!found) {
                if (config.ignoreEmptyCells == 1U) continue;
                RegionModel region;
                region.x = x;
                region.y = y;
                region.width = width;
                region.height = height;
                region.points = rect_points(x, y, width, height);
                region.score = 1.0f;
                regions.push_back(std::move(region));
                continue;
            }

            RegionModel region;
            if (config.trimCellToContent == 1U) {
                region.x = minX;
                region.y = minY;
                region.width = std::max(1, maxX - minX + 1);
                region.height = std::max(1, maxY - minY + 1);
            } else {
                region.x = x;
                region.y = y;
                region.width = width;
                region.height = height;
            }
            region.points = rect_points(region.x, region.y, region.width, region.height);
            region.score = 1.0f;
            regions.push_back(std::move(region));
        }
    }
    return regions;
}

void reset_detection_result(NativeDetectionResult& result) {
    result.mode = kModeFallbackBackground;
    result.regionCount = 0;
    result.regions = nullptr;
    result.stats = NativeDetectionStats{};
}

void reset_magic_result(NativeMagicResult& result) {
    result.region = NativeRegion{};
    result.mask = nullptr;
    result.maskLength = 0;
    result.imageWidth = 0;
    result.imageHeight = 0;
    result.seedX = 0;
    result.seedY = 0;
    result.pixelCount = 0;
    result.found = 0U;
}

}  // namespace

const char* detector_backend_name_builtin() {
    return "builtin-offline-polygon-v1";
}

int detect_icons_builtin(const NativeImageBuffer* image, const NativeDetectionConfig* config, NativeDetectionResult* result) {
    if (image == nullptr || config == nullptr || result == nullptr) return 1;
    reset_detection_result(*result);
    const auto startedAt = std::chrono::steady_clock::now();
    if (image->width <= 0 || image->height <= 0 || image->pixels == nullptr) {
        return 1;
    }

    DetectionContext context = build_detection_context(*image, *config);
    if (config->enableHoleFill == 1U) fill_holes(context.mask, image->width, image->height);
    for (int index = 0; index < config->erodeIterations; ++index) erode(context.mask, image->width, image->height);
    for (int index = 0; index < config->dilateIterations; ++index) dilate(context.mask, image->width, image->height);

    const std::vector<Component> components = extract_components(*image, context.mask, false);
    std::vector<RegionModel> regions;
    regions.reserve(components.size());
    for (const Component& component : components) {
        RegionModel region = component_to_region(component, context.background, *config);
        if (region.width > 0 && region.height > 0) {
            regions.push_back(std::move(region));
        }
    }
    if (config->mergeNearbyRegions == 1U) {
        regions = merge_nearby(std::move(regions), std::max(0, config->gapThreshold));
    }
    std::sort(regions.begin(), regions.end(), [](const RegionModel& left, const RegionModel& right) {
        return left.y == right.y ? left.x < right.x : left.y < right.y;
    });

    if (!regions.empty()) {
        auto* allocated = new NativeRegion[regions.size()];
        for (std::size_t index = 0; index < regions.size(); ++index) {
            populate_region(allocated[index], regions[index]);
        }
        result->regions = allocated;
        result->regionCount = static_cast<int>(regions.size());
    }
    result->mode = context.mode;
    result->stats.estimatedBackgroundArgb = encode_argb(context.background);
    result->stats.candidatePixels = context.candidatePixels;
    result->stats.connectedComponents = static_cast<int>(components.size());
    result->stats.regionCount = result->regionCount;
    result->stats.backgroundSampleCount = context.sampleCount;
    result->stats.totalTimeMs = static_cast<long>(
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - startedAt).count()
    );
    return 0;
}

int split_grid_builtin(const NativeImageBuffer* image, const NativeGridConfig* config, NativeDetectionResult* result) {
    if (image == nullptr || config == nullptr || result == nullptr) return 1;
    reset_detection_result(*result);
    if (image->width <= 0 || image->height <= 0 || image->pixels == nullptr) return 1;
    std::vector<RegionModel> regions = build_grid_regions(*image, *config);
    if (!regions.empty()) {
        auto* allocated = new NativeRegion[regions.size()];
        for (std::size_t index = 0; index < regions.size(); ++index) {
            populate_region(allocated[index], regions[index]);
        }
        result->regions = allocated;
        result->regionCount = static_cast<int>(regions.size());
    }
    result->mode = kModeFallbackBackground;
    result->stats.regionCount = result->regionCount;
    result->stats.connectedComponents = result->regionCount;
    return 0;
}

int detect_magic_region_builtin(
    const NativeImageBuffer* image,
    int seedX,
    int seedY,
    const NativeDetectionConfig* config,
    NativeMagicResult* result
) {
    if (image == nullptr || config == nullptr || result == nullptr) return 1;
    reset_magic_result(*result);
    if (image->width <= 0 || image->height <= 0 || image->pixels == nullptr) return 1;
    std::vector<std::uint8_t> mask;
    const int pixelCount = flood_magic_region(*image, seedX, seedY, *config, mask);
    if (pixelCount <= 0 || pixelCount < config->minPixelArea) return 0;

    RegionModel region = region_from_mask(mask, image->width, image->height, config->bboxPadding);
    if (region.width <= 0 || region.height <= 0) return 0;

    result->maskLength = static_cast<int>(mask.size());
    result->mask = new std::uint8_t[mask.size()];
    for (std::size_t index = 0; index < mask.size(); ++index) {
        result->mask[index] = mask[index];
    }
    result->imageWidth = image->width;
    result->imageHeight = image->height;
    result->seedX = seedX;
    result->seedY = seedY;
    result->pixelCount = pixelCount;
    result->found = 1U;
    populate_region(result->region, region, true);
    return 0;
}

int merge_magic_masks_builtin(
    const std::uint8_t* currentMask,
    int currentLength,
    const std::uint8_t* addedMask,
    int addedLength,
    int width,
    int height,
    int bboxPadding,
    NativeMagicResult* result
) {
    if (result == nullptr) return 1;
    reset_magic_result(*result);
    if (currentMask == nullptr || addedMask == nullptr || width <= 0 || height <= 0) return 1;
    if (currentLength != addedLength || currentLength != width * height) return 1;

    std::vector<std::uint8_t> merged(static_cast<std::size_t>(currentLength), 0U);
    for (int index = 0; index < currentLength; ++index) {
        merged[static_cast<std::size_t>(index)] = (currentMask[index] != 0U || addedMask[index] != 0U) ? 1U : 0U;
    }
    RegionModel region = region_from_mask(merged, width, height, bboxPadding);
    if (region.width <= 0 || region.height <= 0) return 0;

    result->maskLength = currentLength;
    result->mask = new std::uint8_t[merged.size()];
    for (std::size_t index = 0; index < merged.size(); ++index) {
        result->mask[index] = merged[index];
    }
    result->imageWidth = width;
    result->imageHeight = height;
    result->pixelCount = 0;
    for (std::uint8_t value : merged) {
        if (value != 0U) ++result->pixelCount;
    }
    result->found = 1U;
    populate_region(result->region, region, true);
    return 0;
}

int magic_mask_contains_builtin(const std::uint8_t* mask, int maskLength, int width, int height, int x, int y) {
    if (mask == nullptr || maskLength <= 0 || !is_inside(x, y, width, height)) return 0;
    const int index = y * width + x;
    if (index < 0 || index >= maskLength) return 0;
    return mask[index] != 0U ? 1 : 0;
}

void free_detection_result_builtin(NativeDetectionResult* result) {
    if (result == nullptr) return;
    if (result->regions != nullptr) {
        for (int index = 0; index < result->regionCount; ++index) {
            delete[] result->regions[index].points;
            result->regions[index].points = nullptr;
            result->regions[index].pointCount = 0;
        }
        delete[] result->regions;
        result->regions = nullptr;
    }
    result->regionCount = 0;
}

void free_magic_result_builtin(NativeMagicResult* result) {
    if (result == nullptr) return;
    delete[] result->region.points;
    result->region.points = nullptr;
    result->region.pointCount = 0;
    delete[] result->mask;
    result->mask = nullptr;
    result->maskLength = 0;
    result->found = 0U;
}

}  // namespace workflowtool

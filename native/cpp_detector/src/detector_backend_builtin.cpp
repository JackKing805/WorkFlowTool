#include "detector_backend.h"

float weighted_distance(int32_t a, int32_t b) {
    const float dr = static_cast<float>(red(a) - red(b));
    const float dg = static_cast<float>(green(a) - green(b));
    const float db = static_cast<float>(blue(a) - blue(b));
    const float luma = std::abs(dr * 0.30f + dg * 0.59f + db * 0.11f);
    return luma + std::abs(dr) * 0.35f + std::abs(db) * 0.15f;
}

float magic_distance(int32_t a, int32_t b) {
    const float r = static_cast<float>(std::abs(red(a) - red(b)));
    const float g = static_cast<float>(std::abs(green(a) - green(b)));
    const float bl = static_cast<float>(std::abs(blue(a) - blue(b)));
    const float la = red(a) * 0.299f + green(a) * 0.587f + blue(a) * 0.114f;
    const float lb = red(b) * 0.299f + green(b) * 0.587f + blue(b) * 0.114f;
    return r * 0.85f + g * 1.1f + bl * 0.75f + std::abs(la - lb) * 0.65f;
}

bool matches_background(int32_t argb, int32_t background, const NativeDetectionConfig& config) {
    const int alpha_threshold = std::max(0, config.alpha_threshold);
    const int a = alpha(argb);
    const int bg_a = alpha(background);
    if (a <= alpha_threshold && bg_a <= alpha_threshold) return true;
    if (std::abs(a - bg_a) > std::max(18, alpha_threshold * 3)) return false;
    return weighted_distance(argb, background) <= static_cast<float>(std::max(12, config.color_distance_threshold));
}

int32_t estimate_edge_background(const NativeImageBuffer& image, const NativeDetectionConfig& config, int* sample_count) {
    const int width = image.width;
    const int height = image.height;
    const int edge = std::max(1, std::min(config.edge_sample_width, std::max(1, std::min(width, height) / 2)));
    std::unordered_map<int32_t, int> buckets;
    std::vector<int32_t> samples;
    samples.reserve(static_cast<size_t>(width * 2 + height * 2));

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            if (x < edge || y < edge || x >= width - edge || y >= height - edge) {
                const int32_t argb = image.pixels[idx(x, y, width)];
                const int32_t bucket = ((alpha(argb) / 24) << 24) | ((red(argb) / 24) << 16) | ((green(argb) / 24) << 8) | (blue(argb) / 24);
                buckets[bucket] += 1;
                samples.push_back(argb);
            }
        }
    }
    if (sample_count) *sample_count = static_cast<int>(samples.size());
    if (samples.empty()) return 0;

    int32_t best_bucket = 0;
    int best_count = -1;
    for (const auto& entry : buckets) {
        if (entry.second > best_count) {
            best_bucket = entry.first;
            best_count = entry.second;
        }
    }

    long long a = 0, r = 0, g = 0, b = 0, count = 0;
    for (const int32_t argb : samples) {
        const int32_t bucket = ((alpha(argb) / 24) << 24) | ((red(argb) / 24) << 16) | ((green(argb) / 24) << 8) | (blue(argb) / 24);
        if (bucket != best_bucket) continue;
        a += alpha(argb);
        r += red(argb);
        g += green(argb);
        b += blue(argb);
        count += 1;
    }
    if (count == 0) return samples.front();
    return static_cast<int32_t>(((a / count) << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count));
}

std::vector<RegionWork> trace_regions(const std::vector<uint8_t>& mask, int width, int height, const NativeDetectionConfig& config, int* connected) {
    std::vector<uint8_t> visited(mask.size(), 0);
    std::vector<int> queue;
    queue.reserve(mask.size());
    std::vector<RegionWork> regions;
    int components = 0;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const int start = idx(x, y, width);
            if (!mask[start] || visited[start]) continue;
            components += 1;
            visited[start] = 1;
            queue.clear();
            queue.push_back(start);
            size_t head = 0;
            int min_x = x, max_x = x, min_y = y, max_y = y, pixels = 0;

            while (head < queue.size()) {
                const int current = queue[head++];
                const int cx = current % width;
                const int cy = current / width;
                pixels += 1;
                min_x = std::min(min_x, cx);
                max_x = std::max(max_x, cx);
                min_y = std::min(min_y, cy);
                max_y = std::max(max_y, cy);

                for (int oy = -1; oy <= 1; ++oy) {
                    for (int ox = -1; ox <= 1; ++ox) {
                        if (ox == 0 && oy == 0) continue;
                        const int nx = cx + ox;
                        const int ny = cy + oy;
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                        const int next = idx(nx, ny, width);
                        if (!mask[next] || visited[next]) continue;
                        visited[next] = 1;
                        queue.push_back(next);
                    }
                }
            }

            const int region_width = max_x - min_x + 1;
            const int region_height = max_y - min_y + 1;
            if (config.remove_small_regions) {
                if (region_width < config.min_width || region_height < config.min_height || pixels < config.min_pixel_area) continue;
            }
            const int pad = std::max(0, config.bbox_padding);
            const int left = std::max(0, min_x - pad);
            const int top = std::max(0, min_y - pad);
            const int right = std::min(width - 1, max_x + pad);
            const int bottom = std::min(height - 1, max_y + pad);
            regions.push_back({left, top, right - left + 1, bottom - top + 1, pixels});
        }
    }
    if (connected) *connected = components;
    return regions;
}

bool should_merge(const RegionWork& a, const RegionWork& b, int gap) {
    const int a_right = a.x + a.width;
    const int a_bottom = a.y + a.height;
    const int b_right = b.x + b.width;
    const int b_bottom = b.y + b.height;
    const int horizontal = std::max(0, std::max(a.x - b_right, b.x - a_right));
    const int vertical = std::max(0, std::max(a.y - b_bottom, b.y - a_bottom));
    return horizontal <= gap && vertical <= gap;
}

std::vector<RegionWork> merge_regions(std::vector<RegionWork> regions, int gap) {
    bool changed = true;
    while (changed) {
        changed = false;
        for (size_t i = 0; i < regions.size() && !changed; ++i) {
            for (size_t j = i + 1; j < regions.size(); ++j) {
                if (!should_merge(regions[i], regions[j], gap)) continue;
                const int left = std::min(regions[i].x, regions[j].x);
                const int top = std::min(regions[i].y, regions[j].y);
                const int right = std::max(regions[i].x + regions[i].width, regions[j].x + regions[j].width);
                const int bottom = std::max(regions[i].y + regions[i].height, regions[j].y + regions[j].height);
                regions[i] = {left, top, right - left, bottom - top, regions[i].pixels + regions[j].pixels};
                regions.erase(regions.begin() + static_cast<long long>(j));
                changed = true;
                break;
            }
        }
    }
    return regions;
}

void write_regions(NativeDetectionResult* result, const std::vector<RegionWork>& regions, int mode, int32_t background, int candidate_pixels, int connected, int background_samples, long long total_time_ms) {
    result->mode = mode;
    result->region_count = static_cast<int32_t>(regions.size());
    result->stats.estimated_background_argb = background;
    result->stats.candidate_pixels = candidate_pixels;
    result->stats.connected_components = connected;
    result->stats.region_count = static_cast<int32_t>(regions.size());
    result->stats.background_sample_count = background_samples;
    result->stats.total_time_ms = total_time_ms;
    result->regions = nullptr;
    if (regions.empty()) return;
    auto* native_regions = static_cast<NativeRegion*>(std::calloc(regions.size(), sizeof(NativeRegion)));
    for (size_t i = 0; i < regions.size(); ++i) {
        native_regions[i] = {regions[i].x, regions[i].y, regions[i].width, regions[i].height, 1, 0};
    }
    result->regions = native_regions;
}

long long elapsed_ms(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
}

int32_t builtin_detect_icons(const NativeImageBuffer* image, const NativeDetectionConfig* config, NativeDetectionResult* result) {
    try {
        if (!image || !config || !result || !image->pixels || image->width <= 0 || image->height <= 0) return ERR_INVALID_ARGUMENT;
        const auto start = std::chrono::steady_clock::now();
        const int width = image->width;
        const int height = image->height;
        const int total = width * height;
        std::vector<uint8_t> alpha_mask(static_cast<size_t>(total), 0);
        int alpha_candidates = 0;
        for (int i = 0; i < total; ++i) {
            if (alpha(image->pixels[i]) > config->alpha_threshold) {
                alpha_mask[i] = 1;
                alpha_candidates += 1;
            }
        }

        int connected = 0;
        auto regions = trace_regions(alpha_mask, width, height, *config, &connected);
        if (alpha_candidates > 0 && alpha_candidates < static_cast<int>(total * 0.92f) && !regions.empty()) {
            if (config->merge_nearby_regions) regions = merge_regions(std::move(regions), std::max(0, config->gap_threshold));
            write_regions(result, regions, MODE_ALPHA_MASK, 0, alpha_candidates, connected, 0, elapsed_ms(start));
            return 0;
        }

        int background_samples = 0;
        const int32_t background = config->use_manual_background
            ? config->manual_background_argb
            : estimate_edge_background(*image, *config, &background_samples);
        std::vector<uint8_t> color_mask(static_cast<size_t>(total), 0);
        int color_candidates = 0;
        for (int i = 0; i < total; ++i) {
            if (!matches_background(image->pixels[i], background, *config)) {
                color_mask[i] = 1;
                color_candidates += 1;
            }
        }
        connected = 0;
        regions = trace_regions(color_mask, width, height, *config, &connected);
        if (config->merge_nearby_regions) regions = merge_regions(std::move(regions), std::max(0, config->gap_threshold));
        write_regions(result, regions, MODE_SOLID_BACKGROUND, background, color_candidates, connected, background_samples, elapsed_ms(start));
        return 0;
    } catch (...) {
        return ERR_PANIC;
    }
}

int32_t builtin_split_grid(const NativeImageBuffer* image, const NativeGridConfig* config, NativeDetectionResult* result) {
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

                const int32_t background = image->pixels[idx(left, top, image->width)];
                int min_x = right, min_y = bottom, max_x = left - 1, max_y = top - 1, pixels = 0;
                for (int y = top; y < bottom; ++y) {
                    for (int x = left; x < right; ++x) {
                        const int32_t argb = image->pixels[idx(x, y, image->width)];
                        if (alpha(argb) <= config->alpha_threshold) continue;
                        const int delta = std::max({std::abs(red(argb) - red(background)), std::abs(green(argb) - green(background)), std::abs(blue(argb) - blue(background))});
                        if (delta <= std::max(1, config->background_tolerance)) continue;
                        min_x = std::min(min_x, x);
                        min_y = std::min(min_y, y);
                        max_x = std::max(max_x, x);
                        max_y = std::max(max_y, y);
                        pixels += 1;
                    }
                }
                if (max_x < min_x || max_y < min_y) {
                    if (!config->ignore_empty_cells) {
                        regions.push_back({std::max(0, cell_x), std::max(0, cell_y), std::min(cell_width, image->width - cell_x), std::min(cell_height, image->height - cell_y), 0});
                    }
                    continue;
                }
                regions.push_back({min_x, min_y, max_x - min_x + 1, max_y - min_y + 1, pixels});
            }
        }
        write_regions(result, regions, MODE_FALLBACK_BACKGROUND, 0, 0, static_cast<int>(regions.size()), 0, elapsed_ms(start));
        return 0;
    } catch (...) {
        return ERR_PANIC;
    }
}

int32_t builtin_detect_magic_region(const NativeImageBuffer* image, int32_t seed_x, int32_t seed_y, const NativeDetectionConfig* config, NativeMagicResult* result) {
    try {
        if (!image || !config || !result || !image->pixels || image->width <= 0 || image->height <= 0) return ERR_INVALID_ARGUMENT;
        std::memset(result, 0, sizeof(NativeMagicResult));
        if (seed_x < 0 || seed_y < 0 || seed_x >= image->width || seed_y >= image->height) return 0;
        const int width = image->width;
        const int height = image->height;
        const int total = width * height;
        const int32_t seed = image->pixels[idx(seed_x, seed_y, width)];
        int background_samples = 0;
        const int32_t background = config->use_manual_background
            ? config->manual_background_argb
            : estimate_edge_background(*image, *config, &background_samples);
        if (matches_background(seed, background, *config)) return 0;

        std::vector<uint8_t> visited(static_cast<size_t>(total), 0);
        std::vector<uint8_t> matched(static_cast<size_t>(total), 0);
        std::queue<int> queue;
        const int start = idx(seed_x, seed_y, width);
        visited[start] = 1;
        queue.push(start);
        int min_x = seed_x, max_x = seed_x, min_y = seed_y, max_y = seed_y, pixels = 0;

        while (!queue.empty()) {
            const int current = queue.front();
            queue.pop();
            const int x = current % width;
            const int y = current / width;
            const int32_t argb = image->pixels[current];
            if (std::abs(alpha(argb) - alpha(seed)) > std::max(18, config->alpha_threshold * 3)) continue;
            if (magic_distance(argb, seed) > config->color_distance_threshold * 2.35f) continue;
            matched[current] = 1;
            pixels += 1;
            min_x = std::min(min_x, x);
            min_y = std::min(min_y, y);
            max_x = std::max(max_x, x);
            max_y = std::max(max_y, y);

            for (int oy = -1; oy <= 1; ++oy) {
                for (int ox = -1; ox <= 1; ++ox) {
                    if (ox == 0 && oy == 0) continue;
                    const int nx = x + ox;
                    const int ny = y + oy;
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    const int next = idx(nx, ny, width);
                    if (visited[next]) continue;
                    visited[next] = 1;
                    queue.push(next);
                }
            }
        }
        if (pixels < std::max(1, config->min_pixel_area)) return 0;
        const int pad = std::max(0, config->bbox_padding);
        const int left = std::max(0, min_x - pad);
        const int top = std::max(0, min_y - pad);
        const int right = std::min(width - 1, max_x + pad);
        const int bottom = std::min(height - 1, max_y + pad);
        result->region = {left, top, right - left + 1, bottom - top + 1, 1, 1};
        result->mask_length = total;
        result->mask = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(total)));
        if (!result->mask) return ERR_INVALID_ARGUMENT;
        std::memcpy(result->mask, matched.data(), static_cast<size_t>(total));
        result->image_width = width;
        result->image_height = height;
        result->seed_x = seed_x;
        result->seed_y = seed_y;
        result->pixel_count = pixels;
        result->found = 1;
        return 0;
    } catch (...) {
        return ERR_PANIC;
    }
}

int32_t merge_magic_masks_impl(
    const uint8_t* current_mask,
    int32_t current_length,
    const uint8_t* added_mask,
    int32_t added_length,
    int32_t width,
    int32_t height,
    int32_t bbox_padding,
    NativeMagicResult* result
) {
    try {
        if (!current_mask || !added_mask || !result || width <= 0 || height <= 0) return ERR_INVALID_ARGUMENT;
        const int total = width * height;
        if (current_length != total || added_length != total) return ERR_INVALID_ARGUMENT;
        std::memset(result, 0, sizeof(NativeMagicResult));

        std::vector<uint8_t> merged(static_cast<size_t>(total), 0);
        bool changed = false;
        int pixels = 0;
        int min_x = width;
        int min_y = height;
        int max_x = -1;
        int max_y = -1;

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                const int index = idx(x, y, width);
                const uint8_t selected = (current_mask[index] != 0 || added_mask[index] != 0) ? 1 : 0;
                merged[index] = selected;
                if (!selected) continue;
                pixels += 1;
                min_x = std::min(min_x, x);
                min_y = std::min(min_y, y);
                max_x = std::max(max_x, x);
                max_y = std::max(max_y, y);
                if (added_mask[index] != 0 && current_mask[index] == 0) changed = true;
            }
        }

        if (!changed || pixels <= 0 || max_x < min_x || max_y < min_y) return 0;

        const int pad = std::max(0, bbox_padding);
        const int left = std::max(0, min_x - pad);
        const int top = std::max(0, min_y - pad);
        const int right = std::min(width - 1, max_x + pad);
        const int bottom = std::min(height - 1, max_y + pad);
        result->region = {left, top, right - left + 1, bottom - top + 1, 1, 1};
        result->mask_length = total;
        result->mask = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(total)));
        if (!result->mask) return ERR_INVALID_ARGUMENT;
        std::memcpy(result->mask, merged.data(), static_cast<size_t>(total));
        result->image_width = width;
        result->image_height = height;
        result->seed_x = 0;
        result->seed_y = 0;
        result->pixel_count = pixels;
        result->found = 1;
        return 0;
    } catch (...) {
        return ERR_PANIC;
    }
}

int32_t magic_mask_contains_impl(const uint8_t* mask, int32_t mask_length, int32_t width, int32_t height, int32_t x, int32_t y) {
    if (!mask || width <= 0 || height <= 0 || x < 0 || y < 0 || x >= width || y >= height) return 0;
    const int index = idx(x, y, width);
    if (index < 0 || index >= mask_length) return 0;
    return mask[index] != 0 ? 1 : 0;
}

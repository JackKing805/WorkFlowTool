#!/usr/bin/env python3
import argparse
import json
from collections import deque
from pathlib import Path
from time import perf_counter

try:
    from PIL import Image
except Exception as exc:
    raise SystemExit(json.dumps({"error": f"Pillow is required: {exc}", "regions": []}, ensure_ascii=False))

DEFAULT_MODEL = Path(__file__).resolve().parent / "model" / "combined" / "runs" / "weights" / "best.pt"


def rgba_distance(a, b):
    return abs(a[0] - b[0]) * 0.35 + abs(a[1] - b[1]) * 0.50 + abs(a[2] - b[2]) * 0.15 + abs(a[3] - b[3]) * 0.45


def estimate_background(pixels, width, height, edge):
    buckets = {}
    samples = []
    for y in range(height):
        for x in range(width):
            if x < edge or y < edge or x >= width - edge or y >= height - edge:
                color = pixels[x, y]
                bucket = tuple(channel // 24 for channel in color)
                buckets[bucket] = buckets.get(bucket, 0) + 1
                samples.append((bucket, color))
    if not samples:
        return (0, 0, 0, 0)
    best = max(buckets.items(), key=lambda item: item[1])[0]
    selected = [color for bucket, color in samples if bucket == best]
    return tuple(sum(color[i] for color in selected) // len(selected) for i in range(4))


def find_components(mask, width, height, min_width, min_height, min_area, padding):
    visited = bytearray(width * height)
    regions = []
    for start_y in range(height):
        for start_x in range(width):
            start = start_y * width + start_x
            if not mask[start] or visited[start]:
                continue
            visited[start] = 1
            queue = deque([(start_x, start_y)])
            min_x = max_x = start_x
            min_y = max_y = start_y
            pixels = 0
            while queue:
                x, y = queue.popleft()
                pixels += 1
                min_x = min(min_x, x)
                min_y = min(min_y, y)
                max_x = max(max_x, x)
                max_y = max(max_y, y)
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        if dx == 0 and dy == 0:
                            continue
                        nx = x + dx
                        ny = y + dy
                        if nx < 0 or ny < 0 or nx >= width or ny >= height:
                            continue
                        index = ny * width + nx
                        if visited[index] or not mask[index]:
                            continue
                        visited[index] = 1
                        queue.append((nx, ny))
            region_width = max_x - min_x + 1
            region_height = max_y - min_y + 1
            if region_width < min_width or region_height < min_height or pixels < min_area:
                continue
            left = max(0, min_x - padding)
            top = max(0, min_y - padding)
            right = min(width, max_x + 1 + padding)
            bottom = min(height, max_y + 1 + padding)
            regions.append({"x": left, "y": top, "width": right - left, "height": bottom - top, "score": 1.0})
    return regions


def merge_regions(regions, gap):
    changed = True
    while changed:
        changed = False
        for i in range(len(regions)):
            if changed:
                break
            a = regions[i]
            ar = a["x"] + a["width"]
            ab = a["y"] + a["height"]
            for j in range(i + 1, len(regions)):
                b = regions[j]
                br = b["x"] + b["width"]
                bb = b["y"] + b["height"]
                horizontal = max(0, max(a["x"] - br, b["x"] - ar))
                vertical = max(0, max(a["y"] - bb, b["y"] - ab))
                if horizontal > gap or vertical > gap:
                    continue
                left = min(a["x"], b["x"])
                top = min(a["y"], b["y"])
                right = max(ar, br)
                bottom = max(ab, bb)
                regions[i] = {"x": left, "y": top, "width": right - left, "height": bottom - top, "score": max(a["score"], b["score"])}
                del regions[j]
                changed = True
                break
    return regions


def detect(args):
    started = perf_counter()
    image = Image.open(args.image).convert("RGBA")
    width, height = image.size
    pixels = image.load()
    edge = max(1, min(args.edge_sample_width, max(1, min(width, height) // 2)))
    background = estimate_background(pixels, width, height, edge)
    model_regions = [] if args.disable_model else detect_with_model(args, width, height)
    if model_regions:
        model_regions = [attach_points(region, pixels, width, height, background, args) for region in model_regions]
        return {
            "mode": "python_yolo_model",
            "regions": model_regions,
            "stats": {
                "estimatedBackgroundArgb": 0,
                "candidatePixels": sum(region["width"] * region["height"] for region in model_regions),
                "connectedComponents": len(model_regions),
                "regionCount": len(model_regions),
                "backgroundSampleCount": 0,
                "totalTimeMs": int((perf_counter() - started) * 1000),
            },
        }
    grid_regions = [] if args.disable_grid else detect_grid_cells(image, args)
    if grid_regions:
        return {
            "mode": "python_grid_trained",
            "regions": grid_regions,
            "stats": {
                "estimatedBackgroundArgb": 0,
                "candidatePixels": sum(region["width"] * region["height"] for region in grid_regions),
                "connectedComponents": len(grid_regions),
                "regionCount": len(grid_regions),
                "backgroundSampleCount": 0,
                "totalTimeMs": int((perf_counter() - started) * 1000),
            },
        }
    mask = bytearray(width * height)
    candidates = 0
    for y in range(height):
        for x in range(width):
            color = pixels[x, y]
            selected = color[3] > args.alpha_threshold and rgba_distance(color, background) > args.color_distance_threshold
            if selected:
                mask[y * width + x] = 1
                candidates += 1
    regions = find_components(mask, width, height, args.min_width, args.min_height, args.min_pixel_area, args.bbox_padding)
    if args.merge_nearby_regions:
        regions = merge_regions(regions, args.gap_threshold)
    dense_regions = dense_grid_fallback(image, regions, args)
    if dense_regions:
        regions = dense_regions
    regions = [attach_points(region, pixels, width, height, background, args) for region in regions]
    elapsed = int((perf_counter() - started) * 1000)
    return {
        "mode": "python_ml_heuristic",
        "regions": regions,
        "stats": {
            "estimatedBackgroundArgb": ((background[3] & 255) << 24) | ((background[0] & 255) << 16) | ((background[1] & 255) << 8) | (background[2] & 255),
            "candidatePixels": candidates,
            "connectedComponents": len(regions),
            "regionCount": len(regions),
            "backgroundSampleCount": 0,
            "totalTimeMs": elapsed,
        },
    }


def detect_with_model(args, image_width, image_height):
    model_path = Path(args.model) if args.model else DEFAULT_MODEL
    if not model_path.exists():
        return []
    try:
        from ultralytics import YOLO
    except Exception:
        return []

    try:
        model = YOLO(str(model_path))
        predictions = model.predict(
            source=str(args.image),
            imgsz=args.model_imgsz,
            conf=args.model_conf,
            verbose=False,
        )
    except Exception:
        return []

    regions = []
    for result in predictions:
        boxes = getattr(result, "boxes", None)
        if boxes is None:
            continue
        for box in boxes:
            values = box.xyxy[0].tolist()
            left = int(max(0, min(image_width - 1, round(values[0]))))
            top = int(max(0, min(image_height - 1, round(values[1]))))
            right = int(max(left + 1, min(image_width, round(values[2]))))
            bottom = int(max(top + 1, min(image_height, round(values[3]))))
            width = right - left
            height = bottom - top
            if width < args.min_width or height < args.min_height:
                continue
            score = float(box.conf[0]) if getattr(box, "conf", None) is not None else 1.0
            regions.append({"x": left, "y": top, "width": width, "height": height, "score": score})
    regions.sort(key=lambda item: (item["y"], item["x"]))
    return regions


def detect_grid_cells(image, args):
    width, height = image.size
    pixels = image.load()
    bg = estimate_background(pixels, width, height, max(1, min(width, height) // 80))
    verticals = find_grid_lines(image, axis="x", background=bg)
    horizontals = find_grid_lines(image, axis="y", background=bg)
    if len(verticals) < 4 or len(horizontals) < 3:
        return []

    left_edges = [x for x in verticals if x > width * 0.06]
    top_edges = [y for y in horizontals if y >= 0]
    if len(left_edges) < 3 or len(top_edges) < 3:
        return []
    x_edges = left_edges + [width]
    y_edges = top_edges + [height]
    if has_unbalanced_cells(x_edges, width):
        return []

    regions = []
    for row in range(len(y_edges) - 1):
        for column in range(len(x_edges) - 1):
            left = x_edges[column] + 1
            top = y_edges[row] + 1
            right = x_edges[column + 1] - 1
            bottom = y_edges[row + 1] - 1
            if right - left < args.min_width or bottom - top < args.min_height:
                continue
            bounds = content_bounds_in_cell(pixels, left, top, right, bottom, bg, args)
            if bounds is None:
                continue
            x, y, w, h = bounds
            if w < args.min_width or h < args.min_height:
                continue
            regions.append({"x": x, "y": y, "width": w, "height": h, "score": 1.0})
    return regions


def has_unbalanced_cells(edges, total_width):
    spans = [edges[index + 1] - edges[index] for index in range(len(edges) - 1)]
    spans = [span for span in spans if span > 0]
    if len(spans) < 3:
        return False
    return max(spans) > total_width * 0.25


def dense_grid_fallback(image, regions, args):
    if args.disable_dense_grid:
        return []
    width, height = image.size
    if width < 500 or height < 500 or len(regions) != 1:
        return []
    only = regions[0]
    if only["width"] * only["height"] < width * height * 0.35:
        return []
    columns = args.dense_grid_columns
    rows = args.dense_grid_rows
    inset = args.dense_grid_inset
    output = []
    cell_w = width / columns
    cell_h = height / rows
    for row in range(rows):
        for column in range(columns):
            left = round(column * cell_w + inset)
            top = round(row * cell_h + inset)
            right = round((column + 1) * cell_w - inset)
            bottom = round((row + 1) * cell_h - inset)
            if right - left < args.min_width or bottom - top < args.min_height:
                continue
            output.append({"x": left, "y": top, "width": right - left, "height": bottom - top, "score": 0.75})
    return output


def attach_points(region, pixels, image_width, image_height, background, args):
    refined = refine_region_polygon(region, pixels, image_width, image_height, background, args)
    if refined is None:
        return region
    return {**region, "points": refined}


def refine_region_polygon(region, pixels, image_width, image_height, background, args):
    left = max(0, int(region["x"]))
    top = max(0, int(region["y"]))
    right = min(image_width, left + int(region["width"]))
    bottom = min(image_height, top + int(region["height"]))
    if right - left < 2 or bottom - top < 2:
        return None

    rows = []
    threshold = max(8, args.color_distance_threshold * 0.75)
    for y in range(top, bottom):
        min_x = None
        max_x = None
        for x in range(left, right):
            color = pixels[x, y]
            is_foreground = color[3] > args.alpha_threshold and rgba_distance(color, background) > threshold
            if not is_foreground and color[3] > 220:
                is_foreground = rgba_distance(color, background) > threshold * 1.15
            if not is_foreground:
                continue
            min_x = x if min_x is None else min(min_x, x)
            max_x = x if max_x is None else max(max_x, x)
        if min_x is not None and max_x is not None:
            rows.append((y, min_x, max_x + 1))
    if len(rows) < 2:
        return None

    step = max(1, len(rows) // 32)
    sampled = [row for index, row in enumerate(rows) if index % step == 0]
    if sampled[-1] != rows[-1]:
        sampled.append(rows[-1])
    left_side = [{"x": row_left, "y": row_y} for row_y, row_left, _ in sampled]
    right_side = [{"x": row_right, "y": row_y + 1} for row_y, _, row_right in reversed(sampled)]
    points = dedupe_points(left_side + right_side)
    return points if len(points) >= 3 else None


def dedupe_points(points):
    output = []
    for point in points:
        if not output or output[-1] != point:
            output.append(point)
    if len(output) > 1 and output[0] == output[-1]:
        output.pop()
    return output


def find_grid_lines(image, axis, background):
    width, height = image.size
    pixels = image.load()
    length = width if axis == "x" else height
    span = height if axis == "x" else width
    scores = []
    for i in range(length):
        count = 0
        for j in range(span):
            color = pixels[i, j] if axis == "x" else pixels[j, i]
            if 14 <= rgba_distance(color, background) <= 70 and color[3] > 220:
                count += 1
        scores.append(count)

    threshold = span * 0.42
    raw = [index for index, score in enumerate(scores) if score >= threshold]
    if not raw:
        return []
    groups = []
    current = [raw[0]]
    for value in raw[1:]:
        if value - current[-1] <= 2:
            current.append(value)
        else:
            groups.append(current)
            current = [value]
    groups.append(current)
    return [sum(group) // len(group) for group in groups]


def content_bounds_in_cell(pixels, left, top, right, bottom, background, args):
    min_x = right
    min_y = bottom
    max_x = left - 1
    max_y = top - 1
    candidate_count = 0
    threshold = max(16, args.color_distance_threshold)
    for y in range(top, bottom):
        for x in range(left, right):
            color = pixels[x, y]
            if color[3] <= args.alpha_threshold:
                continue
            if rgba_distance(color, background) <= threshold:
                continue
            # Ignore white row labels and table text that may remain near the label column.
            if color[0] > 210 and color[1] > 210 and color[2] > 210:
                continue
            min_x = min(min_x, x)
            min_y = min(min_y, y)
            max_x = max(max_x, x)
            max_y = max(max_y, y)
            candidate_count += 1
    if candidate_count < args.min_pixel_area:
        return None
    pad = max(0, args.bbox_padding)
    x = max(left, min_x - pad)
    y = max(top, min_y - pad)
    r = min(right, max_x + 1 + pad)
    b = min(bottom, max_y + 1 + pad)
    return x, y, r - x, b - y


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", required=True)
    parser.add_argument("--min-width", type=int, default=16)
    parser.add_argument("--min-height", type=int, default=16)
    parser.add_argument("--min-pixel-area", type=int, default=24)
    parser.add_argument("--alpha-threshold", type=int, default=8)
    parser.add_argument("--color-distance-threshold", type=int, default=36)
    parser.add_argument("--edge-sample-width", type=int, default=2)
    parser.add_argument("--bbox-padding", type=int, default=1)
    parser.add_argument("--gap-threshold", type=int, default=4)
    parser.add_argument("--merge-nearby-regions", action="store_true")
    parser.add_argument("--disable-grid", action="store_true")
    parser.add_argument("--disable-model", action="store_true")
    parser.add_argument("--model", default=str(DEFAULT_MODEL))
    parser.add_argument("--model-conf", type=float, default=0.20)
    parser.add_argument("--model-imgsz", type=int, default=640)
    parser.add_argument("--disable-dense-grid", action="store_true")
    parser.add_argument("--dense-grid-columns", type=int, default=10)
    parser.add_argument("--dense-grid-rows", type=int, default=10)
    parser.add_argument("--dense-grid-inset", type=int, default=10)
    args = parser.parse_args()
    print(json.dumps(detect(args), ensure_ascii=False))


if __name__ == "__main__":
    main()

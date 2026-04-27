from __future__ import annotations

import argparse
import json
import time
from collections import deque
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from offline_common import (
    argb_to_rgba,
    clamp,
    estimate_edge_background,
    load_image_rgba,
    load_json,
    rgba_to_argb,
    weighted_color_distance,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Offline icon detector with polygon output.")
    parser.add_argument("--image", required=True)
    parser.add_argument("--min-width", type=int, default=16)
    parser.add_argument("--min-height", type=int, default=16)
    parser.add_argument("--min-pixel-area", type=int, default=24)
    parser.add_argument("--alpha-threshold", type=int, default=8)
    parser.add_argument("--background-tolerance", type=int, default=12)
    parser.add_argument("--color-distance-threshold", type=float, default=36.0)
    parser.add_argument("--edge-sample-width", type=int, default=2)
    parser.add_argument("--dilate-iterations", type=int, default=0)
    parser.add_argument("--erode-iterations", type=int, default=0)
    parser.add_argument("--bbox-padding", type=int, default=1)
    parser.add_argument("--gap-threshold", type=int, default=4)
    parser.add_argument("--manual-background-argb", type=int, default=0)
    parser.add_argument("--merge-nearby-regions", action="store_true")
    parser.add_argument("--remove-small-regions", action="store_true")
    parser.add_argument("--enable-hole-fill", action="store_true")
    parser.add_argument("--use-manual-background", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    started_at = time.perf_counter()
    image_path = Path(args.image)
    width, height, pixels = load_image_rgba(image_path)
    model = load_runtime_model(Path(__file__).resolve().parent)
    defaults = model.get("defaults", {}) if isinstance(model.get("defaults"), dict) else {}

    effective_min_width = resolve_default_int(args.min_width, 16, defaults.get("minWidth"))
    effective_min_height = resolve_default_int(args.min_height, 16, defaults.get("minHeight"))
    effective_min_pixel_area = resolve_default_int(args.min_pixel_area, 24, defaults.get("minPixelArea"))
    effective_alpha_threshold = resolve_default_int(args.alpha_threshold, 8, defaults.get("alphaThreshold"))
    effective_edge_sample_width = resolve_default_int(args.edge_sample_width, 2, defaults.get("edgeSampleWidth"))
    effective_bbox_padding = resolve_default_int(args.bbox_padding, 1, defaults.get("bboxPadding"))
    effective_color_distance = resolve_default_float(
        args.color_distance_threshold,
        36.0,
        defaults.get("colorDistanceThreshold"),
    )

    if args.use_manual_background:
        background = argb_to_rgba(args.manual_background_argb)
        sample_count = 0
        mode = "solid_background"
    else:
        background, sample_count, mode = estimate_edge_background(
            pixels,
            width,
            height,
            effective_edge_sample_width,
            effective_alpha_threshold,
        )

    threshold = effective_color_distance
    threshold = max(8.0, threshold * float(model["heuristics"].get("distanceScale", 1.0)))
    threshold += float(model["heuristics"].get("distanceOffset", 0.0))
    threshold = clamp(threshold, 8.0, 128.0)

    mask = [
        is_foreground_pixel(
            pixel,
            background,
            mode,
            alpha_threshold=effective_alpha_threshold,
            color_distance_threshold=threshold,
            background_tolerance=args.background_tolerance,
        )
        for pixel in pixels
    ]
    candidate_pixels = sum(1 for value in mask if value)

    if args.enable_hole_fill:
        mask = fill_holes(mask, width, height)
    if args.erode_iterations > 0:
        for _ in range(args.erode_iterations):
            mask = erode(mask, width, height)
    if args.dilate_iterations > 0:
        for _ in range(args.dilate_iterations):
            mask = dilate(mask, width, height)

    components = extract_components(mask, pixels, width, height)
    if args.merge_nearby_regions and len(components) > 1:
        merge_gap = int(max(args.gap_threshold, int(model["heuristics"].get("mergeGap", 0))))
        components = merge_components(components, merge_gap)

    score_floor = float(model["heuristics"].get("scoreFloor", 0.12))
    sample_target = int(model["heuristics"].get("rowSampleTarget", 48))
    simplify_tolerance = int(model["heuristics"].get("polygonSimplifyTolerance", 1))
    regions = []
    for component in components:
        region = component_to_region(
            component,
            background,
            threshold,
            mode,
            args,
            effective_min_width,
            effective_min_height,
            effective_min_pixel_area,
            effective_bbox_padding,
            score_floor,
            sample_target,
            simplify_tolerance,
        )
        if region is not None:
            regions.append(region)

    regions.sort(key=lambda region: (region["y"], region["x"]))
    total_time_ms = int(round((time.perf_counter() - started_at) * 1000.0))
    payload = {
        "mode": mode,
        "regions": regions,
        "stats": {
            "estimatedBackgroundArgb": rgba_to_argb(background),
            "candidatePixels": candidate_pixels,
            "connectedComponents": len(components),
            "regionCount": len(regions),
            "backgroundSampleCount": sample_count,
            "totalTimeMs": total_time_ms,
        },
    }
    print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    return 0


def load_runtime_model(base_dir: Path) -> Dict[str, Any]:
    default_model = {
        "version": 1,
        "backend": "polygon_heuristic_v1",
        "heuristics": {
            "distanceScale": 1.0,
            "distanceOffset": 0.0,
            "mergeGap": 4,
            "scoreFloor": 0.12,
            "rowSampleTarget": 48,
            "polygonSimplifyTolerance": 1,
        },
    }
    loaded = load_json(base_dir / "model" / "combined" / "model.json", default_model)
    if not isinstance(loaded, dict):
        return default_model
    heuristics = loaded.setdefault("heuristics", {})
    if not isinstance(heuristics, dict):
        loaded["heuristics"] = dict(default_model["heuristics"])
    for key, value in default_model["heuristics"].items():
        loaded["heuristics"].setdefault(key, value)
    loaded.setdefault("backend", default_model["backend"])
    loaded.setdefault("version", default_model["version"])
    return loaded


def is_foreground_pixel(
    pixel: Tuple[int, int, int, int],
    background: Tuple[int, int, int, int],
    mode: str,
    *,
    alpha_threshold: int,
    color_distance_threshold: float,
    background_tolerance: int,
) -> bool:
    alpha = pixel[3]
    if alpha <= alpha_threshold:
        return False
    if mode == "alpha_mask" and alpha < 250:
        return True
    if background[3] <= alpha_threshold and alpha >= 250:
        return True
    threshold = max(12.0, color_distance_threshold - background_tolerance * 0.25)
    return weighted_color_distance(pixel, background) > threshold


def fill_holes(mask: Sequence[bool], width: int, height: int) -> List[bool]:
    if width <= 0 or height <= 0:
        return list(mask)
    visited = bytearray(width * height)
    queue: deque[int] = deque()

    def enqueue(index: int) -> None:
        if visited[index] or mask[index]:
            return
        visited[index] = 1
        queue.append(index)

    for x in range(width):
        enqueue(x)
        enqueue((height - 1) * width + x)
    for y in range(height):
        enqueue(y * width)
        enqueue(y * width + width - 1)

    while queue:
        index = queue.popleft()
        x = index % width
        y = index // width
        for nx, ny in neighbors4(x, y, width, height):
            next_index = ny * width + nx
            if not visited[next_index] and not mask[next_index]:
                visited[next_index] = 1
                queue.append(next_index)

    filled = list(mask)
    for index, value in enumerate(mask):
        if not value and not visited[index]:
            filled[index] = True
    return filled


def dilate(mask: Sequence[bool], width: int, height: int) -> List[bool]:
    output = [False] * (width * height)
    for y in range(height):
        for x in range(width):
            index = y * width + x
            if mask[index]:
                output[index] = True
                for nx, ny in neighbors8(x, y, width, height):
                    output[ny * width + nx] = True
    return output


def erode(mask: Sequence[bool], width: int, height: int) -> List[bool]:
    output = [False] * (width * height)
    for y in range(height):
        for x in range(width):
            index = y * width + x
            if not mask[index]:
                continue
            if all(mask[ny * width + nx] for nx, ny in neighbors8(x, y, width, height)):
                output[index] = True
    return output


def extract_components(
    mask: Sequence[bool],
    pixels: Sequence[Tuple[int, int, int, int]],
    width: int,
    height: int,
) -> List[Dict[str, Any]]:
    visited = bytearray(width * height)
    components: List[Dict[str, Any]] = []
    for index, value in enumerate(mask):
        if not value or visited[index]:
            continue
        queue: deque[int] = deque([index])
        visited[index] = 1
        coords: List[Tuple[int, int]] = []
        rows: Dict[int, List[int]] = {}
        sum_r = sum_g = sum_b = sum_a = 0
        min_x = width
        min_y = height
        max_x = -1
        max_y = -1
        while queue:
            current = queue.popleft()
            x = current % width
            y = current // width
            coords.append((x, y))
            pixel = pixels[current]
            sum_r += pixel[0]
            sum_g += pixel[1]
            sum_b += pixel[2]
            sum_a += pixel[3]
            min_x = min(min_x, x)
            min_y = min(min_y, y)
            max_x = max(max_x, x)
            max_y = max(max_y, y)
            span = rows.setdefault(y, [x, x])
            if x < span[0]:
                span[0] = x
            if x > span[1]:
                span[1] = x
            for nx, ny in neighbors4(x, y, width, height):
                next_index = ny * width + nx
                if mask[next_index] and not visited[next_index]:
                    visited[next_index] = 1
                    queue.append(next_index)
        count = len(coords)
        components.append(
            {
                "coords": coords,
                "rows": rows,
                "bbox": {
                    "x": min_x,
                    "y": min_y,
                    "width": max(1, max_x - min_x + 1),
                    "height": max(1, max_y - min_y + 1),
                },
                "pixelCount": count,
                "meanColor": (
                    int(round(sum_r / count)),
                    int(round(sum_g / count)),
                    int(round(sum_b / count)),
                    int(round(sum_a / count)),
                ),
            }
        )
    return components


def merge_components(components: Sequence[Dict[str, Any]], gap: int) -> List[Dict[str, Any]]:
    if len(components) < 2:
        return list(components)
    parent = list(range(len(components)))

    def find(index: int) -> int:
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    def union(left: int, right: int) -> None:
        root_left = find(left)
        root_right = find(right)
        if root_left != root_right:
            parent[root_right] = root_left

    for left in range(len(components)):
        for right in range(left + 1, len(components)):
            if should_merge(components[left]["bbox"], components[right]["bbox"], gap):
                union(left, right)

    groups: Dict[int, List[Dict[str, Any]]] = {}
    for index, component in enumerate(components):
        groups.setdefault(find(index), []).append(component)
    return [merge_component_group(group) for group in groups.values()]


def should_merge(left: Dict[str, int], right: Dict[str, int], gap: int) -> bool:
    left_right = left["x"] + left["width"]
    left_bottom = left["y"] + left["height"]
    right_right = right["x"] + right["width"]
    right_bottom = right["y"] + right["height"]
    horizontal_gap = max(0, max(left["x"], right["x"]) - min(left_right, right_right))
    vertical_gap = max(0, max(left["y"], right["y"]) - min(left_bottom, right_bottom))
    return horizontal_gap <= gap and vertical_gap <= gap


def merge_component_group(group: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    coords: List[Tuple[int, int]] = []
    rows: Dict[int, List[int]] = {}
    min_x = min(component["bbox"]["x"] for component in group)
    min_y = min(component["bbox"]["y"] for component in group)
    max_x = max(component["bbox"]["x"] + component["bbox"]["width"] - 1 for component in group)
    max_y = max(component["bbox"]["y"] + component["bbox"]["height"] - 1 for component in group)
    sum_r = sum(component["meanColor"][0] * component["pixelCount"] for component in group)
    sum_g = sum(component["meanColor"][1] * component["pixelCount"] for component in group)
    sum_b = sum(component["meanColor"][2] * component["pixelCount"] for component in group)
    sum_a = sum(component["meanColor"][3] * component["pixelCount"] for component in group)
    total_pixels = sum(component["pixelCount"] for component in group)
    for component in group:
        coords.extend(component["coords"])
        for y, span in component["rows"].items():
            current = rows.setdefault(y, [span[0], span[1]])
            current[0] = min(current[0], span[0])
            current[1] = max(current[1], span[1])
    return {
        "coords": coords,
        "rows": rows,
        "bbox": {
            "x": min_x,
            "y": min_y,
            "width": max(1, max_x - min_x + 1),
            "height": max(1, max_y - min_y + 1),
        },
        "pixelCount": total_pixels,
        "meanColor": (
            int(round(sum_r / total_pixels)),
            int(round(sum_g / total_pixels)),
            int(round(sum_b / total_pixels)),
            int(round(sum_a / total_pixels)),
        ),
    }


def component_to_region(
    component: Dict[str, Any],
    background: Tuple[int, int, int, int],
    threshold: float,
    mode: str,
    args: argparse.Namespace,
    min_width: int,
    min_height: int,
    min_pixel_area: int,
    bbox_padding: int,
    score_floor: float,
    sample_target: int,
    simplify_tolerance: int,
) -> Optional[Dict[str, Any]]:
    bbox = component["bbox"]
    width = bbox["width"]
    height = bbox["height"]
    pixel_count = component["pixelCount"]
    if width < min_width or height < min_height or pixel_count < min_pixel_area:
        return None

    area = max(1, width * height)
    density = pixel_count / float(area)
    contrast = weighted_color_distance(component["meanColor"], background) / max(1.0, threshold * 2.2)
    size_ratio = min(1.0, pixel_count / float(max(min_pixel_area, 1) * 8))
    alpha_boost = component["meanColor"][3] / 255.0
    score = clamp(density * 0.45 + contrast * 0.35 + size_ratio * 0.10 + alpha_boost * 0.10, 0.0, 0.99)
    if args.remove_small_regions and score < score_floor:
        return None

    points = polygon_from_rows(component["rows"], sample_target, simplify_tolerance)
    if not points:
        points = rect_points(bbox)

    padded_bbox = pad_bbox(points, bbox_padding)
    if padded_bbox["width"] < min_width or padded_bbox["height"] < min_height:
        return None

    return {
        "bbox": padded_bbox,
        "x": padded_bbox["x"],
        "y": padded_bbox["y"],
        "width": padded_bbox["width"],
        "height": padded_bbox["height"],
        "points": points,
        "score": round(float(score), 4),
        "pixelCount": pixel_count,
        "mode": mode,
    }


def polygon_from_rows(rows: Dict[int, List[int]], sample_target: int, simplify_tolerance: int) -> List[Dict[str, int]]:
    ordered = sorted(rows.items())
    if not ordered:
        return []
    step = max(1, len(ordered) // max(12, sample_target))
    sampled = ordered[::step]
    if sampled[-1] != ordered[-1]:
        sampled.append(ordered[-1])
    left_side = [{"x": span[0], "y": y} for y, span in sampled]
    right_side = [{"x": span[1] + 1, "y": y + 1} for y, span in reversed(sampled)]
    points = dedupe_adjacent(left_side + right_side)
    if len(points) < 4:
        first_y, first_span = ordered[0]
        last_y, last_span = ordered[-1]
        return rect_points(
            {
                "x": min(first_span[0], last_span[0]),
                "y": first_y,
                "width": max(first_span[1], last_span[1]) - min(first_span[0], last_span[0]) + 1,
                "height": last_y - first_y + 1,
            }
        )
    return simplify_polygon(points, simplify_tolerance)


def simplify_polygon(points: List[Dict[str, int]], tolerance: int) -> List[Dict[str, int]]:
    if len(points) <= 4 or tolerance <= 0:
        return points
    simplified = [points[0]]
    for index in range(1, len(points) - 1):
        previous = simplified[-1]
        current = points[index]
        following = points[index + 1]
        if is_collinear(previous, current, following, tolerance):
            continue
        simplified.append(current)
    simplified.append(points[-1])
    return dedupe_adjacent(simplified)


def is_collinear(
    previous: Dict[str, int],
    current: Dict[str, int],
    following: Dict[str, int],
    tolerance: int,
) -> bool:
    left_x = current["x"] - previous["x"]
    left_y = current["y"] - previous["y"]
    right_x = following["x"] - current["x"]
    right_y = following["y"] - current["y"]
    cross = abs(left_x * right_y - left_y * right_x)
    return cross <= tolerance


def dedupe_adjacent(points: Iterable[Dict[str, int]]) -> List[Dict[str, int]]:
    output: List[Dict[str, int]] = []
    for point in points:
        if not output or output[-1] != point:
            output.append(point)
    if len(output) > 1 and output[0] == output[-1]:
        output.pop()
    return output


def rect_points(bbox: Dict[str, int]) -> List[Dict[str, int]]:
    x = int(bbox["x"])
    y = int(bbox["y"])
    width = max(1, int(bbox["width"]))
    height = max(1, int(bbox["height"]))
    return [
        {"x": x, "y": y},
        {"x": x + width, "y": y},
        {"x": x + width, "y": y + height},
        {"x": x, "y": y + height},
    ]


def pad_bbox(points: Sequence[Dict[str, int]], padding: int) -> Dict[str, int]:
    xs = [point["x"] for point in points]
    ys = [point["y"] for point in points]
    min_x = min(xs) - max(0, padding)
    min_y = min(ys) - max(0, padding)
    max_x = max(xs) + max(0, padding)
    max_y = max(ys) + max(0, padding)
    return {
        "x": min_x,
        "y": min_y,
        "width": max(1, max_x - min_x),
        "height": max(1, max_y - min_y),
    }


def resolve_default_int(value: int, default_value: int, model_value: Any) -> int:
    if value != default_value:
        return int(value)
    try:
        return int(model_value)
    except (TypeError, ValueError):
        return int(value)


def resolve_default_float(value: float, default_value: float, model_value: Any) -> float:
    if abs(float(value) - float(default_value)) > 1e-6:
        return float(value)
    try:
        return float(model_value)
    except (TypeError, ValueError):
        return float(value)


def neighbors4(x: int, y: int, width: int, height: int) -> Iterable[Tuple[int, int]]:
    if x > 0:
        yield x - 1, y
    if x + 1 < width:
        yield x + 1, y
    if y > 0:
        yield x, y - 1
    if y + 1 < height:
        yield x, y + 1


def neighbors8(x: int, y: int, width: int, height: int) -> Iterable[Tuple[int, int]]:
    for ny in range(max(0, y - 1), min(height, y + 2)):
        for nx in range(max(0, x - 1), min(width, x + 2)):
            if nx == x and ny == y:
                continue
            yield nx, ny


if __name__ == "__main__":
    raise SystemExit(main())

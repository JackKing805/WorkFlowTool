from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from offline_common import (
    argb_to_rgba,
    estimate_edge_background,
    load_image_rgba,
    rgba_to_argb,
    weighted_color_distance,
    write_jsonl,
)


SEED_SPECS: Dict[str, Dict[str, Any]] = {
    "icons.png": {
        "detect": {
            "min_width": 40,
            "min_height": 40,
            "min_pixel_area": 600,
            "color_distance_threshold": 28,
        },
        "min_score": 0.75,
    },
    "icons2.png": {
        "detect": {
            "min_width": 40,
            "min_height": 40,
            "min_pixel_area": 600,
            "color_distance_threshold": 28,
        },
        "min_score": 0.75,
    },
    "test.png": {
        "detect": {
            "min_width": 40,
            "min_height": 40,
            "min_pixel_area": 600,
            "color_distance_threshold": 28,
        },
        "min_score": 0.40,
    },
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Bootstrap offline seed datasets and pretrain local models.")
    parser.add_argument("--skip-train", action="store_true")
    parser.add_argument("--clean", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parent
    training_root = root / "training_sets"
    seed_pretrain_file = training_root / "seed_pretrain" / "annotations.jsonl"
    magic_seed_file = training_root / "magic_seed" / "annotations.jsonl"
    background_seed_file = training_root / "background_seed" / "annotations.jsonl"

    if args.clean:
        for file in (seed_pretrain_file, magic_seed_file, background_seed_file):
            if file.exists():
                file.unlink()

    seed_records: List[Dict[str, Any]] = []
    magic_records: List[Dict[str, Any]] = []
    background_records: List[Dict[str, Any]] = []
    summaries: List[Dict[str, Any]] = []

    for image_path in sorted((root / "seed_images").glob("*.png")):
        spec = SEED_SPECS.get(image_path.name)
        if spec is None:
            continue
        detection = run_seed_detection(root, image_path, spec["detect"])
        filtered_regions = [
            region for region in detection["regions"]
            if float(region.get("score") or 0.0) >= float(spec.get("min_score") or 0.0)
        ]
        if not filtered_regions:
            continue

        width, height, pixels = load_image_rgba(image_path)
        detected_background = argb_to_rgba(int(detection["stats"]["estimatedBackgroundArgb"]))
        seed_records.append(
            build_seed_pretrain_record(image_path, filtered_regions)
        )
        magic_records.extend(
            build_magic_seed_records(
                image_path=image_path,
                image_width=width,
                image_height=height,
                pixels=pixels,
                regions=filtered_regions,
                background=detected_background,
                foreground_threshold=max(16.0, float(spec["detect"]["color_distance_threshold"]) * 0.5),
            )
        )
        background_records.append(
            build_background_seed_record(image_path, width, height, pixels)
        )
        summaries.append(
            {
                "image": image_path.name,
                "regionCount": len(filtered_regions),
                "magicSeeds": sum(1 for record in magic_records if record["image"].endswith(image_path.name)),
            }
        )

    write_jsonl(seed_pretrain_file, seed_records)
    write_jsonl(magic_seed_file, magic_records)
    write_jsonl(background_seed_file, background_records)

    payload: Dict[str, Any] = {
        "seedPretrainSamples": len(seed_records),
        "seedInstances": sum(len(record["instances"]) for record in seed_records),
        "magicSeedRecords": len(magic_records),
        "backgroundSeedRecords": len(background_records),
        "images": summaries,
    }

    if not args.skip_train:
        payload["commands"] = run_training_pipeline(root)
        payload["pythonValidation"] = validate_python_detector(root)

    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


def run_seed_detection(root: Path, image_path: Path, detect_spec: Dict[str, Any]) -> Dict[str, Any]:
    command = [
        sys.executable,
        str(root / "detect_icons.py"),
        "--image",
        str(image_path),
        "--min-width",
        str(detect_spec["min_width"]),
        "--min-height",
        str(detect_spec["min_height"]),
        "--min-pixel-area",
        str(detect_spec["min_pixel_area"]),
        "--color-distance-threshold",
        str(detect_spec["color_distance_threshold"]),
    ]
    env = dict(os.environ)
    env.setdefault("PYTHONPYCACHEPREFIX", str(root / ".pycache"))
    output = subprocess.check_output(command, cwd=root, env=env, text=True)
    return json.loads(output)


def build_seed_pretrain_record(image_path: Path, regions: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    relative_image = f"../../seed_images/{image_path.name}"
    return {
        "image": relative_image,
        "imageHash": file_sha256(image_path),
        "instances": [
            {
                "bbox": dict(region["bbox"]),
                "points": [dict(point) for point in region.get("points") or []],
                "label": "icon",
            }
            for region in regions
        ],
    }


def build_magic_seed_records(
    *,
    image_path: Path,
    image_width: int,
    image_height: int,
    pixels: Sequence[Tuple[int, int, int, int]],
    regions: Sequence[Dict[str, Any]],
    background: Tuple[int, int, int, int],
    foreground_threshold: float,
) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    relative_image = f"../../seed_images/{image_path.name}"
    for region in regions:
        bbox = region["bbox"]
        points = region.get("points") or []
        seed = choose_seed_pixel(
            bbox=bbox,
            points=points,
            image_width=image_width,
            image_height=image_height,
            pixels=pixels,
            background=background,
            foreground_threshold=foreground_threshold,
        )
        if seed is None:
            continue
        seed_x, seed_y = seed
        tolerance = estimate_magic_tolerance(
            bbox=bbox,
            points=points,
            image_width=image_width,
            image_height=image_height,
            pixels=pixels,
            background=background,
            foreground_threshold=foreground_threshold,
            seed_x=seed_x,
            seed_y=seed_y,
        )
        records.append(
            {
                "image": relative_image,
                "seedX": seed_x,
                "seedY": seed_y,
                "tolerance": tolerance,
                "region": dict(bbox),
            }
        )
    return dedupe_records(records)


def build_background_seed_record(
    image_path: Path,
    width: int,
    height: int,
    pixels: Sequence[Tuple[int, int, int, int]],
) -> Dict[str, Any]:
    background, _, _ = estimate_edge_background(
        pixels,
        width,
        height,
        edge_sample_width=2,
        alpha_threshold=8,
    )
    argb = rgba_to_argb(background)
    return {"edgeArgb": argb, "backgroundArgb": argb, "image": f"../../seed_images/{image_path.name}"}


def choose_seed_pixel(
    *,
    bbox: Dict[str, int],
    points: Sequence[Dict[str, int]],
    image_width: int,
    image_height: int,
    pixels: Sequence[Tuple[int, int, int, int]],
    background: Tuple[int, int, int, int],
    foreground_threshold: float,
) -> Optional[Tuple[int, int]]:
    center_x = bbox["x"] + bbox["width"] / 2.0
    center_y = bbox["y"] + bbox["height"] / 2.0
    best: Optional[Tuple[float, float, int, int]] = None
    for x, y, pixel in iter_region_pixels(
        bbox=bbox,
        points=points,
        image_width=image_width,
        image_height=image_height,
        pixels=pixels,
    ):
        if pixel[3] <= 8:
            continue
        background_distance = weighted_color_distance(pixel, background)
        if background_distance <= foreground_threshold:
            continue
        center_distance = (x - center_x) ** 2 + (y - center_y) ** 2
        rank = (center_distance, -background_distance, x, y)
        if best is None or rank < best:
            best = rank
    if best is None:
        return None
    return int(best[2]), int(best[3])


def estimate_magic_tolerance(
    *,
    bbox: Dict[str, int],
    points: Sequence[Dict[str, int]],
    image_width: int,
    image_height: int,
    pixels: Sequence[Tuple[int, int, int, int]],
    background: Tuple[int, int, int, int],
    foreground_threshold: float,
    seed_x: int,
    seed_y: int,
) -> int:
    seed_color = pixels[seed_y * image_width + seed_x]
    distances: List[float] = []
    for _, _, pixel in iter_region_pixels(
        bbox=bbox,
        points=points,
        image_width=image_width,
        image_height=image_height,
        pixels=pixels,
    ):
        if pixel[3] <= 8:
            continue
        if weighted_color_distance(pixel, background) <= foreground_threshold:
            continue
        distances.append(weighted_color_distance(pixel, seed_color))
    if not distances:
        return 18
    distances.sort()
    median_value = distances[len(distances) // 2]
    percentile_index = min(len(distances) - 1, max(0, int(math.floor(len(distances) * 0.75))))
    percentile_value = distances[percentile_index]
    estimated = max(median_value, percentile_value * 0.85) + 3.0
    return int(max(10, min(48, round(estimated))))


def iter_region_pixels(
    *,
    bbox: Dict[str, int],
    points: Sequence[Dict[str, int]],
    image_width: int,
    image_height: int,
    pixels: Sequence[Tuple[int, int, int, int]],
) -> Iterable[Tuple[int, int, Tuple[int, int, int, int]]]:
    left = max(0, int(bbox["x"]))
    top = max(0, int(bbox["y"]))
    right = min(image_width, left + int(bbox["width"]))
    bottom = min(image_height, top + int(bbox["height"]))
    normalized_points = [
        (int(point["x"]), int(point["y"]))
        for point in points
        if isinstance(point, dict) and "x" in point and "y" in point
    ]
    for y in range(top, bottom):
        row_offset = y * image_width
        for x in range(left, right):
            if normalized_points and not contains_point(normalized_points, x + 0.5, y + 0.5):
                continue
            yield x, y, pixels[row_offset + x]


def contains_point(points: Sequence[Tuple[int, int]], x: float, y: float) -> bool:
    if len(points) < 3:
        return False
    inside = False
    previous_x, previous_y = points[-1]
    for current_x, current_y in points:
        intersects = ((current_y > y) != (previous_y > y)) and (
            x < (previous_x - current_x) * (y - current_y) / ((previous_y - current_y) or 1e-6) + current_x
        )
        if intersects:
            inside = not inside
        previous_x, previous_y = current_x, current_y
    return inside


def dedupe_records(records: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
    seen = set()
    output: List[Dict[str, Any]] = []
    for record in records:
        key = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        if key in seen:
            continue
        seen.add(key)
        output.append(record)
    return output


def run_training_pipeline(root: Path) -> List[Dict[str, Any]]:
    commands = [
        ["make_training_set.py"],
        ["train_icon_detector.py", "--dataset", "training_sets/combined", "--out", "model/combined", "--epochs", "4", "--imgsz", "512", "--batch", "2"],
        ["train_magic_model.py"],
        ["train_background_model.py"],
    ]
    output: List[Dict[str, Any]] = []
    for args in commands:
        command = [sys.executable, str(root / args[0]), *args[1:]]
        env = dict(os.environ)
        env.setdefault("PYTHONPYCACHEPREFIX", str(root / ".pycache"))
        result = subprocess.check_output(command, cwd=root, env=env, text=True).strip()
        output.append({"command": args, "output": json.loads(result)})
    return output


def validate_python_detector(root: Path) -> List[Dict[str, Any]]:
    validation: List[Dict[str, Any]] = []
    for image_path in sorted((root / "seed_images").glob("*.png")):
        command = [sys.executable, str(root / "detect_icons.py"), "--image", str(image_path)]
        env = dict(os.environ)
        env.setdefault("PYTHONPYCACHEPREFIX", str(root / ".pycache"))
        payload = json.loads(subprocess.check_output(command, cwd=root, env=env, text=True))
        validation.append(
            {
                "image": image_path.name,
                "regions": len(payload.get("regions") or []),
                "mode": payload.get("mode"),
                "backgroundSampleCount": payload.get("stats", {}).get("backgroundSampleCount"),
            }
        )
    return validation


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())

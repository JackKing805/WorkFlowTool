from __future__ import annotations

import argparse
import json
import statistics
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Tuple

from offline_common import canonicalize_record, estimate_edge_background, load_image_rgba, read_jsonl, write_json, weighted_color_distance


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train the offline icon detector manifest.")
    parser.add_argument("--dataset", default="training_sets/combined")
    parser.add_argument("--out", default="model/combined")
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--imgsz", type=int, default=512)
    parser.add_argument("--batch", type=int, default=2)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parent
    dataset_root = resolve_under(root, args.dataset)
    output_root = resolve_under(root, args.out)
    annotation_file = dataset_root / "annotations.jsonl"

    records = [
        record
        for raw_record in read_jsonl(annotation_file)
        for record in [canonicalize_record(raw_record, dataset_root)]
        if record is not None
    ]
    model = build_model(records, args)
    write_json(output_root / "model.json", model)
    print(json.dumps({"samples": model["summary"]["sampleCount"], "output": str(output_root / "model.json")}, ensure_ascii=False))
    return 0


def resolve_under(root: Path, value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = (root / path).resolve()
    return path


def build_model(records: List[Dict[str, Any]], args: argparse.Namespace) -> Dict[str, Any]:
    widths: List[int] = []
    heights: List[int] = []
    areas: List[int] = []
    point_counts: List[int] = []
    contrasts: List[float] = []
    hole_instances = 0

    for record in records:
        image_path = Path(record["image"])
        try:
            width, height, pixels = load_image_rgba(image_path)
            background, _, _ = estimate_edge_background(pixels, width, height, edge_sample_width=2, alpha_threshold=8)
        except Exception:
            background = (0, 0, 0, 0)
            pixels = []
            width = height = 0

        for instance in record["instances"]:
            if str(instance.get("label") or "").lower() in {"hole", "inner_hole", "cutout"}:
                hole_instances += 1
            bbox = instance["bbox"]
            widths.append(int(bbox["width"]))
            heights.append(int(bbox["height"]))
            areas.append(int(bbox["width"]) * int(bbox["height"]))
            point_counts.append(len(instance["points"]))
            if pixels and width > 0 and height > 0:
                mean = mean_bbox_color(pixels, width, height, bbox)
                contrasts.append(weighted_color_distance(mean, background))

    median_width = median_or(widths, 32)
    median_height = median_or(heights, 32)
    median_area = median_or(areas, 256)
    median_points = median_or(point_counts, 4)
    median_contrast = median_or(contrasts, 36.0)

    min_pixel_area = int(clamp_int(round(median_area * 0.13), 48, 2048))
    color_distance_threshold = int(clamp_int(round(median_contrast * 0.56), 16, 72))
    model = {
        "version": 1,
        "backend": "polygon_heuristic_v1",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "training": {
            "dataset": args.dataset,
            "epochs": args.epochs,
            "imgsz": args.imgsz,
            "batch": args.batch,
        },
        "defaults": {
            "alphaThreshold": 8,
            "edgeSampleWidth": 2,
            "bboxPadding": 1,
            "minPixelArea": min_pixel_area,
            "colorDistanceThreshold": color_distance_threshold,
            "minWidth": int(clamp_int(round(median_width * 0.64), 12, 160)),
            "minHeight": int(clamp_int(round(median_height * 0.64), 12, 160)),
        },
        "heuristics": {
            "distanceScale": round(clamp_float(median_contrast / 36.0, 0.75, 1.30), 3),
            "distanceOffset": round(clamp_float((median_contrast - color_distance_threshold) / 4.0, -8.0, 12.0), 3),
            "mergeGap": int(clamp_int(round(min(median_width, median_height) * 0.08), 2, 12)),
            "scoreFloor": round(clamp_float(0.18 + min(0.04, median_contrast / 1000.0), 0.14, 0.22), 3),
            "rowSampleTarget": int(clamp_int(median_points * 4, 16, 96)),
            "polygonSimplifyTolerance": int(clamp_int(round(max(median_width, median_height) / 48.0), 1, 4)),
            "detectInteriorHoles": True,
            "holeMinAreaRatio": 0.125,
            "holeMaxAreaRatio": 0.72,
        },
        "summary": {
            "sampleCount": len(records),
            "instanceCount": sum(len(record["instances"]) for record in records),
            "holeInstanceCount": hole_instances,
            "medianWidth": median_width,
            "medianHeight": median_height,
            "medianArea": median_area,
            "medianPointCount": median_points,
            "medianContrast": round(float(median_contrast), 3),
        },
    }
    return model


def mean_bbox_color(
    pixels: List[Tuple[int, int, int, int]],
    width: int,
    height: int,
    bbox: Dict[str, int],
) -> Tuple[int, int, int, int]:
    left = max(0, int(bbox["x"]))
    top = max(0, int(bbox["y"]))
    right = min(width, left + int(bbox["width"]))
    bottom = min(height, top + int(bbox["height"]))
    values: List[Tuple[int, int, int, int]] = []
    for y in range(top, bottom):
        row_offset = y * width
        for x in range(left, right):
            values.append(pixels[row_offset + x])
    if not values:
        return (0, 0, 0, 0)
    total_r = total_g = total_b = total_a = 0
    for red, green, blue, alpha in values:
        total_r += red
        total_g += green
        total_b += blue
        total_a += alpha
    count = len(values)
    return (
        int(round(total_r / count)),
        int(round(total_g / count)),
        int(round(total_b / count)),
        int(round(total_a / count)),
    )


def median_or(values: List[Any], fallback: Any) -> Any:
    return statistics.median(values) if values else fallback


def clamp_int(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(maximum, int(value)))


def clamp_float(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, float(value)))


if __name__ == "__main__":
    raise SystemExit(main())

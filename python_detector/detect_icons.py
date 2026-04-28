from __future__ import annotations

import argparse
import json
import time
from collections import deque
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

import numpy as np
import onnxruntime as ort
from PIL import Image


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run first-run trained ONNX icon segmentation.")
    parser.add_argument("--image", required=True)
    parser.add_argument("--model", default="model/instance_segmentation/model.onnx")
    parser.add_argument("--score-threshold", type=float, default=0.35)
    parser.add_argument("--mask-threshold", type=float, default=0.5)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    started_at = time.perf_counter()
    root = Path(__file__).resolve().parent
    image_path = Path(args.image)
    model_path = resolve_under(root, args.model)
    metadata = load_metadata(model_path)
    score_threshold = float(metadata.get("scoreThreshold", args.score_threshold))
    mask_threshold = float(metadata.get("maskThreshold", args.mask_threshold))

    image = Image.open(image_path).convert("RGBA")
    source_array = np.asarray(image, dtype=np.float32)
    source_height, source_width = source_array.shape[:2]
    image_array = pad_to_multiple(source_array, 4).transpose(2, 0, 1)[None, :, :, :] / 255.0
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    logits = session.run(None, {input_name: image_array})[0]
    probabilities = 1.0 / (1.0 + np.exp(-np.asarray(logits[0, 0], dtype=np.float32)))
    probabilities = probabilities[:source_height, :source_width]
    mask = probabilities >= mask_threshold
    image_pixels = np.asarray(image, dtype=np.uint8)
    background = estimate_edge_background(image_pixels)
    components = extract_components(mask)
    regions = [
        region
        for component in components
        for region in component_to_regions(component, probabilities, image_pixels, background, score_threshold)
    ]
    regions.sort(key=lambda region: (region["bbox"]["y"], region["bbox"]["x"]))
    total_time_ms = int(round((time.perf_counter() - started_at) * 1000.0))
    print(
        json.dumps(
            {
                "mode": "alpha_mask",
                "regions": regions,
                "stats": {
                    "estimatedBackgroundArgb": 0,
                    "candidatePixels": int(mask.sum()),
                    "connectedComponents": len(components),
                    "regionCount": len(regions),
                    "backgroundSampleCount": 0,
                    "totalTimeMs": total_time_ms,
                    "backend": "mask_rcnn_onnx",
                },
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
    )
    return 0


def resolve_under(root: Path, value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = (root / path).resolve()
    return path


def load_metadata(model_path: Path) -> Dict[str, object]:
    metadata_path = model_path.with_suffix(".json")
    if not metadata_path.exists():
        return {}
    try:
        payload = json.loads(metadata_path.read_text("utf-8"))
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def pad_to_multiple(array: np.ndarray, multiple: int) -> np.ndarray:
    height, width = array.shape[:2]
    next_height = ((height + multiple - 1) // multiple) * multiple
    next_width = ((width + multiple - 1) // multiple) * multiple
    if next_height == height and next_width == width:
        return array
    padded = np.zeros((next_height, next_width, array.shape[2]), dtype=array.dtype)
    padded[:height, :width, :] = array
    if next_height > height:
        padded[height:, :width, :] = array[height - 1 : height, :, :]
    if next_width > width:
        padded[:, width:, :] = padded[:, width - 1 : width, :]
    return padded


def extract_components(mask: np.ndarray) -> List[List[Tuple[int, int]]]:
    height, width = mask.shape
    visited = np.zeros(mask.shape, dtype=np.bool_)
    components: List[List[Tuple[int, int]]] = []
    for y in range(height):
        for x in range(width):
            if not mask[y, x] or visited[y, x]:
                continue
            queue: deque[Tuple[int, int]] = deque([(x, y)])
            visited[y, x] = True
            coords: List[Tuple[int, int]] = []
            while queue:
                cx, cy = queue.popleft()
                coords.append((cx, cy))
                for nx, ny in neighbors4(cx, cy, width, height):
                    if mask[ny, nx] and not visited[ny, nx]:
                        visited[ny, nx] = True
                        queue.append((nx, ny))
            components.append(coords)
    return components


def component_to_regions(
    coords: Sequence[Tuple[int, int]],
    probabilities: np.ndarray,
    image_pixels: np.ndarray,
    background: Tuple[int, int, int, int],
    score_threshold: float,
) -> List[Dict[str, object]]:
    if len(coords) < 12:
        return []
    xs = [coord[0] for coord in coords]
    ys = [coord[1] for coord in coords]
    left = min(xs)
    top = min(ys)
    right = max(xs) + 1
    bottom = max(ys) + 1
    width = right - left
    height = bottom - top
    if width <= 1 or height <= 1:
        return []
    score = float(np.mean([probabilities[y, x] for x, y in coords]))
    if score < score_threshold:
        return []
    component_mask = np.zeros((height, width), dtype=np.bool_)
    for x, y in coords:
        component_mask[y - top, x - left] = True
    alpha = refine_alpha_mask(component_mask, probabilities[top:bottom, left:right], image_pixels[top:bottom, left:right], background)
    regions: List[Dict[str, object]] = []
    for local_component in extract_components(alpha > 0):
        local_alpha = np.zeros(alpha.shape, dtype=np.uint8)
        for x, y in local_component:
            local_alpha[y, x] = alpha[y, x]
        trimmed = trim_alpha_mask(local_alpha)
        if trimmed is None:
            continue
        trim_left, trim_top, region_alpha = trimmed
        region_height, region_width = region_alpha.shape
        if int((region_alpha > 0).sum()) < 12:
            continue
        regions.append(
            {
                "bbox": {"x": left + trim_left, "y": top + trim_top, "width": region_width, "height": region_height},
                "maskWidth": region_width,
                "maskHeight": region_height,
                "alphaMask": region_alpha.reshape(-1).astype(int).tolist(),
                "score": round(score, 4),
            }
        )
    return regions


def neighbors4(x: int, y: int, width: int, height: int) -> Iterable[Tuple[int, int]]:
    if x > 0:
        yield x - 1, y
    if x + 1 < width:
        yield x + 1, y
    if y > 0:
        yield x, y - 1
    if y + 1 < height:
        yield x, y + 1


def refine_alpha_mask(
    component_mask: np.ndarray,
    probabilities: np.ndarray,
    pixels: np.ndarray,
    background: Tuple[int, int, int, int],
) -> np.ndarray:
    alpha_channel = pixels[:, :, 3].astype(np.int16)
    transparent_foreground = alpha_channel > 8
    has_transparency = float((alpha_channel <= 8).sum()) / float(alpha_channel.size) >= 0.10
    if has_transparency:
        foreground = transparent_foreground
    else:
        distances = color_distance(pixels, background)
        foreground = distances > 22.0
    refined = component_mask & foreground
    if refined.sum() < max(8, int(component_mask.sum() * 0.08)):
        refined = component_mask
    alpha = np.zeros(component_mask.shape, dtype=np.uint8)
    soft = np.clip(probabilities * 255.0, 0, 255).astype(np.uint8)
    alpha[refined] = np.maximum(soft[refined], 180)
    return alpha


def trim_alpha_mask(alpha: np.ndarray) -> Tuple[int, int, np.ndarray] | None:
    ys, xs = np.nonzero(alpha > 0)
    if xs.size == 0 or ys.size == 0:
        return None
    left = int(xs.min())
    top = int(ys.min())
    right = int(xs.max()) + 1
    bottom = int(ys.max()) + 1
    return left, top, alpha[top:bottom, left:right]


def estimate_edge_background(pixels: np.ndarray) -> Tuple[int, int, int, int]:
    height, width, _ = pixels.shape
    edge = max(1, min(3, width, height))
    samples = np.concatenate(
        [
            pixels[:edge, :, :].reshape(-1, 4),
            pixels[height - edge :, :, :].reshape(-1, 4),
            pixels[:, :edge, :].reshape(-1, 4),
            pixels[:, width - edge :, :].reshape(-1, 4),
        ],
        axis=0,
    )
    if samples.size == 0:
        return (0, 0, 0, 0)
    opaque = samples[samples[:, 3] > 8]
    source = opaque if len(opaque) else samples
    buckets = (source.astype(np.uint16) // np.array([16, 16, 16, 32], dtype=np.uint16)).astype(np.uint16)
    keys, counts = np.unique(buckets, axis=0, return_counts=True)
    dominant_bucket = keys[int(np.argmax(counts))]
    dominant = source[np.all(buckets == dominant_bucket, axis=1)]
    mean = np.rint(dominant.mean(axis=0)).astype(np.uint8)
    return int(mean[0]), int(mean[1]), int(mean[2]), int(mean[3])


def color_distance(pixels: np.ndarray, background: Tuple[int, int, int, int]) -> np.ndarray:
    bg = np.asarray(background, dtype=np.int16)
    values = pixels.astype(np.int16)
    diff = np.abs(values - bg)
    return diff[:, :, 0] * 0.35 + diff[:, :, 1] * 0.50 + diff[:, :, 2] * 0.15 + diff[:, :, 3] * 0.45


if __name__ == "__main__":
    raise SystemExit(main())

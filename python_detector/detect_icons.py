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
    image_array = np.asarray(image, dtype=np.float32).transpose(2, 0, 1)[None, :, :, :] / 255.0
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    logits = session.run(None, {input_name: image_array})[0]
    probabilities = 1.0 / (1.0 + np.exp(-np.asarray(logits[0, 0], dtype=np.float32)))
    mask = probabilities >= mask_threshold
    components = extract_components(mask)
    regions = [
        region
        for component in components
        for region in [component_to_region(component, probabilities, score_threshold)]
        if region is not None
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


def component_to_region(
    coords: Sequence[Tuple[int, int]],
    probabilities: np.ndarray,
    score_threshold: float,
) -> Dict[str, object] | None:
    if len(coords) < 12:
        return None
    xs = [coord[0] for coord in coords]
    ys = [coord[1] for coord in coords]
    left = min(xs)
    top = min(ys)
    right = max(xs) + 1
    bottom = max(ys) + 1
    width = right - left
    height = bottom - top
    if width <= 1 or height <= 1:
        return None
    score = float(np.mean([probabilities[y, x] for x, y in coords]))
    if score < score_threshold:
        return None
    alpha = np.zeros((height, width), dtype=np.uint8)
    for x, y in coords:
        alpha[y - top, x - left] = int(round(float(probabilities[y, x]) * 255.0))
    return {
        "bbox": {"x": left, "y": top, "width": width, "height": height},
        "maskWidth": width,
        "maskHeight": height,
        "alphaMask": alpha.reshape(-1).astype(int).tolist(),
        "score": round(score, 4),
    }


def neighbors4(x: int, y: int, width: int, height: int) -> Iterable[Tuple[int, int]]:
    if x > 0:
        yield x - 1, y
    if x + 1 < width:
        yield x + 1, y
    if y > 0:
        yield x, y - 1
    if y + 1 < height:
        yield x, y + 1


if __name__ == "__main__":
    raise SystemExit(main())

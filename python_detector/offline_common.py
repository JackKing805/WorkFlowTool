from __future__ import annotations

import json
import struct
import zlib
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple


RGBA = Tuple[int, int, int, int]


def clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def to_signed_32(value: int) -> int:
    value &= 0xFFFFFFFF
    return value if value < 0x80000000 else value - 0x100000000


def rgba_to_argb(rgba: RGBA) -> int:
    r, g, b, a = rgba
    return to_signed_32((a << 24) | (r << 16) | (g << 8) | b)


def argb_to_rgba(value: int) -> RGBA:
    value &= 0xFFFFFFFF
    return (
        (value >> 16) & 0xFF,
        (value >> 8) & 0xFF,
        value & 0xFF,
        (value >> 24) & 0xFF,
    )


def weighted_color_distance(a: RGBA, b: RGBA) -> float:
    return (
        abs(a[0] - b[0]) * 0.35
        + abs(a[1] - b[1]) * 0.50
        + abs(a[2] - b[2]) * 0.15
        + abs(a[3] - b[3]) * 0.45
    )


def mean_rgba(values: Sequence[RGBA]) -> RGBA:
    if not values:
        return (0, 0, 0, 0)
    total_r = total_g = total_b = total_a = 0
    for r, g, b, a in values:
        total_r += r
        total_g += g
        total_b += b
        total_a += a
    count = float(len(values))
    return (
        int(round(total_r / count)),
        int(round(total_g / count)),
        int(round(total_b / count)),
        int(round(total_a / count)),
    )


def normalize_bbox(raw: Any) -> Optional[Dict[str, int]]:
    if not isinstance(raw, dict):
        return None
    try:
        x = int(round(float(raw["x"])))
        y = int(round(float(raw["y"])))
        width = max(1, int(round(float(raw["width"]))))
        height = max(1, int(round(float(raw["height"]))))
    except (KeyError, TypeError, ValueError):
        return None
    return {"x": x, "y": y, "width": width, "height": height}


def normalize_alpha_mask(raw: Any, width: int, height: int) -> List[int]:
    if not isinstance(raw, list) or len(raw) != width * height:
        return []
    output: List[int] = []
    for value in raw:
        try:
            output.append(max(0, min(255, int(round(float(value))))))
        except (TypeError, ValueError):
            return []
    if not any(value > 0 for value in output):
        return []
    return output


def canonicalize_instance(raw: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(raw, dict):
        return None
    bbox = normalize_bbox(raw.get("bbox")) or normalize_bbox(raw)
    if bbox is None:
        return None
    try:
        mask_width = int(raw["maskWidth"])
        mask_height = int(raw["maskHeight"])
    except (KeyError, TypeError, ValueError):
        return None
    alpha_mask = normalize_alpha_mask(raw.get("alphaMask"), mask_width, mask_height)
    if not alpha_mask:
        return None
    return {
        "bbox": bbox,
        "maskWidth": mask_width,
        "maskHeight": mask_height,
        "alphaMask": alpha_mask,
        "label": str(raw.get("label") or "icon"),
    }


def resolve_image_path(value: Any, dataset_root: Path) -> Optional[str]:
    if not isinstance(value, str) or not value.strip():
        return None
    image_path = Path(value)
    if not image_path.is_absolute():
        image_path = (dataset_root / image_path).resolve()
    else:
        image_path = image_path.resolve()
    return str(image_path)


def canonicalize_record(record: Dict[str, Any], dataset_root: Path) -> Optional[Dict[str, Any]]:
    if not isinstance(record, dict):
        return None
    image_path = resolve_image_path(record.get("image") or record.get("imagePath"), dataset_root)
    if image_path is None:
        return None
    instances = []
    raw_instances = record.get("instances")
    if isinstance(raw_instances, list):
        for raw in raw_instances:
            instance = canonicalize_instance(raw)
            if instance is not None:
                instances.append(instance)
    if not instances:
        return None
    return {
        "image": image_path,
        "imageHash": str(record.get("imageHash") or ""),
        "instances": instances,
    }


def read_jsonl(path: Path) -> List[Dict[str, Any]]:
    if not path.exists():
        return []
    records: List[Dict[str, Any]] = []
    for raw_line in path.read_text("utf-8").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        try:
            parsed = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            records.append(parsed)
    return records


def write_jsonl(path: Path, records: Iterable[Dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [json.dumps(record, ensure_ascii=False, separators=(",", ":")) for record in records]
    content = "\n".join(lines)
    if content:
        content += "\n"
    path.write_text(content, "utf-8")


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text("utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", "utf-8")


def estimate_edge_background(
    pixels: Sequence[RGBA],
    width: int,
    height: int,
    edge_sample_width: int,
    alpha_threshold: int,
) -> Tuple[RGBA, int, str]:
    if width <= 0 or height <= 0 or not pixels:
        return (0, 0, 0, 0), 0, "solid_background"
    edge = max(1, min(int(edge_sample_width), width, height))
    samples: List[RGBA] = []
    for y in range(height):
        for x in range(width):
            if x < edge or y < edge or x >= width - edge or y >= height - edge:
                samples.append(pixels[y * width + x])
    if not samples:
        return (0, 0, 0, 0), 0, "solid_background"
    transparent = [pixel for pixel in samples if pixel[3] <= alpha_threshold]
    transparent_ratio = len(transparent) / float(len(samples))
    mode = "alpha_mask" if transparent_ratio >= 0.60 else "solid_background"
    if mode == "alpha_mask":
        return (0, 0, 0, 0), len(samples), mode

    opaque_samples = [pixel for pixel in samples if pixel[3] > alpha_threshold]
    source = opaque_samples or samples
    buckets: Dict[Tuple[int, int, int, int], List[RGBA]] = {}
    for pixel in source:
        bucket = (
            (pixel[0] // 16) * 16,
            (pixel[1] // 16) * 16,
            (pixel[2] // 16) * 16,
            (pixel[3] // 32) * 32,
        )
        buckets.setdefault(bucket, []).append(pixel)
    dominant = max(buckets.values(), key=len)
    return mean_rgba(dominant), len(samples), mode


def load_image_rgba(path: Path) -> Tuple[int, int, List[RGBA]]:
    try:
        from PIL import Image  # type: ignore

        image = Image.open(path).convert("RGBA")
        width, height = image.size
        return width, height, list(image.getdata())
    except Exception:
        return load_png_rgba(path)


def load_png_rgba(path: Path) -> Tuple[int, int, List[RGBA]]:
    data = path.read_bytes()
    signature = b"\x89PNG\r\n\x1a\n"
    if not data.startswith(signature):
        raise ValueError(f"Unsupported image format for offline loader: {path}")

    width = height = 0
    bit_depth = color_type = interlace = None
    palette: Optional[List[Tuple[int, int, int]]] = None
    transparency: Optional[bytes] = None
    idat = bytearray()

    index = len(signature)
    while index + 8 <= len(data):
        chunk_length = struct.unpack(">I", data[index:index + 4])[0]
        index += 4
        chunk_type = data[index:index + 4]
        index += 4
        chunk_data = data[index:index + chunk_length]
        index += chunk_length + 4
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", chunk_data)
        elif chunk_type == b"PLTE":
            palette = [
                tuple(chunk_data[offset:offset + 3])
                for offset in range(0, len(chunk_data), 3)
            ]
        elif chunk_type == b"tRNS":
            transparency = bytes(chunk_data)
        elif chunk_type == b"IDAT":
            idat.extend(chunk_data)
        elif chunk_type == b"IEND":
            break

    if width <= 0 or height <= 0:
        raise ValueError(f"Invalid PNG size: {path}")
    if bit_depth != 8 or interlace not in (0, None):
        raise ValueError(f"Unsupported PNG encoding: {path}")

    channels_by_type = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
    if color_type not in channels_by_type:
        raise ValueError(f"Unsupported PNG color type: {color_type}")

    channels = channels_by_type[color_type]
    stride = width * channels
    raw = zlib.decompress(bytes(idat))
    rows: List[bytearray] = []
    previous = bytearray(stride)
    position = 0
    for _ in range(height):
        filter_type = raw[position]
        position += 1
        row = bytearray(raw[position:position + stride])
        position += stride
        rows.append(_unfilter_row(filter_type, row, previous, channels))
        previous = rows[-1]

    pixels: List[RGBA] = []
    for row in rows:
        if color_type == 6:
            for offset in range(0, len(row), 4):
                pixels.append((row[offset], row[offset + 1], row[offset + 2], row[offset + 3]))
        elif color_type == 2:
            for offset in range(0, len(row), 3):
                pixels.append((row[offset], row[offset + 1], row[offset + 2], 255))
        elif color_type == 0:
            for value in row:
                pixels.append((value, value, value, 255))
        elif color_type == 4:
            for offset in range(0, len(row), 2):
                value = row[offset]
                pixels.append((value, value, value, row[offset + 1]))
        elif color_type == 3:
            if palette is None:
                raise ValueError(f"Missing PNG palette: {path}")
            alpha_map = list(transparency or b"")
            for palette_index in row:
                red, green, blue = palette[palette_index]
                alpha = alpha_map[palette_index] if palette_index < len(alpha_map) else 255
                pixels.append((red, green, blue, alpha))
    return width, height, pixels


def _unfilter_row(filter_type: int, row: bytearray, previous: bytearray, bpp: int) -> bytearray:
    output = bytearray(len(row))
    for index, value in enumerate(row):
        left = output[index - bpp] if index >= bpp else 0
        up = previous[index] if index < len(previous) else 0
        up_left = previous[index - bpp] if index >= bpp and index - bpp < len(previous) else 0
        if filter_type == 0:
            output[index] = value
        elif filter_type == 1:
            output[index] = (value + left) & 0xFF
        elif filter_type == 2:
            output[index] = (value + up) & 0xFF
        elif filter_type == 3:
            output[index] = (value + ((left + up) // 2)) & 0xFF
        elif filter_type == 4:
            output[index] = (value + _paeth(left, up, up_left)) & 0xFF
        else:
            raise ValueError(f"Unsupported PNG filter: {filter_type}")
    return output


def _paeth(left: int, up: int, up_left: int) -> int:
    prediction = left + up - up_left
    distance_left = abs(prediction - left)
    distance_up = abs(prediction - up)
    distance_up_left = abs(prediction - up_left)
    if distance_left <= distance_up and distance_left <= distance_up_left:
        return left
    if distance_up <= distance_up_left:
        return up
    return up_left

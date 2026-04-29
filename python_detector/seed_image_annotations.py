from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

from PIL import Image

RGBA = Tuple[int, int, int, int]
SUPPORTED_SEED_IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp"}


@dataclass(frozen=True)
class Component:
    points: List[Tuple[int, int]]
    left: int
    top: int
    right: int
    bottom: int


def seed_image_records(seed_root: Path) -> List[Dict[str, object]]:
    if not seed_root.exists():
        return []
    records: List[Dict[str, object]] = []
    for image_path in sorted(seed_root.rglob("*")):
        if not image_path.is_file() or image_path.suffix.lower() not in SUPPORTED_SEED_IMAGE_EXTENSIONS:
            continue
        instances = auto_instances_for_image(image_path)
        if instances:
            records.append({"image": str(image_path.resolve()), "imageHash": "", "instances": instances})
    return records


def auto_instances_for_image(image_path: Path) -> List[Dict[str, object]]:
    try:
        image = Image.open(image_path).convert("RGBA")
    except OSError:
        return []
    width, height = image.size
    pixels = list(image.getdata())
    min_area = max(12, int(width * height * 0.0002))
    background = estimate_background(pixels, width, height)
    mask = foreground_mask(pixels, background)
    return [
        instance
        for component in connected_components(mask, width, height)
        for instance in [component_to_instance(component, pixels, width, min_area)]
        if instance is not None
    ]


def component_to_instance(
    component: Component,
    pixels: Sequence[RGBA],
    image_width: int,
    min_area: int,
) -> Dict[str, object] | None:
    if len(component.points) < min_area:
        return None
    mask_width = component.right - component.left
    mask_height = component.bottom - component.top
    if mask_width <= 1 or mask_height <= 1:
        return None
    alpha = [0] * (mask_width * mask_height)
    for x, y in component.points:
        source_alpha = pixels[y * image_width + x][3]
        alpha[(y - component.top) * mask_width + (x - component.left)] = max(180, source_alpha)
    return {
        "bbox": {"x": component.left, "y": component.top, "width": mask_width, "height": mask_height},
        "maskWidth": mask_width,
        "maskHeight": mask_height,
        "alphaMask": alpha,
        "label": "icon",
    }


def estimate_background(pixels: Sequence[RGBA], width: int, height: int) -> RGBA:
    edge = max(1, min(4, width, height))
    samples: List[RGBA] = []
    for y in range(height):
        for x in range(width):
            if x < edge or y < edge or x >= width - edge or y >= height - edge:
                samples.append(pixels[y * width + x])
    if not samples:
        return (0, 0, 0, 0)
    buckets: Dict[Tuple[int, int, int, int], List[RGBA]] = {}
    for pixel in samples:
        bucket = tuple((channel // 16) * 16 for channel in pixel)
        buckets.setdefault(bucket, []).append(pixel)
    bucket_pixels = max(buckets.values(), key=len)
    count = float(len(bucket_pixels))
    return (
        int(sum(pixel[0] for pixel in bucket_pixels) / count),
        int(sum(pixel[1] for pixel in bucket_pixels) / count),
        int(sum(pixel[2] for pixel in bucket_pixels) / count),
        int(sum(pixel[3] for pixel in bucket_pixels) / count),
    )


def foreground_mask(pixels: Sequence[RGBA], background: RGBA) -> List[bool]:
    if background[3] <= 16:
        return [pixel[3] > 16 for pixel in pixels]
    return [pixel[3] > 16 and color_distance(pixel, background) > 32.0 for pixel in pixels]


def color_distance(a: RGBA, b: RGBA) -> float:
    return (
        abs(a[0] - b[0]) * 0.35
        + abs(a[1] - b[1]) * 0.50
        + abs(a[2] - b[2]) * 0.15
        + abs(a[3] - b[3]) * 0.35
    )


def connected_components(mask: Sequence[bool], width: int, height: int) -> List[Component]:
    visited = [False] * len(mask)
    output: List[Component] = []
    for y in range(height):
        for x in range(width):
            index = y * width + x
            if not mask[index] or visited[index]:
                continue
            output.append(collect_component(mask, visited, x, y, width, height))
    return output


def collect_component(
    mask: Sequence[bool],
    visited: List[bool],
    start_x: int,
    start_y: int,
    width: int,
    height: int,
) -> Component:
    queue: deque[Tuple[int, int]] = deque([(start_x, start_y)])
    visited[start_y * width + start_x] = True
    points: List[Tuple[int, int]] = []
    left = right = start_x
    top = bottom = start_y
    while queue:
        x, y = queue.popleft()
        points.append((x, y))
        left = min(left, x)
        top = min(top, y)
        right = max(right, x)
        bottom = max(bottom, y)
        for nx, ny in neighbors4(x, y, width, height):
            next_index = ny * width + nx
            if mask[next_index] and not visited[next_index]:
                visited[next_index] = True
                queue.append((nx, ny))
    return Component(points=points, left=left, top=top, right=right + 1, bottom=bottom + 1)


def neighbors4(x: int, y: int, width: int, height: int) -> Iterable[Tuple[int, int]]:
    if x > 0:
        yield x - 1, y
    if x + 1 < width:
        yield x + 1, y
    if y > 0:
        yield x, y - 1
    if y + 1 < height:
        yield x, y + 1

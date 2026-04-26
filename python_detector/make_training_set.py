#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from subprocess import check_output

from PIL import Image

SCRIPT_DIR = Path(__file__).resolve().parent


def run_detector(image, disable_grid=False):
    command = [
        "python",
        str(SCRIPT_DIR / "detect_icons.py"),
        "--image",
        str(image),
        "--min-width",
        "20",
        "--min-height",
        "20",
        "--min-pixel-area",
        "200",
        "--bbox-padding",
        "3",
        "--merge-nearby-regions",
        "--disable-model",
    ]
    if disable_grid:
        command.append("--disable-grid")
    return json.loads(check_output(command, encoding="utf-8"))


def coarse_grid(image, columns, rows, inset=8):
    width, height = Image.open(image).size
    cell_w = width / columns
    cell_h = height / rows
    regions = []
    for row in range(rows):
        for col in range(columns):
            x = round(col * cell_w + inset)
            y = round(row * cell_h + inset)
            right = round((col + 1) * cell_w - inset)
            bottom = round((row + 1) * cell_h - inset)
            regions.append({"x": x, "y": y, "width": max(1, right - x), "height": max(1, bottom - y)})
    return regions


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(SCRIPT_DIR / "training_sets" / "combined"))
    parser.add_argument("--recent-out", default=str(SCRIPT_DIR / "training_sets" / "recent_feedback"))
    args = parser.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    recent_out = Path(args.recent_out)
    recent_out.mkdir(parents=True, exist_ok=True)
    records = []

    test = seed_image("test.png", Path("test.png"))
    records.append({"image": relative_image_path(test, out), "regions": run_detector(test)["regions"]})

    icons = seed_image("icons.png", Path("xunlian/icons.png"))
    records.append({"image": relative_image_path(icons, out), "regions": run_detector(icons)["regions"]})

    icons2 = seed_image("icons2.png", Path("xunlian/icons2.png"))
    records.append({"image": relative_image_path(icons2, out), "regions": coarse_grid(icons2, columns=10, rows=10, inset=10)})

    feedback_records = load_user_feedback(out)
    records.extend(feedback_records)
    write_recent_feedback_dataset(recent_out)

    with (out / "annotations.jsonl").open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    print(json.dumps({
        "dataset": str(out),
        "recent_dataset": str(recent_out),
        "records": len(records),
        "regions": [len(r["regions"]) for r in records]
    }, ensure_ascii=False))


def load_user_feedback(out_dir):
    feedback_root = SCRIPT_DIR / "training_sets" / "user_feedback"
    manifest = feedback_root / "annotations.jsonl"
    if not manifest.exists():
        return []
    records = []
    lines = [line for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    for index, line in enumerate(lines):
        if not line.strip():
            continue
        record = json.loads(line)
        source = feedback_root / record["image"]
        if not source.exists():
            continue
        record["image"] = str(source.resolve().relative_to(out_dir.resolve(), walk_up=True)).replace("\\", "/")
        # Weight user-confirmed corrections more heavily, with a boost for recent samples.
        remaining = len(lines) - index
        weight = 4 + min(4, max(0, remaining - 1))
        records.extend(dict(record) for _ in range(weight))
    return records


def write_recent_feedback_dataset(out_dir):
    feedback_root = SCRIPT_DIR / "training_sets" / "user_feedback"
    manifest = feedback_root / "annotations.jsonl"
    if not manifest.exists():
        return
    lines = [line for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not lines:
        return
    record = json.loads(lines[-1])
    source = feedback_root / record["image"]
    if not source.exists():
        return
    record["image"] = str(source.resolve().relative_to(out_dir.resolve(), walk_up=True)).replace("\\", "/")
    with (out_dir / "annotations.jsonl").open("w", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def seed_image(name, dev_fallback):
    bundled = SCRIPT_DIR / "seed_images" / name
    if bundled.exists():
        return bundled
    return dev_fallback


def relative_image_path(image, out_dir):
    return str(image.resolve().relative_to(out_dir.resolve(), walk_up=True)).replace("\\", "/")


if __name__ == "__main__":
    main()

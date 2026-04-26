#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

from PIL import Image

SCRIPT_DIR = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description="Train a lightweight magic-wand tolerance model.")
    parser.add_argument("--dataset", default=str(SCRIPT_DIR / "training_sets" / "magic_feedback"))
    parser.add_argument("--out", default=str(SCRIPT_DIR / "model" / "magic"))
    args = parser.parse_args()

    dataset = Path(args.dataset)
    records = load_records(dataset)
    model_records = []
    for record in records:
        image = Image.open(dataset / record["image"]).convert("RGBA")
        seed_x = clamp(int(record["seedX"]), 0, image.width - 1)
        seed_y = clamp(int(record["seedY"]), 0, image.height - 1)
        r, g, b, _ = image.getpixel((seed_x, seed_y))
        model_records.append(
            {
                "r": r,
                "g": g,
                "b": b,
                "tolerance": clamp(int(record.get("tolerance", 32)), 1, 255),
            }
        )

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model = {
        "status": "trained",
        "records": model_records,
        "count": len(model_records),
    }
    (out / "model.json").write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": "trained", "records": len(model_records), "model": str(out / "model.json")}, ensure_ascii=False))


def load_records(dataset):
    manifest = dataset / "annotations.jsonl"
    if not manifest.exists():
        raise SystemExit("Missing magic feedback annotations.jsonl")
    records = []
    for line_number, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        record = json.loads(line)
        image = dataset / record["image"]
        if not image.exists():
            raise SystemExit(f"Line {line_number}: image not found: {image}")
        for key in ("seedX", "seedY", "tolerance"):
            if key not in record:
                raise SystemExit(f"Line {line_number}: missing {key}")
        records.append(record)
    if not records:
        raise SystemExit("No magic training records found")
    return records


def clamp(value, lower, upper):
    return max(lower, min(upper, value))


if __name__ == "__main__":
    main()

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List

from offline_common import load_image_rgba, read_jsonl, write_json


def main() -> int:
    root = Path(__file__).resolve().parent
    output_file = root / "model" / "magic" / "model.json"
    records: List[Dict[str, int]] = []

    for dataset_root in (root / "training_sets" / "magic_seed", root / "training_sets" / "magic_feedback"):
        for item in read_jsonl(dataset_root / "annotations.jsonl"):
            image_value = item.get("image")
            if not isinstance(image_value, str):
                continue
            image_path = dataset_root / image_value if not Path(image_value).is_absolute() else Path(image_value)
            try:
                width, height, pixels = load_image_rgba(image_path)
            except Exception:
                continue
            seed_x = int(item.get("seedX") or 0)
            seed_y = int(item.get("seedY") or 0)
            if seed_x < 0 or seed_y < 0 or seed_x >= width or seed_y >= height:
                continue
            red, green, blue, _ = pixels[seed_y * width + seed_x]
            records.append(
                {
                    "r": red,
                    "g": green,
                    "b": blue,
                    "tolerance": int(item.get("tolerance") or 0),
                }
            )

    payload = {"version": 1, "records": dedupe(records)}
    write_json(output_file, payload)
    print(json.dumps({"records": len(payload["records"]), "output": str(output_file)}, ensure_ascii=False))
    return 0


def dedupe(records: List[Dict[str, int]]) -> List[Dict[str, int]]:
    seen = set()
    output = []
    for record in records:
        key = (record["r"], record["g"], record["b"], record["tolerance"])
        if key in seen:
            continue
        seen.add(key)
        output.append(record)
    return output


if __name__ == "__main__":
    raise SystemExit(main())

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List

from offline_common import read_jsonl, write_json


def main() -> int:
    root = Path(__file__).resolve().parent
    output_file = root / "model" / "background" / "model.json"
    records: List[Dict[str, int]] = []

    for dataset_root in (root / "training_sets" / "background_seed", root / "training_sets" / "background_feedback"):
        for item in read_jsonl(dataset_root / "annotations.jsonl"):
            try:
                edge_argb = int(item.get("edgeArgb"))
                background_argb = int(item.get("backgroundArgb"))
            except (TypeError, ValueError):
                continue
            records.append({"edgeArgb": edge_argb, "backgroundArgb": background_argb})

    payload = {"version": 1, "records": dedupe(records)}
    write_json(output_file, payload)
    print(json.dumps({"records": len(payload["records"]), "output": str(output_file)}, ensure_ascii=False))
    return 0


def dedupe(records: List[Dict[str, int]]) -> List[Dict[str, int]]:
    seen = set()
    output = []
    for record in records:
        key = (record["edgeArgb"], record["backgroundArgb"])
        if key in seen:
            continue
        seen.add(key)
        output.append(record)
    return output


if __name__ == "__main__":
    raise SystemExit(main())

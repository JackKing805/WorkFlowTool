from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List

from offline_common import canonicalize_record, read_jsonl, write_jsonl
from seed_image_annotations import seed_image_records


def main() -> int:
    root = Path(__file__).resolve().parent
    training_root = root / "training_sets"
    combined_output = training_root / "combined" / "annotations.jsonl"
    recent_output = training_root / "recent_feedback" / "annotations.jsonl"

    source_dirs = [
        path
        for path in training_root.iterdir()
        if path.is_dir() and path.name not in {"combined", "recent_feedback", "background_feedback"}
    ]

    combined_records: List[Dict[str, object]] = []
    user_feedback_records: List[Dict[str, object]] = []
    for source_dir in sorted(source_dirs):
        annotation_file = source_dir / "annotations.jsonl"
        if not annotation_file.exists():
            continue
        for raw_record in read_jsonl(annotation_file):
            record = canonicalize_record(raw_record, source_dir)
            if record is None:
                continue
            combined_records.append(record)
            if source_dir.name == "user_feedback":
                user_feedback_records.append(record)
    combined_records.extend(seed_image_records(root / "seed_images"))

    deduped_combined = dedupe_records(combined_records)
    deduped_recent = dedupe_records(user_feedback_records[-32:])
    write_jsonl(combined_output, deduped_combined)
    write_jsonl(recent_output, deduped_recent)

    payload = {
        "combinedSamples": len(deduped_combined),
        "recentSamples": len(deduped_recent),
        "combinedOutput": str(combined_output),
        "recentOutput": str(recent_output),
    }
    print(json.dumps(payload, ensure_ascii=False))
    return 0


def dedupe_records(records: List[Dict[str, object]]) -> List[Dict[str, object]]:
    seen = set()
    output: List[Dict[str, object]] = []
    for record in records:
        key = (
            str(record.get("image") or ""),
            str(record.get("imageHash") or ""),
            json_key(record.get("instances") or []),
        )
        if key in seen:
            continue
        seen.add(key)
        output.append(record)
    return output


def json_key(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


if __name__ == "__main__":
    raise SystemExit(main())

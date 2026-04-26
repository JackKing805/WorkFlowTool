#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description="Train a lightweight background color correction model.")
    parser.add_argument("--dataset", default=str(SCRIPT_DIR / "training_sets" / "background_feedback"))
    parser.add_argument("--out", default=str(SCRIPT_DIR / "model" / "background"))
    args = parser.parse_args()

    dataset = Path(args.dataset)
    records = load_records(dataset)
    model_records = [
        {
            "edgeArgb": int(record["edgeArgb"]),
            "backgroundArgb": int(record["backgroundArgb"]),
        }
        for record in records
    ]
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model = {"status": "trained", "count": len(model_records), "records": model_records}
    (out / "model.json").write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": "trained", "records": len(model_records), "model": str(out / "model.json")}, ensure_ascii=False))


def load_records(dataset):
    manifest = dataset / "annotations.jsonl"
    if not manifest.exists():
        raise SystemExit("Missing background feedback annotations.jsonl")
    records = []
    for line_number, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        record = json.loads(line)
        for key in ("edgeArgb", "backgroundArgb"):
            if key not in record:
                raise SystemExit(f"Line {line_number}: missing {key}")
        records.append(record)
    if not records:
        raise SystemExit("No background training records found")
    return records


if __name__ == "__main__":
    main()

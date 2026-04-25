#!/usr/bin/env python3
import argparse
import json
import os
import shutil
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent


def validate_dataset(dataset_dir: Path):
    manifest = dataset_dir / "annotations.jsonl"
    if not manifest.exists():
        raise SystemExit(
            "Missing annotations.jsonl. Expected one JSON object per line: "
            '{"image":"images/sheet.png","regions":[{"x":1,"y":2,"width":32,"height":32}]}'
        )
    records = []
    for line_number, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        record = json.loads(line)
        image = dataset_dir / record["image"]
        if not image.exists():
            raise SystemExit(f"Line {line_number}: image not found: {image}")
        regions = record.get("regions", [])
        if not regions:
            raise SystemExit(f"Line {line_number}: at least one region is required")
        for region in regions:
            for key in ("x", "y", "width", "height"):
                if key not in region:
                    raise SystemExit(f"Line {line_number}: region missing {key}")
        records.append(record)
    if not records:
        raise SystemExit("No training records found")
    return records


def main():
    parser = argparse.ArgumentParser(description="Train or validate a Python icon detector dataset.")
    parser.add_argument("--dataset", required=True, help="Directory containing annotations.jsonl and images/")
    parser.add_argument("--out", default="python_detector/model", help="Output directory for model artifacts")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=2)
    args = parser.parse_args()

    dataset_dir = Path(args.dataset)
    records = validate_dataset(dataset_dir)
    output_dir = Path(args.out)
    output_dir.mkdir(parents=True, exist_ok=True)

    if args.validate_only:
        print(json.dumps({"status": "ok", "records": len(records)}, ensure_ascii=False))
        return

    yolo_root = output_dir / "yolo_dataset"
    export_yolo_dataset(dataset_dir, records, yolo_root)
    os.environ.setdefault("YOLO_CONFIG_DIR", str((SCRIPT_DIR / ".ultralytics").resolve()))
    Path(os.environ["YOLO_CONFIG_DIR"]).mkdir(parents=True, exist_ok=True)

    try:
        import torch  # noqa: F401
        from ultralytics import YOLO
    except Exception as exc:
        metadata = {
            "status": "dataset_ready",
            "records": len(records),
            "training": "skipped",
            "reason": f"PyTorch/Ultralytics is not available: {exc}",
            "next": "Install PyTorch/Ultralytics or export this dataset to a training machine.",
        }
        (output_dir / "training_manifest.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(metadata, ensure_ascii=False))
        return

    model = YOLO("yolov8n.pt")
    result = model.train(
        data=str(yolo_root / "dataset.yaml"),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        project=str(output_dir.resolve()),
        name="runs",
        exist_ok=True,
        verbose=False,
    )
    best = output_dir / "runs" / "weights" / "best.pt"
    trained_best = Path(result.save_dir) / "weights" / "best.pt"
    if trained_best.exists() and trained_best.resolve() != best.resolve():
        best.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(trained_best, best)
    metadata = {
        "status": "trained",
        "records": len(records),
        "model": str(best),
        "result": str(result.save_dir),
    }
    (output_dir / "training_manifest.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metadata, ensure_ascii=False))


def export_yolo_dataset(dataset_dir: Path, records, yolo_root: Path):
    images_dir = yolo_root / "images" / "train"
    labels_dir = yolo_root / "labels" / "train"
    if yolo_root.exists():
        shutil.rmtree(yolo_root)
    images_dir.mkdir(parents=True, exist_ok=True)
    labels_dir.mkdir(parents=True, exist_ok=True)

    from PIL import Image

    for index, record in enumerate(records, start=1):
        source = dataset_dir / record["image"]
        suffix = source.suffix.lower() or ".png"
        target_image = images_dir / f"image_{index:04d}{suffix}"
        shutil.copy2(source, target_image)
        width, height = Image.open(source).size
        label_lines = []
        for region in record["regions"]:
            x = float(region["x"])
            y = float(region["y"])
            w = float(region["width"])
            h = float(region["height"])
            cx = (x + w / 2.0) / width
            cy = (y + h / 2.0) / height
            label_lines.append(f"0 {cx:.8f} {cy:.8f} {w / width:.8f} {h / height:.8f}")
        (labels_dir / f"image_{index:04d}.txt").write_text("\n".join(label_lines), encoding="utf-8")

    (yolo_root / "dataset.yaml").write_text(
        "path: " + str(yolo_root.resolve()).replace("\\", "/") + "\n"
        "train: images/train\n"
        "val: images/train\n"
        "names:\n"
        "  0: icon\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()

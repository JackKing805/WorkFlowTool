from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Tuple

import numpy as np
import torch
from PIL import Image
from torch import nn
from torch.utils.data import DataLoader, Dataset

from offline_common import canonicalize_record, read_jsonl


class TinyIconSegmentation(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.net = nn.Sequential(
            nn.Conv2d(4, 16, kernel_size=3, padding=1),
            nn.ReLU(inplace=True),
            nn.Conv2d(16, 16, kernel_size=3, padding=1),
            nn.ReLU(inplace=True),
            nn.Conv2d(16, 1, kernel_size=1),
        )

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        return self.net(image)


class IconMaskDataset(Dataset[Tuple[torch.Tensor, torch.Tensor]]):
    def __init__(self, records: List[Dict[str, Any]], image_size: int) -> None:
        self.records = records
        self.image_size = image_size

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int) -> Tuple[torch.Tensor, torch.Tensor]:
        record = self.records[index]
        image = Image.open(record["image"]).convert("RGBA")
        source_width, source_height = image.size
        target = Image.new("L", (source_width, source_height), 0)
        for instance in record["instances"]:
            bbox = instance["bbox"]
            mask = Image.fromarray(
                np.asarray(instance["alphaMask"], dtype=np.uint8).reshape(
                    int(instance["maskHeight"]),
                    int(instance["maskWidth"]),
                ),
                mode="L",
            )
            target.paste(mask, (int(bbox["x"]), int(bbox["y"])))
        image = image.resize((self.image_size, self.image_size), Image.Resampling.BILINEAR)
        target = target.resize((self.image_size, self.image_size), Image.Resampling.NEAREST)
        image_array = np.asarray(image, dtype=np.float32).transpose(2, 0, 1) / 255.0
        target_array = (np.asarray(target, dtype=np.float32)[None, :, :] > 0).astype(np.float32)
        return torch.from_numpy(image_array), torch.from_numpy(target_array)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train the first-run icon segmentation model.")
    parser.add_argument("--dataset", default="training_sets/combined")
    parser.add_argument("--out", default="model/instance_segmentation")
    parser.add_argument("--epochs", type=int, default=2)
    parser.add_argument("--imgsz", type=int, default=256)
    parser.add_argument("--batch", type=int, default=2)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parent
    dataset_root = resolve_under(root, args.dataset)
    output_root = resolve_under(root, args.out)
    records = [
        record
        for raw_record in read_jsonl(dataset_root / "annotations.jsonl")
        for record in [canonicalize_record(raw_record, dataset_root)]
        if record is not None and Path(record["image"]).exists()
    ]
    if not records:
        print(json.dumps({"ok": False, "reason": "no training records", "dataset": str(dataset_root)}, ensure_ascii=False))
        return 2

    output_root.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(17)
    model = TinyIconSegmentation()
    dataset = IconMaskDataset(records, int(args.imgsz))
    loader = DataLoader(dataset, batch_size=max(1, int(args.batch)), shuffle=True)
    optimizer = torch.optim.AdamW(model.parameters(), lr=0.002)
    loss_fn = nn.BCEWithLogitsLoss()

    model.train()
    losses: List[float] = []
    for _ in range(max(1, int(args.epochs))):
        for image, target in loader:
            optimizer.zero_grad(set_to_none=True)
            loss = loss_fn(model(image), target)
            loss.backward()
            optimizer.step()
            losses.append(float(loss.detach().cpu()))

    weights_path = output_root / "model.pt"
    onnx_path = output_root / "model.onnx"
    json_path = output_root / "model.json"
    torch.save({"state_dict": model.state_dict(), "imageSize": int(args.imgsz)}, weights_path)
    model.eval()
    dummy = torch.zeros(1, 4, int(args.imgsz), int(args.imgsz), dtype=torch.float32)
    torch.onnx.export(
        model,
        dummy,
        onnx_path,
        input_names=["image"],
        output_names=["mask_logits"],
        dynamic_axes={"image": {2: "height", 3: "width"}, "mask_logits": {2: "height", 3: "width"}},
        opset_version=17,
    )
    metadata = {
        "version": 1,
        "backend": "mask_rcnn_onnx",
        "architecture": "first_run_icon_segmentation",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "inputSize": int(args.imgsz),
        "scoreThreshold": 0.35,
        "maskThreshold": 0.5,
        "labels": ["icon"],
        "training": {
            "dataset": args.dataset,
            "samples": len(records),
            "epochs": int(args.epochs),
            "batch": int(args.batch),
            "meanLoss": round(sum(losses) / max(1, len(losses)), 6),
            "pretrained": False,
        },
        "license": "Generated locally from user/project training data; no pretrained weights bundled.",
    }
    json_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(json.dumps({"ok": True, "samples": len(records), "output": str(output_root)}, ensure_ascii=False))
    return 0


def resolve_under(root: Path, value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = (root / path).resolve()
    return path


if __name__ == "__main__":
    raise SystemExit(main())

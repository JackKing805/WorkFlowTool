from __future__ import annotations

import argparse
import json
import random
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Tuple

import numpy as np
import torch
from PIL import Image
from torch import nn
from torch.utils.data import DataLoader, Dataset

from offline_common import canonicalize_record, read_jsonl


def configure_stdio() -> None:
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            reconfigure(encoding="utf-8", errors="replace")


class TinyIconSegmentation(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.enc1 = conv_block(4, 16)
        self.down1 = nn.MaxPool2d(2)
        self.enc2 = conv_block(16, 32)
        self.down2 = nn.MaxPool2d(2)
        self.bottleneck = conv_block(32, 64)
        self.up2 = nn.Upsample(scale_factor=2, mode="bilinear", align_corners=False)
        self.dec2 = conv_block(96, 32)
        self.up1 = nn.Upsample(scale_factor=2, mode="bilinear", align_corners=False)
        self.dec1 = conv_block(48, 16)
        self.head = nn.Conv2d(16, 1, kernel_size=1)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        e1 = self.enc1(image)
        e2 = self.enc2(self.down1(e1))
        b = self.bottleneck(self.down2(e2))
        d2 = self.dec2(torch.cat([self.up2(b), e2], dim=1))
        d1 = self.dec1(torch.cat([self.up1(d2), e1], dim=1))
        return self.head(d1)


def conv_block(input_channels: int, output_channels: int) -> nn.Sequential:
    return nn.Sequential(
        nn.Conv2d(input_channels, output_channels, kernel_size=3, padding=1),
        nn.ReLU(inplace=True),
        nn.Conv2d(output_channels, output_channels, kernel_size=3, padding=1),
        nn.ReLU(inplace=True),
    )


class IconMaskDataset(Dataset[Tuple[torch.Tensor, torch.Tensor]]):
    def __init__(self, records: List[Dict[str, Any]], image_size: int, augment: bool) -> None:
        self.records = records
        self.image_size = image_size
        self.augment = augment

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
        if self.augment:
            image, target = augment_pair(image, target)
        image_array = np.asarray(image, dtype=np.float32).transpose(2, 0, 1) / 255.0
        target_array = np.asarray(target, dtype=np.float32)[None, :, :] / 255.0
        return torch.from_numpy(image_array), torch.from_numpy(target_array)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train the first-run icon segmentation model.")
    parser.add_argument("--dataset", default="training_sets/combined")
    parser.add_argument("--out", default="model/instance_segmentation")
    parser.add_argument("--epochs", type=int, default=8)
    parser.add_argument("--imgsz", type=int, default=256)
    parser.add_argument("--batch", type=int, default=2)
    return parser.parse_args()


def augment_pair(image: Image.Image, target: Image.Image) -> Tuple[Image.Image, Image.Image]:
    if random.random() < 0.5:
        image = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        target = target.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    array = np.asarray(image, dtype=np.float32)
    contrast = random.uniform(0.88, 1.12)
    brightness = random.uniform(-10.0, 10.0)
    noise = np.random.normal(0.0, 2.0, array.shape).astype(np.float32)
    array[:, :, :3] = np.clip((array[:, :, :3] - 127.5) * contrast + 127.5 + brightness + noise[:, :, :3], 0, 255)
    return Image.fromarray(array.astype(np.uint8), mode="RGBA"), target


def dice_loss(logits: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    probabilities = torch.sigmoid(logits)
    intersection = (probabilities * target).sum(dim=(1, 2, 3))
    denominator = probabilities.sum(dim=(1, 2, 3)) + target.sum(dim=(1, 2, 3))
    return (1.0 - ((2.0 * intersection + 1.0) / (denominator + 1.0))).mean()


def main() -> int:
    configure_stdio()
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
    random.seed(17)
    np.random.seed(17)
    model = TinyIconSegmentation()
    dataset = IconMaskDataset(records, int(args.imgsz), augment=True)
    loader = DataLoader(dataset, batch_size=max(1, int(args.batch)), shuffle=True)
    optimizer = torch.optim.AdamW(model.parameters(), lr=0.002)
    loss_fn = nn.BCEWithLogitsLoss()

    model.train()
    losses: List[float] = []
    for _ in range(max(1, int(args.epochs))):
        for image, target in loader:
            optimizer.zero_grad(set_to_none=True)
            logits = model(image)
            loss = loss_fn(logits, target) + dice_loss(logits, target)
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
        opset_version=18,
    )
    metadata = {
        "version": 2,
        "backend": "mask_rcnn_onnx",
        "architecture": "light_unet_icon_segmentation_v2",
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

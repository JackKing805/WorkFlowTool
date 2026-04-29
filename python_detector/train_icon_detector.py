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
import torch.nn.functional as F
from PIL import Image
from torch import nn
from torch.utils.data import DataLoader, Dataset

from offline_common import canonicalize_record, read_jsonl

MODEL_ARCHITECTURE = "local_unet_icon_segmentation_v3"


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
    parser.add_argument("--resume", default="", help="Optional existing local model.pt to continue training from.")
    parser.add_argument("--lr", type=float, default=0.002)
    parser.add_argument("--bce-weight", type=float, default=0.55)
    parser.add_argument("--dice-weight", type=float, default=0.30)
    parser.add_argument("--focal-weight", type=float, default=0.15)
    parser.add_argument("--score-threshold", type=float, default=0.18)
    parser.add_argument("--mask-threshold", type=float, default=0.28)
    return parser.parse_args()


def augment_pair(image: Image.Image, target: Image.Image) -> Tuple[Image.Image, Image.Image]:
    if random.random() < 0.5:
        image = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        target = target.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    if random.random() < 0.35:
        angle = random.uniform(-7.0, 7.0)
        image = image.rotate(angle, resample=Image.Resampling.BILINEAR, fillcolor=(0, 0, 0, 0))
        target = target.rotate(angle, resample=Image.Resampling.NEAREST, fillcolor=0)
    if random.random() < 0.45:
        max_shift = max(2, int(min(image.size) * 0.08))
        dx = random.randint(-max_shift, max_shift)
        dy = random.randint(-max_shift, max_shift)
        shifted_image = Image.new("RGBA", image.size, (0, 0, 0, 0))
        shifted_target = Image.new("L", target.size, 0)
        shifted_image.paste(image, (dx, dy))
        shifted_target.paste(target, (dx, dy))
        image, target = shifted_image, shifted_target
    array = np.asarray(image, dtype=np.float32)
    contrast = random.uniform(0.80, 1.22)
    brightness = random.uniform(-16.0, 16.0)
    noise = np.random.normal(0.0, 3.0, array.shape).astype(np.float32)
    array[:, :, :3] = np.clip((array[:, :, :3] - 127.5) * contrast + 127.5 + brightness + noise[:, :, :3], 0, 255)
    if random.random() < 0.35:
        target_array = np.asarray(target, dtype=np.uint8)
        background = target_array <= 0
        tint = np.array(
            [random.randint(0, 255), random.randint(0, 255), random.randint(0, 255)],
            dtype=np.float32,
        )
        mix = random.uniform(0.03, 0.10)
        array[:, :, :3][background] = np.clip(array[:, :, :3][background] * (1.0 - mix) + tint * mix, 0, 255)
    return Image.fromarray(array.astype(np.uint8), mode="RGBA"), target


def dice_loss(logits: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    probabilities = torch.sigmoid(logits)
    intersection = (probabilities * target).sum(dim=(1, 2, 3))
    denominator = probabilities.sum(dim=(1, 2, 3)) + target.sum(dim=(1, 2, 3))
    return (1.0 - ((2.0 * intersection + 1.0) / (denominator + 1.0))).mean()


def focal_loss(logits: torch.Tensor, target: torch.Tensor, pos_weight: torch.Tensor, gamma: float = 2.0) -> torch.Tensor:
    bce = F.binary_cross_entropy_with_logits(logits, target, pos_weight=pos_weight, reduction="none")
    probabilities = torch.sigmoid(logits)
    pt = torch.where(target > 0.5, probabilities, 1.0 - probabilities).clamp(1e-4, 1.0)
    return ((1.0 - pt) ** gamma * bce).mean()


def target_foreground_ratio(records: List[Dict[str, Any]], image_size: int) -> float:
    dataset = IconMaskDataset(records, image_size, augment=False)
    foreground = 0.0
    total = 0.0
    for _, target in dataset:
        foreground += float((target > 0.05).sum().item())
        total += float(target.numel())
    return foreground / max(1.0, total)


def batch_pos_weight(target: torch.Tensor) -> torch.Tensor:
    foreground = torch.clamp((target > 0.05).sum().float(), min=1.0)
    background = torch.clamp(target.numel() - foreground, min=1.0)
    value = torch.clamp(background / foreground, min=2.0, max=80.0)
    return value.to(device=target.device, dtype=target.dtype)


def validation_stats(model: nn.Module, records: List[Dict[str, Any]], image_size: int, mask_threshold: float) -> Dict[str, float]:
    dataset = IconMaskDataset(records, image_size, augment=False)
    loader = DataLoader(dataset, batch_size=1, shuffle=False)
    max_probability = 0.0
    mean_probability = 0.0
    predicted_ratio = 0.0
    dice_scores: List[float] = []
    count = 0
    model.eval()
    with torch.no_grad():
        for image, target in loader:
            probabilities = torch.sigmoid(model(image))
            max_probability = max(max_probability, float(probabilities.max().cpu()))
            mean_probability += float(probabilities.mean().cpu())
            predicted = probabilities >= mask_threshold
            predicted_ratio += float(predicted.float().mean().cpu())
            intersection = float((predicted.float() * (target > 0.05).float()).sum().cpu())
            denominator = float(predicted.float().sum().cpu() + (target > 0.05).float().sum().cpu())
            dice_scores.append((2.0 * intersection + 1.0) / (denominator + 1.0))
            count += 1
    return {
        "maxProbability": max_probability,
        "meanProbability": mean_probability / max(1, count),
        "predictedForegroundRatio": predicted_ratio / max(1, count),
        "meanDiceAtThreshold": sum(dice_scores) / max(1, len(dice_scores)),
    }


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
    resume_path = resolve_under(root, args.resume) if args.resume else None
    resumed_from = ""
    if resume_path is not None and resume_path.exists():
        checkpoint = torch.load(resume_path, map_location="cpu")
        state_dict = checkpoint.get("state_dict") if isinstance(checkpoint, dict) else None
        if isinstance(state_dict, dict):
            model.load_state_dict(state_dict, strict=True)
            resumed_from = str(resume_path)
        else:
            print(json.dumps({"ok": False, "reason": "resume checkpoint has no state_dict", "resume": str(resume_path)}, ensure_ascii=False))
            return 4
    dataset = IconMaskDataset(records, int(args.imgsz), augment=True)
    loader = DataLoader(dataset, batch_size=max(1, int(args.batch)), shuffle=True)
    learning_rate = max(0.00005, min(float(args.lr), 0.01))
    bce_weight, dice_weight, focal_weight = normalized_loss_weights(args.bce_weight, args.dice_weight, args.focal_weight)
    score_threshold = max(0.05, min(float(args.score_threshold), 0.95))
    mask_threshold = max(0.05, min(float(args.mask_threshold), 0.95))
    optimizer = torch.optim.AdamW(model.parameters(), lr=learning_rate)
    foreground_ratio = target_foreground_ratio(records, int(args.imgsz))

    model.train()
    losses: List[float] = []
    for _ in range(max(1, int(args.epochs))):
        for image, target in loader:
            optimizer.zero_grad(set_to_none=True)
            logits = model(image)
            pos_weight = batch_pos_weight(target)
            bce = F.binary_cross_entropy_with_logits(logits, target, pos_weight=pos_weight)
            loss = (
                (bce_weight * bce) +
                (dice_weight * dice_loss(logits, target)) +
                (focal_weight * focal_loss(logits, target, pos_weight))
            )
            loss.backward()
            optimizer.step()
            losses.append(float(loss.detach().cpu()))

    validation = validation_stats(model, records, int(args.imgsz), mask_threshold)
    if validation["maxProbability"] < score_threshold or validation["predictedForegroundRatio"] <= 0.00001:
        print(
            json.dumps(
                {
                    "ok": False,
                    "reason": "trained model predicts no foreground",
                    "foregroundRatio": foreground_ratio,
                    "validation": validation,
                },
                ensure_ascii=False,
            )
        )
        return 3

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
        "architecture": MODEL_ARCHITECTURE,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "inputSize": int(args.imgsz),
        "scoreThreshold": score_threshold,
        "maskThreshold": mask_threshold,
        "labels": ["icon"],
        "training": {
            "dataset": args.dataset,
            "samples": len(records),
            "epochs": int(args.epochs),
            "batch": int(args.batch),
            "meanLoss": round(sum(losses) / max(1, len(losses)), 6),
            "finalLoss": round(losses[-1], 6) if losses else 0.0,
            "foregroundRatio": round(foreground_ratio, 8),
            "validation": {key: round(value, 8) for key, value in validation.items()},
            "learningRate": round(learning_rate, 8),
            "lossWeights": {
                "bce": round(bce_weight, 6),
                "dice": round(dice_weight, 6),
                "focal": round(focal_weight, 6),
            },
            "pretrained": False,
            "resumedFrom": resumed_from,
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


def normalized_loss_weights(bce: float, dice: float, focal: float) -> Tuple[float, float, float]:
    values = [max(0.0, float(bce)), max(0.0, float(dice)), max(0.0, float(focal))]
    total = sum(values)
    if total <= 0.0:
        return 0.55, 0.30, 0.15
    return values[0] / total, values[1] / total, values[2] / total


if __name__ == "__main__":
    raise SystemExit(main())

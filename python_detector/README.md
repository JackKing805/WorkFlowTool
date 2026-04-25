# Python icon detector

This folder contains the Python-side detector and training entrypoints.

## Runtime detection

```powershell
python python_detector\detect_icons.py --image test.png --min-width 8 --min-height 8 --min-pixel-area 16 --merge-nearby-regions
```

The app calls this script first and falls back to the C++ detector if Python returns no regions.
If `python_detector/model/combined/runs/weights/best.pt` exists, the script tries the trained YOLO model first, then falls back to grid and connected-component detection.

## Training data format

Create a dataset directory:

```text
dataset/
  annotations.jsonl
  images/
    sheet_001.png
```

Each line in `annotations.jsonl` is one labeled image:

```json
{"image":"images/sheet_001.png","regions":[{"x":10,"y":20,"width":64,"height":64}]}
```

Validate the dataset:

```powershell
python python_detector\train_icon_detector.py --dataset dataset --validate-only
```

Build the combined starter dataset from the bundled images:

```powershell
python python_detector\make_training_set.py
```

Train locally:

```powershell
python python_detector\train_icon_detector.py --dataset python_detector\training_sets\combined --out python_detector\model\combined --epochs 20 --imgsz 640 --batch 2
```

The desktop app has a "持续学习训练集" switch in Advanced Settings. When enabled, successful exports append the current visible regions to `python_detector/training_sets/user_feedback/annotations.jsonl`; retrain with that dataset, or merge it into `combined`, to make future recognition better.

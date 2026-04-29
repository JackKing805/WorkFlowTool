# Python Detector

This directory contains the Python-side icon detector used by the desktop app.

Runtime behavior:

- The Kotlin app creates a virtual environment under the app runtime directory.
- Missing Python packages are installed from PyPI with `pip install -r requirements.txt`.
- No pretrained model is shipped in the repository.
- On first detection, if `model/instance_segmentation/model.onnx` is missing in the runtime copy, the app trains a local model from `training_sets/*/annotations.jsonl`.
- Training writes `model.pt`, `model.onnx`, and `model.json` to `model/instance_segmentation/`.

Main scripts:

- `make_training_set.py`: merges mask-first JSONL annotations into `training_sets/combined/annotations.jsonl`.
- `train_icon_detector.py`: trains a local-only segmentation model from annotations, using foreground-weighted losses and validation safeguards, then exports ONNX.
- `detect_icons.py`: runs ONNX Runtime CPU inference, applies adaptive mask thresholding when needed, and outputs mask-first JSON.

The detector output uses:

- `mode`
- `regions[].bbox`
- `regions[].maskWidth`
- `regions[].maskHeight`
- `regions[].alphaMask`
- `regions[].score`
- `stats.backend = "mask_rcnn_onnx"`
- Diagnostic stats including `maxProbability`, `meanProbability`, `effectiveMaskThreshold`, and `thresholdStrategy`

Environment overrides:

- `WORKFLOWTOOL_PYTHON`: system Python command used to create the venv.
- `WORKFLOWTOOL_PYTHON_VENV`: custom venv path.
- `WORKFLOWTOOL_SKIP_PYTHON_DEP_INSTALL=true`: fail instead of installing missing packages.
- `WORKFLOWTOOL_TRAIN_EPOCHS`: first-run training epoch count.

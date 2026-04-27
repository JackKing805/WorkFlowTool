# Offline Python Detector

该目录提供完全离线的图标识别脚本和训练辅助脚本。

当前实现特点：

- 推理主协议输出 `bbox + points + score + stats`
- 训练集主格式为 `instances[]`，兼容旧 `regions[]`
- 默认模型为本地 `model/combined/model.json`
- 检测脚本优先走本地多边形分割启发式，不依赖联网
- 读取图片时优先使用 Pillow；若环境没有 Pillow，仍可用内置 PNG 解析器处理 Kotlin 桥接生成的 PNG

开发约束：

- 正式交付应把离线 Python 运行时放到 `third_party/python/<platform>/`
- 正式交付应把离线 wheel 放到 `third_party/wheels/<platform>/`
- 若开发环境暂时没有项目内 Python，可设置 `WORKFLOWTOOL_ALLOW_SYSTEM_PYTHON=true`

脚本说明：

- `detect_icons.py`: 离线检测，输出 polygon-aware JSON
- `bootstrap_seed_models.py`: 用 `seed_images/` 生成种子训练集并预训练三类本地模型
- `make_training_set.py`: 合并并迁移训练集到 canonical JSONL
- `train_icon_detector.py`: 生成本地模型清单 `model/combined/model.json`
- `train_magic_model.py`: 从魔棒反馈生成颜色容差模型
- `train_background_model.py`: 从背景反馈生成背景颜色模型

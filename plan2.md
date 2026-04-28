# 直接升级为全新免费可商用识别链路

## Summary

放弃旧的启发式识别与兼容层，直接把识别模块重构为一套新的离线实例分割方案：

- 训练框架固定为 PyTorch + torchvision。
- 桌面端推理固定为 ONNX Runtime CPU。
- 模型固定为单类 Mask R-CNN，识别目标就是 icon。
- 仅保留免费可商用、允许离线分发的库与模型资产。
- Kotlin 侧按新协议重接，不再保留旧 Python/C++ 检测逻辑和旧模型格式。

推荐原因：这条链路精度、成熟度、许可证边界、离线落地能力最平衡，明显比继续堆启发式
更强，也比 AGPL 风险方案更适合商用。

## Key Changes

### 1. 识别架构整体替换

- 删除旧的启发式 Python 检测主链设计目标，不再以 mask_cutout_v2 为核心。
- 新建统一识别后端：
    - 训练：PyTorch + torchvision Mask R-CNN
    - 导出：ONNX
    - 推理：onnxruntime
- 不再把“旧后端兼容”作为目标，不保留旧模型清单格式、旧启发式参数训练思路、旧回退协
  议约束。
- C++ 检测后端不再作为主识别方案；如保留，仅允许作为后续独立优化项，不进入本次主方
  案。

### 2. 新数据与模型规范

- 训练集主格式统一为 instances[]，每个实例至少包含：
    - bbox
    - maskWidth
    - maskHeight
    - alphaMask
    - label
- 类别固定为单类 icon，不做多分类设计。
- 新模型目录统一为：
    - python_detector/model/instance_segmentation/model.onnx
    - python_detector/model/instance_segmentation/model.pt
    - python_detector/model/instance_segmentation/model.json
- model.json 固定记录：
    - 模型版本
    - 输入尺寸
    - score 阈值
    - NMS 阈值
    - mask 二值化阈值
    - 标签列表
    - 训练时间与数据摘要
    - 许可证与分发说明

### 3. Python 检测模块重写

- detect_icons.py 改为纯 ONNX 推理脚本，不再承载旧启发式主流程。
- 输入为图片路径，输出为统一 JSON：
    - mode
    - regions[]
    - stats
- regions[] 固定输出：
    - bbox
    - maskWidth
    - maskHeight
    - alphaMask
    - score
- stats.backend 固定为 mask_rcnn_onnx。
- 推理后处理固定包含：
    - score 过滤
    - NMS
    - mask 二值化
    - bbox 与 mask 对齐
    - 按左上坐标排序，保证 UI 稳定
- 不再保留旧 payload 兼容解析分支。

### 4. 训练与导出链路重写

- 新增训练脚本：
    - 从现有 JSONL 标注读取 mask-first 样本
    - 转换为 torchvision 数据集
    - 训练单类 Mask R-CNN
- 新增导出脚本：
    - 将训练好的 .pt 权重导出为 .onnx
    - 生成配套 model.json
- 新增评估脚本：
    - 评估 precision / recall / mean IoU
    - 对训练集与验证集分别输出结果
- 若使用预训练初始化：
    - 只允许读取本地权重文件
    - 禁止脚本内自动下载
    - 若本地无预训练权重，则支持从随机初始化开始训练

### 5. Kotlin 桌面端接入改造

- PythonDetectorBridge 按新协议简化重写，只解析新实例分割输出。
- ServiceFactory.detector() 改为直接绑定新的 Python 识别服务，不再围绕旧复合检测器
  设计。
- UI 层继续消费 CropRegion 语义，但数据来源完全由新实例分割结果生成。
- 应用启动检查项更新为：
    - Python 运行时存在
    - onnxruntime/numpy/Pillow 离线依赖存在
    - model.onnx 与 model.json 存在
- 缺少模型或依赖时直接报识别不可用，不再静默走旧后端。

### 6. 许可证与依赖白名单

本次仅允许以下技术进入正式方案：

- Python：PSF License
- PyTorch：BSD-3-Clause
- torchvision：BSD-3-Clause
- onnxruntime：MIT
- numpy：BSD-3-Clause
- Pillow：HPND 类宽松许可
- 可选 opencv-python-headless：Apache-2.0
- 可选 pycocotools：BSD

明确排除：

- Ultralytics YOLO 正式链路
- 任何 GPL / AGPL / non-commercial / research-only / license-unknown
- 任何需要联网拉权重、联网鉴权、联网推理的框架或模型

### 7. 仓库与交付物整理

- 更新 third_party/THIRD_PARTY_MANIFEST.json，记录所有新库与模型资产许可证。
- 更新 python_detector/README.md 与项目 README.md，明确新识别架构、离线运行方式、许
  可证边界。
- 清理旧识别方案文档，避免仓库里同时存在多套冲突主方案说明。
- 将“免费可商用、允许离线分发”写成新增依赖的硬门槛检查要求。

## Public Interfaces / Format Changes

- 检测输出协议以新 JSON 为准，不保证兼容旧 payload。
- stats.backend 只接受新值 mask_rcnn_onnx。
- Python 检测 CLI 以新参数定义为准，至少包含：
    - --image
    - --model
    - --score-threshold
    - --mask-threshold
- 不保留旧启发式参数集合的兼容入口。

## Test Plan

- Python 单元测试：
    - 数据集转换正确生成训练 mask 与 bbox
    - ONNX 输出能正确转为 regions[].alphaMask
    - 空预测、低分预测、非法输出都会稳定返回空结果
- Python 集成测试：
    - 本地 ONNX 模型可加载并完成离线推理
    - 简单合成图能识别多个独立图标
    - 输出的 bbox 与 mask 尺寸一致
- Kotlin 集成测试：
    - 新 PythonDetectorBridge 能解析新 JSON
    - 新识别结果能正常进入编辑、导出流程
- 许可证验收：
    - 所有新增库都写入 manifest
    - 不存在许可证不明资产
    - 不存在自动下载权重代码路径
- 业务验收：
    - 断网环境可启动、识别、导出
    - 无系统 Python 依赖
    - 识别质量明显高于当前启发式方案

## Assumptions

- 本次目标是“重构到更强识别框架”，不是低风险渐进迁移。
- 单类 icon 实例分割已经满足产品需求，不需要同时做 OCR、分类或多标签检测。
- 用户接受训练链路变重，只要最终桌面端仍是离线 CPU 可运行。
- 若后续需要进一步提速，可在此方案稳定后再评估量化或轻量化模型，但不影响本次框架决
  策。
- 移除旧代码，旧文件，无用文件，无用代码，无用变量，无用参数
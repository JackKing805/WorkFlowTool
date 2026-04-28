
# 图标识别重构方案（免费可商用、完全离线）

## Summary

重构后的 Python、C++、Kotlin 整条链路只使用“免费可商用、可离线运行”的本地库和本地模型，不依
赖联网服务、不调用在线 API、不要求运行时访问网络。

固定原则：

- 依赖许可仅允许宽松许可：MIT、BSD、Apache-2.0、PSF、zlib/libpng 等。
- 所有训练、推理、后处理、导出都必须本地执行。
- 运行时禁止任何联网依赖，包括在线模型下载、在线许可证校验、在线推理服务。
- 项目交付物必须包含本地依赖目录、模型目录、离线安装脚本和版本清单。

## Key Changes

### 1. 依赖策略改为完全离线

- Python 依赖只允许本地 wheel 安装，统一放到仓库内 third_party/wheels/<platform>/。
- 项目内置本地 Python 运行时，放到 third_party/python/<platform>/。
- 本地模型、C++ 依赖头文件/静态资源都放到项目目录下。
- 安装脚本只做“从项目目录解压/安装到项目目录”，不做在线下载。
- PythonRuntime 改为优先且默认只使用项目内 Python，不再探测系统 python/python3。

### 2. Python 模型链路

- 训练、导出、推理全部离线执行。
- 主模型采用本地实例分割方案，训练后导出为本地参数文件。
- 推理仅依赖本地：
    - numpy
    - Pillow
    - 必要时少量本地图像处理库
- 不使用任何需要联网初始化、远程拉模型、远程缓存的框架行为。
- 训练脚本必须显式禁用自动下载预训练权重；若需要初始权重，必须作为本地文件随项目提供。
- 推理输出保持：
    - bbox
    - points
    - score
    - stats

### 3. 数据格式升级

- 将训练标注从 bbox-only 升级为 polygon-aware：
    - instances[].bbox
    - instances[].points
- Kotlin 保存训练样本时，优先记录用户修正后的真实点位。
- 旧 regions[] 数据提供本地迁移脚本，离线转换到新格式。
- 连续学习仍保留，但训练和导出全部在本地执行，不上传任何数据。

### 4. C++ 后端

- C++ 只使用标准库和宽松许可本地库。
- 保留传统检测兜底，同时扩展原生 ABI 支持不定长点位数组。
- C++ 增加本地轮廓提取、多边形压缩、bbox 同步计算。
- 首轮不要求 C++ 跑主模型，也不能接任何联网 SDK。

### 5. Kotlin / 桌面集成

- PythonDetectorBridge 改为标准 JSON 解析，稳定读取 polygon 结果。
- NativeDetectorBridge 同步支持原生 points。
- AppRuntimeFiles 改为从仓库内或打包资源内复制离线依赖，不从外部拉取。
- 应用启动时增加离线依赖自检：
    - 本地 Python 是否存在
    - 本地 wheels/venv 是否完整
    - 本地模型是否存在
    - 本地原生库是否存在

## Approved Offline Dependencies

仅规划这类库，且都以本地文件形式随项目提供：

### Python

- Python：PSF
- PyTorch：BSD-3-Clause，仅用于本地训练
- torchvision：BSD-3-Clause，仅用于本地训练
- numpy：BSD-3-Clause
- Pillow：宽松许可
- 可选 pycocotools：BSD
- 可选 opencv-python-headless：Apache-2.0
- 可选 shapely：BSD-3-Clause

约束：

- 不允许任何 pip 在线安装步骤进入交付主流程。
- 不允许运行时自动下载模型、缓存或数据。

### C++

- C++ 标准库
- 可选 stb_image / stb_image_write：MIT/Public Domain
- 可选 nlohmann/json：MIT

约束：

- 不引入需要系统安装和联网取包的大型原生依赖。
- 不使用任何在线授权或在线激活组件。

## Public Interfaces / Format Changes

- 训练集主格式改为 instances[]，每个实例包含 bbox + points。
- Python 推理输出中 regions[].points 作为正式字段。
- C++ NativeRegion 扩展点位指针和点数。
- Kotlin 桥接统一以 polygon 为主协议，bbox 为兼容字段。

## Test Plan

- 离线性测试：
    - 在断网环境下完成训练、导出、推理、启动应用
    - 运行时不发生外网访问
- 依赖测试：
    - 项目内 Python 能独立创建本地环境
    - 本地 wheel 安装成功
    - 本地模型可直接加载
- 功能测试：
    - 输出 bbox + 不定长 points
    - 无模型时能回退到本地 C++/启发式检测
    - 用户编辑后的 points 能写入训练集并在下次训练生效
- 集成测试：
    - Windows 和 macOS 在无系统 Python、无网络条件下可运行识别
    - 打包后的应用能复制并使用本地依赖目录

## Assumptions / Defaults

- 首轮目标平台仍为 Windows + macOS。
- 所有依赖、模型、权重、wheel、runtime 都由项目随包提供，不依赖网络下载。
- 若某个库虽免费可商用，但其标准使用方式强依赖联网，则不纳入方案。
- 若某个模型权重许可不清或需要在线获取，则直接排除。
- 最终交付将附带一份离线依赖清单和许可证清单，作为商用分发依据。

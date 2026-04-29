
# 代码优化计划

## Summary

继续按“三项都要”的方向做代码优化：减少重复清单、拆分训练样本生成逻辑、降低生成样本时的无效遍历，并保
持现有功能行为不变。实现后，训练仍会使用 seed_images 下全部图片，运行目录安装仍能拿到内置 seed 样
本。

## Key Changes

- 优化资源安装清单：
    - 在 AppRuntimeFiles 中保留脚本、README、requirements、内置 annotation 的显式清单。
    - 新增一个按目录递归发现 python_detector/seed_images 文件的 helper，用它生成 seed 资源列表，替代
      几十行手写 PNG 路径。
    - 发现范围包含 png/jpg/jpeg/webp/md/license 等实际存在文件，但排除 SVG 原始目录、__pycache__、
      training_sets/combined、recent_feedback、模型候选输出。
- 拆分 Python 训练集生成：
    - 新增 python_detector/seed_image_annotations.py。
    - 将 seed_image_records、auto_instances_for_image、背景估计、前景 mask、连通域提取、邻居遍历迁入
      该模块。
    - make_training_set.py 只保留：读取 training_sets/*/annotations.jsonl、调用 seed 图片记录生成、
      去重、写 combined/recent。
- 优化 seed 自动标注性能：
    - 连通域遍历时同步记录 bbox，避免后续再构造 xs/ys。
    - 对小连通域先过滤，再分配 alpha mask。
    - 图片扫描阶段只进入支持的图片后缀，非图片说明/许可证文件不参与处理。
    - 保持输出 schema 不变：image、imageHash、instances，每个 instance 含 bbox/maskWidth/maskHeight/
      alphaMask/label。
- 清理和忽略生成物：
    - 确认 .gitignore 覆盖 python_detector/__pycache__/、python_detector/training_sets/combined/、
      python_detector/training_sets/recent_feedback/、候选/运行时模型输出。
    - 不删除保留的 PNG seed、合成 sheet、许可证和 README。

## Tests

- Python：
    - 新增 seed_image_annotations 单元测试，覆盖透明背景、纯色背景、多连通域、噪点过滤。
    - 运行 python3 -m py_compile 覆盖 make_training_set.py、seed_image_annotations.py、训练/识别脚
      本。
    - 运行 python3 make_training_set.py，确认能重新生成 combined，样本数不低于当前 39。
- Kotlin：
    - 增加或更新 AppRuntimeFiles 相关测试，确认 seed 资源发现包含 PNG/sheets，不包含已删除的 SVG 原
      始目录。
    - 运行 sh gradlew test。

## Assumptions

- 只做代码结构、性能和体积优化，不改变模型训练策略、UI、快捷键或历史记录行为。
- seed_images 下所有支持格式图片都应参与训练；说明和许可证只随资源安装保留，不参与训练。
- 生成目录仍作为运行时产物，不进入源码长期保留。
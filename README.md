# 图标自动裁剪工具

跨平台桌面应用，用于从图片中自动识别图标区域，支持手动编辑裁剪框并批量导出。

## 技术栈

- Kotlin/JVM 17
- Compose Multiplatform Desktop

## 运行

macOS / Linux:

```bash
chmod +x ./gradlew
./gradlew run
```

Windows:

```powershell
.\gradlew.bat run
```

如果你本机已经安装了 `gradle`，也可以直接运行：

```bash
gradle run
```

Python 检测默认优先使用项目内离线 Python 运行时。开发阶段如果还没放入 `third_party/python/<platform>/`，可以显式设置 `WORKFLOWTOOL_ALLOW_SYSTEM_PYTHON=true` 临时回退到系统 Python。

应用启动时会执行一次离线依赖自检，检查：

- 项目内 Python 是否存在
- `third_party/wheels/<platform>/` 下是否存在离线 wheel / venv 文件
- 本地模型是否存在

如果需要提前把仓库内离线资产整理到指定运行目录，可执行：

```bash
python3 scripts/prepare_offline_runtime.py --target /tmp/workflowtool-runtime --clean
```


## 打包

```bash
./gradlew packageDistributionForCurrentOS
```

配置中已包含 Windows `exe/msi`、macOS `dmg`、Linux `deb/rpm` 目标格式。

## 功能

- 导入 PNG/JPG/JPEG/WEBP 图片
- 自动识别非背景图标连通区域
- 自定义矩形裁剪区域
- 移动、缩放、隐藏、删除、多选裁剪区域
- 批量导出 PNG/JPG/WEBP
- 支持透明边距裁剪、补齐正方形、固定尺寸缩放和命名规则

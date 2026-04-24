# 图标自动裁剪工具

跨平台桌面应用，用于从图片中自动识别图标区域，支持手动编辑裁剪框并批量导出。

## 技术栈

- Kotlin/JVM 17
- Compose Multiplatform Desktop
- Java AWT/ImageIO 图像处理核心
- Rust 原生图像检测后端
- `imageproc` 纯 Rust 图像连通域识别

## 运行

```powershell
gradle run
```

如果使用 Gradle Wrapper：

```powershell
.\gradlew.bat run
```


## 打包

```powershell
gradle packageDistributionForCurrentOS
```

配置中已包含 Windows `exe/msi`、macOS `dmg`、Linux `deb/rpm` 目标格式。

## 功能

- 导入 PNG/JPG/JPEG/WEBP 图片
- 自动识别非背景图标连通区域
- 网格拆分
- 自定义矩形裁剪区域
- 移动、缩放、隐藏、删除、多选裁剪区域
- 批量导出 PNG/JPG/WEBP
- 支持透明边距裁剪、补齐正方形、固定尺寸缩放和命名规则

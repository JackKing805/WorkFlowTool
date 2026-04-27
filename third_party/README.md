# Third-Party Offline Assets

该目录用于存放随项目分发的离线依赖。

约束：

- 只允许放入可离线使用、宽松许可的运行时和 wheel
- 不在应用运行时联网下载任何依赖
- 新增文件后需要同步更新 `third_party/offline-assets.txt`

建议布局：

- `python/<platform>/`: 项目内 Python 运行时
- `wheels/<platform>/`: 离线 wheel 或预构建虚拟环境文件
- `THIRD_PARTY_MANIFEST.json`: 依赖版本与许可清单

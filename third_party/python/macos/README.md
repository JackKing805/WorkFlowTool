将 macOS 项目内 Python 运行时放到这里，例如：

- `third_party/python/macos/bin/python3`
- `third_party/python/macos/lib/...`

当前仓库内默认提供的是开发态包装器：

- `bin/python3`
- `bin/python`

它们会转发到系统 `python3`，用于本地开发时跑持续学习和离线脚本。
正式离线分发时应替换成真实的项目内 Python 运行时。

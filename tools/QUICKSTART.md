# 快速开始 - 本地工具设置

## 方法1: 自动下载（推荐）

### Windows
```bash
cd tools
setup_tools.bat
```

### Linux/Mac
```bash
cd tools
chmod +x setup_tools.sh
./setup_tools.sh
```

## 方法2: 手动设置

1. **下载ADB** (~2MB)
   - Windows: https://developer.android.com/studio/releases/platform-tools
   - 解压后复制 `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll` 到 `tools/adb/`

2. **下载JDK 17** (~200MB)
   - https://adoptium.net/
   - 选择 OpenJDK 17
   - 解压到 `tools/java/jdk-17/`

3. **下载Python** (~20MB, 仅Windows)
   - https://www.python.org/downloads/
   - 下载 Windows embeddable package
   - 解压到 `tools/python/python-3.x/`

## 验证设置

运行验证脚本：
```bash
python tools/verify_tools.py
```

或手动检查：
```bash
# ADB
tools/adb/adb version

# Java
tools/java/jdk-17/bin/java -version

# Python (Windows)
tools/python/python-3.x/python.exe --version
```

## 使用

设置完成后，开发工具脚本会自动使用本地工具：

```bash
python dev_tools.py check
python dev_tools.py build-install-launch
```

## 需要帮助？

- 详细说明: [README.md](README.md)
- 手动设置: [manual_setup.md](manual_setup.md)
- 便携设置: [../PORTABLE_SETUP.md](../PORTABLE_SETUP.md)

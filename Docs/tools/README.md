# 本地工具目录

此目录用于存放项目所需的本地工具，实现完全便携开发。

## 目录结构

```
tools/
├── adb/              # Android Debug Bridge
│   ├── adb.exe       # Windows版本
│   ├── AdbWinApi.dll
│   └── AdbWinUsbApi.dll
├── java/             # Java运行时
│   └── jdk-17/       # JDK 17
└── python/           # Python解释器
    └── python-3.x/   # Python 3.x
```

## 如何设置

### 1. ADB工具

#### Windows
1. 下载 Android Platform Tools: https://developer.android.com/studio/releases/platform-tools
2. 解压后，将以下文件复制到 `tools/adb/`:
   - `adb.exe`
   - `AdbWinApi.dll`
   - `AdbWinUsbApi.dll`

#### Linux/Mac
1. 下载 Android Platform Tools
2. 将 `adb` 可执行文件复制到 `tools/adb/adb`

### 2. Java (JDK 17)

#### Windows
1. 下载 OpenJDK 17: https://adoptium.net/
2. 解压后，将整个JDK目录复制到 `tools/java/jdk-17/`

#### Linux/Mac
1. 下载 OpenJDK 17
2. 解压到 `tools/java/jdk-17/`

### 3. Python 3.x

#### Windows
1. 下载 Python 3.x: https://www.python.org/downloads/
2. 选择"Portable"版本或安装后复制整个Python目录到 `tools/python/python-3.x/`
3. 需要包含: `python.exe`, `pythonw.exe`, `python3.dll` 等

#### Linux/Mac
1. 下载 Python 3.x
2. 编译或使用预编译版本，复制到 `tools/python/python-3.x/`

## 自动检测

项目脚本会自动检测并使用本地工具，如果本地工具不存在，会回退到系统PATH中的工具。

## 注意事项

- 这些工具文件较大，建议添加到 `.gitignore`
- 首次设置需要手动下载和放置文件
- 可以创建下载脚本自动化此过程（见 `tools/download_tools.py`）

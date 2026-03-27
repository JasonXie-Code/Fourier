# 手动设置本地工具指南

如果自动下载脚本无法使用，可以手动下载和设置工具。

## 1. ADB (Android Debug Bridge)

### Windows
1. 访问: https://developer.android.com/studio/releases/platform-tools
2. 下载 `platform-tools-latest-windows.zip`
3. 解压后，将以下文件复制到 `tools/adb/`:
   ```
   tools/adb/
   ├── adb.exe
   ├── AdbWinApi.dll
   └── AdbWinUsbApi.dll
   ```

### Linux
1. 下载 `platform-tools-latest-linux.zip`
2. 解压后，将 `adb` 复制到 `tools/adb/adb`
3. 设置执行权限: `chmod +x tools/adb/adb`

### Mac
1. 下载 `platform-tools-latest-darwin.zip`
2. 解压后，将 `adb` 复制到 `tools/adb/adb`
3. 设置执行权限: `chmod +x tools/adb/adb`

## 2. Java JDK 17

### Windows
1. 访问: https://adoptium.net/
2. 下载 OpenJDK 17 (Windows x64)
3. 解压后，将整个JDK目录复制到 `tools/java/jdk-17/`
   ```
   tools/java/jdk-17/
   ├── bin/
   ├── lib/
   ├── include/
   └── ...
   ```

### Linux
1. 下载 OpenJDK 17 (Linux x64)
2. 解压到 `tools/java/jdk-17/`

### Mac
1. 下载 OpenJDK 17 (Mac x64)
2. 解压到 `tools/java/jdk-17/`

## 3. Python 3.x (仅Windows需要)

### Windows
1. 访问: https://www.python.org/downloads/
2. 下载 Python 3.x Windows embeddable package
3. 解压到 `tools/python/python-3.x/`
   ```
   tools/python/python-3.x/
   ├── python.exe
   ├── pythonw.exe
   ├── python3.dll
   └── ...
   ```

### Linux/Mac
通常系统自带Python，无需下载。如需使用特定版本：
1. 下载Python源码或预编译版本
2. 编译或解压到 `tools/python/python-3.x/`

## 验证设置

运行以下命令验证工具是否正确设置：

```bash
# 检查ADB
tools/adb/adb version

# 检查Java
tools/java/jdk-17/bin/java -version

# 检查Python (Windows)
tools/python/python-3.x/python.exe --version
```

## 目录结构示例

```
tools/
├── adb/
│   ├── adb.exe          (Windows)
│   ├── AdbWinApi.dll    (Windows)
│   ├── AdbWinUsbApi.dll (Windows)
│   └── adb              (Linux/Mac)
├── java/
│   └── jdk-17/
│       ├── bin/
│       ├── lib/
│       └── ...
└── python/
    └── python-3.x/
        ├── python.exe   (Windows)
        └── ...
```

## 注意事项

1. **文件大小**: 这些工具文件较大（JDK约200MB，ADB约2MB），建议添加到`.gitignore`
2. **路径**: 确保路径正确，脚本会自动检测
3. **权限**: Linux/Mac需要确保adb有执行权限
4. **版本**: 建议使用指定版本以确保兼容性

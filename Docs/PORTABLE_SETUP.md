# 便携开发环境设置指南

本指南将帮助您设置完全便携的开发环境，所有工具都本地化到项目目录中。

## 快速设置（推荐）

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

## 手动设置

如果自动脚本无法使用，请参考 [tools/manual_setup.md](tools/manual_setup.md)

## 工具目录结构

```
tools/
├── adb/              # Android Debug Bridge
│   ├── adb.exe       # Windows
│   └── adb           # Linux/Mac
├── java/             # Java Development Kit
│   └── jdk-17/       # JDK 17
└── python/           # Python解释器
    └── python-3.x/   # Python 3.x (仅Windows需要)
```

## 设置步骤

### 1. ADB工具 (~2MB)

**自动下载:**
```bash
python tools/download_tools.py
```

**手动设置:**
- 下载: https://developer.android.com/studio/releases/platform-tools
- 解压并复制到 `tools/adb/`

### 2. Java JDK 17 (~200MB)

**自动下载:**
```bash
python tools/download_tools.py
```

**手动设置:**
- 下载: https://adoptium.net/
- 选择 OpenJDK 17
- 解压到 `tools/java/jdk-17/`

### 3. Python 3.x (~20MB, 仅Windows)

**Windows自动下载:**
```bash
python tools/download_tools.py
```

**Linux/Mac:**
通常系统自带Python，无需下载。

## 验证设置

运行以下命令验证：

```bash
# 检查ADB
tools/adb/adb version

# 检查Java
tools/java/jdk-17/bin/java -version

# 检查Python (Windows)
tools/python/python-3.x/python.exe --version
```

## 使用本地工具

设置完成后，`dev_tools.py` 脚本会自动使用本地工具：

```bash
# 脚本会自动检测并使用本地工具
python dev_tools.py check
python dev_tools.py build-install-launch
```

## 优势

✅ **完全便携** - 所有工具都在项目目录中  
✅ **无需配置** - 不需要设置系统PATH  
✅ **版本一致** - 团队使用相同版本的工具  
✅ **离线开发** - 不依赖系统环境  

## 注意事项

1. **文件大小**: 工具文件较大（约250MB），建议添加到`.gitignore`
2. **首次设置**: 需要下载工具，可能需要一些时间
3. **自动回退**: 如果本地工具不存在，脚本会回退到系统PATH中的工具
4. **权限**: Linux/Mac需要确保adb有执行权限

## 故障排除

### 问题: 脚本找不到本地工具

**解决方案:**
1. 检查工具文件是否存在
2. 检查文件路径是否正确
3. 检查文件权限（Linux/Mac）

### 问题: 下载失败

**解决方案:**
1. 检查网络连接
2. 手动下载工具（见 manual_setup.md）
3. 检查下载URL是否有效

### 问题: Java构建失败

**解决方案:**
1. 确认JDK版本为17
2. 检查JAVA_HOME环境变量（脚本会自动设置）
3. 检查Gradle配置

## 更新工具

要更新工具版本：

1. 删除旧版本: `rm -rf tools/adb/*` (或对应目录)
2. 重新运行设置脚本
3. 或手动下载新版本并替换

## 项目大小

设置完成后，项目大小约为：
- 源代码: ~500KB
- ADB: ~2MB
- JDK: ~200MB
- Python: ~20MB (仅Windows)
- **总计**: ~250MB

## 下一步

设置完成后，您可以：

1. 运行 `python dev_tools.py check` 验证环境
2. 运行 `python dev_tools.py build-install-launch` 构建并安装应用
3. 开始开发！

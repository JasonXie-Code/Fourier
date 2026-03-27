# 开发工具脚本使用说明

`dev_tools.py` 是一个便捷的Python脚本，用于简化Android应用的开发、调试和日志管理流程。

## 前置要求

1. **Python 3.6+**
2. **Android SDK Platform Tools** (包含ADB)
   - 确保 `adb` 命令在系统PATH中
   - 下载地址: https://developer.android.com/studio/releases/platform-tools

## 快速开始

### Windows
```bash
python dev_tools.py check
```

### Linux/Mac
```bash
python3 dev_tools.py check
# 或使用包装脚本
chmod +x dev_tools.sh
./dev_tools.sh check
```

## 可用命令

### 1. 检查设备连接
```bash
python dev_tools.py check
```
检查是否有Android设备已连接。

### 2. 构建APK
```bash
python dev_tools.py build
```
使用Gradle构建Debug APK。

### 3. 安装APK
```bash
python dev_tools.py install
# 或指定APK路径
python dev_tools.py install --apk path/to/app.apk
```
安装APK到连接的设备。

### 4. 卸载应用
```bash
python dev_tools.py uninstall
```
从设备卸载应用。

### 5. 启动应用
```bash
python dev_tools.py launch
```
启动应用的主Activity。

### 6. 停止应用
```bash
python dev_tools.py stop
```
强制停止应用。

### 7. 查看实时日志
```bash
# 查看应用日志（自动过滤包名）
python dev_tools.py log

# 查看所有日志
python dev_tools.py log --filter ""

# 查看特定标签的日志
python dev_tools.py log --filter "FourierAudioAnalyzer"
```

### 8. 保存日志到文件
```bash
# 保存日志（按Ctrl+C停止）
python dev_tools.py log-save

# 保存指定时长的日志
python dev_tools.py log-save --duration 30

# 指定文件名
python dev_tools.py log-save --filename my_log.txt

# 过滤特定标签
python dev_tools.py log-save --filter "FourierAudioAnalyzer" --duration 60
```
日志文件保存在 `logs/` 目录中。

### 9. 清除日志
```bash
python dev_tools.py clear-log
```
清除设备上的日志缓冲区。

### 10. 拉取录制文件
```bash
python dev_tools.py pull-recordings
```
从设备拉取录制的音频文件到本地 `recordings/` 目录。

### 11. 拉取截图
```bash
python dev_tools.py pull-screenshots
```
从设备拉取截图文件到本地 `screenshots/` 目录。

### 12. 查看应用信息
```bash
python dev_tools.py info
```
显示应用的包名、版本等信息。

### 13. 授予权限
```bash
python dev_tools.py permissions
```
授予应用所需的权限（如麦克风权限）。

### 14. 完整流程（推荐）
```bash
python dev_tools.py build-install-launch
```
一键执行：构建APK → 安装 → 启动应用。

## 使用示例

### 示例1: 完整开发流程
```bash
# 1. 检查设备
python dev_tools.py check

# 2. 构建、安装并启动
python dev_tools.py build-install-launch

# 3. 查看日志（另一个终端）
python dev_tools.py log

# 4. 保存日志用于调试
python dev_tools.py log-save --duration 60 --filename crash_log.txt
```

### 示例2: 调试崩溃问题
```bash
# 1. 清除旧日志
python dev_tools.py clear-log

# 2. 启动应用
python dev_tools.py launch

# 3. 重现问题，然后保存日志
python dev_tools.py log-save --filename crash_$(date +%Y%m%d_%H%M%S).txt
```

### 示例3: 获取应用数据
```bash
# 拉取所有录制和截图
python dev_tools.py pull-recordings
python dev_tools.py pull-screenshots
```

## 日志文件位置

- **日志文件**: `logs/logcat_YYYYMMDD_HHMMSS.txt`
- **录制文件**: `recordings/`
- **截图文件**: `screenshots/`

## 常见问题

### Q: 提示"未找到ADB命令"
A: 请确保Android SDK Platform Tools已安装，并且 `adb` 在系统PATH中。
   - Windows: 添加到系统环境变量
   - Linux/Mac: 添加到 `~/.bashrc` 或 `~/.zshrc`

### Q: 提示"未检测到设备"
A: 
1. 确保设备通过USB连接
2. 在设备上启用"USB调试"
3. 首次连接时授权此电脑
4. 运行 `adb devices` 检查设备列表

### Q: 安装失败
A:
1. 检查设备是否有足够空间
2. 尝试先卸载: `python dev_tools.py uninstall`
3. 检查APK文件是否存在

### Q: 日志文件太大
A: 使用 `--duration` 参数限制记录时长，或使用 `--filter` 过滤特定标签。

## 高级用法

### 组合命令（Shell脚本）
创建 `quick_deploy.sh`:
```bash
#!/bin/bash
python3 dev_tools.py build-install-launch
sleep 2
python3 dev_tools.py log --filter "FourierAudioAnalyzer"
```

### 自动化测试
```bash
# 运行测试并保存日志
python dev_tools.py clear-log
python dev_tools.py launch
sleep 30  # 运行30秒
python dev_tools.py log-save --duration 30 --filename test_run.txt
python dev_tools.py stop
```

## 提示

1. **使用别名**: 在 `~/.bashrc` 中添加:
   ```bash
   alias adt='python3 /path/to/dev_tools.py'
   ```
   然后可以直接使用 `adt check`

2. **日志过滤**: 使用 `--filter` 参数可以大幅减少日志量，提高可读性

3. **批量操作**: 可以编写简单的shell脚本组合多个命令

4. **定期清理**: 定期清理 `logs/` 目录中的旧日志文件

## 脚本配置

如需修改应用包名或其他配置，编辑 `dev_tools.py` 文件顶部的配置:
```python
APP_PACKAGE = "com.fourier.audioanalyzer"
APP_ACTIVITY = "com.fourier.audioanalyzer.MainActivity"
APK_PATH = "app/build/outputs/apk/debug/app-debug.apk"
```

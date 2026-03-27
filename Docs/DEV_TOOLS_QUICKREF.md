# 开发工具快速参考

## 常用命令速查

```bash
# ========== 设备检查 ==========
python dev_tools.py check                    # 检查设备连接

# ========== 构建和安装 ==========
python dev_tools.py build                    # 构建APK
python dev_tools.py install                  # 安装APK
python dev_tools.py build-install-launch     # 一键：构建→安装→启动

# ========== 应用控制 ==========
python dev_tools.py launch                   # 启动应用
python dev_tools.py stop                     # 停止应用
python dev_tools.py uninstall                # 卸载应用

# ========== 日志管理 ==========
python dev_tools.py log                      # 查看实时日志
python dev_tools.py log-save                 # 保存日志（Ctrl+C停止）
python dev_tools.py log-save --duration 30   # 保存30秒日志
python dev_tools.py clear-log                # 清除日志缓冲区

# ========== 文件管理 ==========
python dev_tools.py pull-recordings          # 拉取录制文件
python dev_tools.py pull-screenshots         # 拉取截图文件

# ========== 信息查询 ==========
python dev_tools.py info                     # 查看应用信息
python dev_tools.py permissions              # 授予权限
```

## 典型工作流程

### 1. 首次部署
```bash
python dev_tools.py check
python dev_tools.py build-install-launch
python dev_tools.py permissions
```

### 2. 日常开发循环
```bash
# 终端1: 构建并安装
python dev_tools.py build-install-launch

# 终端2: 查看日志
python dev_tools.py log
```

### 3. 调试崩溃
```bash
python dev_tools.py clear-log
python dev_tools.py launch
# ... 重现问题 ...
python dev_tools.py log-save --filename crash.txt
```

### 4. 获取应用数据
```bash
python dev_tools.py pull-recordings
python dev_tools.py pull-screenshots
```

## 参数说明

| 参数 | 说明 | 示例 |
|------|------|------|
| `--apk` | 指定APK路径 | `--apk custom.apk` |
| `--filter` | 日志过滤标签 | `--filter FourierAudioAnalyzer` |
| `--duration` | 日志记录时长(秒) | `--duration 60` |
| `--filename` | 日志文件名 | `--filename debug.log` |

## 文件位置

- **日志**: `logs/logcat_*.txt`
- **录制**: `recordings/`
- **截图**: `screenshots/`

## 快捷键

- **Ctrl+C**: 停止日志记录/查看

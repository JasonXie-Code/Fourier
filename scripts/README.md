# 脚本目录

本目录包含项目的辅助脚本文件。

## 📁 脚本分类

### 检查脚本
- `check_status.py` - 检查项目完成情况
- `check_dependencies.py` - 检查项目依赖状态
- `check_offline_status.py` - 检查完全离线开发设置状态
- `check_copy_progress.py` - 检查依赖复制进度

### 复制脚本
- `copy_all_dependencies_fixed.py` - 复制所有依赖到项目目录（完全离线）
- `copy_sdk_only.py` - 只复制Android SDK组件

### 监控脚本
- `monitor_copy_progress.py` - 实时监控依赖复制进度

### 部署脚本
- `portable_deploy.py` - 便携部署脚本
- `一键绿色部署.bat` - Windows一键部署脚本

### 工具脚本
- `download_gradle_wrapper.py` - 下载Gradle Wrapper

## 🚀 使用方法

### 检查项目状态
```bash
python scripts/check_status.py
python scripts/check_dependencies.py
python scripts/check_offline_status.py
```

### 复制依赖（完全离线）
```bash
python scripts/copy_all_dependencies_fixed.py --yes
```

### 监控复制进度
```bash
python scripts/monitor_copy_progress.py
```

### 一键部署
```bash
scripts\一键绿色部署.bat
```

## 📝 说明

这些脚本主要用于：
- 项目设置和配置
- 依赖管理和复制
- 状态检查和监控
- 便携部署

日常开发主要使用根目录的 `dev_tools.py`。

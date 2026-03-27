# 文件组织说明

## 📁 目录结构

### 根目录（核心文件）

根目录只保留核心项目文件：

```
根目录/
├── README.md              # 项目主说明
├── dev_tools.py           # 开发工具脚本（主要）
├── dev_tools.bat          # Windows包装器
├── dev_tools.sh           # Linux/Mac包装器
├── gradlew                # Gradle wrapper (Unix)
├── gradlew.bat            # Gradle wrapper (Windows)
├── build.gradle.kts       # 项目构建配置
├── settings.gradle.kts    # Gradle设置
├── gradle.properties      # Gradle属性
├── local.properties       # Android SDK配置
├── init.gradle.kts        # Gradle初始化脚本
└── .gitignore             # Git忽略配置
```

### Docs/ 目录（所有文档）

所有文档文件都位于 `Docs/` 目录：

- **快速开始**: QUICKSTART.md
- **构建说明**: BUILD_INSTRUCTIONS.md
- **开发工具**: DEV_TOOLS_README.md, DEV_TOOLS_QUICKREF.md
- **功能特性**: FEATURES.md, FEATURE_SUGGESTIONS.md
- **便携设置**: PORTABLE_SETUP.md, SETUP_COMPLETE.md
- **离线设置**: OFFLINE_SETUP_*.md, OFFLINE_STATUS_*.md
- **依赖管理**: DEPENDENCIES_*.md
- **项目状态**: STATUS_REPORT.md
- **工具文档**: tools/ 子目录

### scripts/ 目录（辅助脚本）

所有辅助脚本位于 `scripts/` 目录：

- **检查脚本**: check_*.py
- **复制脚本**: copy_*.py
- **监控脚本**: monitor_*.py
- **部署脚本**: portable_deploy.py, 一键绿色部署.bat
- **工具脚本**: download_*.py

### tools/ 目录（本地工具）

本地开发工具位于 `tools/` 目录：

- **adb/**: Android Debug Bridge
- **java/jdk-17/**: Java Development Kit
- **python/python-3.10/**: Python解释器

## 📝 文件分类

### 核心文件（根目录）
- 项目配置和构建文件
- 主要开发工具脚本
- 项目说明文档

### 文档文件（Docs/）
- 所有说明文档
- 使用指南
- 设置文档

### 辅助脚本（scripts/）
- 检查脚本
- 复制脚本
- 监控脚本
- 部署脚本

## 🎯 使用建议

1. **日常开发**: 使用根目录的 `dev_tools.py`
2. **查看文档**: 访问 `Docs/` 目录
3. **运行辅助脚本**: 使用 `scripts/` 目录中的脚本
4. **检查状态**: 运行 `python scripts/check_status.py`

## 📊 统计

- **根目录文件**: 13个（仅核心文件）
- **Docs目录**: 21个文档文件
- **scripts目录**: 11个脚本文件

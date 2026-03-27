# 本地依赖说明

本项目使用本地依赖库以确保便携开发。所有依赖库都位于此目录中。

## 依赖库结构

```
local_dependencies/
├── android/          # Android相关库
├── kotlin/           # Kotlin相关库
└── third_party/      # 第三方库
```

## 如何添加新的本地依赖

1. 将 `.jar` 或 `.aar` 文件复制到 `app/libs/` 目录
2. 在 `app/build.gradle.kts` 中已配置自动包含 `libs` 目录中的所有jar/aar文件

## 当前使用的依赖

- AndroidX Core KTX
- AndroidX AppCompat
- Material Design Components
- ConstraintLayout
- Kotlin Coroutines
- Lifecycle Components

所有依赖通过Maven仓库下载，如需完全离线开发，请使用Gradle的依赖缓存功能。

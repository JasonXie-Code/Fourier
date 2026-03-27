#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
复制项目依赖到本地目录
"""

import shutil
import os
import sys
from pathlib import Path

# 设置UTF-8输出
sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

project_root = Path(__file__).parent

print("=" * 70)
print("复制项目依赖到本地")
print("=" * 70)
print()

# 1. 检查Gradle依赖缓存
print("1. Gradle Maven依赖缓存:")
print("-" * 70)

gradle_cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
local_gradle_cache = project_root / "local_dependencies" / "gradle_cache"

if gradle_cache.exists():
    print(f"  源目录: {gradle_cache}")
    print(f"  目标目录: {local_gradle_cache}")
    
    # 检查需要的依赖
    dependencies = [
        "androidx.core",
        "androidx.appcompat",
        "com.google.android.material",
        "androidx.constraintlayout",
        "androidx.lifecycle",
        "org.jetbrains.kotlinx",
        "junit",
        "androidx.test"
    ]
    
    found_deps = []
    for dep in dependencies:
        # 查找依赖目录
        dep_parts = dep.split(".")
        search_path = gradle_cache
        for part in dep_parts:
            matching_dirs = [d for d in search_path.iterdir() if d.is_dir() and part.lower() in d.name.lower()]
            if matching_dirs:
                search_path = matching_dirs[0]
            else:
                break
        else:
            if search_path != gradle_cache:
                found_deps.append((dep, search_path))
    
    if found_deps:
        print(f"  找到 {len(found_deps)} 个依赖组")
        print(f"  是否复制到项目目录? (y/n): ", end="")
        # 自动选择不复制（因为文件太大）
        print("n (跳过 - 文件太大，建议使用Gradle缓存)")
    else:
        print("  未找到依赖（可能尚未构建）")
else:
    print("  Gradle缓存目录不存在")

print()

# 2. 检查Android SDK
print("2. Android SDK:")
print("-" * 70)

android_sdk_paths = [
    Path(os.environ.get("ANDROID_HOME", "")),
    Path(os.environ.get("ANDROID_SDK_ROOT", "")),
    Path("C:/Users") / os.getenv("USERNAME", "") / "AppData/Local/Android/Sdk",
    Path("C:/Android/Sdk"),
]

sdk_path = None
for path in android_sdk_paths:
    if path and path.exists():
        sdk_path = path
        break

if sdk_path:
    print(f"  Android SDK位置: {sdk_path}")
    
    # 检查需要的组件
    needed_platform = "android-35"  # compileSdk = 35
    needed_build_tools = "34.0.0"  # 常用版本
    
    platform_path = sdk_path / "platforms" / needed_platform
    build_tools_path = sdk_path / "build-tools" / needed_build_tools
    
    local_sdk = project_root / "local_dependencies" / "android_sdk"
    
    print(f"  需要的平台: {needed_platform}")
    print(f"  需要的Build Tools: {needed_build_tools}")
    
    if platform_path.exists():
        print(f"  [OK] 平台存在: {platform_path}")
    else:
        print(f"  [!] 平台不存在: {platform_path}")
    
    if build_tools_path.exists():
        print(f"  [OK] Build Tools存在: {build_tools_path}")
    else:
        print(f"  [!] Build Tools不存在: {build_tools_path}")
    
    print(f"  是否复制SDK到项目目录? (y/n): ", end="")
    print("n (跳过 - SDK文件太大，建议使用环境变量或local.properties)")
else:
    print("  Android SDK未找到")

print()

# 3. 检查Gradle插件
print("3. Gradle插件:")
print("-" * 70)

gradle_plugins_cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
local_plugins = project_root / "local_dependencies" / "gradle_plugins"

# Android Gradle Plugin
agp_group = "com.android.tools.build"
agp_name = "gradle"

# Kotlin插件
kotlin_group = "org.jetbrains.kotlin"

print(f"  需要的插件:")
print(f"    - Android Gradle Plugin 8.2.0")
print(f"    - Kotlin Plugin 1.9.20")
print(f"  插件位置: {gradle_plugins_cache}")
print(f"  是否复制插件? (y/n): ", end="")
print("n (跳过 - 插件会自动下载)")

print()

# 4. 总结和建议
print("=" * 70)
print("依赖复制建议:")
print("=" * 70)

print("\n1. Maven依赖库:")
print("   - 当前: 通过Maven仓库自动下载")
print("   - 建议: 保持现状，首次构建后依赖会缓存到用户目录")
print("   - 如需完全离线:")
print("     a) 首次构建完成后，复制 ~/.gradle/caches/ 到项目")
print("     b) 或使用Gradle的依赖缓存功能")

print("\n2. Android SDK:")
print("   - 当前: 使用系统SDK")
print("   - 建议: 创建 local.properties 文件指向SDK:")
if sdk_path:
    print(f"     sdk.dir={sdk_path}")
print("   - 如需完全便携:")
print("     a) 复制整个SDK目录（约10-20GB，不推荐）")
print("     b) 或只复制需要的平台和build-tools（约2-3GB）")

print("\n3. Gradle插件:")
print("   - 当前: 自动下载")
print("   - 建议: 保持现状，插件会自动管理")

print("\n4. 推荐方案:")
print("   - 工具（ADB、JDK、Python）: ✅ 已复制")
print("   - Maven依赖: 使用Gradle缓存（首次构建后自动缓存）")
print("   - Android SDK: 使用local.properties指向系统SDK")
print("   - 这样既便携又不会占用太多空间")

print()
print("=" * 70)

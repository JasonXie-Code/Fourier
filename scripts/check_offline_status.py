#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检查完全离线开发设置状态
"""

import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

# 脚本在 scripts/ 下，项目根目录为上级
project_root = Path(__file__).resolve().parent.parent
local_deps = project_root / "android" / "local_dependencies"

print("=" * 70)
print("完全离线开发设置状态检查")
print("=" * 70)
print()

# 1. 检查Android SDK
print("1. Android SDK组件:")
print("-" * 70)

android_sdk = local_deps / "android_sdk"
if android_sdk.exists():
    # Platform
    platforms = android_sdk / "platforms"
    if platforms.exists():
        platform_dirs = [d for d in platforms.iterdir() if d.is_dir()]
        print(f"  Platform: {len(platform_dirs)} 个")
        for p in platform_dirs:
            # 计算大小
            try:
                size = sum(f.stat().st_size for f in p.rglob('*') if f.is_file())
                file_count = sum(1 for f in p.rglob('*') if f.is_file())
                print(f"    [OK] {p.name}: {size / (1024*1024):.1f} MB ({file_count} 个文件)")
            except:
                print(f"    [OK] {p.name}: 正在复制...")
    else:
        print(f"  Platform: [X] 未复制")
    
    # Build Tools
    build_tools = android_sdk / "build-tools"
    if build_tools.exists():
        bt_dirs = [d for d in build_tools.iterdir() if d.is_dir()]
        print(f"  Build Tools: {len(bt_dirs)} 个")
        for bt in bt_dirs:
            try:
                size = sum(f.stat().st_size for f in bt.rglob('*') if f.is_file())
                file_count = sum(1 for f in bt.rglob('*') if f.is_file())
                print(f"    [OK] {bt.name}: {size / (1024*1024):.1f} MB ({file_count} 个文件)")
            except:
                print(f"    [OK] {bt.name}: 正在复制...")
    else:
        print(f"  Build Tools: [X] 未复制")
else:
    print(f"  [X] Android SDK目录不存在")

print()

# 2. 检查Gradle依赖缓存
print("2. Gradle Maven依赖缓存:")
print("-" * 70)

gradle_cache = local_deps / "gradle_cache"
if gradle_cache.exists():
    cache_dirs = [d for d in gradle_cache.iterdir() if d.is_dir()]
    print(f"  依赖组: {len(cache_dirs)} 个")
    
    # 需要的依赖组
    needed_groups = [
        "androidx.core",
        "androidx.appcompat",
        "com.google.android.material",
        "androidx.constraintlayout",
        "androidx.lifecycle",
        "org.jetbrains.kotlinx",
        "junit",
        "androidx.test",
        "com.android.tools.build",
        "org.jetbrains.kotlin"
    ]
    
    found_groups = []
    for group in needed_groups:
        group_parts = group.split(".")
        # 检查是否存在
        found = False
        for cache_dir in cache_dirs:
            if group_parts[0].lower() in cache_dir.name.lower():
                found = True
                found_groups.append(group)
                break
        
        if found:
            print(f"    [OK] {group}")
        else:
            print(f"    [X] {group}")
    
    if len(found_groups) < len(needed_groups):
        print(f"\n  进度: {len(found_groups)}/{len(needed_groups)} 个依赖组已复制")
        print(f"  提示: 需要先运行一次构建以生成依赖缓存")
else:
    print(f"  [X] Gradle缓存目录不存在")

print()

# 3. 检查配置文件
print("3. 配置文件:")
print("-" * 70)

# local.properties（在 android 工程目录下）
local_properties = project_root / "android" / "local.properties"
if local_properties.exists():
    content = local_properties.read_text(encoding='utf-8')
    if "local_dependencies" in content:
        print(f"  [OK] local.properties: 指向本地SDK")
    else:
        print(f"  [!] local.properties: 指向系统SDK")
else:
    print(f"  [X] local.properties: 不存在")

# settings.gradle.kts（在 android 工程目录下）
settings_file = project_root / "android" / "settings.gradle.kts"
if settings_file.exists():
    content = settings_file.read_text(encoding='utf-8')
    if "local_dependencies" in content:
        print(f"  [OK] settings.gradle.kts: 已配置本地仓库")
    else:
        print(f"  [!] settings.gradle.kts: 未配置本地仓库")
else:
    print(f"  [X] settings.gradle.kts: 不存在")

print()

# 4. 检查工具
print("4. 开发工具:")
print("-" * 70)

tools_status = {
    "ADB": (project_root / "tools" / "adb" / "adb.exe").exists(),
    "JDK": (project_root / "tools" / "java" / "jdk-17").exists(),
    "Python": (project_root / "tools" / "python" / "python-3.10").exists(),
}

for tool, status in tools_status.items():
    print(f"  {'[OK]' if status else '[X]'} {tool}")

print()

# 5. 总结
print("=" * 70)
print("完成度总结:")
print("=" * 70)

# 计算完成度
total_items = 0
completed_items = 0

# Android SDK
if platforms.exists() and len(platform_dirs) > 0:
    completed_items += 1
total_items += 1

if build_tools.exists() and len(bt_dirs) > 0:
    completed_items += 1
total_items += 1

# Gradle依赖
if gradle_cache.exists():
    progress = len(found_groups) / len(needed_groups) if needed_groups else 0
    completed_items += progress
    total_items += 1

# 工具
tools_completed = sum(1 for v in tools_status.values() if v)
completed_items += tools_completed
total_items += len(tools_status)

completion_rate = (completed_items / total_items * 100) if total_items > 0 else 0

print(f"\n总体完成度: {completion_rate:.1f}%")
print()

print("详细状态:")
print(f"  Android SDK Platform: {'✅' if platforms.exists() and len(platform_dirs) > 0 else '⏳'}")
print(f"  Android SDK Build Tools: {'✅' if build_tools.exists() and len(bt_dirs) > 0 else '⏳'}")
print(f"  Gradle Maven依赖: {len(found_groups)}/{len(needed_groups)} ({len(found_groups)/len(needed_groups)*100:.0f}%)")
print(f"  开发工具: {tools_completed}/{len(tools_status)} (100%)")

print()
print("=" * 70)

# 下一步建议
print("\n下一步操作:")
if not (build_tools.exists() and len(bt_dirs) > 0):
    print("1. 等待Build Tools复制完成（正在后台运行）")
if len(found_groups) < len(needed_groups):
    print("2. 运行一次构建以生成Maven依赖缓存:")
    print("   gradlew.bat assembleDebug")
    print("3. 然后复制Maven依赖:")
    print("   python copy_all_dependencies_fixed.py --yes")
else:
    print("1. ✅ 所有依赖已复制完成！")
    print("2. 测试离线构建:")
    print("   gradlew.bat assembleDebug --offline")

print()
print("=" * 70)

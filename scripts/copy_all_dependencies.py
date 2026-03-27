#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
复制所有依赖到项目目录，实现完全离线开发
"""

import shutil
import os
import sys
from pathlib import Path

# 设置UTF-8输出
sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

project_root = Path(__file__).parent
local_deps = project_root / "local_dependencies"
local_deps.mkdir(exist_ok=True)

print("=" * 70)
print("复制所有依赖到项目目录 - 完全离线开发")
print("=" * 70)
print()

total_size = 0

# 1. 复制Maven依赖缓存
print("1. 复制Maven依赖缓存...")
print("-" * 70)

gradle_cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
local_gradle_cache = local_deps / "gradle_cache"

if gradle_cache.exists():
    print(f"  源目录: {gradle_cache}")
    print(f"  目标目录: {local_gradle_cache}")
    
    # 需要复制的依赖组
    needed_groups = [
        "androidx.core",
        "androidx.appcompat", 
        "com.google.android.material",
        "androidx.constraintlayout",
        "androidx.lifecycle",
        "org.jetbrains.kotlinx",
        "junit",
        "androidx.test",
        "com.android.tools.build",  # Android Gradle Plugin
        "org.jetbrains.kotlin"      # Kotlin Plugin
    ]
    
    if local_gradle_cache.exists():
        print(f"  [!] 目标目录已存在，是否覆盖? (y/n): ", end="")
        response = input().lower()
        if response != 'y':
            print("  跳过Maven依赖复制")
        else:
            shutil.rmtree(str(local_gradle_cache))
            local_gradle_cache.mkdir(parents=True)
    else:
        local_gradle_cache.mkdir(parents=True)
    
    # 复制依赖
    copied_count = 0
    for group in needed_groups:
        group_parts = group.split(".")
        search_path = gradle_cache
        
        # 查找组目录
        for part in group_parts:
            matching_dirs = [d for d in search_path.iterdir() 
                           if d.is_dir() and part.lower() in d.name.lower()]
            if matching_dirs:
                search_path = matching_dirs[0]
            else:
                search_path = None
                break
        
        if search_path and search_path.exists():
            dest_path = local_gradle_cache / search_path.name
            try:
                if dest_path.exists():
                    shutil.rmtree(str(dest_path))
                shutil.copytree(str(search_path), str(dest_path))
                size = sum(f.stat().st_size for f in dest_path.rglob('*') if f.is_file())
                total_size += size
                print(f"  [OK] {group}: {size / (1024*1024):.1f} MB")
                copied_count += 1
            except Exception as e:
                print(f"  [X] {group}: 复制失败 - {e}")
    
    if copied_count > 0:
        print(f"  ✓ Maven依赖复制完成 ({copied_count}个组)")
    else:
        print("  [!] 未找到依赖（可能尚未构建）")
else:
    print(f"  [X] Gradle缓存目录不存在: {gradle_cache}")

print()

# 2. 复制Android SDK必要组件
print("2. 复制Android SDK必要组件...")
print("-" * 70)

sdk_paths = [
    Path(os.environ.get("ANDROID_HOME", "")),
    Path(os.environ.get("ANDROID_SDK_ROOT", "")),
    Path("C:/Users") / os.getenv("USERNAME", "") / "AppData/Local/Android/Sdk",
    Path("C:/Android/Sdk"),
]

sdk_path = None
for path in sdk_paths:
    if path and path.exists():
        sdk_path = path
        break

if sdk_path:
    print(f"  Android SDK位置: {sdk_path}")
    
    local_sdk = local_deps / "android_sdk"
    local_sdk.mkdir(exist_ok=True)
    
    # 复制Platform
    needed_platform = "android-35"
    platform_source = sdk_path / "platforms" / needed_platform
    platform_dest = local_sdk / "platforms" / needed_platform
    
    if platform_source.exists():
        print(f"  复制Platform: {needed_platform}")
        if platform_dest.exists():
            shutil.rmtree(str(platform_dest))
        platform_dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(str(platform_source), str(platform_dest))
        size = sum(f.stat().st_size for f in platform_dest.rglob('*') if f.is_file())
        total_size += size
        print(f"    [OK] {size / (1024*1024):.1f} MB")
    else:
        print(f"    [X] Platform不存在: {platform_source}")
    
    # 复制Build Tools（尝试多个版本）
    build_tools_versions = ["34.0.0", "36.1.0", "35.0.0"]
    build_tools_copied = False
    
    for version in build_tools_versions:
        bt_source = sdk_path / "build-tools" / version
        if bt_source.exists():
            bt_dest = local_sdk / "build-tools" / version
            print(f"  复制Build Tools: {version}")
            if bt_dest.exists():
                shutil.rmtree(str(bt_dest))
            bt_dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(str(bt_source), str(bt_dest))
            size = sum(f.stat().st_size for f in bt_dest.rglob('*') if f.is_file())
            total_size += size
            print(f"    [OK] {size / (1024*1024):.1f} MB")
            build_tools_copied = True
            break
    
    if not build_tools_copied:
        print("    [X] 未找到Build Tools")
    
    # 复制platform-tools（如果还没有）
    platform_tools_source = sdk_path / "platform-tools"
    platform_tools_dest = local_sdk / "platform-tools"
    
    if platform_tools_source.exists() and not (project_root / "tools" / "adb" / "adb.exe").exists():
        print(f"  复制Platform Tools")
        if platform_tools_dest.exists():
            shutil.rmtree(str(platform_tools_dest))
        platform_tools_dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(str(platform_tools_source), str(platform_tools_dest))
        size = sum(f.stat().st_size for f in platform_tools_dest.rglob('*') if f.is_file())
        total_size += size
        print(f"    [OK] {size / (1024*1024):.1f} MB")
    
    print(f"  ✓ Android SDK组件复制完成")
else:
    print(f"  [X] Android SDK未找到")

print()

# 3. 更新local.properties指向本地SDK
print("3. 更新配置文件...")
print("-" * 70)

local_properties = project_root / "local.properties"
local_sdk_path = local_deps / "android_sdk"

if local_sdk_path.exists():
    # 转换为Windows路径格式
    sdk_dir = str(local_sdk_path).replace('\\', '\\\\')
    content = f"sdk.dir={sdk_dir}\n"
    
    with open(local_properties, 'w', encoding='utf-8') as f:
        f.write(content)
    
    print(f"  [OK] local.properties已更新")
    print(f"       指向: {local_sdk_path}")
else:
    print(f"  [!] 本地SDK目录不存在，保持原有配置")

print()

# 4. 创建Gradle配置以使用本地依赖
print("4. 配置Gradle使用本地依赖...")
print("-" * 70)

# 创建init.gradle.kts来配置本地仓库
init_gradle = project_root / "init.gradle.kts"
init_content = """// 配置使用本地依赖缓存
settingsEvaluated { settings ->
    settings.dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
        repositories {
            // 优先使用本地缓存
            maven {
                url = uri("${rootProject.projectDir}/local_dependencies/gradle_cache")
            }
            // 回退到远程仓库
            google()
            mavenCentral()
        }
    }
}
"""

with open(init_gradle, 'w', encoding='utf-8') as f:
    f.write(init_content)

print(f"  [OK] 已创建 init.gradle.kts")
print(f"       使用: gradlew.bat --init-script init.gradle.kts assembleDebug")

print()

# 总结
print("=" * 70)
print("复制完成总结")
print("=" * 70)
print(f"\n总大小: {total_size / (1024*1024):.1f} MB ({total_size / (1024*1024*1024):.2f} GB)")
print(f"\n已复制到: {local_deps}")
print(f"  - Gradle依赖缓存: {local_gradle_cache}")
print(f"  - Android SDK: {local_sdk_path}")

print("\n下一步:")
print("1. 使用本地SDK构建:")
print("   gradlew.bat assembleDebug")
print("\n2. 如需使用本地依赖缓存，运行:")
print("   gradlew.bat --init-script init.gradle.kts assembleDebug")
print("\n3. 或修改 settings.gradle.kts 添加本地仓库")

print()
print("=" * 70)

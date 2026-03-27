#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
复制所有依赖到项目目录，实现完全离线开发（修复版）
"""

import shutil
import os
import sys
import argparse
from pathlib import Path

# 设置UTF-8输出
sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

# 解析命令行参数
parser = argparse.ArgumentParser(description='复制所有依赖到项目目录')
parser.add_argument('--yes', '-y', action='store_true', help='自动确认所有提示')
args = parser.parse_args()

# 脚本在 scripts/ 下，项目根目录为上级
project_root = Path(__file__).resolve().parent.parent
local_deps = project_root / "android" / "local_dependencies"
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
    print("  正在扫描依赖...")
    
    # 需要复制的依赖组（更精确的匹配）
    needed_deps = {
        "androidx.core": "core-ktx",
        "androidx.appcompat": "appcompat",
        "com.google.android.material": "material",
        "androidx.constraintlayout": "constraintlayout",
        "androidx.lifecycle": "lifecycle",
        "org.jetbrains.kotlinx": "kotlinx-coroutines",
        "junit": "junit",
        "androidx.test": "test",
        "com.android.tools.build": "gradle",  # Android Gradle Plugin
        "org.jetbrains.kotlin": "kotlin"      # Kotlin Plugin
    }
    
    if local_gradle_cache.exists():
        if args.yes:
            print(f"  [!] 目标目录已存在，自动覆盖...")
            shutil.rmtree(str(local_gradle_cache))
            local_gradle_cache.mkdir(parents=True)
        else:
            print(f"  [!] 目标目录已存在，是否覆盖? (y/n): ", end="")
            try:
                response = input().lower()
                if response != 'y':
                    print("  跳过Maven依赖复制")
                    local_gradle_cache = None
                else:
                    shutil.rmtree(str(local_gradle_cache))
                    local_gradle_cache.mkdir(parents=True)
            except EOFError:
                print("  自动覆盖（非交互模式）")
                shutil.rmtree(str(local_gradle_cache))
                local_gradle_cache.mkdir(parents=True)
    else:
        local_gradle_cache.mkdir(parents=True)
    
    if local_gradle_cache:
        # 复制整个files-2.1目录（包含所有依赖）
        print("  正在复制整个依赖缓存目录（这可能需要一些时间）...")
        try:
            # 复制所有子目录
            copied_count = 0
            for item in gradle_cache.iterdir():
                if item.is_dir():
                    dest_item = local_gradle_cache / item.name
                    if dest_item.exists():
                        shutil.rmtree(str(dest_item))
                    shutil.copytree(str(item), str(dest_item))
                    size = sum(f.stat().st_size for f in dest_item.rglob('*') if f.is_file())
                    total_size += size
                    copied_count += 1
                    if copied_count % 10 == 0:
                        print(f"    已复制 {copied_count} 个目录... ({total_size / (1024*1024):.1f} MB)")
            
            print(f"  ✓ Maven依赖复制完成 ({copied_count}个目录, {total_size / (1024*1024):.1f} MB)")
        except Exception as e:
            print(f"  [X] 复制失败: {e}")
            print("  提示: 如果文件太大，可以只复制需要的依赖组")
else:
    print(f"  [X] Gradle缓存目录不存在: {gradle_cache}")
    print("  提示: 请先运行一次构建以生成依赖缓存")

print()

# 2. 复制Android SDK必要组件
print("2. 复制Android SDK必要组件...")
print("-" * 70)

# 从local.properties读取SDK路径
sdk_path = None
local_properties = project_root / "android" / "local.properties"
if local_properties.exists():
    content = local_properties.read_text(encoding='utf-8')
    for line in content.split('\n'):
        if line.startswith('sdk.dir='):
            sdk_path_str = line.split('=', 1)[1].strip().replace('\\\\', '\\')
            sdk_path = Path(sdk_path_str)
            break

# 如果local.properties中没有，尝试常见位置
if not sdk_path or not sdk_path.exists():
    sdk_paths = [
        Path(os.environ.get("ANDROID_HOME", "")),
        Path(os.environ.get("ANDROID_SDK_ROOT", "")),
        Path("C:/Users") / os.getenv("USERNAME", "") / "AppData/Local/Android/Sdk",
        Path("C:/Android/Sdk"),
    ]
    
    for path in sdk_paths:
        if path and path.exists():
            sdk_path = path
            break

if sdk_path and sdk_path.exists():
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
            if args.yes:
                print(f"    [!] 目标已存在，自动覆盖...")
                shutil.rmtree(str(platform_dest))
            else:
                print(f"    [!] 目标已存在，是否覆盖? (y/n): ", end="")
                try:
                    response = input().lower()
                    if response == 'y':
                        shutil.rmtree(str(platform_dest))
                    else:
                        print("    跳过Platform复制")
                        platform_source = None
                except EOFError:
                    print("    自动覆盖（非交互模式）")
                    shutil.rmtree(str(platform_dest))
        
        if platform_source and platform_source.exists():
            platform_dest.parent.mkdir(parents=True, exist_ok=True)
            print("    正在复制（这可能需要几分钟）...")
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
                if args.yes:
                    print(f"    [!] 目标已存在，自动覆盖...")
                    shutil.rmtree(str(bt_dest))
                else:
                    print(f"    [!] 目标已存在，是否覆盖? (y/n): ", end="")
                    try:
                        response = input().lower()
                        if response == 'y':
                            shutil.rmtree(str(bt_dest))
                        else:
                            print("    跳过Build Tools复制")
                            continue
                    except EOFError:
                        print("    自动覆盖（非交互模式）")
                        shutil.rmtree(str(bt_dest))
            
            bt_dest.parent.mkdir(parents=True, exist_ok=True)
            print("    正在复制（这可能需要几分钟）...")
            shutil.copytree(str(bt_source), str(bt_dest))
            size = sum(f.stat().st_size for f in bt_dest.rglob('*') if f.is_file())
            total_size += size
            print(f"    [OK] {size / (1024*1024):.1f} MB")
            build_tools_copied = True
            break
    
    if not build_tools_copied:
        print("    [X] 未找到Build Tools")
    
    print(f"  ✓ Android SDK组件复制完成")
else:
    print(f"  [X] Android SDK未找到")
    print(f"  请检查local.properties或环境变量")

print()

# 3. 更新local.properties指向本地SDK
print("3. 更新配置文件...")
print("-" * 70)

local_sdk_path = local_deps / "android_sdk"

if local_sdk_path.exists() and list(local_sdk_path.iterdir()):
    # 转换为Windows路径格式
    sdk_dir = str(local_sdk_path.absolute()).replace('\\', '\\\\')
    content = f"sdk.dir={sdk_dir}\n"
    
    with open(local_properties, 'w', encoding='utf-8') as f:
        f.write(content)
    
    print(f"  [OK] local.properties已更新")
    print(f"       指向: {local_sdk_path.absolute()}")
else:
    print(f"  [!] 本地SDK目录为空，保持原有配置")

print()

# 4. 创建Gradle配置以使用本地依赖
print("4. 配置Gradle使用本地依赖...")
print("-" * 70)

# 更新settings.gradle.kts添加本地仓库
settings_file = project_root / "android" / "settings.gradle.kts"
if settings_file.exists():
    content = settings_file.read_text(encoding='utf-8')
    
    # 检查是否已有本地仓库配置
    if "local_dependencies" not in content:
        # 在repositories中添加本地仓库
        new_content = content.replace(
            'repositories {',
            '''repositories {
        // 本地依赖缓存（优先）
        maven {
            url = uri("${rootProject.projectDir}/local_dependencies/gradle_cache")
            isAllowInsecureProtocol = true
        }'''
        )
        
        with open(settings_file, 'w', encoding='utf-8') as f:
            f.write(new_content)
        
        print(f"  [OK] settings.gradle.kts已更新，添加本地仓库")
    else:
        print(f"  [OK] settings.gradle.kts已包含本地仓库配置")

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
print("1. 构建项目（将使用本地依赖）:")
print("   gradlew.bat assembleDebug")
print("\n2. 如需完全离线，确保:")
print("   - local.properties指向本地SDK")
print("   - settings.gradle.kts包含本地仓库")

print()
print("=" * 70)

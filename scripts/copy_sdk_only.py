#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
只复制Android SDK组件（Maven依赖可以稍后复制）
"""

import os
import shutil
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

# 脚本在 scripts/ 下，项目根目录为上级
project_root = Path(__file__).resolve().parent.parent
local_deps = project_root / "android" / "local_dependencies"
local_sdk = local_deps / "android_sdk"
local_sdk.mkdir(parents=True, exist_ok=True)

# SDK路径：优先环境变量，回退到当前用户默认安装位置
sdk_path = None
for _candidate in [
    os.environ.get("ANDROID_HOME"),
    os.environ.get("ANDROID_SDK_ROOT"),
    str(Path("C:/Users") / os.getenv("USERNAME", "") / "AppData/Local/Android/Sdk"),
]:
    if _candidate and Path(_candidate).exists():
        sdk_path = Path(_candidate)
        break
if sdk_path is None:
    print("[错误] 未找到 Android SDK，请设置 ANDROID_HOME 环境变量")
    input("按回车键退出...")
    sys.exit(1)

print("=" * 70)
print("复制Android SDK组件")
print("=" * 70)
print()

total_size = 0

# 复制Platform
needed_platform = "android-35"
platform_source = sdk_path / "platforms" / needed_platform
platform_dest = local_sdk / "platforms" / needed_platform

if platform_source.exists():
    print(f"1. 复制Platform: {needed_platform}")
    print(f"   从: {platform_source}")
    print(f"   到: {platform_dest}")
    
    if platform_dest.exists():
        print("   [!] 目标已存在，删除旧文件...")
        shutil.rmtree(str(platform_dest))
    
    print("   正在复制（这可能需要10-30分钟）...")
    platform_dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(str(platform_source), str(platform_dest))
    size = sum(f.stat().st_size for f in platform_dest.rglob('*') if f.is_file())
    total_size += size
    print(f"   [OK] {size / (1024*1024):.1f} MB")
else:
    print(f"   [X] Platform不存在: {platform_source}")

print()

# 复制Build Tools
build_tools_versions = ["34.0.0", "36.1.0", "35.0.0"]
build_tools_copied = False

for version in build_tools_versions:
    bt_source = sdk_path / "build-tools" / version
    if bt_source.exists():
        bt_dest = local_sdk / "build-tools" / version
        print(f"2. 复制Build Tools: {version}")
        print(f"   从: {bt_source}")
        print(f"   到: {bt_dest}")
        
        if bt_dest.exists():
            print("   [!] 目标已存在，删除旧文件...")
            shutil.rmtree(str(bt_dest))
        
        print("   正在复制（这可能需要5-15分钟）...")
        bt_dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(str(bt_source), str(bt_dest))
        size = sum(f.stat().st_size for f in bt_dest.rglob('*') if f.is_file())
        total_size += size
        print(f"   [OK] {size / (1024*1024):.1f} MB")
        build_tools_copied = True
        break

if not build_tools_copied:
    print("2. [X] 未找到Build Tools")

print()

# 更新local.properties
print("3. 更新local.properties...")
local_properties = project_root / "android" / "local.properties"
sdk_dir = str(local_sdk.absolute()).replace('\\', '\\\\')
content = f"sdk.dir={sdk_dir}\n"

with open(local_properties, 'w', encoding='utf-8') as f:
    f.write(content)

print(f"   [OK] local.properties已更新")
print(f"        指向: {local_sdk.absolute()}")

print()
print("=" * 70)
print(f"完成！总大小: {total_size / (1024*1024):.1f} MB ({total_size / (1024*1024*1024):.2f} GB)")
print("=" * 70)

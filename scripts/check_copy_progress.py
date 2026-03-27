#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检查依赖复制进度
"""

import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

# 脚本在 scripts/ 下，项目根目录为上级
project_root = Path(__file__).resolve().parent.parent
local_deps = project_root / "android" / "local_dependencies"

print("=" * 60)
print("依赖复制进度检查")
print("=" * 60)
print()

# 检查Gradle缓存
gradle_cache = local_deps / "gradle_cache"
if gradle_cache.exists():
    cache_dirs = [d for d in gradle_cache.iterdir() if d.is_dir()]
    print(f"Gradle依赖缓存:")
    print(f"  目录数: {len(cache_dirs)}")
    if cache_dirs:
        print(f"  示例目录:")
        for d in cache_dirs[:5]:
            print(f"    - {d.name}")
        if len(cache_dirs) > 5:
            print(f"    ... 还有 {len(cache_dirs) - 5} 个目录")
else:
    print("Gradle依赖缓存: 未开始")

print()

# 检查Android SDK
android_sdk = local_deps / "android_sdk"
if android_sdk.exists():
    platforms = android_sdk / "platforms"
    build_tools = android_sdk / "build-tools"
    
    print(f"Android SDK:")
    
    if platforms.exists():
        platform_dirs = [d for d in platforms.iterdir() if d.is_dir()]
        print(f"  平台: {len(platform_dirs)} 个")
        for p in platform_dirs:
            print(f"    - {p.name}")
    else:
        print(f"  平台: 未开始")
    
    if build_tools.exists():
        bt_dirs = [d for d in build_tools.iterdir() if d.is_dir()]
        print(f"  Build Tools: {len(bt_dirs)} 个")
        for bt in bt_dirs:
            print(f"    - {bt.name}")
    else:
        print(f"  Build Tools: 未开始")
else:
    print("Android SDK: 未开始")

print()
print("=" * 60)
print("提示: 复制过程可能需要30-60分钟")
print("运行 'python monitor_copy_progress.py' 实时监控")
print("=" * 60)

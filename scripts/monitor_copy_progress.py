#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
监控依赖复制进度
"""

import time
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

# 脚本在 scripts/ 下，项目根目录为上级
project_root = Path(__file__).resolve().parent.parent
local_deps = project_root / "android" / "local_dependencies"

print("监控依赖复制进度...")
print("按 Ctrl+C 停止监控\n")

try:
    while True:
        gradle_cache = local_deps / "gradle_cache"
        android_sdk = local_deps / "android_sdk"
        
        # 检查Gradle缓存
        if gradle_cache.exists():
            cache_dirs = [d for d in gradle_cache.iterdir() if d.is_dir()]
            cache_size = sum(
                sum(f.stat().st_size for f in d.rglob('*') if f.is_file())
                for d in cache_dirs[:10]  # 只计算前10个以加快速度
            )
            print(f"\rGradle缓存: {len(cache_dirs)} 个目录, ~{cache_size / (1024*1024):.1f} MB", end="")
        else:
            print(f"\rGradle缓存: 未开始", end="")
        
        # 检查Android SDK
        if android_sdk.exists():
            platforms = android_sdk / "platforms"
            build_tools = android_sdk / "build-tools"
            
            platform_count = len(list(platforms.iterdir())) if platforms.exists() else 0
            bt_count = len(list(build_tools.iterdir())) if build_tools.exists() else 0
            
            print(f" | Android SDK: {platform_count} 平台, {bt_count} Build Tools", end="")
        else:
            print(f" | Android SDK: 未开始", end="")
        
        sys.stdout.flush()
        time.sleep(2)
        
except KeyboardInterrupt:
    print("\n\n监控已停止")

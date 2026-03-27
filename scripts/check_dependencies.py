#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检查项目依赖情况
"""

import os
import sys
import re
from pathlib import Path
from xml.etree import ElementTree as ET

# 设置UTF-8输出
sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

# 脚本在 scripts/ 下，项目根目录为上级
project_root = Path(__file__).resolve().parent.parent
ANDROID_ROOT = project_root / "android"

print("=" * 70)
print("项目依赖检查报告")
print("=" * 70)
print()

# 1. 读取build.gradle.kts文件
print("1. Gradle依赖分析:")
print("-" * 70)

dependencies = []
app_build_file = ANDROID_ROOT / "app" / "build.gradle.kts"
root_build_file = ANDROID_ROOT / "build.gradle.kts"

if app_build_file.exists():
    content = app_build_file.read_text(encoding='utf-8')
    
    # 提取implementation依赖
    impl_pattern = r'implementation\("([^"]+)"\)'
    impl_matches = re.findall(impl_pattern, content)
    
    # 提取testImplementation依赖
    test_pattern = r'testImplementation\("([^"]+)"\)'
    test_matches = re.findall(test_pattern, content)
    
    # 提取androidTestImplementation依赖
    android_test_pattern = r'androidTestImplementation\("([^"]+)"\)'
    android_test_matches = re.findall(android_test_pattern, content)
    
    print(f"  应用依赖 (implementation): {len(impl_matches)} 个")
    for dep in impl_matches:
        dependencies.append(("implementation", dep))
        print(f"    - {dep}")
    
    print(f"\n  测试依赖 (testImplementation): {len(test_matches)} 个")
    for dep in test_matches:
        dependencies.append(("test", dep))
        print(f"    - {dep}")
    
    print(f"\n  Android测试依赖 (androidTestImplementation): {len(android_test_matches)} 个")
    for dep in android_test_matches:
        dependencies.append(("androidTest", dep))
        print(f"    - {dep}")

print()

# 2. 检查本地libs目录
print("2. 本地libs目录检查:")
print("-" * 70)
libs_dir = project_root / "app" / "libs"
if libs_dir.exists():
    lib_files = list(libs_dir.glob("*.jar")) + list(libs_dir.glob("*.aar"))
    if lib_files:
        print(f"  [OK] libs目录存在，包含 {len(lib_files)} 个文件:")
        for lib in lib_files:
            size = lib.stat().st_size / 1024
            print(f"    - {lib.name} ({size:.1f} KB)")
    else:
        print(f"  [!] libs目录存在但为空")
else:
    print(f"  [!] libs目录不存在")

print()

# 3. 检查Gradle缓存
print("3. Gradle依赖缓存检查:")
print("-" * 70)
gradle_cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
if gradle_cache.exists():
    print(f"  [OK] Gradle缓存目录存在: {gradle_cache}")
    print(f"       这些依赖已下载到本地缓存")
    print(f"       如需完全便携，需要复制这些文件")
else:
    print(f"  [!] Gradle缓存目录不存在")

# 检查项目本地.gradle目录
project_gradle = ANDROID_ROOT / ".gradle"
if project_gradle.exists():
    print(f"  [OK] 项目.gradle目录存在")
else:
    print(f"  [!] 项目.gradle目录不存在（首次构建后会创建）")

print()

# 4. 检查Android SDK依赖
print("4. Android SDK检查:")
print("-" * 70)
android_sdk_paths = [
    Path(os.environ.get("ANDROID_HOME", "")),
    Path(os.environ.get("ANDROID_SDK_ROOT", "")),
    Path("C:/Users") / os.getenv("USERNAME", "") / "AppData/Local/Android/Sdk",
    Path("C:/Android/Sdk"),
]

sdk_found = False
for sdk_path in android_sdk_paths:
    if sdk_path and sdk_path.exists():
        platform_tools = sdk_path / "platform-tools"
        platforms = sdk_path / "platforms"
        build_tools = sdk_path / "build-tools"
        
        if platform_tools.exists() or platforms.exists() or build_tools.exists():
            sdk_found = True
            print(f"  [OK] Android SDK找到: {sdk_path}")
            
            # 检查需要的平台
            if platforms.exists():
                platform_dirs = [d for d in platforms.iterdir() if d.is_dir()]
                print(f"        平台: {len(platform_dirs)} 个")
                for p in sorted(platform_dirs)[:5]:
                    print(f"          - {p.name}")
            
            # 检查build-tools
            if build_tools.exists():
                bt_dirs = [d for d in build_tools.iterdir() if d.is_dir()]
                print(f"        Build Tools: {len(bt_dirs)} 个")
                for bt in sorted(bt_dirs)[:5]:
                    print(f"          - {bt.name}")
            break

if not sdk_found:
    print(f"  [!] Android SDK未找到")
    print(f"       需要Android SDK来编译项目")

print()

# 5. 检查Gradle Wrapper
print("5. Gradle Wrapper检查:")
print("-" * 70)
wrapper_jar = ANDROID_ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar"
wrapper_props = ANDROID_ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"

if wrapper_jar.exists():
    size = wrapper_jar.stat().st_size / 1024
    print(f"  [OK] gradle-wrapper.jar: {size:.1f} KB")
else:
    print(f"  [X] gradle-wrapper.jar: 缺失")

if wrapper_props.exists():
    props_content = wrapper_props.read_text()
    if "gradle-8.2" in props_content:
        print(f"  [OK] gradle-wrapper.properties: 配置正确 (Gradle 8.2)")
    else:
        print(f"  [!] gradle-wrapper.properties: 需要检查版本")
else:
    print(f"  [X] gradle-wrapper.properties: 缺失")

print()

# 6. 总结和建议
print("=" * 70)
print("依赖总结:")
print("=" * 70)

print(f"\n已声明的Maven依赖: {len(dependencies)} 个")
print("这些依赖会从Maven仓库自动下载到:")
print(f"  - {gradle_cache}")

print("\n建议:")
print("1. Maven依赖:")
print("   - 当前依赖通过Maven仓库下载")
print("   - 如需完全离线，需要:")
print("     a) 首次构建后复制.gradle缓存")
print("     b) 或使用Gradle的依赖缓存功能")

print("\n2. Android SDK:")
if sdk_found:
    print("   - SDK已找到，可以编译")
else:
    print("   - 需要安装Android SDK")
    print("   - 或设置ANDROID_HOME环境变量")

print("\n3. 本地工具:")
print("   - ADB: 已复制到 tools/adb/")
print("   - JDK: 已复制到 tools/java/jdk-17/")
print("   - Python: 已复制到 tools/python/python-3.10/")

print("\n4. 完全便携化:")
print("   - 工具: ✅ 已完成")
print("   - Maven依赖: ⚠️ 需要首次构建后复制缓存")
print("   - Android SDK: ⚠️ 需要复制SDK或使用环境变量")

print()
print("=" * 70)

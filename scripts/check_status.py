#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检查项目完成情况
"""

import os
import sys
from pathlib import Path

# 设置UTF-8输出
sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

# 脚本在 scripts/ 下，项目根目录为上级
project_root = Path(__file__).resolve().parent.parent
ANDROID_ROOT = project_root / "android"

print("=" * 60)
print("项目完成情况检查")
print("=" * 60)
print()

# 1. 检查工具目录
print("1. 工具目录检查:")
print("-" * 60)

tools_dir = project_root / "tools"
tools_status = {
    "ADB": False,
    "Java": False,
    "Python": False
}

# 检查ADB
adb_exe = tools_dir / "adb" / "adb.exe"
if adb_exe.exists():
    tools_status["ADB"] = True
    print(f"  [OK] ADB: {adb_exe}")
    # 检查相关DLL
    dlls = ["AdbWinApi.dll", "AdbWinUsbApi.dll"]
    for dll in dlls:
        dll_path = tools_dir / "adb" / dll
        if dll_path.exists():
            print(f"        - {dll}: OK")
        else:
            print(f"        - {dll}: 缺失")
else:
    print(f"  [X] ADB: 未找到")

# 检查Java
java_dir = tools_dir / "java"
if java_dir.exists():
    jdk_dirs = list(java_dir.iterdir())
    if jdk_dirs:
        tools_status["Java"] = True
        for jdk in jdk_dirs:
            if jdk.is_dir():
                java_exe = jdk / "bin" / "java.exe"
                if java_exe.exists():
                    print(f"  [OK] Java: {jdk.name}")
                else:
                    print(f"  [!] Java目录存在但java.exe未找到: {jdk}")
    else:
        print(f"  [X] Java: 目录存在但为空")
else:
    print(f"  [X] Java: 目录不存在")

# 检查Python
python_dir = tools_dir / "python"
if python_dir.exists():
    python_dirs = list(python_dir.iterdir())
    if python_dirs:
        for py_dir in python_dirs:
            if py_dir.is_dir():
                python_exe = py_dir / "python.exe"
                if python_exe.exists():
                    tools_status["Python"] = True
                    print(f"  [OK] Python: {py_dir.name}")
                    break
        if not tools_status["Python"]:
            print(f"  [X] Python: 目录存在但python.exe未找到")
    else:
        print(f"  [X] Python: 目录存在但为空")
else:
    print(f"  [X] Python: 目录不存在")

print()

# 2. 检查Gradle
print("2. Gradle检查:")
print("-" * 60)
gradle_wrapper_jar = ANDROID_ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar"
if gradle_wrapper_jar.exists():
    size = gradle_wrapper_jar.stat().st_size / 1024
    print(f"  [OK] Gradle Wrapper JAR: {size:.1f} KB")
else:
    print(f"  [X] Gradle Wrapper JAR: 未找到")

gradlew_bat = ANDROID_ROOT / "gradlew.bat"
if gradlew_bat.exists():
    print(f"  [OK] gradlew.bat: 存在")
else:
    print(f"  [X] gradlew.bat: 未找到")

print()

# 3. 检查编译输出
print("3. 编译输出检查:")
print("-" * 60)
apk_path = ANDROID_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
if apk_path.exists():
    size = apk_path.stat().st_size / (1024 * 1024)
    print(f"  [OK] APK已构建: {size:.2f} MB")
    print(f"       路径: {apk_path}")
else:
    print(f"  [X] APK: 未构建")
    print(f"       路径: {apk_path}")

build_dir = ANDROID_ROOT / "app" / "build"
if build_dir.exists():
    print(f"  [OK] build目录: 存在")
else:
    print(f"  [!] build目录: 不存在（尚未编译）")

print()

# 4. 检查文档
print("4. 文档检查:")
print("-" * 60)
docs_dir = project_root / "Docs"
if docs_dir.exists():
    doc_files = list(docs_dir.glob("*.md"))
    print(f"  [OK] Docs目录: {len(doc_files)} 个文档文件")
else:
    print(f"  [X] Docs目录: 不存在")

print()

# 5. 检查脚本
print("5. 脚本检查:")
print("-" * 60)
# 脚本分布：根目录与 scripts/
script_checks = [
    ("一键绿色部署.bat", project_root),
    ("scripts/portable_deploy.py", project_root / "scripts" / "portable_deploy.py"),
    ("scripts/dev_tools.py", project_root / "scripts" / "dev_tools.py"),
]
for name, script_path in script_checks:
    if script_path.exists():
        print(f"  [OK] {name}")
    else:
        print(f"  [X] {name}: 未找到")

print()

# 总结
print("=" * 60)
print("总结:")
print("=" * 60)

all_tools = all(tools_status.values())
if all_tools:
    print("  [OK] 所有工具已复制到项目目录")
else:
    print("  [!] 部分工具缺失:")
    for tool, status in tools_status.items():
        status_str = "OK" if status else "缺失"
        print(f"        - {tool}: {status_str}")

if apk_path.exists():
    print("  [OK] APK已成功构建")
else:
    print("  [!] APK尚未构建，运行以下命令构建:")
    print("        python dev_tools.py build")
    print("        或")
    print("        gradlew.bat assembleDebug")

print()
print("=" * 60)

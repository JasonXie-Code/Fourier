#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
复制系统工具到项目目录
"""

import shutil
import os
import sys
import io
from pathlib import Path

# 设置UTF-8输出
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# 项目根目录
# 脚本在 scripts/ 下，项目根目录为上级
project_root = Path(__file__).resolve().parent.parent
tools_dir = project_root / "tools"

# 创建目录结构
tools_dir.mkdir(exist_ok=True)
(tools_dir / "adb").mkdir(exist_ok=True)
(tools_dir / "java").mkdir(exist_ok=True)
(tools_dir / "python").mkdir(exist_ok=True)

print("正在复制工具到项目目录...\n")

# 1. 复制ADB
print("1. 复制ADB工具...")
# 动态查找ADB路径
adb_source = None
# 尝试多个可能的ADB位置
adb_paths = [
    Path("C:/Program Files/platform-tools"),
    Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools" if os.environ.get("ANDROID_HOME") else None,
    project_root / "tools" / "adb",
]
for path in adb_paths:
    if path and path.exists():
        adb_source = path
        break
adb_dest = tools_dir / "adb"

if adb_source.exists():
    # 复制ADB相关文件
    adb_files = ["adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll"]
    copied = 0
    for file in adb_files:
        src = adb_source / file
        if src.exists():
            dst = adb_dest / file
            shutil.copy2(str(src), str(dst))
            print(f"   [OK] {file}")
            copied += 1
        else:
            print(f"   [SKIP] 未找到: {file}")
    
    if copied > 0:
        print(f"   [OK] ADB复制完成 ({copied}个文件)")
    else:
        print("   [FAIL] 未找到ADB文件")
else:
    print(f"   [FAIL] ADB目录不存在: {adb_source}")

# 2. 复制Java/JDK
print("\n2. 复制Java JDK...")
# 尝试多个可能的JDK位置
java_paths = [
    Path("C:/Program Files/Java"),
    Path("C:/Program Files (x86)/Java"),
    Path(os.environ.get("JAVA_HOME", "")),
    Path("C:/Program Files/Eclipse Adoptium"),
    Path("C:/Program Files/Microsoft"),
]

jdk_found = False
for java_base in java_paths:
    if not java_base or not java_base.exists():
        continue
    
    # 查找JDK目录
    for jdk_dir in java_base.iterdir():
        if jdk_dir.is_dir() and ("jdk" in jdk_dir.name.lower() or "java" in jdk_dir.name.lower()):
            # 检查是否是有效的JDK目录（有bin/java.exe）
            java_exe = jdk_dir / "bin" / "java.exe"
            if java_exe.exists():
                jdk_dest = tools_dir / "java" / "jdk-17"
                if jdk_dest.exists():
                    shutil.rmtree(str(jdk_dest))
                
                print(f"   找到JDK: {jdk_dir}")
                print(f"   正在复制到: {jdk_dest}")
                shutil.copytree(str(jdk_dir), str(jdk_dest), dirs_exist_ok=True)
                print(f"   [OK] JDK复制完成")
                jdk_found = True
                break
    
    if jdk_found:
        break

if not jdk_found:
    print("   [WARN] 未找到JDK目录，请手动复制")
    print("   或者使用Java 21（如果兼容）")

# 3. 复制Python
print("\n3. 复制Python...")
# 使用项目目录中的Python（动态查找）
python_source = None
python_dirs = ["python-3.10", "python-3.11", "python-3.12", "python-3.13"]
for py_dir in python_dirs:
    py_path = project_root / "tools" / "python" / py_dir / "python.exe"
    if py_path.exists():
        python_source = project_root / "tools" / "python" / py_dir
        break
if python_source is None:
    python_source = project_root / "tools" / "python" / "python-3.10"  # 回退到找到的版本
python_dest = tools_dir / "python" / "python-3.10"

if python_source.exists():
    if python_dest.exists():
        shutil.rmtree(str(python_dest))
    
    print(f"   正在复制Python从: {python_source}")
    print(f"   复制到: {python_dest}")
    
    # 复制整个Python目录
    shutil.copytree(str(python_source), str(python_dest), dirs_exist_ok=True)
    print(f"   [OK] Python复制完成")
else:
    print(f"   [FAIL] Python目录不存在: {python_source}")

print("\n" + "="*50)
print("复制完成！")
print("="*50)
print(f"\n工具目录结构:")
print(f"  {tools_dir}/")
if (tools_dir / "adb" / "adb.exe").exists():
    print(f"    adb/ - [OK]")
else:
    print(f"    adb/ - [FAIL]")
if (tools_dir / "java" / "jdk-17").exists() or list((tools_dir / "java").iterdir()):
    print(f"    java/ - [OK]")
else:
    print(f"    java/ - [FAIL]")
if (tools_dir / "python" / "python-3.10").exists():
    print(f"    python/ - [OK]")
else:
    print(f"    python/ - [FAIL]")

print("\n运行以下命令验证:")
print("  python tools/verify_tools.py")

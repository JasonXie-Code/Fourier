#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证本地工具是否正确设置
"""

import os
import sys
import platform
import subprocess
from pathlib import Path

TOOLS_DIR = Path(__file__).parent
IS_WINDOWS = platform.system() == "Windows"

def check_tool(name, path, test_cmd=None):
    """检查工具是否存在并可执行"""
    print(f"\n检查 {name}...")
    
    if not path.exists():
        print(f"  ✗ 未找到: {path}")
        return False
    
    print(f"  ✓ 文件存在: {path}")
    
    if test_cmd:
        try:
            result = subprocess.run(
                test_cmd,
                capture_output=True,
                text=True,
                timeout=5
            )
            if result.returncode == 0:
                print(f"  ✓ 可以执行")
                if result.stdout:
                    print(f"  输出: {result.stdout.strip()[:100]}")
                return True
            else:
                print(f"  ✗ 执行失败: {result.stderr}")
                return False
        except Exception as e:
            print(f"  ✗ 执行错误: {e}")
            return False
    
    return True

def main():
    print("=" * 50)
    print("本地工具验证")
    print("=" * 50)
    
    # ADB
    adb_path = TOOLS_DIR / "adb" / ("adb.exe" if IS_WINDOWS else "adb")
    adb_ok = check_tool("ADB", adb_path, [str(adb_path), "version"])
    
    # Java
    java_path = TOOLS_DIR / "java" / "jdk-17" / "bin" / ("java.exe" if IS_WINDOWS else "java")
    java_ok = check_tool("Java JDK", java_path, [str(java_path), "-version"])
    
    # Python (仅Windows需要)
    if IS_WINDOWS:
        python_path = TOOLS_DIR / "python" / "python-3.x" / "python.exe"
        python_ok = check_tool("Python", python_path, [str(python_path), "--version"])
    else:
        print("\n检查 Python...")
        print("  ℹ Linux/Mac通常使用系统Python")
        python_ok = True
    
    # 总结
    print("\n" + "=" * 50)
    print("验证结果:")
    print(f"  ADB:   {'✓' if adb_ok else '✗'}")
    print(f"  Java:  {'✓' if java_ok else '✗'}")
    print(f"  Python: {'✓' if python_ok else '✗'}")
    print("=" * 50)
    
    if adb_ok and java_ok and python_ok:
        print("\n✓ 所有工具设置正确！")
        return 0
    else:
        print("\n✗ 部分工具未正确设置")
        print("\n请参考以下文档:")
        print("  - Docs/tools/README.md")
        print("  - Docs/tools/manual_setup.md")
        print("  - Docs/PORTABLE_SETUP.md")
        return 1

if __name__ == "__main__":
    sys.exit(main())

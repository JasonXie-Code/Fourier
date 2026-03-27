#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键绿色部署
将项目中的 Python 文件及工具引用改为使用项目目录内的路径，便于复制后在新位置直接使用。

若本机未安装 Python，请双击运行项目根目录下的「一键绿色部署.bat」，会使用项目内 tools\\python 下的 Python。
"""

import os
import subprocess
import sys
from pathlib import Path

# 项目根目录（本脚本在 scripts/ 子目录下）
PROJECT_ROOT = Path(__file__).resolve().parent.parent
os.chdir(PROJECT_ROOT)


def find_local_python():
    """查找项目目录中的 Python（优先 tools/python/python-3.x）"""
    candidates = [
        PROJECT_ROOT / "tools" / "python" / "python-3.10" / "python.exe",
        PROJECT_ROOT / "tools" / "python" / "python-3.11" / "python.exe",
        PROJECT_ROOT / "tools" / "python" / "python-3.12" / "python.exe",
        PROJECT_ROOT / "tools" / "python" / "python-3.13" / "python.exe",
    ]
    for p in candidates:
        if p.exists():
            return str(p)
    # 尝试任意 python-3.x
    tools_python = PROJECT_ROOT / "tools" / "python"
    if tools_python.exists():
        for d in sorted(tools_python.iterdir()):
            if d.is_dir() and d.name.startswith("python-3."):
                exe = d / "python.exe"
                if exe.exists():
                    return str(exe)
    return None


def main():
    print("=" * 40)
    print("一键绿色部署")
    print("=" * 40)
    print()
    print("项目根目录:", PROJECT_ROOT)
    print()

    python_exe = find_local_python()
    if python_exe:
        print("[找到] 项目 Python:", python_exe)
    else:
        print("[信息] 未找到项目目录中的 Python，将使用系统 Python")
        python_exe = sys.executable
    print()

    print("=" * 40)
    print("正在执行部署...")
    print("=" * 40)
    print()

    deploy_script = PROJECT_ROOT / "scripts" / "portable_deploy.py"
    if not deploy_script.exists():
        print("[错误] 未找到 scripts\\portable_deploy.py")
        input("按回车键退出...")
        return 1

    code = subprocess.run(
        [python_exe, str(deploy_script)],
        cwd=PROJECT_ROOT,
    ).returncode

    print()
    if code == 0:
        print("=" * 40)
        print("部署成功完成！")
        print("=" * 40)
        print()
        print("提示:")
        print("  - 所有 Python 文件已更新为使用项目相对路径")
        print("  - 复制到新电脑/新目录后，在新位置再运行本脚本一次即可")
        print("  - 首次编译时运行 安装调试App.py 选 2 或 9 可自动写入本机 SDK 路径")
        print()
    else:
        print("=" * 40)
        print("部署过程中出现错误")
        print("=" * 40)
        print()

    input("按回车键退出...")
    return code


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键绿色部署脚本
将所有Python文件中的硬编码路径改为项目相对路径
"""

import os
import re
import sys
from pathlib import Path

# 项目根目录（脚本在 scripts/ 子目录下）
PROJECT_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = Path(__file__).resolve().parent

def find_local_python():
    """查找项目目录中的Python"""
    python_dirs = ["python-3.10", "python-3.11", "python-3.12", "python-3.13"]
    for py_dir in python_dirs:
        py_path = PROJECT_ROOT / "tools" / "python" / py_dir / "python.exe"
        if py_path.exists():
            return py_path
    return None

def make_portable():
    """将所有硬编码路径改为项目相对路径"""
    changes_made = False
    
    print("=" * 60)
    print("一键绿色部署 - 修改文件路径")
    print("=" * 60)
    print()
    
    # 1. 修改 copy_tools.py
    copy_tools_file = SCRIPTS_DIR / "copy_tools.py"
    if copy_tools_file.exists():
        print("正在修改: copy_tools.py")
        try:
            content = copy_tools_file.read_text(encoding='utf-8')
            original_content = content
            
            # 替换硬编码的ADB路径为动态查找
            if 'adb_source = Path("C:/Program Files/platform-tools")' in content:
                replacement = '''# 动态查找ADB路径
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
        break'''
                content = content.replace(
                    'adb_source = Path("C:/Program Files/platform-tools")',
                    replacement
                )
            
            # 替换硬编码的Python路径为项目相对路径（动态查找）
            if 'python_source = Path("C:/Users/Jason Xie/AppData/Local/Programs/Python/Python310")' in content:
                # 查找项目中的Python版本
                python_dirs = ["python-3.10", "python-3.11", "python-3.12", "python-3.13"]
                python_version = None
                for py_dir in python_dirs:
                    py_path = PROJECT_ROOT / "tools" / "python" / py_dir / "python.exe"
                    if py_path.exists():
                        python_version = py_dir
                        break
                
                if python_version:
                    replacement = f'''# 使用项目目录中的Python（动态查找）
python_source = None
python_dirs = ["python-3.10", "python-3.11", "python-3.12", "python-3.13"]
for py_dir in python_dirs:
    py_path = project_root / "tools" / "python" / py_dir / "python.exe"
    if py_path.exists():
        python_source = project_root / "tools" / "python" / py_dir
        break
if python_source is None:
    python_source = project_root / "tools" / "python" / "{python_version}"  # 回退到找到的版本'''
                else:
                    replacement = '''# 使用项目目录中的Python
python_source = project_root / "tools" / "python" / "python-3.10"  # 默认版本，如果不存在请手动指定'''
                
                content = content.replace(
                    'python_source = Path("C:/Users/Jason Xie/AppData/Local/Programs/Python/Python310")',
                    replacement
                )
            
            if content != original_content:
                copy_tools_file.write_text(content, encoding='utf-8')
                print("  [✓] copy_tools.py 已更新")
                changes_made = True
            else:
                print("  [跳过] copy_tools.py 无需修改")
        except Exception as e:
            print(f"  [✗] 修改失败: {e}")
    
    # 2. 修改 dev_tools.bat 使用项目Python
    dev_tools_bat = SCRIPTS_DIR / "dev_tools.bat"
    if dev_tools_bat.exists():
        print("\n正在修改: dev_tools.bat")
        try:
            content = dev_tools_bat.read_text(encoding='utf-8')
            original_content = content
            
            # 查找项目Python
            local_python = find_local_python()
            
            if local_python:
                # 构建新的命令，使用项目Python的绝对路径
                # 使用%~dp0确保路径正确，转义路径中的反斜杠
                python_path = str(local_python).replace('\\', '\\\\')
                new_line = f'"{python_path}" "%~dp0dev_tools.py" %*'
                
                # 替换python命令（支持多种格式）
                replaced = False
                if 'python dev_tools.py %*' in content:
                    content = content.replace(
                        'python dev_tools.py %*',
                        new_line
                    )
                    replaced = True
                elif 'python "%~dp0dev_tools.py" %*' in content:
                    content = content.replace(
                        'python "%~dp0dev_tools.py" %*',
                        new_line
                    )
                    replaced = True
                
                if replaced:
                    dev_tools_bat.write_text(content, encoding='utf-8')
                    print(f"  [✓] dev_tools.bat 已更新，使用: {local_python.name}")
                    changes_made = True
                else:
                    # 检查是否已经使用了项目Python
                    if python_path.replace('\\\\', '\\') in content or str(local_python) in content:
                        print("  [跳过] dev_tools.bat 已使用项目Python")
                    else:
                        print(f"  [警告] 未找到可替换的python命令，手动添加: {new_line}")
            else:
                print("  [警告] 未找到项目Python，保持使用系统Python")
        except Exception as e:
            print(f"  [✗] 修改失败: {e}")
    
    # 3. 检查其他文件
    print("\n检查其他文件:")
    other_files = [
        (SCRIPTS_DIR / "dev_tools.py", "scripts/dev_tools.py"),
        (PROJECT_ROOT / "tools" / "download_tools.py", "tools/download_tools.py"),
        (PROJECT_ROOT / "tools" / "verify_tools.py", "tools/verify_tools.py"),
    ]
    
    for file_path, display_name in other_files:
        if file_path.exists():
            try:
                content = file_path.read_text(encoding='utf-8')
                # 检查是否已使用相对路径
                if "Path(__file__).parent" in content or "TOOLS_DIR" in content or "TOOLS_DIR = Path(__file__).parent" in content:
                    print(f"  [✓] {display_name} 已使用相对路径")
                else:
                    print(f"  [ℹ] {display_name} 无需修改（使用其他方式）")
            except Exception as e:
                print(f"  [✗] {display_name} 检查失败: {e}")
    
    # 4. 验证工具路径
    print("\n" + "=" * 60)
    print("验证工具路径")
    print("=" * 60)
    
    tools_status = {}
    
    # 检查ADB
    adb_path = PROJECT_ROOT / "tools" / "adb" / "adb.exe"
    tools_status["ADB"] = adb_path.exists()
    print(f"  ADB: {'[✓]' if tools_status['ADB'] else '[✗]'} {adb_path}")
    
    # 检查Java
    java_path = PROJECT_ROOT / "tools" / "java" / "jdk-17" / "bin" / "java.exe"
    tools_status["Java"] = java_path.exists()
    print(f"  Java: {'[✓]' if tools_status['Java'] else '[✗]'} {java_path}")
    
    # 检查Python
    local_python = find_local_python()
    tools_status["Python"] = local_python is not None
    if local_python:
        print(f"  Python: [✓] {local_python}")
    else:
        print(f"  Python: [✗] 未找到项目目录中的Python")
    
    print()
    print("=" * 60)
    if changes_made:
        print("[完成] 所有文件已更新为使用项目相对路径")
    else:
        print("[完成] 所有文件已是最新状态")
    
    if all(tools_status.values()):
        print("[成功] 所有工具已就绪")
    else:
        print("[警告] 部分工具缺失，请运行以下命令下载:")
        print("  - copy_tools.py (从系统复制)")
        print("  - tools\\download_tools.py (从网络下载)")
    print()
    print("复制到新位置后：在新位置再运行一次「一键绿色部署」；")
    print("首次编译时运行 安装调试App 选 [2] 或 [9]，会自动写入本机 SDK 路径。")
    print("=" * 60)
    
    return changes_made

if __name__ == "__main__":
    try:
        make_portable()
        sys.exit(0)
    except Exception as e:
        print(f"\n[错误] 部署失败: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

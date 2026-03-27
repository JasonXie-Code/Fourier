#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
统计项目代码行数的脚本
不包括编译工具和日志文件
"""

import os
import re

# 要包含的文件类型
INCLUDE_EXTENSIONS = {
    '.py', '.java', '.kt', '.xml', '.gradle', '.kts'
}

# 要排除的目录
EXCLUDE_DIRS = {
    '.gradle', '__pycache__', 'logs', 'tools', 'Docs', 'build',
    'local_dependencies', 'gradle', 'libs', 'res', 'bin', 'conf',
    'include', 'jmods', 'legal', 'lib', 'DLLs', 'Doc', 'Lib',
    'Scripts', 'Tools', 'share', 'tcl'
}


def count_lines_in_file(file_path):
    """统计单个文件的非空行数"""
    try:
        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
            lines = f.readlines()
        # 过滤空行和只包含空白字符的行
        non_empty_lines = [line for line in lines if line.strip()]
        return len(non_empty_lines)
    except Exception as e:
        print(f"读取文件失败: {file_path}, 错误: {e}")
        return 0


def count_lines_in_directory(directory):
    """统计目录及其子目录中的代码行数"""
    total_lines = 0
    
    for root, dirs, files in os.walk(directory):
        # 排除不需要的目录
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
        
        for file in files:
            # 检查文件扩展名
            ext = os.path.splitext(file)[1]
            if ext in INCLUDE_EXTENSIONS:
                file_path = os.path.join(root, file)
                lines = count_lines_in_file(file_path)
                total_lines += lines
    
    return total_lines


def main():
    """主函数"""
    project_root = os.path.dirname(os.path.abspath(__file__))
    
    print("统计项目代码行数（不包括空行）：")
    print("=" * 50)
    
    # 统计scripts目录
    scripts_dir = os.path.join(project_root, 'scripts')
    if os.path.exists(scripts_dir):
        scripts_lines = count_lines_in_directory(scripts_dir)
        print(f"scripts目录: {scripts_lines} 行")
    else:
        print("scripts目录: 0 行")
    
    # 统计ip.py文件
    ip_file = os.path.join(project_root, 'ip.py')
    if os.path.exists(ip_file):
        ip_lines = count_lines_in_file(ip_file)
        print(f"ip.py文件: {ip_lines} 行")
    else:
        print("ip.py文件: 0 行")
    
    # 统计android目录
    android_dir = os.path.join(project_root, 'android')
    if os.path.exists(android_dir):
        android_lines = count_lines_in_directory(android_dir)
        print(f"android目录: {android_lines} 行")
    else:
        print("android目录: 0 行")
    
    # 统计安装调试App.py文件
    install_app_file = os.path.join(project_root, '安装调试App.py')
    if os.path.exists(install_app_file):
        install_app_lines = count_lines_in_file(install_app_file)
        print(f"安装调试App.py文件: {install_app_lines} 行")
    else:
        print("安装调试App.py文件: 0 行")
    
    # 计算总行数
    total_lines = 0
    if os.path.exists(scripts_dir):
        total_lines += count_lines_in_directory(scripts_dir)
    if os.path.exists(ip_file):
        total_lines += count_lines_in_file(ip_file)
    if os.path.exists(android_dir):
        total_lines += count_lines_in_directory(android_dir)
    if os.path.exists(install_app_file):
        total_lines += count_lines_in_file(install_app_file)
    
    print("=" * 50)
    print(f"项目代码总行数: {total_lines} 行")


if __name__ == "__main__":
    main()

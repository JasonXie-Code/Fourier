#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自动下载工具脚本
用于下载ADB、JDK和Python到本地tools目录
"""

import os
import sys
import platform
import urllib.request
import zipfile
import tarfile
import shutil
from pathlib import Path

# 工具版本
ADB_VERSION = "latest"
JDK_VERSION = "17.0.8"
PYTHON_VERSION = "3.11.5"

# 下载URL（需要根据实际情况更新）
DOWNLOAD_URLS = {
    "windows": {
        "adb": "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
        "jdk": f"https://github.com/adoptium/temurin17-binaries/releases/download/jdk-{JDK_VERSION}+8/OpenJDK17U-jdk_x64_windows_hotspot_{JDK_VERSION.replace('.', '_')}_8.zip",
        "python": f"https://www.python.org/ftp/python/{PYTHON_VERSION}/python-{PYTHON_VERSION}-embed-amd64.zip"
    },
    "linux": {
        "adb": "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
        "jdk": f"https://github.com/adoptium/temurin17-binaries/releases/download/jdk-{JDK_VERSION}+8/OpenJDK17U-jdk_x64_linux_hotspot_{JDK_VERSION.replace('.', '_')}_8.tar.gz",
        "python": None  # Linux通常系统自带Python
    },
    "darwin": {
        "adb": "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
        "jdk": f"https://github.com/adoptium/temurin17-binaries/releases/download/jdk-{JDK_VERSION}+8/OpenJDK17U-jdk_x64_mac_hotspot_{JDK_VERSION.replace('.', '_')}_8.tar.gz",
        "python": None  # Mac通常系统自带Python
    }
}

def get_platform():
    """获取当前平台"""
    system = platform.system().lower()
    if system == "windows":
        return "windows"
    elif system == "linux":
        return "linux"
    elif system == "darwin":
        return "darwin"
    else:
        return "unknown"

def download_file(url, dest_path):
    """下载文件"""
    print(f"正在下载: {url}")
    print(f"保存到: {dest_path}")
    
    try:
        urllib.request.urlretrieve(url, dest_path, reporthook=download_progress)
        print(f"\n✓ 下载完成: {dest_path}")
        return True
    except Exception as e:
        print(f"\n✗ 下载失败: {e}")
        return False

def download_progress(count, block_size, total_size):
    """下载进度回调"""
    percent = int(count * block_size * 100 / total_size)
    sys.stdout.write(f"\r进度: {percent}%")
    sys.stdout.flush()

def extract_zip(zip_path, extract_to):
    """解压ZIP文件"""
    print(f"正在解压: {zip_path}")
    try:
        with zipfile.ZipFile(zip_path, 'r') as zip_ref:
            zip_ref.extractall(extract_to)
        print(f"✓ 解压完成: {extract_to}")
        return True
    except Exception as e:
        print(f"✗ 解压失败: {e}")
        return False

def extract_tar(tar_path, extract_to):
    """解压TAR文件"""
    print(f"正在解压: {tar_path}")
    try:
        with tarfile.open(tar_path, 'r:gz') as tar_ref:
            tar_ref.extractall(extract_to)
        print(f"✓ 解压完成: {extract_to}")
        return True
    except Exception as e:
        print(f"✗ 解压失败: {e}")
        return False

def setup_adb(platform_name):
    """设置ADB"""
    print("\n=== 设置 ADB ===")
    tools_dir = Path(__file__).parent
    adb_dir = tools_dir / "adb"
    adb_dir.mkdir(exist_ok=True)
    
    url = DOWNLOAD_URLS[platform_name]["adb"]
    if not url:
        print("此平台不支持自动下载ADB，请手动下载")
        return False
    
    zip_path = tools_dir / "platform-tools.zip"
    
    if not download_file(url, zip_path):
        return False
    
    temp_extract = tools_dir / "temp_extract"
    if not extract_zip(zip_path, temp_extract):
        return False
    
    # 复制文件
    platform_tools_dir = temp_extract / "platform-tools"
    if platform_name == "windows":
        shutil.copy2(platform_tools_dir / "adb.exe", adb_dir / "adb.exe")
        shutil.copy2(platform_tools_dir / "AdbWinApi.dll", adb_dir / "AdbWinApi.dll")
        shutil.copy2(platform_tools_dir / "AdbWinUsbApi.dll", adb_dir / "AdbWinUsbApi.dll")
    else:
        shutil.copy2(platform_tools_dir / "adb", adb_dir / "adb")
        os.chmod(adb_dir / "adb", 0o755)
    
    # 清理
    shutil.rmtree(temp_extract)
    zip_path.unlink()
    
    print("✓ ADB设置完成")
    return True

def setup_jdk(platform_name):
    """设置JDK"""
    print("\n=== 设置 JDK ===")
    tools_dir = Path(__file__).parent
    jdk_dir = tools_dir / "java" / "jdk-17"
    jdk_dir.parent.mkdir(exist_ok=True)
    
    url = DOWNLOAD_URLS[platform_name]["jdk"]
    if not url:
        print("此平台不支持自动下载JDK，请手动下载")
        return False
    
    archive_path = tools_dir / f"jdk-{JDK_VERSION}.{'zip' if platform_name == 'windows' else 'tar.gz'}"
    
    if not download_file(url, archive_path):
        return False
    
    temp_extract = tools_dir / "temp_extract"
    if not (extract_zip(archive_path, temp_extract) if platform_name == "windows" 
            else extract_tar(archive_path, temp_extract)):
        return False
    
    # 查找JDK目录
    jdk_source = None
    for item in temp_extract.iterdir():
        if item.is_dir() and "jdk" in item.name.lower():
            jdk_source = item
            break
    
    if jdk_source:
        if jdk_dir.exists():
            shutil.rmtree(jdk_dir)
        shutil.move(str(jdk_source), str(jdk_dir))
        print("✓ JDK设置完成")
    else:
        print("✗ 未找到JDK目录")
        return False
    
    # 清理
    shutil.rmtree(temp_extract)
    archive_path.unlink()
    
    return True

def setup_python(platform_name):
    """设置Python"""
    print("\n=== 设置 Python ===")
    
    if platform_name != "windows":
        print("Linux/Mac系统通常自带Python，跳过下载")
        print("如需使用本地Python，请手动下载并放置到 tools/python/python-3.x/")
        return True
    
    tools_dir = Path(__file__).parent
    python_dir = tools_dir / "python" / f"python-{PYTHON_VERSION}"
    python_dir.parent.mkdir(exist_ok=True)
    
    url = DOWNLOAD_URLS[platform_name]["python"]
    if not url:
        print("此平台不支持自动下载Python，请手动下载")
        return False
    
    zip_path = tools_dir / f"python-{PYTHON_VERSION}.zip"
    
    if not download_file(url, zip_path):
        return False
    
    if not extract_zip(zip_path, python_dir):
        return False
    
    zip_path.unlink()
    
    print("✓ Python设置完成")
    return True

def main():
    platform_name = get_platform()
    
    if platform_name == "unknown":
        print("错误: 未知平台")
        return
    
    print(f"检测到平台: {platform_name}")
    print("此脚本将下载以下工具到本地tools目录:")
    print("  1. Android Debug Bridge (ADB)")
    print("  2. OpenJDK 17")
    print("  3. Python 3.x (仅Windows)")
    print()
    
    response = input("是否继续? (y/n): ")
    if response.lower() != 'y':
        print("已取消")
        return
    
    success = True
    success &= setup_adb(platform_name)
    success &= setup_jdk(platform_name)
    success &= setup_python(platform_name)
    
    if success:
        print("\n✓ 所有工具设置完成!")
        print("\n注意: 请确保tools目录中的文件已正确设置")
    else:
        print("\n✗ 部分工具设置失败，请检查错误信息")

if __name__ == "__main__":
    main()

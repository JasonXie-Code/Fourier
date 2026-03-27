#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
下载Gradle Distribution到项目目录
"""

import urllib.request
import sys
import os
from pathlib import Path

# 设置输出编码为UTF-8
if sys.platform == 'win32':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def download_gradle_distribution():
    """下载Gradle 8.7 Distribution到项目目录"""
    project_root = Path(__file__).parent.parent
    android_root = project_root / "android"
    dist_dir = android_root / "gradle" / "wrapper" / "dists" / "gradle-8.7-all"
    
    # 创建目录结构（Gradle wrapper会自动创建hash子目录）
    dist_dir.mkdir(parents=True, exist_ok=True)
    
    # Gradle distribution下载URL
    url = "https://services.gradle.org/distributions/gradle-8.7-all.zip"
    
    # 临时下载位置
    temp_zip = android_root / "gradle-8.7-all.zip"
    
    print("=" * 60)
    print("下载Gradle 8.7 Distribution到项目目录")
    print("=" * 60)
    print(f"下载URL: {url}")
    print(f"保存到: {dist_dir}")
    print(f"文件大小: 约200MB")
    print()
    
    # 检查是否已存在
    existing_zips = list(dist_dir.rglob("gradle-8.7-all.zip"))
    if existing_zips:
        print(f"[提示] 发现已存在的Gradle distribution:")
        for zip_file in existing_zips:
            size_mb = zip_file.stat().st_size / (1024 * 1024)
            print(f"  {zip_file}")
            print(f"  大小: {size_mb:.2f} MB")
        print()
        response = input("是否重新下载？(Y/N，默认N): ").strip().upper()
        if response != 'Y':
            print("[取消] 使用现有文件")
            return True
    
    try:
        print("正在下载...")
        print("(这可能需要几分钟，请耐心等待)")
        print()
        
        def show_progress(block_num, block_size, total_size):
            downloaded = block_num * block_size
            percent = min(downloaded * 100 / total_size, 100) if total_size > 0 else 0
            mb_downloaded = downloaded / (1024 * 1024)
            mb_total = total_size / (1024 * 1024) if total_size > 0 else 0
            print(f"\r进度: {percent:.1f}% ({mb_downloaded:.2f} MB / {mb_total:.2f} MB)", end='', flush=True)
        
        urllib.request.urlretrieve(url, temp_zip, show_progress)
        print()  # 换行
        
        # 获取文件大小
        zip_size = temp_zip.stat().st_size / (1024 * 1024)
        print(f"[OK] 下载完成: {temp_zip}")
        print(f"文件大小: {zip_size:.2f} MB")
        print()
        
        print("=" * 60)
        print("下载完成！")
        print("=" * 60)
        print()
        print("下一步:")
        print("1. Gradle wrapper会自动检测并使用这个文件")
        print("2. 运行 'gradlew.bat --version' 来验证")
        print("3. 如果wrapper没有自动找到，请手动将文件移动到:")
        print(f"   {dist_dir}\\<hash>\\gradle-8.7-all.zip")
        print()
        print("提示: 运行 'gradlew.bat --version' 会自动创建hash目录")
        print("      然后将下载的zip文件移动到该目录即可")
        print()
        
        return True
        
    except Exception as e:
        print(f"[ERROR] 下载失败: {e}")
        print()
        print("备用方案:")
        print("1. 手动下载: https://services.gradle.org/distributions/gradle-8.7-all.zip")
        print(f"2. 保存到临时位置: {temp_zip}")
        print("3. 运行 'gradlew.bat --version' 创建hash目录")
        print("4. 将zip文件移动到hash目录中")
        return False

if __name__ == "__main__":
    download_gradle_distribution()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
下载Gradle Wrapper JAR文件
"""

import urllib.request
from pathlib import Path

def download_gradle_wrapper():
    """下载Gradle Wrapper JAR（保存到 android 工程目录）"""
    project_root = Path(__file__).resolve().parent.parent
    android_root = project_root / "android"
    wrapper_dir = android_root / "gradle" / "wrapper"
    wrapper_dir.mkdir(parents=True, exist_ok=True)
    jar_path = wrapper_dir / "gradle-wrapper.jar"
    
    # Gradle wrapper jar下载URL（使用官方仓库）
    url = "https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
    
    print(f"正在下载 Gradle Wrapper JAR...")
    print(f"URL: {url}")
    print(f"保存到: {jar_path}")
    
    try:
        urllib.request.urlretrieve(url, jar_path)
        print(f"✓ 下载完成: {jar_path}")
        print(f"文件大小: {jar_path.stat().st_size / 1024:.2f} KB")
        return True
    except Exception as e:
        print(f"✗ 下载失败: {e}")
        print("\n备用方案:")
        print("1. 手动下载: https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar")
        print(f"2. 保存到: {jar_path}")
        return False

if __name__ == "__main__":
    download_gradle_wrapper()

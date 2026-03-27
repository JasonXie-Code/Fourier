#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
整理项目文件：移除多余脚本，移动文档到Docs文件夹
"""

import shutil
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8') if hasattr(sys.stdout, 'reconfigure') else None

project_root = Path(__file__).parent
docs_dir = project_root / "Docs"
scripts_dir = project_root / "scripts"
scripts_dir.mkdir(exist_ok=True)

print("=" * 70)
print("整理项目文件")
print("=" * 70)
print()

# 1. 移动到Docs的文档文件
print("1. 移动文档文件到Docs目录...")
print("-" * 70)

docs_to_move = [
    "DEPENDENCIES_CHECK_SUMMARY.md",
    "DEPENDENCIES_STATUS.md",
    "STATUS_REPORT.md",
    "OFFLINE_SETUP_COMPLETE.md",
    "OFFLINE_SETUP_STATUS.md",
    "OFFLINE_SETUP_SUMMARY.md",
    "OFFLINE_STATUS_FINAL.md",
    "FINAL_OFFLINE_SETUP.md",
]

moved_docs = 0
for doc in docs_to_move:
    src = project_root / doc
    if src.exists():
        dst = docs_dir / doc
        shutil.move(str(src), str(dst))
        print(f"  [OK] {doc} -> Docs/{doc}")
        moved_docs += 1
    else:
        print(f"  [SKIP] {doc} (不存在)")

print(f"\n  共移动 {moved_docs} 个文档文件")

print()

# 2. 移动到scripts目录的脚本文件
print("2. 移动脚本文件到scripts目录...")
print("-" * 70)

scripts_to_move = [
    "check_copy_progress.py",
    "check_dependencies.py",
    "check_offline_status.py",
    "check_status.py",
    "copy_all_dependencies_fixed.py",
    "copy_sdk_only.py",
    "monitor_copy_progress.py",
    "portable_deploy.py",
    "download_gradle_wrapper.py",
]

moved_scripts = 0
for script in scripts_to_move:
    src = project_root / script
    if src.exists():
        dst = scripts_dir / script
        shutil.move(str(src), str(dst))
        print(f"  [OK] {script} -> scripts/{script}")
        moved_scripts += 1
    else:
        print(f"  [SKIP] {script} (不存在)")

print(f"\n  共移动 {moved_scripts} 个脚本文件")

print()

# 3. 删除多余的旧版本脚本
print("3. 删除多余的旧版本脚本...")
print("-" * 70)

scripts_to_delete = [
    "copy_all_dependencies.py",
    "copy_dependencies.py",
    "copy_tools.py",
    "organize_docs.py",
    "organize_files.py",  # 当前脚本，执行完后删除
]

deleted_scripts = 0
for script in scripts_to_delete:
    src = project_root / script
    if src.exists():
        src.unlink()
        print(f"  [DEL] {script}")
        deleted_scripts += 1
    else:
        print(f"  [SKIP] {script} (不存在)")

print(f"\n  共删除 {deleted_scripts} 个旧脚本")

print()

# 4. 删除临时文件
print("4. 删除临时文件...")
print("-" * 70)

temp_files = [
    "status_check.txt",
]

deleted_temp = 0
for temp in temp_files:
    src = project_root / temp
    if src.exists():
        src.unlink()
        print(f"  [DEL] {temp}")
        deleted_temp += 1

if deleted_temp > 0:
    print(f"\n  共删除 {deleted_temp} 个临时文件")
else:
    print("  无临时文件需要删除")

print()

# 5. 移动部署脚本到scripts
print("5. 移动部署脚本到scripts目录...")
print("-" * 70)

deploy_scripts = [
    "一键绿色部署.bat",
]

moved_deploy = 0
for script in deploy_scripts:
    src = project_root / script
    if src.exists():
        dst = scripts_dir / script
        shutil.move(str(src), str(dst))
        print(f"  [OK] {script} -> scripts/{script}")
        moved_deploy += 1

if moved_deploy > 0:
    print(f"\n  共移动 {moved_deploy} 个部署脚本")
else:
    print("  无部署脚本需要移动")

print()

# 总结
print("=" * 70)
print("整理完成总结")
print("=" * 70)
print(f"\n文档文件: 移动 {moved_docs} 个到 Docs/")
print(f"脚本文件: 移动 {moved_scripts} 个到 scripts/")
print(f"部署脚本: 移动 {moved_deploy} 个到 scripts/")
print(f"删除文件: {deleted_scripts} 个旧脚本, {deleted_temp} 个临时文件")

print("\n保留在根目录的核心文件:")
print("  - README.md (项目主说明)")
print("  - dev_tools.py, dev_tools.bat, dev_tools.sh (开发工具)")
print("  - gradlew, gradlew.bat (Gradle wrapper)")
print("  - build.gradle.kts, settings.gradle.kts (Gradle配置)")
print("  - local.properties (SDK配置)")

print()
print("=" * 70)

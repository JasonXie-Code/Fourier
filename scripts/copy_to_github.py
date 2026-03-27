# -*- coding: utf-8 -*-
"""将 Local 工程镜像到 ../Github，排除与 .gitignore 一致的大型/生成目录。"""
from __future__ import annotations

import os
import shutil
import sys

# 脚本位于 Local/scripts/，源为 Local 根目录
_HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(_HERE, ".."))
DST = os.path.normpath(os.path.join(_HERE, "..", "..", "Github"))

# 相对 SRC 的路径前缀（正斜杠，小写比较）
BLOCKED_PREFIXES = [
    ".gradle",
    "__pycache__",
    "logs",
    "recordings",
    "screenshots",
    "tools/adb",
    "tools/java",
    "tools/python",
    "tools/temp_extract",
    "android/.gradle",
    "android/app/build",
    "android/local_dependencies/android_sdk",
    "android/local_dependencies/gradle_cache",
]


def _norm_rel(rel: str) -> str:
    return rel.replace("\\", "/").strip("/").lower()


def _blocked(rel: str) -> bool:
    r = _norm_rel(rel)
    if not r:
        return False
    for bp in BLOCKED_PREFIXES:
        if r == bp or r.startswith(bp + "/"):
            return True
    return False


def _join_rel(rel_root: str, name: str) -> str:
    if not rel_root or rel_root == ".":
        return name
    return rel_root.replace("\\", "/") + "/" + name.replace("\\", "/")


def _blocked_file(rel: str) -> bool:
    base = os.path.basename(rel.replace("\\", "/"))
    return base.lower() == "local.properties"


def main() -> int:
    if not os.path.isdir(SRC):
        print("源目录不存在:", SRC, file=sys.stderr)
        return 1
    if os.path.abspath(SRC) == os.path.abspath(DST):
        print("源与目标相同，中止。", file=sys.stderr)
        return 1

    if os.path.isdir(DST):
        # Windows 下深层路径可能导致 shutil.rmtree 失败，用 rd /s /q 更稳
        if sys.platform == "win32":
            os.system(f'cmd /c rd /s /q "{DST}"')
        else:
            shutil.rmtree(DST, ignore_errors=True)
    os.makedirs(DST, exist_ok=True)

    n_files = 0
    for root, dirs, files in os.walk(SRC, topdown=True):
        rel_root = os.path.relpath(root, SRC)
        if rel_root == ".":
            rel_root = ""

        dirs[:] = [d for d in dirs if not _blocked(_join_rel(rel_root, d))]

        dst_dir = os.path.join(DST, rel_root) if rel_root else DST
        os.makedirs(dst_dir, exist_ok=True)

        for f in files:
            rel_f = _join_rel(rel_root, f) if rel_root else f
            if _blocked(rel_f) or _blocked_file(rel_f):
                continue
            src_f = os.path.join(root, f)
            dst_f = os.path.join(dst_dir, f)
            shutil.copy2(src_f, dst_f)
            n_files += 1

    # 仓库根 .gitignore（与 config/.gitignore 一致，便于 git 直接识别）
    cfg = os.path.join(SRC, "config", ".gitignore")
    if os.path.isfile(cfg):
        shutil.copy2(cfg, os.path.join(DST, ".gitignore"))

    print("完成：目标", DST)
    print("复制文件数:", n_files)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

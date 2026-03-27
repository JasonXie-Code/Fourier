#!/bin/bash
# Linux/Mac shell脚本 - 设置本地工具

echo "========================================"
echo "本地工具设置脚本"
echo "========================================"
echo

# 检查Python
if ! command -v python3 &> /dev/null; then
    echo "错误: 未找到Python3"
    echo "请先安装Python3"
    exit 1
fi

echo "正在运行工具下载脚本..."
python3 download_tools.py

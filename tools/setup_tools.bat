@echo off
REM Windows批处理脚本 - 设置本地工具
echo ========================================
echo 本地工具设置脚本
echo ========================================
echo.

REM 检查Python
python --version >nul 2>&1
if errorlevel 1 (
    echo 错误: 未找到Python
    echo 请先安装Python或使用本地Python工具
    pause
    exit /b 1
)

echo 正在运行工具下载脚本...
python download_tools.py

pause

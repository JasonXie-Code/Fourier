@echo off
chcp 65001 >nul
REM ========================================
REM 一键绿色部署脚本
REM 将编辑器中的Python文件以及其他工具引用都改为项目目录里面的
REM ========================================

echo ========================================
echo 一键绿色部署
echo ========================================
echo.

REM 获取脚本所在目录（项目根目录）
set "PROJECT_ROOT=%~dp0"
cd /d "%PROJECT_ROOT%"

echo 项目根目录: %PROJECT_ROOT%
echo.

REM 查找项目目录中的Python（优先使用）
set "LOCAL_PYTHON="
if exist "tools\python\python-3.10\python.exe" (
    set "LOCAL_PYTHON=%PROJECT_ROOT%tools\python\python-3.10\python.exe"
    echo [找到] 项目Python: %LOCAL_PYTHON%
) else if exist "tools\python\python-3.11\python.exe" (
    set "LOCAL_PYTHON=%PROJECT_ROOT%tools\python\python-3.11\python.exe"
    echo [找到] 项目Python: %LOCAL_PYTHON%
) else (
    REM 尝试查找任何python-3.x目录
    for /d %%d in ("tools\python\python-3.*") do (
        if exist "%%d\python.exe" (
            set "LOCAL_PYTHON=%PROJECT_ROOT%%%d\python.exe"
            echo [找到] 项目Python: %LOCAL_PYTHON%
            goto :found_python
        )
    )
)

:found_python
if not defined LOCAL_PYTHON (
    echo [信息] 未找到项目目录中的Python，将使用系统Python
    set "LOCAL_PYTHON=python"
)

echo.
echo ========================================
echo 正在执行部署...
echo ========================================
echo.

REM 运行部署脚本
"%LOCAL_PYTHON%" "%PROJECT_ROOT%scripts\portable_deploy.py"
set "DEPLOY_RESULT=%ERRORLEVEL%"

echo.
if %DEPLOY_RESULT% equ 0 (
    echo ========================================
    echo 部署成功完成！
    echo ========================================
    echo.
    echo 提示:
    echo   - 所有Python文件已更新为使用项目相对路径
    echo   - 运行 dev_tools.bat 将自动使用项目目录中的Python
    echo   - 现在可以使用项目目录中的工具，无需依赖系统环境
    echo.
) else (
    echo ========================================
    echo 部署过程中出现错误
    echo ========================================
    echo.
)

pause

@echo off
chcp 65001 >nul
set "ROOT=%~dp0"
cd /d "%ROOT%"

set "PY="
if exist "tools\python\python-3.10\python.exe" set "PY=%ROOT%tools\python\python-3.10\python.exe"
if exist "tools\python\python-3.11\python.exe" set "PY=%ROOT%tools\python\python-3.11\python.exe"
if exist "tools\python\python-3.12\python.exe" set "PY=%ROOT%tools\python\python-3.12\python.exe"
if exist "tools\python\python-3.13\python.exe" set "PY=%ROOT%tools\python\python-3.13\python.exe"
if not defined PY for /d %%d in ("tools\python\python-3.*") do if exist "%%d\python.exe" set "PY=%ROOT%%%d\python.exe"
if not defined PY set "PY=python"

"%PY%" "%ROOT%scripts\一键绿色部署.py"
pause

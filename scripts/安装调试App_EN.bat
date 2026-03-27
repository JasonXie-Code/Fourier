@echo off
REM ========================================
REM Install & Debug App Script
REM Build, Install, View Logs
REM ========================================

setlocal enabledelayedexpansion

REM Get script directory (project root)
set "PROJECT_ROOT=%~dp0"
cd /d "%PROJECT_ROOT%"

REM App config
set "APP_PACKAGE=com.fourier.audioanalyzer"
set "APP_ACTIVITY=com.fourier.audioanalyzer.MainActivity"
set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"

REM Init tools path
call :init_tools

REM Main menu loop
:main_menu
cls
echo ========================================
echo Install and Debug App - Menu
echo ========================================
echo.
echo Please select an option:
echo.
call :echo_menu "1" "Check device connection"
call :echo_menu "2" "Build APK"
call :echo_menu "3" "Install APK"
call :echo_menu "4" "Launch app"
call :echo_menu "5" "Stop app"
call :echo_menu "6" "View logs (realtime)"
call :echo_menu "7" "Clear log buffer"
call :echo_menu "8" "Uninstall app"
call :echo_menu "9" "One-click (build+install+launch)"
call :echo_menu "A" "View crash logs"
call :echo_menu "0" "Exit"
echo.
set /p MENU_CHOICE="Enter option (0-9, A): "

if "%MENU_CHOICE%"=="1" call :check_device
if "%MENU_CHOICE%"=="2" call :build_apk
if "%MENU_CHOICE%"=="3" call :install_apk
if "%MENU_CHOICE%"=="4" call :launch_app
if "%MENU_CHOICE%"=="5" call :stop_app
if "%MENU_CHOICE%"=="6" call :view_logs
if "%MENU_CHOICE%"=="7" call :clear_logs
if "%MENU_CHOICE%"=="8" call :uninstall_app
if "%MENU_CHOICE%"=="9" call :build_install_launch
if /i "%MENU_CHOICE%"=="A" call :view_crash_logs
if "%MENU_CHOICE%"=="0" goto :end

if not "%MENU_CHOICE%"=="" (
    echo.
    echo Press any key to return to menu...
    pause >nul
)
goto :main_menu

REM ========================================
REM Helper: safe echo menu item
REM ========================================
:echo_menu
set "MENU_NUM=%~1"
set "MENU_TEXT=%~2"
echo   [%MENU_NUM%] %MENU_TEXT%
set "MENU_NUM="
set "MENU_TEXT="
exit /b

REM ========================================
REM Exit script
REM ========================================
:end
echo.
echo Thank you!
pause
exit /b 0

REM ========================================
REM Init tools path
REM ========================================
:init_tools
set "LOCAL_ADB="
set "LOCAL_JAVA="
set "JAVA_HOME="

REM Find ADB
if exist "tools\adb\adb.exe" (
    set "LOCAL_ADB=%PROJECT_ROOT%tools\adb\adb.exe"
) else (
    set "LOCAL_ADB=adb"
)

REM Find Java
if exist "tools\java\jdk-17\bin\java.exe" (
    set "LOCAL_JAVA=%PROJECT_ROOT%tools\java\jdk-17\bin\java.exe"
    set "JAVA_HOME=%PROJECT_ROOT%tools\java\jdk-17"
)
exit /b

REM ========================================
REM Option 1: Check device
REM ========================================
:check_device
echo.
echo ========================================
echo Check device connection
echo ========================================
echo.

"%LOCAL_ADB%" devices
if errorlevel 1 (
    echo.
    echo [Error] ADB not found or failed
    echo Please ensure:
    echo   1. tools\adb\adb.exe exists in project
    echo   2. Or adb is in system PATH
    exit /b 1
)

echo.
echo Checking device...
"%LOCAL_ADB%" devices | findstr /C:"device" >nul
if errorlevel 1 (
    echo [Error] No Android device connected
    echo.
    echo Please ensure:
    echo   1. Device connected via USB
    echo   2. USB debugging enabled
    echo   3. Authorize this computer for USB debugging
    exit /b 1
)

echo [OK] Android device detected
exit /b 0

REM ========================================
REM Option 2: Build APK
REM ========================================
:build_apk
echo.
echo ========================================
echo Build APK
echo ========================================
echo.

REM Set JAVA_HOME for build
if defined JAVA_HOME (
    echo Using local JDK: %JAVA_HOME%
) else (
    echo Using system Java
)

echo Cleaning build...
echo [Tip] Clean process will show detailed info...
call gradlew.bat clean --console=plain
if errorlevel 1 (
    echo [Warning] Clean may have errors, continuing build...
)

echo.
echo ========================================
echo Compiling APK (showing real-time progress)...
echo ========================================
echo.
call gradlew.bat assembleDebug --console=plain
set "BUILD_RESULT=%ERRORLEVEL%"

if !BUILD_RESULT! neq 0 (
    echo.
    echo [Error] APK build failed
    echo Please check error messages
    exit /b 1
)

if not exist "%APK_PATH%" (
    echo.
    echo [Error] APK not found: %APK_PATH%
    exit /b 1
)

echo.
echo [OK] APK built: %APK_PATH%
for %%F in ("%APK_PATH%") do echo File size: %%~zF bytes
exit /b 0

REM ========================================
REM Option 3: Install APK
REM ========================================
:install_apk
echo.
echo ========================================
echo Install APK
echo ========================================
echo.

if not exist "%APK_PATH%" (
    echo [Warning] APK not found: %APK_PATH%
    echo Build APK first?
    set /p BUILD_FIRST="(Y/N, default Y): "
    if /i "!BUILD_FIRST!"=="N" (
        echo [Cancelled] Install cancelled
        exit /b 1
    )
    call :build_apk
    if errorlevel 1 (
        echo [Error] Build failed, cannot install
        exit /b 1
    )
)

echo Installing APK to device...
"%LOCAL_ADB%" install -r "%APK_PATH%"
set "INSTALL_RESULT=%ERRORLEVEL%"

if !INSTALL_RESULT! neq 0 (
    echo.
    echo [Error] APK install failed
    echo Please check error messages
    exit /b 1
)

echo.
echo [OK] APK installed
exit /b 0

REM ========================================
REM Option 4: Launch app
REM ========================================
:launch_app
echo.
echo ========================================
echo Launch app
echo ========================================
echo.

echo Launching app...
"%LOCAL_ADB%" shell am start -n "%APP_PACKAGE%/%APP_ACTIVITY%"
if errorlevel 1 (
    echo [Warning] Launch failed
    echo Possible reasons:
    echo   1. App not installed
    echo   2. Device not connected
    exit /b 1
)

echo [OK] App launched
exit /b 0

REM ========================================
REM Option 5: Stop app
REM ========================================
:stop_app
echo.
echo ========================================
echo Stop app
echo ========================================
echo.

echo Stopping app...
"%LOCAL_ADB%" shell am force-stop "%APP_PACKAGE%"
if errorlevel 1 (
    echo [Warning] Stop app failed
    exit /b 1
)

echo [OK] App stopped
exit /b 0

REM ========================================
REM Option 6: View logs (realtime)
REM ========================================
:view_logs
echo.
echo ========================================
echo View logs (realtime)
echo ========================================
echo.
echo [Tip] Displaying all logs for this app in real-time, press Ctrl+C to exit...
echo.

REM Clear old log buffer
"%LOCAL_ADB%" logcat -c

REM Show all logs for our app (Verbose level + AndroidRuntime crashes) in real-time
"%LOCAL_ADB%" logcat -v time "%APP_PACKAGE%:V AndroidRuntime:E *:S"

echo.
echo ========================================
echo Log view ended
echo ========================================
exit /b 0

REM ========================================
REM Option 10: View crash logs
REM ========================================
:view_crash_logs
echo.
echo ========================================
echo View crash logs
echo ========================================
echo.

echo Showing recent crash logs...
echo.
"%LOCAL_ADB%" logcat -d -v time AndroidRuntime:E "%APP_PACKAGE%:E" *:S

echo.
echo ========================================
echo Crash log view done
echo ========================================
echo.
echo Tip: If you see crash info, please check:
echo   1. App permissions are granted
echo   2. Android version is compatible (Android 7.0+ required)
echo   3. Device has enough memory
echo.
exit /b 0

REM ========================================
REM Option 7: Clear log buffer
REM ========================================
:clear_logs
echo.
echo ========================================
echo Clear log buffer
echo ========================================
echo.

echo Clearing log buffer...
"%LOCAL_ADB%" logcat -c
if errorlevel 1 (
    echo [Error] Clear logs failed
    exit /b 1
)

echo [OK] Log buffer cleared
exit /b 0

REM ========================================
REM Option 8: Uninstall app
REM ========================================
:uninstall_app
echo.
echo ========================================
echo Uninstall app
echo ========================================
echo.
echo [Warning] About to uninstall: %APP_PACKAGE%
set /p CONFIRM="Confirm uninstall? (Y/N, default N): "

if /i not "!CONFIRM!"=="Y" (
    echo [Cancelled] Uninstall cancelled
    exit /b 0
)

echo.
echo Uninstalling app...
"%LOCAL_ADB%" uninstall "%APP_PACKAGE%"
if errorlevel 1 (
    echo [Error] Uninstall failed
    exit /b 1
)

echo [OK] App uninstalled
exit /b 0

REM ========================================
REM Option 9: One-click (build+install+launch)
REM ========================================
:build_install_launch
echo.
echo ========================================
echo One-click (build+install+launch)
echo ========================================
echo.

REM Check device
call :check_device
if errorlevel 1 (
    echo [Error] Device check failed, cannot continue
    exit /b 1
)

REM Build APK
call :build_apk
if errorlevel 1 (
    echo [Error] Build failed, cannot continue
    exit /b 1
)

REM Install APK
call :install_apk
if errorlevel 1 (
    echo [Error] Install failed, cannot continue
    exit /b 1
)

REM Launch app
call :launch_app
if errorlevel 1 (
    echo [Warning] Launch failed, but install succeeded
    exit /b 1
)

echo.
echo ========================================
echo One-click execution completed!
echo ========================================
echo.
echo Summary:
echo   [OK] APK compiled successfully
echo   [OK] APK installed successfully
echo   [OK] App launched successfully
echo.
echo Tip: You can select option 6 to view app logs
exit /b 0

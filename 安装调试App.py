#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
安装调试 App - 交互式菜单
功能：检查设备、编译、安装、启动、查看日志等（与 安装调试App.bat 等效）
"""

import os
import sys
import subprocess
import platform
from pathlib import Path
from datetime import datetime

# ANSI 颜色转义码
if platform.system() == "Windows":
    # Windows 10+ 终端支持 ANSI，但可能需要初始化
    os.system('')

GREEN = "\033[32m"
RED = "\033[31m"
RESET = "\033[0m"

def print_success(msg):
    print(f"{GREEN}{msg}{RESET}")

def print_error(msg):
    print(f"{RED}{msg}{RESET}")

# 项目根目录（脚本所在目录）
PROJECT_ROOT = Path(__file__).resolve().parent
os.chdir(PROJECT_ROOT)

# 应用配置（Android 工程在 android/ 子目录）
ANDROID_ROOT = PROJECT_ROOT / "android"
APP_PACKAGE = "com.fourier.audioanalyzer"
APP_ACTIVITY = "com.fourier.audioanalyzer.MainActivity"
APK_PATH = ANDROID_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

IS_WINDOWS = platform.system() == "Windows"
ADB_EXE = PROJECT_ROOT / "tools" / "adb" / "adb.exe"
JDK_DIR = PROJECT_ROOT / "tools" / "java" / "jdk-17"
GRADLEW = ANDROID_ROOT / ("gradlew.bat" if IS_WINDOWS else "gradlew")


def get_adb():
    """获取 ADB 路径：优先使用项目 tools 下的 adb"""
    if IS_WINDOWS and ADB_EXE.exists():
        return str(ADB_EXE)
    return "adb"


# 当前选中的设备（用于多设备场景）
SELECTED_DEVICE = None


def get_connected_devices():
    """获取已连接的设备列表，返回 [(device_id, status), ...]"""
    adb = get_adb()
    try:
        result = subprocess.run([adb, "devices"], capture_output=True, encoding='utf-8', errors='replace')
        if result.returncode != 0:
            return []
        devices = []
        for line in result.stdout.strip().split("\n")[1:]:  # 跳过第一行 "List of devices attached"
            line = line.strip()
            if line and "\t" in line:
                parts = line.split("\t")
                if len(parts) >= 2 and parts[1] == "device":
                    devices.append((parts[0], parts[1]))
        return devices
    except Exception:
        return []


def select_device():
    """如果有多个设备，让用户选择一个；如果只有一个，自动选中"""
    global SELECTED_DEVICE
    devices = get_connected_devices()
    
    if not devices:
        print_error("[错误] 未检测到已连接的 Android 设备")
        SELECTED_DEVICE = None
        return None
    
    if len(devices) == 1:
        SELECTED_DEVICE = devices[0][0]
        return SELECTED_DEVICE
    
    # 多个设备，让用户选择
    print()
    print("检测到多个设备，请选择要操作的设备：")
    print()
    for i, (device_id, status) in enumerate(devices, 1):
        print(f"  [{i}] {device_id}")
    print()
    
    while True:
        choice = input(f"请输入选项 (1-{len(devices)}): ").strip()
        try:
            idx = int(choice)
            if 1 <= idx <= len(devices):
                SELECTED_DEVICE = devices[idx - 1][0]
                print(f"[已选择] {SELECTED_DEVICE}")
                return SELECTED_DEVICE
        except ValueError:
            pass
        print("无效选项，请重新输入")


def ensure_device_selected():
    """确保已选择设备，如果多设备未选择则自动提示选择。返回 True 表示设备可用，False 表示无设备"""
    global SELECTED_DEVICE
    devices = get_connected_devices()
    
    if not devices:
        print_error("[错误] 未检测到已连接的 Android 设备")
        SELECTED_DEVICE = None
        return False
    
    if len(devices) == 1:
        # 单设备自动选中
        if SELECTED_DEVICE != devices[0][0]:
            SELECTED_DEVICE = devices[0][0]
            print(f"[自动选择] 设备: {SELECTED_DEVICE}")
        return True
    
    # 多设备情况
    if SELECTED_DEVICE:
        # 检查已选设备是否仍然连接
        device_ids = [d[0] for d in devices]
        if SELECTED_DEVICE in device_ids:
            return True
        else:
            print(f"[警告] 之前选择的设备 {SELECTED_DEVICE} 已断开")
            SELECTED_DEVICE = None
    
    # 多设备且未选择，提示用户选择
    print()
    print("检测到多个设备，请先选择要操作的设备：")
    print()
    for i, (device_id, status) in enumerate(devices, 1):
        print(f"  [{i}] {device_id}")
    print()
    
    while True:
        choice = input(f"请输入选项 (1-{len(devices)}): ").strip()
        try:
            idx = int(choice)
            if 1 <= idx <= len(devices):
                SELECTED_DEVICE = devices[idx - 1][0]
                print(f"[已选择] {SELECTED_DEVICE}")
                print()
                return True
        except ValueError:
            pass
        print("无效选项，请重新输入")


def get_adb_cmd(extra_args=None):
    """获取 ADB 命令列表，如果有选中的设备，自动加上 -s 参数"""
    adb = get_adb()
    cmd = [adb]
    if SELECTED_DEVICE:
        cmd.extend(["-s", SELECTED_DEVICE])
    if extra_args:
        cmd.extend(extra_args)
    return cmd


def get_env_with_java():
    """返回带 JAVA_HOME 的环境变量（若存在本地 JDK）"""
    env = os.environ.copy()
    if JDK_DIR.exists():
        env["JAVA_HOME"] = str(JDK_DIR)
    return env


def find_android_sdk():
    """检测 Android SDK 路径：环境变量 → 当前用户常见安装位置"""
    env = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if env:
        p = Path(env)
        if p.exists() and (p / "platform-tools").exists():
            return p
    if IS_WINDOWS:
        user = os.environ.get("USERPROFILE", "")
        if user:
            sdk = Path(user) / "AppData" / "Local" / "Android" / "Sdk"
            if sdk.exists() and (sdk / "platform-tools").exists():
                return sdk
    return None


def ensure_local_properties():
    """确保 android/local.properties 存在且 sdk.dir 指向有效目录，否则尝试检测并写入"""
    prop_file = ANDROID_ROOT / "local.properties"
    current_sdk = None
    if prop_file.exists():
        try:
            for line in prop_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line.startswith("sdk.dir="):
                    value = line.split("=", 1)[1].strip().replace("\\\\", "\\")
                    if value:
                        p = Path(value)
                        if p.exists() and (p / "platform-tools").exists():
                            return True
                        current_sdk = value
                    break
        except Exception:
            pass
    sdk = find_android_sdk()
    if sdk is None:
        print_error("[错误] 未找到 Android SDK。请：")
        print("  1. 安装 Android Studio 或仅安装 Command line tools")
        print("  2. 设置环境变量 ANDROID_HOME 指向 SDK 目录（如 C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk）")
        print("  3. 或手动在 android\\local.properties 中写一行: sdk.dir=你的SDK绝对路径")
        return False
    # 使用正斜杠，Gradle 在 Windows 上也能识别
    sdk_str = sdk.as_posix() if hasattr(sdk, "as_posix") else str(sdk).replace("\\", "/")
    content = "sdk.dir=" + sdk_str + "\n"
    prop_file.parent.mkdir(parents=True, exist_ok=True)
    prop_file.write_text(content, encoding="utf-8")
    print(f"[已写入] {prop_file}  sdk.dir={sdk_str}")
    return True


def run(cmd, env=None, check=False, shell=False):
    """执行命令，返回 (returncode, stdout, stderr) 或直接继承终端"""
    if env is None:
        env = os.environ.copy()
    try:
        capture = not (cmd[0] == get_adb() and "logcat" in cmd)
        result = subprocess.run(
            cmd,
            env=env,
            shell=shell,
            check=check,
            capture_output=capture,
            # 使用 UTF-8 编码并忽略无法解码的字符，避免 Windows 下 GBK 解码错误
            encoding='utf-8',
            errors='replace',
        )
        if result.stdout is not None:
            return result.returncode, result.stdout or "", result.stderr or ""
        return result.returncode, "", ""
    except FileNotFoundError:
        return -1, "", "命令未找到"
    except Exception as e:
        return -1, "", str(e)


def section(title):
    """打印分节标题"""
    print()
    print("=" * 40)
    print(title)
    print("=" * 40)
    print()


def check_device():
    """[1] 检查设备连接"""
    section("检查设备连接")
    adb = get_adb()
    code, out, err = run([adb, "devices"])
    if code != 0:
        print_error("[错误] ADB 未找到或执行失败")
        print("请检查：")
        print("  1. 是否已放置 tools\\adb\\adb.exe")
        print("  2. 或将 adb 加入 PATH 环境变量")
        return False
    if out:
        print(out)
    if "device" not in out or out.strip().count("\n") < 1:
        print_error("[错误] 未检测到已连接的 Android 设备")
        print("请检查：")
        print("  1. 手机是否已开启 USB 调试")
        print("  2. 数据线是否支持 USB 传输")
        print("  3. 是否已授权本机进行 USB 调试")
        return False
    print_success("[成功] 已检测到 Android 设备")
    return True


def build_apk():
    """[2] 编译 APK"""
    section("编译 APK")
    if not ensure_local_properties():
        return False
    env = get_env_with_java()
    if JDK_DIR.exists():
        print(f"使用 JDK: {JDK_DIR}")
    else:
        print("未检测到本地 Java，使用系统 Java")
    print("提示: 首次编译会启动 Gradle Daemon 并可能下载依赖，约需 1～3 分钟，请耐心等待。")
    print()
    gradlew_cmd = [str(GRADLEW), "clean", "--console=plain"]
    print("正在执行清理...")
    code_clean = subprocess.run(gradlew_cmd, env=env, cwd=ANDROID_ROOT).returncode
    print()
    print("正在编译 Debug APK，请稍候...")
    gradlew_cmd = [str(GRADLEW), "assembleDebug", "--console=plain"]
    code = subprocess.run(gradlew_cmd, env=env, cwd=ANDROID_ROOT).returncode
    if code != 0:
        print_error("[错误] APK 编译失败，请检查上述错误信息")
        return False
    if not APK_PATH.exists():
        print_error(f"[错误] APK 文件未找到: {APK_PATH}")
        return False
    size = APK_PATH.stat().st_size
    print_success(f"[成功] APK 已生成: {APK_PATH}")
    print(f"文件大小: {size} 字节")
    return True


def install_apk():
    """[3] 安装 APK"""
    section("安装 APK")
    if not ensure_device_selected():
        return False
    if not APK_PATH.exists():
        print_error(f"[错误] APK 文件未找到: {APK_PATH}")
        r = input("是否先编译 APK？(Y/N, 默认 Y): ").strip().upper() or "Y"
        if r == "N":
            print("[取消] 已取消安装")
            return False
        if not build_apk():
            return False
    
    # 使用选中的设备
    cmd = get_adb_cmd(["install", "-r", str(APK_PATH)])
    print("正在安装 APK 到设备...")
    if SELECTED_DEVICE:
        print(f"目标设备: {SELECTED_DEVICE}")
    code, out, err = run(cmd)
    if code != 0:
        msg = (err or out) or ""
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in msg or "signatures do not match" in msg:
            print_error("[错误] 设备上已安装的版本签名与当前 APK 不一致（例如在不同电脑上编译过），无法覆盖安装。")
            print("请先选 [8] 卸载应用，再重新安装；或选择下方「先卸载再安装」。")
            r = input("是否先卸载再安装？(Y/N，默认 Y): ").strip().upper() or "Y"
            if r == "Y":
                run(get_adb_cmd(["uninstall", APP_PACKAGE]))
                print("正在重新安装...")
                code2, _, err2 = run(get_adb_cmd(["install", "-r", str(APK_PATH)]))
                if code2 == 0:
                    print_success("[成功] APK 已安装")
                    return True
                if err2:
                    print(err2)
        else:
            print_error("[错误] APK 安装失败，请检查设备连接与权限")
            if msg:
                print(msg)
        return False
    print_success("[成功] APK 已安装")
    return True


def launch_app():
    """[4] 启动应用"""
    section("启动应用")
    if not ensure_device_selected():
        return False
    print("正在启动应用...")
    if SELECTED_DEVICE:
        print(f"目标设备: {SELECTED_DEVICE}")
    code, _, _ = run(get_adb_cmd(["shell", "am", "start", "-n", f"{APP_PACKAGE}/{APP_ACTIVITY}"]))
    if code != 0:
        print_error("[错误] 启动失败，请检查应用是否已安装、设备是否已连接")
        return False
    print_success("[成功] 应用已启动")
    return True


def stop_app():
    """[5] 停止应用"""
    section("停止应用")
    if not ensure_device_selected():
        return False
    print("正在停止应用...")
    if SELECTED_DEVICE:
        print(f"目标设备: {SELECTED_DEVICE}")
    code, _, _ = run(get_adb_cmd(["shell", "am", "force-stop", APP_PACKAGE]))
    if code != 0:
        print_error("[错误] 停止应用失败")
        return False
    print_success("[成功] 应用已停止")
    return True


def get_app_pid():
    """获取应用的 PID，如果应用未运行则返回 None"""
    code, out, _ = run(get_adb_cmd(["shell", "pidof", APP_PACKAGE]))
    if code == 0 and out.strip():
        # 可能有多个进程，取第一个
        return out.strip().split()[0]
    return None


def view_logs():
    """[6] 查看日志（实时，仅本应用）"""
    section("查看日志（实时）")
    if not ensure_device_selected():
        return False
    
    # 获取应用 PID，按进程过滤显示所有日志
    pid = get_app_pid()
    if pid:
        print(f"[提示] 正在实时显示本应用全部日志 (PID: {pid})，按 Ctrl+C 退出...")
        print("        应用重启后需重新进入此菜单刷新 PID")
    else:
        print("[提示] 应用未运行，将显示所有相关日志，按 Ctrl+C 退出...")
    print("        （先显示当前缓冲区内容，再持续输出新日志）")
    if SELECTED_DEVICE:
        print(f"目标设备: {SELECTED_DEVICE}")
    print()
    
    # 如果有 PID，使用 --pid 过滤（显示该进程的所有日志）
    # 否则使用 tag 过滤
    if pid:
        cmd = get_adb_cmd(["logcat", "-v", "time", "--pid=" + pid])
    else:
        # 回退到 tag 过滤
        cmd = get_adb_cmd(["logcat", "-v", "time", 
               "DialogDebug:V", "AudioRecorder:V", "AudioFileProcessor:V", "Oscilloscope:V", "SpectrumBounds:V", "Fourier.Audio:V", "AndroidRuntime:E", "*:S"])
    
    proc = None
    try:
        # 用 Popen 接管输出并用 UTF-8 解码，避免 Windows 下 GBK 解码导致无输出或报错
        # 明确设置 shell=False，确保参数列表正确传递
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            shell=False,  # 明确设置 shell=False
            bufsize=1,    # 行缓冲，实时输出
        )
        for raw in iter(proc.stdout.readline, b""):
            line = raw.decode("utf-8", errors="replace")
            sys.stdout.write(line)
            sys.stdout.flush()
        proc.wait()
    except KeyboardInterrupt:
        if proc is not None:
            try:
                proc.terminate()
            except Exception:
                pass
        print("\n已退出日志查看")
    except Exception as e:
        print_error(f"[错误] {e}")
    print()
    print("=" * 40)
    print("日志查看已结束")
    print("=" * 40)
    return True


def clear_logs():
    """[7] 清除日志缓冲区"""
    section("清除日志缓冲区")
    if not ensure_device_selected():
        return False
    print("正在清除设备日志缓冲区...")
    if SELECTED_DEVICE:
        print(f"目标设备: {SELECTED_DEVICE}")
    code, _, err = run(get_adb_cmd(["logcat", "-c"]))
    if code != 0:
        print_error("[错误] 清除日志失败")
        return False
    print_success("[成功] 日志缓冲区已清除")
    return True


def uninstall_app():
    """[8] 卸载应用"""
    section("卸载应用")
    if not ensure_device_selected():
        return False
    print(f"[警告] 即将卸载: {APP_PACKAGE}")
    if SELECTED_DEVICE:
        print(f"目标设备: {SELECTED_DEVICE}")
    r = input("确认卸载？(Y/N, 默认 N): ").strip().upper() or "N"
    if r != "Y":
        print("[取消] 已取消卸载")
        return True
    print("正在卸载...")
    code, _, _ = run(get_adb_cmd(["uninstall", APP_PACKAGE]))
    if code != 0:
        print_error("[错误] 卸载失败")
        return False
    print_success("[成功] 应用已卸载")
    return True


def build_install_launch():
    """[9] 一键执行（编译 + 安装 + 启动）"""
    section("一键执行（编译+安装+启动）")
    
    # 先选择设备（如果有多个）
    device = select_device()
    if not device:
        print_error("[错误] 未选择设备，无法继续")
        return False
    
    if not check_device():
        print_error("[错误] 设备检查失败，无法继续")
        return False
    if not build_apk():
        print_error("[错误] 编译失败，无法继续")
        return False
    if not install_apk():
        print_error("[错误] 安装失败，无法继续")
        return False
    if not launch_app():
        print_error("[错误] 启动失败，无法继续")
        return False
    print()
    print("=" * 40)
    print("一键执行完成")
    print("=" * 40)
    print()
    print("已完成：")
    print_success("  [成功] APK 已编译")
    print_success("  [成功] APK 已安装")
    print_success("  [成功] 应用已启动")
    print()
    print("可选择选项 6 查看实时日志")
    return True


def view_crash_logs():
    """[A] 查看崩溃日志"""
    section("查看崩溃日志")
    if not ensure_device_selected():
        return False
    print("正在显示最近的崩溃日志...")
    if SELECTED_DEVICE:
        print(f"目标设备: {SELECTED_DEVICE}")
    print()
    code, out, err = run(get_adb_cmd(["logcat", "-d", "-v", "time", 
                          "AndroidRuntime:E", "DialogDebug:E", "AudioRecorder:E", "AudioFileProcessor:E", "Oscilloscope:E", "SpectrumBounds:E", "*:S"]))
    if out:
        print(out)
    print()
    print("=" * 40)
    print("崩溃日志查看完成")
    print("=" * 40)
    print()
    print("提示：如果看到崩溃信息，请检查：")
    print("  1. 应用权限是否已授予")
    print("  2. Android 版本是否兼容（需 Android 7.0+）")
    print("  3. 设备是否有足够内存")
    return True


def connect_wireless_adb():
    """[W] 连接无线 ADB 设备"""
    global SELECTED_DEVICE
    section("连接无线 ADB 设备")
    
    print("无线 ADB 连接方式：")
    print()
    print("  [1] 输入 IP:端口 直接连接（需设备已开启无线调试）")
    print("  [2] 通过配对码配对新设备（Android 11+）")
    print()
    
    mode = input("请选择 (1/2): ").strip()
    
    if mode == "1":
        # 直接连接
        print()
        print("请输入设备的 IP 地址和端口（格式：IP:端口）")
        print("例如：192.168.1.100:5555")
        print()
        addr = input("IP:端口: ").strip()
        if not addr:
            print("[取消] 未输入地址")
            return False
        
        # 添加默认端口
        if ":" not in addr:
            addr += ":5555"
        
        print(f"正在连接 {addr}...")
        adb = get_adb()
        code, out, err = run([adb, "connect", addr])
        
        if code == 0 and "connected" in (out + err).lower():
            print_success(f"[成功] 已连接到 {addr}")
            SELECTED_DEVICE = addr
            return True
        else:
            print_error(f"[错误] 连接失败")
            if out:
                print(out)
            if err:
                print(err)
            return False
    
    elif mode == "2":
        # 配对模式（Android 11+）
        print()
        print("请在手机上：")
        print("  1. 打开 设置 > 开发者选项 > 无线调试")
        print("  2. 点击「使用配对码配对设备」")
        print("  3. 手机上会显示 IP:端口 和 配对码")
        print()
        
        pair_addr = input("配对用 IP:端口: ").strip()
        if not pair_addr:
            print("[取消] 未输入地址")
            return False
        
        pair_code = input("配对码: ").strip()
        if not pair_code:
            print("[取消] 未输入配对码")
            return False
        
        print(f"正在配对 {pair_addr}...")
        adb = get_adb()
        
        # 使用 subprocess 直接运行，因为 pair 命令需要交互
        # 使用 UTF-8 编码并忽略无法解码的字符，避免 Windows 下 GBK 解码错误
        proc = subprocess.Popen(
            [adb, "pair", pair_addr],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            encoding='utf-8',
            errors='replace'
        )
        out, err = proc.communicate(input=pair_code + "\n", timeout=30)
        
        if "Successfully paired" in (out + err) or "成功" in (out + err):
            print_success("[成功] 配对成功！")
            print()
            print("现在需要连接设备。")
            print("请查看手机「无线调试」页面上显示的 IP 地址和端口")
            print("（注意：连接端口和配对端口可能不同）")
            print()
            
            conn_addr = input("连接用 IP:端口: ").strip()
            if conn_addr:
                code, out, err = run([adb, "connect", conn_addr])
                if code == 0 and "connected" in (out + err).lower():
                    print_success(f"[成功] 已连接到 {conn_addr}")
                    SELECTED_DEVICE = conn_addr
                    return True
                else:
                    print_error("[错误] 连接失败")
                    if out:
                        print(out)
                    if err:
                        print(err)
            return False
        else:
            print_error("[错误] 配对失败")
            if out:
                print(out)
            if err:
                print(err)
            return False
    
    else:
        print("[取消] 无效选项")
        return False


def save_logs():
    """[S] 保存日志到文件"""
    section("保存日志")
    if not ensure_device_selected():
        return False
    
    # 日志保存目录（项目根目录下的 logs 文件夹）
    logs_dir = PROJECT_ROOT / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    
    # 生成带时间戳的文件名
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    log_file = logs_dir / f"logcat_{timestamp}.txt"
    
    print(f"正在获取设备日志...")
    if SELECTED_DEVICE:
        print(f"目标设备: {SELECTED_DEVICE}")
    print(f"保存路径: {log_file}")
    
    # 获取应用 PID，按进程过滤
    pid = get_app_pid()
    if pid:
        print(f"应用 PID: {pid}，将保存该进程的所有日志")
        cmd = get_adb_cmd(["logcat", "-d", "-v", "time", "--pid=" + pid])
    else:
        print("应用未运行，将按 tag 过滤保存日志")
        cmd = get_adb_cmd(["logcat", "-d", "-v", "time",
               "DialogDebug:V", "AudioRecorder:V", "AudioFileProcessor:V", "Oscilloscope:V", "SpectrumBounds:V", "Fourier.Audio:V", "AndroidRuntime:E", "*:S"])
    print()
    
    try:
        # 使用字节捕获，避免 Windows 下 text=True 用 gbk 解码导致 UnicodeDecodeError
        # 明确设置 shell=False，确保参数列表正确传递
        result = subprocess.run(
            cmd,
            capture_output=True,
            timeout=30,  # 设置30秒超时
            shell=False  # 明确设置 shell=False
        )
        
        if result.returncode != 0:
            print_error("[错误] 获取日志失败")
            if result.stderr:
                try:
                    print(result.stderr.decode("utf-8", errors="replace"))
                except Exception:
                    print(result.stderr)
            return False
        
        # 用 UTF-8 解码，非法字节用替换字符，避免 GBK 解码错误
        log_content = (result.stdout or b"").decode("utf-8", errors="replace")
        log_file.write_text(log_content, encoding="utf-8")
        file_size = log_file.stat().st_size
        print_success(f"[成功] 日志已保存")
        print(f"文件: {log_file}")
        print(f"大小: {file_size} 字节")
        if file_size == 0:
            print("[提示] 日志文件为空，可能设备日志缓冲区已被清除")
        else:
            # 显示日志的前几行作为预览
            lines = log_content.split('\n')
            preview_lines = min(5, len(lines))
            if preview_lines > 0:
                print()
                print("日志预览（前5行）：")
                for i, line in enumerate(lines[:preview_lines], 1):
                    if line.strip():
                        print(f"  {line[:100]}")  # 每行最多显示100个字符
    except subprocess.TimeoutExpired:
        print_error("[错误] 获取日志超时")
        return False
    except Exception as e:
        print_error(f"[错误] 保存日志文件失败: {e}")
        return False
    
    return True


def main_menu():
    """主菜单循环"""
    while True:
        if IS_WINDOWS:
            os.system("cls")
        else:
            os.system("clear")
        print("=" * 40)
        print("安装调试 App - 功能菜单")
        print("=" * 40)
        print()
        if SELECTED_DEVICE:
            print(f"当前设备: {SELECTED_DEVICE}")
        else:
            print("当前设备: 未选择")
        print()
        print("请选择要执行的功能：")
        print()
        print("  [1] 检查设备连接")
        print("  [2] 编译 APK")
        print("  [3] 安装 APK")
        print("  [4] 启动应用")
        print("  [5] 停止应用")
        print("  [6] 查看日志（实时）")
        print("  [7] 清除日志缓冲区")
        print("  [8] 卸载应用")
        print("  [9] 一键执行（编译+安装+启动）")
        print("  [A] 查看崩溃日志")
        print("  [S] 保存日志")
        print("  [D] 选择设备")
        print("  [W] 连接无线 ADB")
        print("  [0] 退出")
        print()
        choice = input("请输入选项 (0-9, A, S, D, W): ").strip().upper()

        if choice == "1":
            check_device()
        elif choice == "2":
            build_apk()
        elif choice == "3":
            install_apk()
        elif choice == "4":
            launch_app()
        elif choice == "5":
            stop_app()
        elif choice == "6":
            view_logs()
        elif choice == "7":
            clear_logs()
        elif choice == "8":
            uninstall_app()
        elif choice == "9":
            build_install_launch()
        elif choice == "A":
            view_crash_logs()
        elif choice == "S":
            save_logs()
        elif choice == "D":
            section("选择设备")
            select_device()
        elif choice == "W":
            connect_wireless_adb()
        elif choice == "0":
            print()
            print("感谢使用！")
            break
        else:
            if choice:
                print()
                print("按任意键返回主菜单...")

        if choice and choice != "0":
            input("\n按回车键返回主菜单...")


if __name__ == "__main__":
    main_menu()

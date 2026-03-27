#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Android开发工具脚本
用于安装、调试和日志管理
"""

import subprocess
import sys
import os
import argparse
import time
import platform
from datetime import datetime
from pathlib import Path

# 项目根目录（脚本在 scripts/ 下）
PROJECT_ROOT = Path(__file__).resolve().parent.parent
ANDROID_ROOT = PROJECT_ROOT / "android"

# 应用配置
APP_PACKAGE = "com.fourier.audioanalyzer"
APP_ACTIVITY = "com.fourier.audioanalyzer.MainActivity"
APK_PATH = ANDROID_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

# 工具路径配置（在项目根目录下）
TOOLS_DIR = PROJECT_ROOT / "tools"
IS_WINDOWS = platform.system() == "Windows"
LOCAL_ADB = TOOLS_DIR / "adb" / ("adb.exe" if IS_WINDOWS else "adb")
LOCAL_JAVA = TOOLS_DIR / "java" / "jdk-17" / "bin" / ("java.exe" if IS_WINDOWS else "java")
LOCAL_PYTHON = TOOLS_DIR / "python" / "python-3.x" / ("python.exe" if IS_WINDOWS else "python3")

def find_tool(tool_name, local_path, env_var=None):
    """查找工具路径，优先使用本地工具"""
    # 1. 检查本地工具
    if local_path and local_path.exists():
        return str(local_path)
    
    # 2. 检查环境变量
    if env_var and os.environ.get(env_var):
        return os.environ[env_var]
    
    # 3. 使用系统PATH中的工具
    return tool_name

class AndroidDevTools:
    def __init__(self):
        self.package = APP_PACKAGE
        self.activity = APP_ACTIVITY
        self.apk_path = APK_PATH
        self.logs_dir = Path("logs")
        self.logs_dir.mkdir(exist_ok=True)
        
        # 查找ADB路径
        self.adb_path = find_tool("adb", LOCAL_ADB, "ADB_PATH")
        if self.adb_path == LOCAL_ADB and LOCAL_ADB.exists():
            print(f"使用本地ADB: {self.adb_path}")
    
    def run_adb(self, command, capture_output=True):
        """执行ADB命令"""
        try:
            cmd = [self.adb_path] + command
            if capture_output:
                result = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    check=False
                )
                return result.returncode == 0, result.stdout, result.stderr
            else:
                subprocess.run(cmd, check=False)
                return True, "", ""
        except FileNotFoundError:
            print("错误: 未找到ADB命令。")
            print("请确保:")
            print("  1. 本地工具已设置: tools/adb/adb")
            print("  2. 或Android SDK已安装并添加到PATH")
            print("  3. 或设置ADB_PATH环境变量")
            return False, "", "ADB not found"
        except Exception as e:
            return False, "", str(e)
    
    def check_device(self):
        """检查设备连接"""
        success, output, error = self.run_adb(["devices"])
        if not success:
            return False
        
        lines = output.strip().split('\n')[1:]  # 跳过第一行标题
        devices = [line.split('\t')[0] for line in lines if line.strip() and 'device' in line]
        
        if not devices:
            print("错误: 未检测到已连接的Android设备。")
            print("请确保:")
            print("  1. 设备已通过USB连接")
            print("  2. USB调试已启用")
            print("  3. 已授权此电脑的USB调试")
            return False
        
        print(f"✓ 检测到 {len(devices)} 个设备: {', '.join(devices)}")
        return True
    
    def build_apk(self):
        """构建APK"""
        print("正在构建APK...")
        
        # 设置JAVA_HOME为本地JDK（如果存在）
        env = os.environ.copy()
        java_home = TOOLS_DIR / "java" / "jdk-17"
        if java_home.exists():
            env["JAVA_HOME"] = str(java_home)
            print(f"使用本地JDK: {java_home}")
        
        if os.name == 'nt':  # Windows
            cmd = ["gradlew.bat", "assembleDebug"]
        else:  # Linux/Mac
            cmd = ["./gradlew", "assembleDebug"]
        
        try:
            result = subprocess.run(cmd, check=False, env=env, cwd=ANDROID_ROOT)
            if result.returncode == 0:
                print("✓ APK构建成功")
                return True
            else:
                print("✗ APK构建失败")
                return False
        except FileNotFoundError:
            print("错误: 未找到Gradle wrapper。")
            return False
    
    def install_apk(self, apk_path=None):
        """安装APK"""
        if apk_path is None:
            apk_path = self.apk_path
        
        if not os.path.exists(apk_path):
            print(f"错误: APK文件不存在: {apk_path}")
            print("正在尝试构建APK...")
            if not self.build_apk():
                return False
        
        apk_str = str(apk_path)
        print(f"正在安装APK: {apk_str}")
        success, output, error = self.run_adb(["install", "-r", apk_str])
        
        if success and "Success" in output:
            print("✓ APK安装成功")
            return True
        else:
            print(f"✗ APK安装失败: {error}")
            return False
    
    def uninstall_app(self):
        """卸载应用"""
        print(f"正在卸载应用: {self.package}")
        success, output, error = self.run_adb(["uninstall", self.package])
        
        if success:
            print("✓ 应用卸载成功")
            return True
        else:
            print(f"✗ 应用卸载失败: {error}")
            return False
    
    def launch_app(self):
        """启动应用"""
        print(f"正在启动应用: {self.package}")
        cmd = [
            "shell", "am", "start",
            "-n", f"{self.package}/{self.activity}"
        ]
        success, output, error = self.run_adb(cmd)
        
        if success:
            print("✓ 应用启动成功")
            return True
        else:
            print(f"✗ 应用启动失败: {error}")
            return False
    
    def stop_app(self):
        """停止应用"""
        print(f"正在停止应用: {self.package}")
        cmd = ["shell", "am", "force-stop", self.package]
        success, output, error = self.run_adb(cmd)
        
        if success:
            print("✓ 应用已停止")
            return True
        else:
            print(f"✗ 停止应用失败: {error}")
            return False
    
    def clear_logcat(self):
        """清除日志"""
        print("正在清除日志...")
        success, output, error = self.run_adb(["logcat", "-c"])
        
        if success:
            print("✓ 日志已清除")
            return True
        else:
            print(f"✗ 清除日志失败: {error}")
            return False
    
    def save_logcat(self, filename=None, filter_tag=None, duration=None):
        """保存日志到文件"""
        if filename is None:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"logcat_{timestamp}.txt"
        
        log_path = self.logs_dir / filename
        
        print(f"正在保存日志到: {log_path}")
        print("按 Ctrl+C 停止记录...")
        
        cmd = ["logcat"]
        if filter_tag:
            cmd.append(f"{filter_tag}:V *:S")  # 只显示指定tag的日志
        
        try:
            with open(log_path, 'w', encoding='utf-8') as f:
                process = subprocess.Popen(
                    ["adb"] + cmd,
                    stdout=f,
                    stderr=subprocess.STDOUT,
                    text=True
                )
                
                if duration:
                    time.sleep(duration)
                    process.terminate()
                    process.wait()
                    print(f"✓ 日志已保存 ({duration}秒)")
                else:
                    process.wait()
                    print(f"✓ 日志已保存")
                
                return True
        except KeyboardInterrupt:
            process.terminate()
            process.wait()
            print(f"\n✓ 日志已保存: {log_path}")
            return True
        except Exception as e:
            print(f"✗ 保存日志失败: {e}")
            return False
    
    def view_logcat(self, filter_tag=None, follow=True):
        """查看实时日志"""
        cmd = ["logcat"]
        
        if filter_tag:
            cmd.append(f"{filter_tag}:V *:S")
        elif self.package:
            # 默认过滤应用包名
            cmd.append(f"{self.package}:V *:S")
        
        if follow:
            cmd.append("-v", "time")
        
        print("正在显示日志 (按 Ctrl+C 退出)...")
        try:
            subprocess.run(["adb"] + cmd, check=False)
        except KeyboardInterrupt:
            print("\n已退出日志查看")
    
    def pull_files(self, remote_path, local_path=None):
        """从设备拉取文件"""
        if local_path is None:
            local_path = remote_path.split('/')[-1]
        
        print(f"正在拉取文件: {remote_path} -> {local_path}")
        success, output, error = self.run_adb(["pull", remote_path, local_path])
        
        if success:
            print(f"✓ 文件已拉取: {local_path}")
            return True
        else:
            print(f"✗ 拉取文件失败: {error}")
            return False
    
    def pull_recordings(self):
        """拉取录制的音频文件"""
        remote_dir = f"/sdcard/Android/data/{self.package}/files/Recordings/"
        local_dir = Path("recordings")
        local_dir.mkdir(exist_ok=True)
        
        print(f"正在拉取录制文件...")
        success, output, error = self.run_adb(["pull", remote_dir, str(local_dir)])
        
        if success:
            print(f"✓ 录制文件已拉取到: {local_dir}")
            return True
        else:
            print(f"✗ 拉取录制文件失败: {error}")
            return False
    
    def pull_screenshots(self):
        """拉取截图文件"""
        remote_dir = f"/sdcard/Android/data/{self.package}/files/Screenshots/"
        local_dir = Path("screenshots")
        local_dir.mkdir(exist_ok=True)
        
        print(f"正在拉取截图文件...")
        success, output, error = self.run_adb(["pull", remote_dir, str(local_dir)])
        
        if success:
            print(f"✓ 截图文件已拉取到: {local_dir}")
            return True
        else:
            print(f"✗ 拉取截图文件失败: {error}")
            return False
    
    def get_app_info(self):
        """获取应用信息"""
        print(f"应用信息:")
        print(f"  包名: {self.package}")
        print(f"  主Activity: {self.activity}")
        
        # 获取版本信息
        cmd = ["shell", "dumpsys", "package", self.package]
        success, output, error = self.run_adb(cmd)
        
        if success:
            lines = output.split('\n')
            for line in lines:
                if "versionName" in line:
                    print(f"  版本: {line.split('=')[-1].strip()}")
                elif "versionCode" in line:
                    print(f"  版本号: {line.split('=')[-1].strip()}")
    
    def grant_permissions(self):
        """授予应用权限"""
        permissions = [
            "android.permission.RECORD_AUDIO",
        ]
        
        print("正在授予权限...")
        for perm in permissions:
            cmd = ["shell", "pm", "grant", self.package, perm]
            success, output, error = self.run_adb(cmd)
            if success:
                print(f"✓ 已授予: {perm}")
            else:
                print(f"✗ 授予失败: {perm}")


def main():
    parser = argparse.ArgumentParser(
        description="Android开发工具 - 安装、调试和日志管理",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例用法:
  # 构建并安装APK
  python dev_tools.py install
  
  # 启动应用
  python dev_tools.py launch
  
  # 查看实时日志
  python dev_tools.py log
  
  # 保存日志到文件
  python dev_tools.py log --save
  
  # 拉取录制文件
  python dev_tools.py pull-recordings
  
  # 完整流程: 构建、安装、启动
  python dev_tools.py build-install-launch
        """
    )
    
    parser.add_argument(
        "action",
        choices=[
            "check", "build", "install", "uninstall",
            "launch", "stop", "log", "log-save",
            "clear-log", "pull-recordings", "pull-screenshots",
            "info", "permissions", "build-install-launch"
        ],
        help="要执行的操作"
    )
    
    parser.add_argument(
        "--apk",
        help="APK文件路径 (默认: app/build/outputs/apk/debug/app-debug.apk)"
    )
    
    parser.add_argument(
        "--filter",
        help="日志过滤标签"
    )
    
    parser.add_argument(
        "--duration",
        type=int,
        help="日志记录时长(秒)"
    )
    
    parser.add_argument(
        "--filename",
        help="日志文件名"
    )
    
    args = parser.parse_args()
    
    tools = AndroidDevTools()
    
    # 检查设备连接
    if args.action != "check":
        if not tools.check_device():
            sys.exit(1)
    
    # 执行操作
    if args.action == "check":
        tools.check_device()
    
    elif args.action == "build":
        tools.build_apk()
    
    elif args.action == "install":
        tools.install_apk(args.apk)
    
    elif args.action == "uninstall":
        tools.uninstall_app()
    
    elif args.action == "launch":
        tools.launch_app()
    
    elif args.action == "stop":
        tools.stop_app()
    
    elif args.action == "log":
        tools.view_logcat(args.filter)
    
    elif args.action == "log-save":
        tools.save_logcat(args.filename, args.filter, args.duration)
    
    elif args.action == "clear-log":
        tools.clear_logcat()
    
    elif args.action == "pull-recordings":
        tools.pull_recordings()
    
    elif args.action == "pull-screenshots":
        tools.pull_screenshots()
    
    elif args.action == "info":
        tools.get_app_info()
    
    elif args.action == "permissions":
        tools.grant_permissions()
    
    elif args.action == "build-install-launch":
        print("=== 完整流程: 构建 -> 安装 -> 启动 ===\n")
        if tools.build_apk():
            print()
            if tools.install_apk():
                print()
                time.sleep(1)  # 等待安装完成
                tools.launch_app()


if __name__ == "__main__":
    main()

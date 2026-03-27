# 傅里叶音频分析器

一个功能强大的Android音频分析应用，使用傅里叶变换实时显示麦克风音频的频谱和波形。

## 功能特性

### 核心功能
- ✅ **实时频谱分析** - 使用FFT显示音频频响
- ✅ **示波器模式** - 实时波形显示
- ✅ **频谱斜率** - 支持0/3/6/12 dB/octave斜率切换
- ✅ **缩放模式** - 线性和对数缩放切换
- ✅ **横屏显示** - 默认横屏运行，适合专业分析

### 实用功能
- ✅ **峰值检测** - 自动检测并标记频谱峰值
- ✅ **频率标记** - 显示常用频率标记（20Hz-20kHz）
- ✅ **增益控制** - 0-200%可调增益
- ✅ **音频录制** - 保存PCM格式音频文件
- ✅ **截图保存** - 长按视图保存当前显示截图
- ✅ **实时信息** - 显示采样率、FFT大小、峰值频率等

### 技术特性
- ✅ **多种窗函数** - 支持矩形、汉宁、汉明、布莱克曼、凯泽窗
- ✅ **高性能FFT** - 优化的Cooley-Tukey算法实现
- ✅ **实时处理** - 流畅的实时音频处理
- ✅ **便携开发** - 所有依赖本地化，支持离线开发

## 系统要求

- Android 7.0 (API 24) 或更高版本
- 目标SDK: Android 15 (API 35)
- 需要麦克风权限

## 项目结构

```
FourierAudioAnalyzer/
├── app/                                 # Android应用模块
│   ├── src/main/
│   │   ├── java/com/fourier/audioanalyzer/
│   │   │   ├── MainActivity.kt          # 主Activity
│   │   │   ├── audio/
│   │   │   │   └── AudioRecorder.kt     # 音频录制模块
│   │   │   ├── fft/
│   │   │   │   └── FFT.kt               # FFT实现
│   │   │   ├── view/
│   │   │   │   └── AudioVisualizerView.kt # 可视化视图
│   │   │   └── util/
│   │   │       ├── ImageUtils.kt        # 图像工具
│   │   │       └── WindowFunction.kt    # 窗函数工具
│   │   ├── res/                         # 资源文件
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts                 # 应用构建配置
├── tools/                               # 本地工具目录（可选）
│   ├── adb/                             # Android Debug Bridge
│   ├── java/jdk-17/                     # Java JDK 17
│   └── python/python-3.x/              # Python 3.x
├── Docs/                                # 文档目录
│   ├── BUILD_INSTRUCTIONS.md            # 构建说明
│   ├── DEV_TOOLS_README.md              # 开发工具说明
│   ├── DEV_TOOLS_QUICKREF.md           # 开发工具快速参考
│   ├── FEATURES.md                      # 功能特性
│   ├── PORTABLE_SETUP.md               # 便携设置指南
│   ├── QUICKSTART.md                   # 快速开始
│   └── ...                             # 更多文档
├── scripts/                             # 辅助脚本目录
│   ├── check_status.py                 # 状态检查脚本
│   ├── copy_all_dependencies_fixed.py # 依赖复制脚本
│   └── ...                             # 更多脚本
├── dev_tools.py                         # 开发工具脚本
├── build.gradle.kts                     # 项目构建配置
├── settings.gradle.kts                  # Gradle设置
└── README.md                            # 项目说明
```

## 便携开发环境

项目支持完全便携开发，所有工具（ADB、JDK、Python）都可以本地化到项目目录中。

### 快速设置本地工具

**自动下载（推荐）:**
```bash
# Windows
cd tools
setup_tools.bat

# Linux/Mac
cd tools
chmod +x setup_tools.sh
./setup_tools.sh
```

**验证设置:**
```bash
python tools/verify_tools.py
```

详细说明请查看 [Docs/PORTABLE_SETUP.md](Docs/PORTABLE_SETUP.md)

**注意**: 所有文档和辅助脚本已整理到 `Docs/` 和 `scripts/` 目录，根目录仅保留核心文件。

## 开发工具脚本

项目包含一个便捷的Python开发工具脚本 `dev_tools.py`，可以快速完成构建、安装、调试和日志管理。

### 快速使用
```bash
# 检查设备连接
python dev_tools.py check

# 一键构建、安装并启动
python dev_tools.py build-install-launch

# 查看实时日志
python dev_tools.py log

# 拉取录制文件和截图
python dev_tools.py pull-recordings
python dev_tools.py pull-screenshots
```

**注意**: 脚本会自动检测并使用本地工具（如果已设置），否则回退到系统PATH中的工具。

详细说明请查看 [Docs/DEV_TOOLS_README.md](Docs/DEV_TOOLS_README.md) 或 [Docs/DEV_TOOLS_QUICKREF.md](Docs/DEV_TOOLS_QUICKREF.md)

## 构建和运行

### 前置要求
- Android Studio Hedgehog | 2023.1.1 或更高版本
- JDK 17 或更高版本
- Android SDK (API 35)
- Python 3.6+ (用于开发工具脚本，可选)

### 构建步骤

1. **克隆或下载项目**
   ```bash
   git clone <repository-url>
   cd FourierAudioAnalyzer
   ```

2. **使用Android Studio打开项目**
   - 打开Android Studio
   - 选择 "Open an Existing Project"
   - 选择项目目录

3. **同步Gradle**
   - Android Studio会自动同步Gradle
   - 如果失败，点击 "Sync Project with Gradle Files"

4. **运行应用**
   - 连接Android设备或启动模拟器
   - 点击 "Run" 按钮或按 `Shift+F10`

### 命令行构建

```bash
# Windows
gradlew.bat assembleDebug

# Linux/Mac
./gradlew assembleDebug
```

生成的APK位于: `app/build/outputs/apk/debug/app-debug.apk`

## 使用方法

### 基本操作

1. **启动应用**
   - 首次启动会请求麦克风权限，请授予权限

2. **切换显示模式**
   - 点击"频谱模式"/"示波器模式"按钮切换

3. **调整缩放**
   - 点击"线性缩放"/"对数缩放"按钮切换

4. **调整频谱斜率**
   - 点击"频谱斜率"按钮循环切换：无 → 3dB/oct → 6dB/oct → 12dB/oct

5. **调整增益**
   - 使用右上角的增益滑块（0-200%）

6. **录制音频**
   - 点击"录制音频"开始录制
   - 再次点击停止并保存（保存在 `/sdcard/Android/data/com.fourier.audioanalyzer/files/Recordings/`）

7. **保存截图**
   - 长按可视化视图保存当前显示截图（保存在 `/sdcard/Android/data/com.fourier.audioanalyzer/files/Screenshots/`）

### 高级功能

- **峰值检测**: 自动检测并标记前10个最强频率峰值
- **频率标记**: 显示标准频率点（20Hz, 50Hz, 100Hz, 200Hz, 500Hz, 1kHz, 2kHz, 5kHz, 10kHz, 20kHz）
- **实时信息**: 左上角显示当前采样率、FFT大小、峰值频率和幅度

## 技术实现

### FFT算法
- 使用Cooley-Tukey快速傅里叶变换算法
- 支持2的幂次方FFT大小（1024, 2048, 4096等）
- 预计算三角函数值以提高性能

### 音频处理
- 使用Android AudioRecord API进行实时音频捕获
- 采样率: 44100 Hz
- 格式: 16-bit PCM, 单声道
- 支持多种窗函数减少频谱泄漏

### 可视化
- 自定义View实现实时绘制
- 支持线性和对数频率轴
- 可配置的频谱斜率（模拟A/C加权）
- 峰值检测和标记

## 依赖说明

本项目使用本地依赖以确保便携开发。主要依赖包括：

- AndroidX Core KTX
- AndroidX AppCompat
- Material Design Components
- ConstraintLayout
- Kotlin Coroutines
- Lifecycle Components

所有依赖通过Maven仓库自动下载。如需完全离线开发，请使用Gradle的依赖缓存功能。

## 文件保存位置

- **音频录制**: `/sdcard/Android/data/com.fourier.audioanalyzer/files/Recordings/`
- **截图**: `/sdcard/Android/data/com.fourier.audioanalyzer/files/Screenshots/`

## 常见问题

### Q: 应用无法获取音频数据
A: 请确保已授予麦克风权限，并在系统设置中检查应用权限。

### Q: 频谱显示不流畅
A: 可以尝试调整FFT大小，较小的FFT大小（如1024）会有更高的更新率。

### Q: 如何导出录制的音频？
A: 录制的PCM文件可以使用Audacity等音频编辑软件打开，需要设置采样率为44100Hz，格式为16-bit PCM单声道。

## 许可证

本项目仅供学习和研究使用。

## 贡献

欢迎提交Issue和Pull Request！

## 更新日志

### v1.0 (2026-01-29)
- 初始版本发布
- 实现基本频谱分析和示波器功能
- 支持频谱斜率和缩放切换
- 添加峰值检测和频率标记
- 实现音频录制和截图保存功能

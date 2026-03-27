# PROJECT.md — Fourier 音频分析仪

## 项目概述

**Fourier 音频分析仪**是一款面向 Android 平台的专业实时音频分析工具，包名 `com.fourier.audioanalyzer`。应用采用 Kotlin 编写，默认横屏运行，深色主题，适合音频调试、声学测量、教学演示等场景。

---

## 技术栈

| 层级 | 技术选型 |
|------|----------|
| 语言 | Kotlin |
| 平台 | Android（minSdk 26 / targetSdk 34） |
| 构建 | Gradle 8.7 + Kotlin DSL |
| UI | Android View 系统 + Material Design 3 |
| 音频 | AudioRecord（PCM 16-bit，44100 Hz） |
| FFT | 自实现 Cooley-Tukey 算法（`fft/FFT.kt`） |
| 渲染 | 硬件加速 Canvas，环形 Bitmap 缓冲区 |

---

## 目录结构

```
Fourier/
├── android/                    # Android 工程根目录
│   └── app/src/main/java/com/fourier/audioanalyzer/
│       ├── MainActivity.kt             # 主界面，协调各模块
│       ├── SettingsActivity.kt         # 设置界面
│       ├── audio/
│       │   ├── AudioRecorder.kt        # 麦克风录音 & 实时 PCM 采集
│       │   ├── AudioFileProcessor.kt   # 音频文件播放与分析
│       │   ├── AudioFilter.kt          # 数字滤波器（低通/高通/带通）
│       │   └── MediaProjectionService.kt # 系统音频捕获（Android 10+）
│       ├── fft/
│       │   └── FFT.kt                  # Cooley-Tukey FFT 实现
│       ├── util/
│       │   ├── AsyncLog.kt             # 异步日志工具
│       │   ├── DebugLog.kt             # 带节流的调试日志
│       │   ├── ImageUtils.kt           # 截图保存工具
│       │   └── WindowFunction.kt       # 窗函数（汉宁/汉明/布莱克曼/凯泽）
│       └── view/
│           ├── AudioVisualizerView.kt  # 频谱 & 波形示波器视图（~3900行）
│           ├── WaterfallView.kt        # 频谱瀑布图（时频图）视图
│           └── SoundLevelMeterView.kt  # 声压级电平计视图
├── 安装调试App.py              # Python 调试辅助脚本（ADB 封装）
├── Docs/                       # 功能说明与开发文档
├── Photos/                     # 仓库主页（README）截图与索引
├── README.md                   # 仓库主页（中英双语说明，GitHub 默认展示）
├── releases/                   # 预编译 APK 与发布说明（见该目录 README）
├── scripts/                    # 构建/部署辅助脚本（含 `copy_to_github.py`：镜像到上级 `Github/` 供推送远程）
├── tools/                      # 本地 ADB / JDK / Python 工具包
├── PROJECT.md                  # 本文件
└── ROADMAP.md                  # 开发路线图
```

---

## 核心模块说明

### AudioVisualizerView（频谱 & 波形视图）
- 支持**实时频谱**（FFT 幅度谱）和**波形示波器**两种模式
- 频率轴缩放：线性 / 对数 / 十二平均律三种模式
- 双指捏合独立缩放横轴（频率）/ 纵轴（幅度），缩放中心跟随双指中点
- 峰值检测与标记、峰值保持线
- 频谱斜率补偿（-12～+12 dB/octave）
- 支持 A 加权滤波、多种窗函数

### WaterfallView（频谱瀑布图）
- 时频三维显示：X 轴=频率，Y 轴=时间（新数据在顶部），颜色=幅度
- **环形缓冲区架构**：60 行/秒，最多保留 60 秒历史（3600 行）
- 新数据到达时仅写 1 行 `setPixels`，无滚动重绘开销
- 双指独立 X/Y 缩放，缩放锚点为双指中点：
  - X 轴（频率轴）：捏合改变频率范围，锚点保持不动
  - Y 轴（时间轴）：捏合改变时间窗口，锚点行保持不动
- 单指拖动：横向平移频率轴，纵向滚动历史
- 双击归位
- 颜色方案：彩虹 / 灰度 / 黑红 / 蓝绿

### AudioRecorder
- 使用 `AudioRecord` 以 44100 Hz / 16-bit PCM 录制
- 内置回调机制，将 PCM 块推送给 FFT 处理器和波形显示

### FFT
- 自实现 Cooley-Tukey 基 2 FFT
- 预计算三角函数表提升性能
- 支持 256 ～ 16384 点变换

---

## 当前功能状态

| 功能 | 状态 |
|------|------|
| 实时频谱（FFT） | ✅ 已实现 |
| 波形示波器 | ✅ 已实现 |
| 频谱瀑布图 | ✅ 已实现 |
| 声压级电平计 | ✅ 已实现 |
| 峰值检测 | ✅ 已实现 |
| 峰值保持线 | ✅ 已实现 |
| 频谱斜率补偿 | ✅ 已实现 |
| 多种窗函数 | ✅ 已实现（汉宁/汉明/布莱克曼/凯泽） |
| 频率轴三种缩放模式 | ✅ 已实现 |
| 双指独立 XY 缩放（瀑布图） | ✅ 已实现（双指中心锚点） |
| 音频文件分析 | ✅ 已实现 |
| 系统音频捕获 | ✅ 已实现（Android 10+） |
| 截图保存 | ✅ 已实现 |
| PCM 录制 | ✅ 已实现 |
| 深色主题 | ✅ 已实现 |
| 横屏默认 | ✅ 已实现 |

---

## 开发环境

- **构建工具**：Gradle 8.7（本地 Gradle Cache 离线构建）
- **JDK**：JDK 17（`tools/java/jdk-17`）
- **ADB**：`tools/adb/adb.exe`
- **调试脚本**：`安装调试App.py`（提供编译/安装/启动/日志查看一键操作）
- **包名**：`com.fourier.audioanalyzer`
- **主 Activity**：`com.fourier.audioanalyzer.MainActivity`

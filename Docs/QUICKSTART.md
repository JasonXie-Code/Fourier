# 快速开始指南

## 5分钟快速上手

### 第一步：打开项目
1. 打开Android Studio
2. 选择 `File > Open`
3. 选择项目目录 `e:\2026.1.29_Fourier`
4. 等待Gradle同步完成（首次可能需要几分钟）

### 第二步：运行应用
1. 连接Android设备（开启USB调试）或启动模拟器
2. 点击工具栏的绿色运行按钮 ▶️
3. 等待应用安装和启动

### 第三步：授予权限
- 首次启动会请求麦克风权限
- 点击"允许"或"授予权限"

### 第四步：开始使用
应用启动后会自动开始分析麦克风音频：

1. **查看频谱**: 默认显示实时频谱
2. **切换模式**: 点击"频谱模式"按钮切换到示波器模式
3. **调整缩放**: 点击"线性缩放"切换到对数缩放
4. **调整增益**: 拖动右上角滑块调整音量
5. **录制音频**: 点击"录制音频"开始录制

## 常用操作

### 切换显示模式
- **频谱模式**: 显示频率域分析（FFT频谱）
- **示波器模式**: 显示时域波形

### 调整频谱斜率
点击"频谱斜率"按钮循环切换：
- 无斜率（0 dB/oct）
- 3 dB/octave
- 6 dB/octave  
- 12 dB/octave

### 缩放模式
- **线性缩放**: 频率轴线性分布，适合精确分析
- **对数缩放**: 频率轴对数分布，符合人耳特性

### 保存数据
- **录制音频**: 点击录制按钮，再次点击停止并保存
- **保存截图**: 长按可视化视图区域

## 文件位置

### 录制的音频
```
/sdcard/Android/data/com.fourier.audioanalyzer/files/Recordings/
```
文件格式：PCM 16-bit，44100Hz，单声道

### 截图
```
/sdcard/Android/data/com.fourier.audioanalyzer/files/Screenshots/
```
文件格式：PNG

## 查看文件

### 使用文件管理器
1. 打开文件管理器应用
2. 导航到上述路径
3. 找到保存的文件

### 使用ADB（开发者）
```bash
adb shell
cd /sdcard/Android/data/com.fourier.audioanalyzer/files/
ls -la Recordings/
ls -la Screenshots/
```

### 导出到电脑
```bash
adb pull /sdcard/Android/data/com.fourier.audioanalyzer/files/Recordings/ ./recordings/
adb pull /sdcard/Android/data/com.fourier.audioanalyzer/files/Screenshots/ ./screenshots/
```

## 播放录制的音频

录制的PCM文件可以使用以下工具播放：

### Audacity（推荐）
1. 打开Audacity
2. `File > Import > Raw Data`
3. 选择PCM文件
4. 设置：
   - Encoding: Signed 16-bit PCM
   - Byte order: Little-endian
   - Channels: 1 (Mono)
   - Start offset: 0
   - Amount to import: 100%
   - Sample rate: 44100 Hz

### FFmpeg
```bash
ffmpeg -f s16le -ar 44100 -ac 1 -i recording_xxx.pcm output.wav
```

## 常见问题

### Q: 没有声音显示？
- 检查麦克风权限是否已授予
- 确认设备麦克风正常工作
- 尝试调整增益滑块

### Q: 频谱显示不流畅？
- 这是正常现象，取决于设备性能
- 可以尝试重启应用

### Q: 如何导出数据？
- 使用ADB pull命令（见上文）
- 或使用文件管理器复制文件

### Q: 应用崩溃？
- 检查Android版本是否≥7.0
- 确认已授予所有必要权限
- 查看logcat日志：`adb logcat | grep Fourier`

## 下一步

- 阅读 [README.md](README.md) 了解完整功能
- 查看 [FEATURES.md](FEATURES.md) 了解所有特性
- 阅读 [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md) 了解构建细节

## 提示和技巧

1. **最佳体验**: 在安静环境中使用，避免环境噪声干扰
2. **频率分析**: 使用对数缩放查看全频段，线性缩放查看细节
3. **峰值检测**: 开启峰值检测可以快速找到主要频率成分
4. **录制技巧**: 录制前先调整增益，确保信号不过载
5. **截图时机**: 在频谱稳定时截图，获得最佳效果

package com.fourier.audioanalyzer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.app.Activity
import android.app.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import android.widget.RadioButton
import android.widget.RadioGroup
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.view.Gravity
import android.view.Display
import android.hardware.display.DisplayManager
import android.view.MenuItem
import android.view.Window
import android.view.MotionEvent
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.graphics.Rect
import android.util.Log
import android.widget.PopupMenu
import com.fourier.audioanalyzer.audio.AudioRecorder
import com.fourier.audioanalyzer.audio.AudioSourceType
import com.fourier.audioanalyzer.audio.AudioFileProcessor
import com.fourier.audioanalyzer.audio.MediaProjectionService
import com.fourier.audioanalyzer.audio.SystemAudioCaptureCallback
import com.fourier.audioanalyzer.databinding.ActivityMainBinding
import com.fourier.audioanalyzer.fft.FFT
import com.fourier.audioanalyzer.util.ImageUtils
import com.fourier.audioanalyzer.util.WindowFunction
import com.fourier.audioanalyzer.util.AsyncLog
import com.fourier.audioanalyzer.util.DebugLog
import com.fourier.audioanalyzer.util.DebugLog.Tag
import com.fourier.audioanalyzer.view.AudioVisualizerView
import com.fourier.audioanalyzer.view.SoundLevelMeterView
import com.fourier.audioanalyzer.view.WaterfallView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import androidx.core.view.WindowInsetsCompat
import kotlin.math.*

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var audioRecorder: AudioRecorder
    private var audioFileProcessor: AudioFileProcessor? = null
    private lateinit var fft: FFT
    private var waterfallView: WaterfallView? = null
    private var soundLevelMeterView: SoundLevelMeterView? = null
    
    private var isRecording = false
    private var isSavingRecording = false
    private var isFileMode = false // 是否为文件分析模式
    private var isPlayingRecording = false // 是否正在播放录制文件
    private var currentPlayingFile: File? = null
    private var isWaterfallMode = false // 是否为瀑布图模式
    private var isSoundLevelMeterMode = false // 是否为分贝计模式
    private var isDisplayPaused = false // 是否暂停显示（启动/停止）
    
    private var btnAuto: com.google.android.material.button.MaterialButton? = null
    private var isDraggingAutoButton = false
    private var frameRateNotAvailableLogged = false // 避免重复打印 setFrameRate 警告
    private var currentMode = AudioVisualizerView.DisplayMode.SPECTRUM
    private var currentScale = AudioVisualizerView.ScaleMode.LINEAR
    private var spectrumSlope = 0f // dB/octave
    private var gain = 1.0f
    private var peakDetectionThresholdDb = -60f  // 峰值检测阈值 dB，-90～0
    private var peakCount = 10  // 显示峰值数量，1～10
    
    private var fftSize = 2048
    private var sampleRate = 44100

    /** 瀑布图专用 FFT 实例（按 waterfallFftSize 懒加载） */
    private var waterfallFft: FFT? = null
    private var waterfallFftSizeForFft: Int = 0
    private fun getWaterfallFft(): FFT {
        if (waterfallFft == null || waterfallFftSizeForFft != waterfallFftSize) {
            waterfallFft = FFT(waterfallFftSize)
            waterfallFftSizeForFft = waterfallFftSize
        }
        return waterfallFft!!
    }

    // ========== 瀑布图 vsync 驱动 ==========
    // 使用 Choreographer 以屏幕刷新率驱动：每帧添加频谱行并重绘
    @Volatile private var latestWaterfallSpectrum: FloatArray? = null
    @Volatile private var latestWaterfallPeakFreq: Float = 0f
    private var waterfallFrameCallbackPosted = false
    private var waterfallAccumulatedRows = 0f
    private var waterfallLastFrameTimeNs = 0L
    private val waterfallChoreographer = Choreographer.getInstance()
    /** 后台线程预计算行像素，减轻主线程负担 */
    private val waterfallPrecomputeExecutor = Executors.newSingleThreadExecutor()
    private val waterfallPrecomputed = AtomicReference<Pair<IntArray, FloatArray>?>(null)
    // 调试：帧间隔统计（检测卡顿）
    private var waterfallFrameCount = 0
    private var waterfallLastStatTimeNs = 0L
    private var waterfallDeltaSecSum = 0f
    private var waterfallDeltaSecMax = 0f
    private var waterfallRowsAddedTotal = 0
    private var waterfallStutterCount = 0
    private val waterfallFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isWaterfallMode || !waterfallFrameCallbackPosted) return
            if (isDisplayPaused) {
                return
            }
            val spectrum = latestWaterfallSpectrum
            waterfallView?.let { wv ->
                if (spectrum != null) {
                    val deltaSec = if (waterfallLastFrameTimeNs != 0L) {
                        (frameTimeNanos - waterfallLastFrameTimeNs) / 1_000_000_000f
                    } else { 0f }
                    waterfallLastFrameTimeNs = frameTimeNanos
                    waterfallAccumulatedRows += deltaSec * 60f
                    var rowsThisFrame = 0
                    val precomputed = waterfallPrecomputed.getAndSet(null)
                    if (precomputed != null) {
                        val (pixels, precomputedSpectrum) = precomputed
                        while (waterfallAccumulatedRows >= 1f) {
                            if (wv.addSpectrumRowFromPixels(pixels, precomputedSpectrum)) {
                                waterfallAccumulatedRows -= 1f
                                rowsThisFrame++
                            } else break
                        }
                    }
                    while (waterfallAccumulatedRows >= 1f) {
                        wv.addSpectrumData(spectrum)
                        waterfallAccumulatedRows -= 1f
                        rowsThisFrame++
                    }
                    waterfallRowsAddedTotal += rowsThisFrame
                    wv.peakFrequency = latestWaterfallPeakFreq
                    waterfallFrameCount++
                    waterfallDeltaSecSum += deltaSec
                    if (deltaSec > waterfallDeltaSecMax) waterfallDeltaSecMax = deltaSec
                    val elapsed = (frameTimeNanos - waterfallLastStatTimeNs) / 1_000_000_000f
                    if (waterfallLastStatTimeNs != 0L && elapsed >= 2f && waterfallFrameCount > 0) {
                        waterfallFrameCount = 0
                        waterfallDeltaSecSum = 0f
                        waterfallDeltaSecMax = 0f
                        waterfallRowsAddedTotal = 0
                        waterfallStutterCount = 0
                        waterfallLastStatTimeNs = frameTimeNanos
                    }
                    if (waterfallLastStatTimeNs == 0L) waterfallLastStatTimeNs = frameTimeNanos
                    // 后台预计算下一帧的行像素
                    val params = wv.preparePrecomputeParams(spectrum)
                    if (params != null) {
                        waterfallPrecomputeExecutor.execute {
                            val pixels = wv.computeRowPixelsForBackground(params)
                            runOnUiThread {
                                waterfallPrecomputed.set(Pair(pixels, params.spectrum))
                            }
                        }
                    }
                }
                wv.invalidate()
            }
            waterfallChoreographer.postFrameCallback(this)
        }
    }

    private fun startWaterfallScrolling() {
        if (!waterfallFrameCallbackPosted) {
            waterfallFrameCallbackPosted = true
            waterfallAccumulatedRows = 0f
            waterfallLastFrameTimeNs = 0L
            waterfallLastStatTimeNs = 0L
            waterfallFrameCount = 0
            waterfallDeltaSecSum = 0f
            waterfallDeltaSecMax = 0f
            waterfallRowsAddedTotal = 0
            waterfallStutterCount = 0
            latestWaterfallSpectrum = null
            waterfallChoreographer.postFrameCallback(waterfallFrameCallback)
        }
    }

    private fun stopWaterfallScrolling() {
        waterfallFrameCallbackPosted = false
        waterfallChoreographer.removeFrameCallback(waterfallFrameCallback)
        latestWaterfallSpectrum = null
        waterfallPrecomputed.set(null)
    }

    /** 恢复瀑布图滚动（暂停后恢复时调用，重置时间戳避免瞬移） */
    private fun resumeWaterfallScrolling() {
        if (waterfallFrameCallbackPosted && isWaterfallMode) {
            waterfallLastFrameTimeNs = 0L
            waterfallChoreographer.postFrameCallback(waterfallFrameCallback)
        }
    }

    // ========== 示波器 vsync 驱动 ==========
    // 音频处理只标记 dirty；每帧最多构建一次快照并更新 UI。
    private var oscilloscopeFrameCallbackPosted = false
    private var oscilloscopeDataDirty = false
    private val oscilloscopeChoreographer = Choreographer.getInstance()

    private fun shouldRunOscilloscopeVsync(): Boolean {
        return currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE &&
            !isWaterfallMode &&
            !isSoundLevelMeterMode &&
            !isDisplayPaused
    }

    private val oscilloscopeFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!oscilloscopeFrameCallbackPosted) return
            if (shouldRunOscilloscopeVsync() && oscilloscopeDataDirty) {
                val panOffset = binding.visualizerView.oscilloscopeOffsetSamples.coerceAtLeast(0)
                val snapshot = buildOscilloscopeSnapshot(panOffset)
                binding.visualizerView.updateWaveform(snapshot.data, snapshot.totalSamples, panOffset)
                oscilloscopeDataDirty = false
            }
            if (oscilloscopeFrameCallbackPosted) {
                oscilloscopeChoreographer.postFrameCallback(this)
            }
        }
    }

    private fun startOscilloscopeVsync() {
        if (!oscilloscopeFrameCallbackPosted) {
            oscilloscopeFrameCallbackPosted = true
            oscilloscopeChoreographer.postFrameCallback(oscilloscopeFrameCallback)
        }
    }

    private fun stopOscilloscopeVsync() {
        oscilloscopeFrameCallbackPosted = false
        oscilloscopeChoreographer.removeFrameCallback(oscilloscopeFrameCallback)
    }

    private fun scheduleOscilloscopeSnapshotUpdate() {
        oscilloscopeDataDirty = true
        if (shouldRunOscilloscopeVsync()) {
            startOscilloscopeVsync()
        }
    }

    /** 实际频谱刷新率测量：帧数、上次统计时间、最近一次显示的实际 FPS */
    private var spectrumFrameCount = 0
    private var lastFpsTimeMs = 0L
    @Volatile private var lastDisplayedFps: Float = 0f

    // 采样率选项
    private val sampleRateOptions = listOf(8000, 16000, 22050, 44100, 48000)
    private val sampleRateLabels = listOf(
        "8000 Hz (电话质量)",
        "16000 Hz (语音质量)",
        "22050 Hz (低质量音频)",
        "44100 Hz (CD质量)",
        "48000 Hz (专业音频)"
    )
    
    // FFT大小选项
    private val fftSizeOptions = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768)
    
    // 累积缓冲区（用于大FFT）
    private val audioBuffer = mutableListOf<Short>()
    // 波形数据环形缓冲区：使用 ShortArray 避免装箱开销，支持高效复制
    private var waveformRingBuffer: ShortArray = ShortArray(0)
    private var waveformWritePos: Int = 0  // 下一个写入位置
    private var waveformDataSize: Int = 0  // 当前有效数据量
    private var totalSamplesReceived: Long = 0L  // 累计接收的样本数（用于稳定采样网格）
    /** 波形缓冲最大样本数：随示波器横轴时间跨度更新，保留最近 N 秒以连续拼接显示 */
    private var maxWaveformBufferSamples: Int = 0
    /** 大 buffer 时与环形缓冲区同步 */
    private val waveformLock = Any()
    /** 预分配的输出数组，避免每帧都创建新数组 */
    private var waveformArrayCache: ShortArray? = null
    /** 暂停时冻结的波形快照（按时间顺序：旧 -> 新），用于暂停后平移查看且不跟随新录制数据 */
    private var pausedWaveformSnapshot: ShortArray? = null
    /** 暂停快照对应的累计样本数右边界（最新样本索引为 totalSamples-1） */
    private var pausedWaveformTotalSamples: Long = 0L
    /** 上次 processWaveform 的时间，用于检测因主线程卡顿/StateFlow 合并导致的丢块（避免波形跳变） */
    private var lastWaveformProcessTimeNs: Long = 0L
    /** 上次更新示波器信息显示的时间 */
    private var lastOscilloscopeInfoUpdateMs: Long = 0L
    
    // 日志节流：每2秒最多输出一次，通过 AsyncLog 在后台执行 I/O 不阻塞主线程
    private var lastOscilloscopeLogTimeMs: Long = 0
    private var lastOscilloscopeScaleLogTimeMs: Long = 0
    private val oscilloscopeLogThrottleIntervalMs = 2000L
    private val oscilloscopeScaleLogThrottleIntervalMs = 300L
    private fun shouldLogOscilloscope(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastOscilloscopeLogTimeMs >= oscilloscopeLogThrottleIntervalMs) {
            lastOscilloscopeLogTimeMs = now
            return true
        }
        return false
    }
    private fun shouldLogOscilloscopeScale(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastOscilloscopeScaleLogTimeMs >= oscilloscopeScaleLogThrottleIntervalMs) {
            lastOscilloscopeScaleLogTimeMs = now
            return true
        }
        return false
    }
    private var overlapRatio = 0.5f // 默认50%重叠
    
    // 重叠率选项
    private val overlapRatioOptions = listOf(0f, 0.25f, 0.5f, 0.75f)
    private val overlapRatioLabels = listOf(
        "无重叠 (0%)",
        "低重叠 (25%)",
        "中重叠 (50%)",
        "高重叠 (75%)"
    )
    
    // SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences
    private val PREFS_NAME = "AudioAnalyzerPrefs"
    private val KEY_FFT_SIZE = "fft_size"
    private val KEY_OVERLAP_RATIO = "overlap_ratio"
    private val KEY_SAMPLE_RATE = "sample_rate"
    private val KEY_WAVEFORM_COLOR = "waveform_color"
    private val KEY_SCALE_MODE = "scale_mode"
    private val KEY_SPECTRUM_SLOPE = "spectrum_slope"
    private val KEY_GAIN = "gain"
    private val KEY_SHOW_FREQUENCY_MARKERS = "show_frequency_markers"
    private val KEY_SHOW_PEAK_DETECTION = "show_peak_detection"
    private val KEY_PEAK_THRESHOLD_DB = "peak_threshold_db"
    private val KEY_PEAK_COUNT = "peak_count"
    private val KEY_OSCILLOSCOPE_STROKE_WIDTH = "oscilloscope_stroke_width"
    private val KEY_OSCILLOSCOPE_GRID_STROKE_WIDTH = "oscilloscope_grid_stroke_width"
    private val KEY_OSCILLOSCOPE_LARGE_WINDOW_USE_PEAK_ENVELOPE = "oscilloscope_large_window_use_peak_envelope"
    private val KEY_SHOW_OSCILLOSCOPE_CENTER_LINE = "show_oscilloscope_center_line"
    private val KEY_SPECTRUM_GRID_STROKE_WIDTH = "spectrum_grid_stroke_width"
    private val KEY_SPECTRUM_MARKER_STROKE_WIDTH = "spectrum_marker_stroke_width"
    private val KEY_AUDIO_SOURCE = "audio_source"
    private val KEY_OSCILLOSCOPE_TRIGGER_ENABLED = "oscilloscope_trigger_enabled"
    private val KEY_OSCILLOSCOPE_TRIGGER_LEVEL = "oscilloscope_trigger_level"
    private val KEY_OSCILLOSCOPE_TRIGGER_MODE = "oscilloscope_trigger_mode"
    private val KEY_OSCILLOSCOPE_SINGLE_TRIGGER = "oscilloscope_single_trigger"
    private val KEY_OSCILLOSCOPE_TRIGGER_HYSTERESIS = "oscilloscope_trigger_hysteresis"
    private val KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_AUTO = "oscilloscope_trigger_holdoff_auto"
    private val KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_MS = "oscilloscope_trigger_holdoff_ms"
    private val KEY_OSCILLOSCOPE_TRIGGER_NOISE_REJECT = "oscilloscope_trigger_noise_reject"
    // 滤波器设置键
    private val KEY_FILTER_ENABLED = "filter_enabled"
    private val KEY_FILTER_TYPE = "filter_type"
    private val KEY_FILTER_CUTOFF = "filter_cutoff"
    private val KEY_FILTER_CENTER = "filter_center"
    private val KEY_FILTER_BANDWIDTH = "filter_bandwidth"
    private val KEY_FILTER_ORDER = "filter_order"
    private val KEY_SCALE_SENSITIVITY = "scale_sensitivity"
    private val KEY_WATERFALL_SENSITIVITY = "waterfall_sensitivity"
    private val KEY_WATERFALL_FFT_SIZE = "waterfall_fft_size"
    private val KEY_WATERFALL_OVERLAP_RATIO = "waterfall_overlap_ratio"
    private val KEY_WATERFALL_COLOR_PALETTE = "waterfall_color_palette"
    private val KEY_SHOW_INFO_PANEL = "show_info_panel"
    // 可视化模式设置
    private val KEY_VISUALIZER_BAR_COUNT = "visualizer_bar_count"
    private val KEY_VISUALIZER_SENSITIVITY = "visualizer_sensitivity"
    private val KEY_VISUALIZER_SLOPE = "visualizer_slope"
    private val KEY_VISUALIZER_BAR_GAP = "visualizer_bar_gap"
    private val KEY_VISUALIZER_PEAK_HOLD = "visualizer_peak_hold"
    private val KEY_VISUALIZER_GRADIENT = "visualizer_gradient"
    // 分贝计设置
    private val KEY_SLM_WEIGHTING = "slm_weighting"
    private val KEY_SLM_RESPONSE_TIME = "slm_response_time"
    private val KEY_SLM_CALIBRATION_OFFSET = "slm_calibration_offset"
    private val KEY_SLM_SHOW_SPL = "slm_show_spl"
    // 缩放信息位置
    private val KEY_SCALE_INFO_X = "scale_info_x"
    private val KEY_SCALE_INFO_Y = "scale_info_y"
    
    /** 兼容旧版：部分设置曾以 Int 存储，现为 Float，读取时统一安全解析 */
    private fun SharedPreferences.getFloatSafe(key: String, default: Float): Float = when (val v = all[key]) {
        is Float -> v
        is Int -> v.toFloat()
        else -> default
    }
    
    private var currentAudioSource: Int = 0  // 0=MIC_RAW, 1=MIC, 2=SYSTEM
    private var oscilloscopeTriggerEnabled: Boolean = false
    private var oscilloscopeTriggerLevelDb: Float = -30f  // dB
    private var oscilloscopeTriggerMode: Int = 0  // 0=上升沿, 1=下降沿, 2=双沿
    private var oscilloscopeSingleTrigger: Boolean = false  // 单次触发模式
    private var oscilloscopeTriggerHysteresis: Float = 5f  // 迟滞量百分比
    private var oscilloscopeTriggerHoldoffAuto: Boolean = true  // 保持时间自动模式
    private var oscilloscopeTriggerHoldoffMs: Float = 1f  // 手动保持时间
    private var oscilloscopeTriggerNoiseReject: Boolean = false  // 噪声抑制
    // 滤波器设置
    private var filterEnabled: Boolean = false
    private var filterType: Int = 0  // 0=低通, 1=高通, 2=带通, 3=陷波
    private var filterCutoff: Float = 1000f  // 截止频率 Hz
    private var filterCenter: Float = 1000f  // 中心频率 Hz
    private var filterBandwidth: Float = 500f  // 带宽 Hz
    private var filterOrder: Int = 2  // 滤波器阶数
    private val audioFilter = com.fourier.audioanalyzer.audio.AudioFilter()
    private var scaleSensitivity: Float = 1.0f  // 0.5=低, 1.0=中, 2.0=高
    private var waterfallSensitivity: Float = 1.0f  // 0.5~50
    private var waterfallFftSize: Int = 2048  // 瀑布图 FFT 大小
    private var waterfallOverlapRatio: Float = 0f  // 瀑布图重叠率（默认0）
    private var waterfallColorPalette: Int = 0  // 0=彩虹, 1=灰度, 2=黑红, 3=蓝绿
    // 分贝计设置
    private var slmWeighting: Int = 0  // 0=A, 1=C, 2=Z, 3=Flat
    private var slmResponseTime: Int = 0  // 0=Fast, 1=Slow
    private var slmCalibrationOffset: Float = 94f  // 校准偏移量（dB）
    private var slmShowSPL: Boolean = true  // 显示 SPL 还是 dBFS
    
    private var showInfoPanel: Boolean = true  // 是否显示左上角信息面板
    private var spectrumInfoFlags: Int = 0x0F  // 默认显示前4项
    private var oscilloscopeInfoFlags: Int = 0x07  // 默认显示前3项
    private var waterfallInfoFlags: Int = 0x21  // 默认显示峰值频率和灵敏度
    // 缩放信息拖动状态
    private var isScaleInfoDragging = false
    private var scaleInfoDragStartX = 0f
    private var scaleInfoDragStartY = 0f
    private var scaleInfoInitialX = 0f
    private var scaleInfoInitialY = 0f
    
    // AUTO 按钮拖动状态
    private var autoButtonX = -1f
    private var autoButtonY = -1f
    
    // 信息项位掩码常量（与 SettingsActivity 保持一致）
    private val SPECTRUM_INFO_PEAK_FREQ = 1
    private val SPECTRUM_INFO_PEAK_AMP = 2
    private val SPECTRUM_INFO_FPS = 4
    private val SPECTRUM_INFO_FFT = 8
    private val SPECTRUM_INFO_SCALE_MODE = 16
    private val SPECTRUM_INFO_ZOOM = 32
    
    private val OSCILLOSCOPE_INFO_TIME_SPAN = 1
    private val OSCILLOSCOPE_INFO_TRIGGER = 2
    private val OSCILLOSCOPE_INFO_SAMPLE_RATE = 4
    private val OSCILLOSCOPE_INFO_ZOOM = 8
    private val OSCILLOSCOPE_INFO_PEAK = 16
    private val OSCILLOSCOPE_INFO_POSITION = 32
    private val OSCILLOSCOPE_INFO_PEAK_TO_PEAK = 64
    private val OSCILLOSCOPE_INFO_RMS = 128
    
    private val WATERFALL_INFO_PEAK_FREQ = 1
    private val WATERFALL_INFO_PEAK_AMP = 2
    private val WATERFALL_INFO_FFT = 8
    private val WATERFALL_INFO_SCALE_MODE = 16
    private val WATERFALL_INFO_SENSITIVITY = 32
    private val WATERFALL_INFO_TIME_RESOLUTION = 64
    
    private val KEY_SPECTRUM_INFO_FLAGS = "spectrum_info_flags"
    private val KEY_OSCILLOSCOPE_INFO_FLAGS = "oscilloscope_info_flags"
    private val KEY_WATERFALL_INFO_FLAGS = "waterfall_info_flags"
    
    // MediaProjection 相关（系统声音捕获）
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjectionService: MediaProjectionService? = null
    private var isSystemAudioActive = false
    private var pendingSystemAudioStart = false
    /** 录音数据采集协程（保证单实例，避免重复 collect 导致时间基准倍速） */
    private var audioDataCollectJob: Job? = null
    /** 采样率状态采集协程（保证单实例） */
    private var sampleRateCollectJob: Job? = null
    /** 系统音频数据采集协程（保证单实例） */
    private var systemAudioDataCollectJob: Job? = null
    /** 系统音频采样率采集协程（保证单实例） */
    private var systemAudioRateCollectJob: Job? = null
    
    /** MediaProjection 权限请求 Launcher */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d("SystemAudio", "MediaProjection 权限已授予")
            startMediaProjectionService(result.resultCode, result.data!!)
        } else {
            Log.w("SystemAudio", "MediaProjection 权限被拒绝")
            Toast.makeText(this, getString(R.string.audio_source_system_not_supported), Toast.LENGTH_LONG).show()
            // 回退到麦克风
            currentAudioSource = 0
            sharedPreferences.edit().putInt(KEY_AUDIO_SOURCE, 0).apply()
            audioRecorder.setAudioSourceType(AudioSourceType.MIC_RAW)
            startAudioProcessing()
        }
    }
    
    /** 通知权限请求 Launcher (Android 13+) */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("SystemAudio", "通知权限已授予")
        } else {
            Log.w("SystemAudio", "通知权限被拒绝，前台服务可能无法正常显示")
        }
        // 无论是否授予，都继续请求 MediaProjection
        requestMediaProjectionPermission()
    }
    
    /** 服务连接 */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MediaProjectionService.LocalBinder
            mediaProjectionService = binder?.getService()
            Log.d("SystemAudio", "MediaProjectionService 已连接")
            
            // 开始从服务收集音频数据
            if (pendingSystemAudioStart) {
                pendingSystemAudioStart = false
                startSystemAudioCollection()
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            mediaProjectionService = null
            isSystemAudioActive = false
            Log.d("SystemAudio", "MediaProjectionService 已断开")
        }
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            if (!isFileMode) {
                startAudioProcessing()
            }
        } else {
            showPermissionDialog()
        }
    }
    
    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            loadAudioFile(it)
        }
    }
    
    // 设置页面
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                // 更新设置
                data.getIntExtra("scaleMode", -1).takeIf { it >= 0 }?.let {
                    currentScale = when (it) {
                        0 -> AudioVisualizerView.ScaleMode.LINEAR
                        1 -> AudioVisualizerView.ScaleMode.LOGARITHMIC
                        2 -> AudioVisualizerView.ScaleMode.TWELVE_TET
                        else -> currentScale
                    }
                    binding.visualizerView.scaleMode = currentScale
                    waterfallView?.scaleMode = currentScale
                    sharedPreferences.edit().putInt(KEY_SCALE_MODE, it).apply()
                }
                data.getFloatExtra("spectrumSlope", Float.NaN).takeIf { !it.isNaN() }?.let {
                    spectrumSlope = it
                    binding.visualizerView.spectrumSlope = spectrumSlope
                    sharedPreferences.edit().putFloat(KEY_SPECTRUM_SLOPE, it).apply()
                }
                data.getFloatExtra("gain", Float.NaN).takeIf { !it.isNaN() }?.let {
                    gain = it
                    binding.visualizerView.gain = gain
                    sharedPreferences.edit().putFloat(KEY_GAIN, it).apply()
                }
                if (data.hasExtra("showFrequencyMarkers")) {
                    val v = data.getBooleanExtra("showFrequencyMarkers", true)
                    binding.visualizerView.showFrequencyMarkers = v
                    sharedPreferences.edit().putBoolean(KEY_SHOW_FREQUENCY_MARKERS, v).apply()
                }
                if (data.hasExtra("showPeakDetection")) {
                    val v = data.getBooleanExtra("showPeakDetection", true)
                    binding.visualizerView.showPeakDetection = v
                    sharedPreferences.edit().putBoolean(KEY_SHOW_PEAK_DETECTION, v).apply()
                }
                data.getFloatExtra("peakThresholdDb", Float.NaN).takeIf { !it.isNaN() }?.let {
                    peakDetectionThresholdDb = it.coerceIn(-90f, 0f)
                    sharedPreferences.edit().putFloat(KEY_PEAK_THRESHOLD_DB, peakDetectionThresholdDb).apply()
                }
                data.getIntExtra("peakCount", -1).takeIf { it in 1..10 }?.let {
                    peakCount = it
                    sharedPreferences.edit().putInt(KEY_PEAK_COUNT, peakCount).apply()
                }
                data.getIntExtra("fftSize", -1).takeIf { it > 0 }?.let {
                    if (it != fftSize) {
                        changeFFTSize(it)
                    }
                }
                data.getFloatExtra("overlapRatio", Float.NaN).takeIf { !it.isNaN() }?.let {
                    if (it != overlapRatio) {
                        changeOverlapRatio(it)
                    }
                }
                // 波形颜色：优先从 Intent 读取，否则从 SharedPreferences 读取
                data.getIntExtra("waveformColor", -1).takeIf { it != -1 }?.let {
                    binding.visualizerView.waveformColor = it
                } ?: run {
                    val defaultWaveformColor = ContextCompat.getColor(this, R.color.waveform_color)
                    val savedWaveformColor = sharedPreferences.getInt(KEY_WAVEFORM_COLOR, defaultWaveformColor)
                    binding.visualizerView.waveformColor = savedWaveformColor
                }
                data.getFloatExtra("oscilloscopeStrokeWidth", Float.NaN).takeIf { !it.isNaN() }?.let {
                    binding.visualizerView.oscilloscopeStrokeWidthBase = it.coerceIn(1f, 10f)
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_STROKE_WIDTH, it).apply()
                }
                // 处理辅助线粗细设置
                data.getFloatExtra("oscilloscopeGridStrokeWidth", Float.NaN).takeIf { !it.isNaN() }?.let {
                    binding.visualizerView.oscilloscopeGridStrokeWidth = it.coerceIn(0.5f, 5f)
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_GRID_STROKE_WIDTH, it).apply()
                }
                if (data.hasExtra("oscilloscopeLargeWindowUsePeakEnvelope")) {
                    val usePeak = data.getBooleanExtra("oscilloscopeLargeWindowUsePeakEnvelope", false)
                    binding.visualizerView.oscilloscopeLargeWindowUsePeakEnvelope = usePeak
                    sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_LARGE_WINDOW_USE_PEAK_ENVELOPE, usePeak).apply()
                }
                if (data.hasExtra("showOscilloscopeCenterLine")) {
                    val show = data.getBooleanExtra("showOscilloscopeCenterLine", true)
                    binding.visualizerView.showOscilloscopeCenterLine = show
                    sharedPreferences.edit().putBoolean(KEY_SHOW_OSCILLOSCOPE_CENTER_LINE, show).apply()
                }
                data.getFloatExtra("spectrumGridStrokeWidth", Float.NaN).takeIf { !it.isNaN() }?.let {
                    binding.visualizerView.spectrumGridStrokeWidth = it.coerceIn(0.5f, 5f)
                    sharedPreferences.edit().putFloat(KEY_SPECTRUM_GRID_STROKE_WIDTH, it).apply()
                }
                data.getFloatExtra("spectrumMarkerStrokeWidth", Float.NaN).takeIf { !it.isNaN() }?.let {
                    binding.visualizerView.spectrumMarkerStrokeWidth = it.coerceIn(0.5f, 5f)
                    sharedPreferences.edit().putFloat(KEY_SPECTRUM_MARKER_STROKE_WIDTH, it).apply()
                }
                // 处理音频源设置
                data.getIntExtra("audioSource", -1).takeIf { it >= 0 }?.let {
                    if (it != currentAudioSource) {
                        changeAudioSource(it)
                    }
                }
                // 处理示波器触发设置
                if (data.hasExtra("triggerEnabled")) {
                    val enabled = data.getBooleanExtra("triggerEnabled", false)
                    oscilloscopeTriggerEnabled = enabled
                    binding.visualizerView.oscilloscopeTriggerEnabled = enabled
                    sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_TRIGGER_ENABLED, enabled).apply()
                }
                data.getFloatExtra("triggerLevelDb", Float.NaN).takeIf { !it.isNaN() }?.let {
                    oscilloscopeTriggerLevelDb = it
                    binding.visualizerView.oscilloscopeTriggerLevelDb = it
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_LEVEL, it).apply()
                }
                data.getIntExtra("triggerMode", -1).takeIf { it >= 0 }?.let {
                    oscilloscopeTriggerMode = it
                    binding.visualizerView.oscilloscopeTriggerMode = it
                    sharedPreferences.edit().putInt(KEY_OSCILLOSCOPE_TRIGGER_MODE, it).apply()
                }
                // 处理触发稳定性设置
                data.getFloatExtra("triggerHysteresis", Float.NaN).takeIf { !it.isNaN() }?.let {
                    oscilloscopeTriggerHysteresis = it.coerceIn(1f, 30f)
                    binding.visualizerView.oscilloscopeTriggerHysteresis = oscilloscopeTriggerHysteresis
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_HYSTERESIS, it).apply()
                }
                if (data.hasExtra("triggerHoldoffAuto")) {
                    oscilloscopeTriggerHoldoffAuto = data.getBooleanExtra("triggerHoldoffAuto", true)
                    binding.visualizerView.oscilloscopeTriggerHoldoffAuto = oscilloscopeTriggerHoldoffAuto
                    sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_AUTO, oscilloscopeTriggerHoldoffAuto).apply()
                }
                data.getFloatExtra("triggerHoldoffMs", Float.NaN).takeIf { !it.isNaN() }?.let {
                    oscilloscopeTriggerHoldoffMs = it.coerceIn(0.1f, 10f)
                    binding.visualizerView.oscilloscopeTriggerHoldoffMs = oscilloscopeTriggerHoldoffMs
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_MS, it).apply()
                }
                if (data.hasExtra("triggerNoiseReject")) {
                    oscilloscopeTriggerNoiseReject = data.getBooleanExtra("triggerNoiseReject", false)
                    binding.visualizerView.oscilloscopeTriggerNoiseReject = oscilloscopeTriggerNoiseReject
                    sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_TRIGGER_NOISE_REJECT, oscilloscopeTriggerNoiseReject).apply()
                }
                if (data.hasExtra("singleTrigger")) {
                    val enabled = data.getBooleanExtra("singleTrigger", false)
                    oscilloscopeSingleTrigger = enabled
                    binding.visualizerView.oscilloscopeSingleTriggerMode = enabled
                    sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_SINGLE_TRIGGER, enabled).apply()
                }
                // 处理滤波器设置
                if (data.hasExtra("filterEnabled")) {
                    filterEnabled = data.getBooleanExtra("filterEnabled", false)
                    sharedPreferences.edit().putBoolean(KEY_FILTER_ENABLED, filterEnabled).apply()
                }
                data.getIntExtra("filterType", -1).takeIf { it >= 0 }?.let {
                    filterType = it
                    sharedPreferences.edit().putInt(KEY_FILTER_TYPE, it).apply()
                }
                data.getFloatExtra("filterCutoff", Float.NaN).takeIf { !it.isNaN() }?.let {
                    filterCutoff = it.coerceIn(20f, 20000f)
                    sharedPreferences.edit().putFloat(KEY_FILTER_CUTOFF, it).apply()
                }
                data.getFloatExtra("filterCenter", Float.NaN).takeIf { !it.isNaN() }?.let {
                    filterCenter = it.coerceIn(20f, 20000f)
                    sharedPreferences.edit().putFloat(KEY_FILTER_CENTER, it).apply()
                }
                data.getFloatExtra("filterBandwidth", Float.NaN).takeIf { !it.isNaN() }?.let {
                    filterBandwidth = it.coerceIn(10f, 5000f)
                    sharedPreferences.edit().putFloat(KEY_FILTER_BANDWIDTH, it).apply()
                }
                data.getIntExtra("filterOrder", -1).takeIf { it in listOf(1, 2, 4, 8) }?.let {
                    filterOrder = it
                    sharedPreferences.edit().putInt(KEY_FILTER_ORDER, it).apply()
                }
                // 更新滤波器配置
                updateAudioFilterConfig()
                data.getFloatExtra("scaleSensitivity", Float.NaN).takeIf { !it.isNaN() }?.let {
                    scaleSensitivity = it
                    binding.visualizerView.scaleSensitivity = it
                    sharedPreferences.edit().putFloat(KEY_SCALE_SENSITIVITY, it).apply()
                }
                data.getFloatExtra("waterfallSensitivity", -1f).takeIf { it >= 0f }?.let {
                    waterfallSensitivity = it
                    waterfallView?.sensitivity = it
                    sharedPreferences.edit().putFloat(KEY_WATERFALL_SENSITIVITY, it).apply()
                }
                data.getIntExtra("waterfallFftSize", -1).takeIf { it > 0 }?.let {
                    waterfallFftSize = it
                    waterfallView?.waterfallFftSize = it
                    sharedPreferences.edit().putInt(KEY_WATERFALL_FFT_SIZE, it).apply()
                }
                data.getFloatExtra("waterfallOverlapRatio", Float.NaN).takeIf { !it.isNaN() }?.let {
                    waterfallOverlapRatio = it.coerceIn(0f, 0.95f)
                    waterfallView?.overlapRatio = it
                    sharedPreferences.edit().putFloat(KEY_WATERFALL_OVERLAP_RATIO, waterfallOverlapRatio).apply()
                }
                data.getIntExtra("waterfallColorPalette", -1).takeIf { it >= 0 }?.let {
                    waterfallColorPalette = it.coerceIn(0, 3)
                    waterfallView?.colorPalette = it
                    sharedPreferences.edit().putInt(KEY_WATERFALL_COLOR_PALETTE, it).apply()
                }
                // 信息面板显示设置
                val newShowInfoPanel = data.getBooleanExtra("showInfoPanel", showInfoPanel)
                if (newShowInfoPanel != showInfoPanel) {
                    showInfoPanel = newShowInfoPanel
                    binding.infoPanel.visibility = if (showInfoPanel) View.VISIBLE else View.GONE
                    sharedPreferences.edit().putBoolean(KEY_SHOW_INFO_PANEL, showInfoPanel).apply()
                }
                // 信息项显示设置
                data.getIntExtra("spectrumInfoFlags", -1).takeIf { it >= 0 }?.let {
                    spectrumInfoFlags = it
                    sharedPreferences.edit().putInt(KEY_SPECTRUM_INFO_FLAGS, it).apply()
                }
                data.getIntExtra("oscilloscopeInfoFlags", -1).takeIf { it >= 0 }?.let {
                    oscilloscopeInfoFlags = it
                    sharedPreferences.edit().putInt(KEY_OSCILLOSCOPE_INFO_FLAGS, it).apply()
                }
                data.getIntExtra("waterfallInfoFlags", -1).takeIf { it >= 0 }?.let {
                    waterfallInfoFlags = it
                    sharedPreferences.edit().putInt(KEY_WATERFALL_INFO_FLAGS, it).apply()
                }
                // 可视化模式设置
                data.getIntExtra("visualizerBarCount", -1).takeIf { it in 8..64 }?.let {
                    binding.visualizerView.visualizerBarCount = it
                    sharedPreferences.edit().putInt(KEY_VISUALIZER_BAR_COUNT, it).apply()
                }
                data.getFloatExtra("visualizerSensitivity", Float.NaN).takeIf { !it.isNaN() }?.let {
                    binding.visualizerView.visualizerSensitivity = it.coerceIn(0.5f, 10.0f)
                    sharedPreferences.edit().putFloat(KEY_VISUALIZER_SENSITIVITY, it).apply()
                }
                data.getFloatExtra("visualizerSlope", Float.NaN).takeIf { !it.isNaN() }?.let {
                    binding.visualizerView.visualizerSlope = it.coerceIn(-12f, 12f)
                    sharedPreferences.edit().putFloat(KEY_VISUALIZER_SLOPE, it).apply()
                }
                data.getFloatExtra("visualizerBarGap", Float.NaN).takeIf { !it.isNaN() }?.let {
                    binding.visualizerView.visualizerBarGap = it.coerceIn(0.1f, 0.5f)
                    sharedPreferences.edit().putFloat(KEY_VISUALIZER_BAR_GAP, it).apply()
                }
                if (data.hasExtra("visualizerPeakHold")) {
                    val v = data.getBooleanExtra("visualizerPeakHold", true)
                    binding.visualizerView.visualizerPeakHold = v
                    sharedPreferences.edit().putBoolean(KEY_VISUALIZER_PEAK_HOLD, v).apply()
                }
                if (data.hasExtra("visualizerGradient")) {
                    val v = data.getBooleanExtra("visualizerGradient", true)
                    binding.visualizerView.visualizerGradient = v
                    sharedPreferences.edit().putBoolean(KEY_VISUALIZER_GRADIENT, v).apply()
                }
                // 分贝计设置
                data.getIntExtra("slmWeighting", -1).takeIf { it >= 0 }?.let {
                    slmWeighting = it
                    sharedPreferences.edit().putInt(KEY_SLM_WEIGHTING, it).apply()
                    soundLevelMeterView?.weightingType = when (it) {
                        0 -> SoundLevelMeterView.WeightingType.A
                        1 -> SoundLevelMeterView.WeightingType.C
                        2 -> SoundLevelMeterView.WeightingType.Z
                        else -> SoundLevelMeterView.WeightingType.FLAT
                    }
                }
                data.getIntExtra("slmResponseTime", -1).takeIf { it >= 0 }?.let {
                    slmResponseTime = it
                    sharedPreferences.edit().putInt(KEY_SLM_RESPONSE_TIME, it).apply()
                    soundLevelMeterView?.responseTime = if (it == 0) 
                        SoundLevelMeterView.ResponseTime.FAST 
                        else SoundLevelMeterView.ResponseTime.SLOW
                }
                data.getFloatExtra("slmCalibrationOffset", Float.NaN).takeIf { !it.isNaN() }?.let {
                    slmCalibrationOffset = it
                    sharedPreferences.edit().putFloat(KEY_SLM_CALIBRATION_OFFSET, it).apply()
                    soundLevelMeterView?.calibrationOffset = it
                }
                if (data.hasExtra("slmShowSPL")) {
                    val v = data.getBooleanExtra("slmShowSPL", true)
                    slmShowSPL = v
                    sharedPreferences.edit().putBoolean(KEY_SLM_SHOW_SPL, v).apply()
                    soundLevelMeterView?.showSPL = v
                }
                // 刷新信息面板（瀑布图/频谱模式切换后需正确显示对应信息）
                updateFFTInfo()
                updateModeSpecificInfo()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        DebugLog.once(Tag.SYSTEM, "app_start") { 
            "应用启动: ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}" 
        }
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 边到边：内容延伸至系统栏下，通过 insets 避开状态栏/导航栏/刘海挖孔
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.TRANSPARENT
        applyEdgeToEdgeInsets(binding.root)
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
        
        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedFFTSize = sharedPreferences.getInt(KEY_FFT_SIZE, 2048)
        // 确保FFT大小是2的幂次方
        fftSize = if ((savedFFTSize and (savedFFTSize - 1)) == 0 && savedFFTSize > 0) {
            savedFFTSize
        } else {
            2048 // 默认值
        }
        overlapRatio = sharedPreferences.getFloatSafe(KEY_OVERLAP_RATIO, 0.5f)
        sampleRate = sharedPreferences.getInt(KEY_SAMPLE_RATE, 44100)
        val defaultWaveformColor = ContextCompat.getColor(this, R.color.waveform_color)
        val savedWaveformColor = sharedPreferences.getInt(KEY_WAVEFORM_COLOR, defaultWaveformColor)
        val savedScaleMode = sharedPreferences.getInt(KEY_SCALE_MODE, 0)
        currentScale = when (savedScaleMode) {
            0 -> AudioVisualizerView.ScaleMode.LINEAR
            1 -> AudioVisualizerView.ScaleMode.LOGARITHMIC
            2 -> AudioVisualizerView.ScaleMode.TWELVE_TET
            else -> AudioVisualizerView.ScaleMode.LINEAR
        }
        spectrumSlope = sharedPreferences.getFloatSafe(KEY_SPECTRUM_SLOPE, 0f)
        gain = sharedPreferences.getFloatSafe(KEY_GAIN, 1f)
        peakDetectionThresholdDb = sharedPreferences.getFloatSafe(KEY_PEAK_THRESHOLD_DB, -60f).coerceIn(-90f, 0f)
        peakCount = sharedPreferences.getInt(KEY_PEAK_COUNT, 10).coerceIn(1, 10)
        currentAudioSource = sharedPreferences.getInt(KEY_AUDIO_SOURCE, 0)
        oscilloscopeTriggerEnabled = sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_TRIGGER_ENABLED, false)
        oscilloscopeTriggerLevelDb = sharedPreferences.getFloatSafe(KEY_OSCILLOSCOPE_TRIGGER_LEVEL, -30f)
        oscilloscopeTriggerMode = sharedPreferences.getInt(KEY_OSCILLOSCOPE_TRIGGER_MODE, 0)
        oscilloscopeSingleTrigger = sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_SINGLE_TRIGGER, false)
        oscilloscopeTriggerHysteresis = sharedPreferences.getFloatSafe(KEY_OSCILLOSCOPE_TRIGGER_HYSTERESIS, 5f).coerceIn(1f, 30f)
        oscilloscopeTriggerHoldoffAuto = sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_AUTO, true)
        oscilloscopeTriggerHoldoffMs = sharedPreferences.getFloatSafe(KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_MS, 1f).coerceIn(0.1f, 10f)
        oscilloscopeTriggerNoiseReject = sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_TRIGGER_NOISE_REJECT, false)
        // 加载滤波器设置
        filterEnabled = sharedPreferences.getBoolean(KEY_FILTER_ENABLED, false)
        filterType = sharedPreferences.getInt(KEY_FILTER_TYPE, 0)
        filterCutoff = sharedPreferences.getFloatSafe(KEY_FILTER_CUTOFF, 1000f).coerceIn(20f, 20000f)
        filterCenter = sharedPreferences.getFloatSafe(KEY_FILTER_CENTER, 1000f).coerceIn(20f, 20000f)
        filterBandwidth = sharedPreferences.getFloatSafe(KEY_FILTER_BANDWIDTH, 500f).coerceIn(10f, 5000f)
        filterOrder = sharedPreferences.getInt(KEY_FILTER_ORDER, 2)
        updateAudioFilterConfig()
        scaleSensitivity = sharedPreferences.getFloatSafe(KEY_SCALE_SENSITIVITY, 1.0f)
        waterfallSensitivity = sharedPreferences.getFloatSafe(KEY_WATERFALL_SENSITIVITY, 1.0f).coerceIn(0.5f, 50f)
        waterfallFftSize = sharedPreferences.getInt(KEY_WATERFALL_FFT_SIZE, 2048)
        waterfallOverlapRatio = sharedPreferences.getFloatSafe(KEY_WATERFALL_OVERLAP_RATIO, 0f).coerceIn(0f, 0.95f)
        waterfallColorPalette = sharedPreferences.getInt(KEY_WATERFALL_COLOR_PALETTE, 0).coerceIn(0, 3)
        // 分贝计设置
        slmWeighting = sharedPreferences.getInt(KEY_SLM_WEIGHTING, 0)
        slmResponseTime = sharedPreferences.getInt(KEY_SLM_RESPONSE_TIME, 0)
        slmCalibrationOffset = sharedPreferences.getFloatSafe(KEY_SLM_CALIBRATION_OFFSET, 94f)
        slmShowSPL = sharedPreferences.getBoolean(KEY_SLM_SHOW_SPL, true)
        showInfoPanel = sharedPreferences.getBoolean(KEY_SHOW_INFO_PANEL, true)
        // 加载可视化模式设置
        binding.visualizerView.visualizerBarCount = sharedPreferences.getInt(KEY_VISUALIZER_BAR_COUNT, 32)
        binding.visualizerView.visualizerSensitivity = sharedPreferences.getFloatSafe(KEY_VISUALIZER_SENSITIVITY, 1.5f).coerceIn(0.5f, 10f)
        binding.visualizerView.visualizerSlope = sharedPreferences.getFloatSafe(KEY_VISUALIZER_SLOPE, 3f)
        binding.visualizerView.visualizerBarGap = sharedPreferences.getFloatSafe(KEY_VISUALIZER_BAR_GAP, 0.2f)
        binding.visualizerView.visualizerPeakHold = sharedPreferences.getBoolean(KEY_VISUALIZER_PEAK_HOLD, true)
        binding.visualizerView.visualizerGradient = sharedPreferences.getBoolean(KEY_VISUALIZER_GRADIENT, true)
        spectrumInfoFlags = sharedPreferences.getInt(KEY_SPECTRUM_INFO_FLAGS, 0x0F)
        oscilloscopeInfoFlags = sharedPreferences.getInt(KEY_OSCILLOSCOPE_INFO_FLAGS, 0x07)
        waterfallInfoFlags = sharedPreferences.getInt(KEY_WATERFALL_INFO_FLAGS, 0x21)
        val savedShowFreq = sharedPreferences.getBoolean(KEY_SHOW_FREQUENCY_MARKERS, true)
        val savedShowPeak = sharedPreferences.getBoolean(KEY_SHOW_PEAK_DETECTION, true)
        
        initializeComponents()
        binding.visualizerView.waveformColor = savedWaveformColor
        binding.visualizerView.oscilloscopeStrokeWidthBase = sharedPreferences.getFloatSafe(KEY_OSCILLOSCOPE_STROKE_WIDTH, 2f).coerceIn(1f, 10f)
        binding.visualizerView.oscilloscopeGridStrokeWidth = sharedPreferences.getFloatSafe(KEY_OSCILLOSCOPE_GRID_STROKE_WIDTH, 1f).coerceIn(0.5f, 5f)
        binding.visualizerView.oscilloscopeLargeWindowUsePeakEnvelope =
            sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_LARGE_WINDOW_USE_PEAK_ENVELOPE, false)
        binding.visualizerView.showOscilloscopeCenterLine = sharedPreferences.getBoolean(KEY_SHOW_OSCILLOSCOPE_CENTER_LINE, true)
        binding.visualizerView.spectrumGridStrokeWidth = sharedPreferences.getFloatSafe(KEY_SPECTRUM_GRID_STROKE_WIDTH, 1f).coerceIn(0.5f, 5f)
        binding.visualizerView.spectrumMarkerStrokeWidth = sharedPreferences.getFloatSafe(KEY_SPECTRUM_MARKER_STROKE_WIDTH, 1.5f).coerceIn(0.5f, 5f)
        binding.visualizerView.scaleMode = currentScale
        binding.visualizerView.spectrumSlope = spectrumSlope
        binding.visualizerView.gain = gain
        binding.visualizerView.showFrequencyMarkers = savedShowFreq
        binding.visualizerView.showPeakDetection = savedShowPeak
        binding.visualizerView.oscilloscopeTriggerEnabled = oscilloscopeTriggerEnabled
        binding.visualizerView.oscilloscopeTriggerLevelDb = oscilloscopeTriggerLevelDb
        binding.visualizerView.oscilloscopeTriggerMode = oscilloscopeTriggerMode
        binding.visualizerView.oscilloscopeSingleTriggerMode = oscilloscopeSingleTrigger
        binding.visualizerView.oscilloscopeTriggerHysteresis = oscilloscopeTriggerHysteresis
        binding.visualizerView.oscilloscopeTriggerHoldoffAuto = oscilloscopeTriggerHoldoffAuto
        binding.visualizerView.oscilloscopeTriggerHoldoffMs = oscilloscopeTriggerHoldoffMs
        binding.visualizerView.oscilloscopeTriggerNoiseReject = oscilloscopeTriggerNoiseReject
        binding.visualizerView.scaleSensitivity = scaleSensitivity
        waterfallView?.scaleMode = currentScale
        waterfallView?.sensitivity = waterfallSensitivity
        waterfallView?.waterfallFftSize = waterfallFftSize
        waterfallView?.overlapRatio = waterfallOverlapRatio
        waterfallView?.colorPalette = waterfallColorPalette
        binding.infoPanel.visibility = if (showInfoPanel) View.VISIBLE else View.GONE
        setupUI()
        // 示波器与瀑布图均启用高刷新率（120Hz/屏幕刷新率）以保持流畅
        setOscilloscopeRefreshRate(currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE || isWaterfallMode)
        // 初始化示波器最大平移偏移量（允许查看 60 秒历史）
        val visibleLength = (sampleRate * binding.visualizerView.oscilloscopeVisibleTimeSpanSec).toInt()
        val maxHistorySamples = (sampleRate * 60f).toInt()
        binding.visualizerView.oscilloscopeMaxOffsetSamples = maxOf(0, maxHistorySamples - visibleLength)
        checkPermissions()
        
        // 处理从其他应用打开音频文件的 Intent
        handleIncomingIntent(intent)
    }

    /**
     * 当应用已经打开时，再次接收到新的 Intent（如从其他应用打开另一个音频文件）
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    /**
     * 处理传入的 Intent（打开音频文件）
     */
    private fun handleIncomingIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type
        
        when (action) {
            Intent.ACTION_VIEW -> {
                // 从文件管理器等应用打开
                intent.data?.let { uri ->
                    Log.d("MainActivity", "收到 ACTION_VIEW: $uri, type=$type")
                    openAudioFileFromUri(uri)
                }
            }
            Intent.ACTION_SEND -> {
                // 从分享菜单打开
                if (type?.startsWith("audio/") == true) {
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                        Log.d("MainActivity", "收到 ACTION_SEND: $uri, type=$type")
                        openAudioFileFromUri(uri)
                    }
                }
            }
        }
    }

    /**
     * 从 URI 打开音频文件并开始分析
     */
    private fun openAudioFileFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                // 停止当前录制（如果正在录制）
                if (isRecording) {
                    stopRecording()
                }
                audioRecorder.stopRecording()
                
                // 创建文件处理器
                audioFileProcessor = AudioFileProcessor(this@MainActivity)
                
                // 加载文件
                val success = audioFileProcessor?.loadAudioFile(uri) ?: false
                
                if (success) {
                    isFileMode = true
                    // 文件模式：更多菜单中"导入"会显示为"麦克风模式"
                    showFileProgressBar()

                    // 显示文件信息
                    val fileInfo = audioFileProcessor?.fileInfo?.value
                    fileInfo?.let {
                        val durationSec = it.duration / 1000000
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.file_loaded, "${it.fileName}\n时长: ${durationSec}秒, 采样率: ${it.sampleRate}Hz"),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    // 更新采样率
                    audioFileProcessor?.sampleRateFlow?.value?.let { rate ->
                        sampleRate = rate
                        binding.visualizerView.sampleRate = rate
                        waterfallView?.sampleRate = rate
                        updateFFT()
                    }

                    // 开始处理文件
                    startFileProcessing()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.file_load_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "打开音频文件失败", e)
                Toast.makeText(this@MainActivity, "加载文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 从 URI 获取文件名
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
        }
        
        if (fileName == null) {
            fileName = uri.lastPathSegment
        }
        
        return fileName
    }

    /** 边到边适配：为 view 设置 window insets 监听，padding 避开系统栏与刘海/挖孔 */
    private fun applyEdgeToEdgeInsets(view: View) {
        val baseControlBarPaddingBottom = binding.controlBar.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val paddingLeft = max(bars.left, cutout.left)
            val paddingTop = max(bars.top, cutout.top)
            val paddingRight = max(bars.right, cutout.right)
            val paddingBottom = max(bars.bottom, cutout.bottom)
            Log.d("DialogDebug", "applyEdgeToEdgeInsets: view=${v.javaClass.simpleName}, " +
                    "bars=[L:$bars.left T:$bars.top R:$bars.right B:$bars.bottom], " +
                    "cutout=[L:$cutout.left T:$cutout.top R:$cutout.right B:$cutout.bottom], " +
                    "finalPadding=[L:$paddingLeft T:$paddingTop R:$paddingRight B:$paddingBottom]")
            // 根布局不吃底部 inset，避免把底部栏整体抬起；改为让底部栏自身向下扩展到导航栏区域
            v.setPadding(paddingLeft, paddingTop, paddingRight, 0)
            binding.controlBar.setPadding(
                binding.controlBar.paddingLeft,
                binding.controlBar.paddingTop,
                binding.controlBar.paddingRight,
                baseControlBarPaddingBottom + paddingBottom
            )
            insets
        }
    }

    /** 示波器模式支持 120Hz 显示：进入时请求高刷新率，离开时恢复默认 */
    private fun setOscilloscopeRefreshRate(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            // API 31+：Window.setFrameRate（反射调用，避免 compileSdk/符号 问题）
            try {
                val setFrameRate = window.javaClass.getMethod(
                    "setFrameRate",
                    Float::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                // FrameRateCompatibility: FIXED_SOURCE=1, DEFAULT=0；CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS=1
                val fixedSource = 1
                val defaultCompat = 0
                val seamless = 1
                setFrameRate.invoke(window, if (enabled) 120f else 0f, if (enabled) fixedSource else defaultCompat, seamless)
            } catch (_: Exception) {
                // 只打印一次警告，不打印堆栈（避免主线程 I/O 卡顿）
                if (!frameRateNotAvailableLogged) {
                    frameRateNotAvailableLogged = true
                    Log.d("Oscilloscope", "setFrameRate not available on this device")
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30：通过 Display 模式选择 120Hz（或最接近的高刷）
            if (enabled) {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
                val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
                val modes = display.supportedModes ?: return
                val best = modes.maxByOrNull { mode ->
                    val rate = mode.refreshRate
                    if (rate >= 90f) rate else 0f
                }
                if (best != null && best.refreshRate >= 90f) {
                    window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
                }
            } else {
                window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
            }
        }
    }

    private fun initializeComponents() {
        // 初始化瀑布图视图
        waterfallView = binding.waterfallView
        
        // 初始化分贝计视图
        soundLevelMeterView = binding.soundLevelMeterView
        
        // 初始化音频录制器，并应用保存的音频源设置
        val audioSourceType = when (currentAudioSource) {
            0 -> AudioSourceType.MIC_RAW
            1 -> AudioSourceType.MIC
            2 -> AudioSourceType.SYSTEM
            else -> AudioSourceType.MIC_RAW
        }
        audioRecorder = AudioRecorder(sampleRate = sampleRate, audioSourceType = audioSourceType)
        
        // 设置系统音频捕获回调
        audioRecorder.systemAudioCaptureCallback = object : SystemAudioCaptureCallback {
            override fun onRequestMediaProjection() {
                Log.d("SystemAudio", "AudioRecorder 请求 MediaProjection 权限")
                requestSystemAudioCapture()
            }
            
            override fun onSystemAudioCaptureStarted() {
                Log.d("SystemAudio", "系统音频捕获已启动")
            }
            
            override fun onSystemAudioCaptureStopped() {
                Log.d("SystemAudio", "系统音频捕获已停止")
            }
        }
        
        updateFFT()
        
        // 设置视图参数
        binding.visualizerView.sampleRate = sampleRate
        binding.visualizerView.fftSize = fftSize
        if (maxWaveformBufferSamples <= 0) {
            maxWaveformBufferSamples = maxOf((sampleRate * 0.02f * 2).toInt(), 8192)
        }
        Log.d("Oscilloscope", "[CALLER] set displayMode from initializeComponents, currentMode=$currentMode")
        binding.visualizerView.displayMode = currentMode  // 确保显示模式正确设置
        waterfallView?.sampleRate = sampleRate
        waterfallView?.fftSize = fftSize
        waterfallView?.overlapRatio = waterfallOverlapRatio
        
        // 添加长按保存截图功能
        binding.visualizerView.setOnLongClickListener {
            saveScreenshot()
            true
        }
        
        binding.waterfallView.setOnLongClickListener {
            saveScreenshot()
            true
        }
        
        setupAutoButton()
    }
    
    private fun updateFFT() {
        // 验证FFT大小是否为2的幂次方
        if ((fftSize and (fftSize - 1)) != 0 || fftSize <= 0) {
            // 如果不是2的幂次方，重置为默认值
            fftSize = 2048
            sharedPreferences.edit().putInt(KEY_FFT_SIZE, fftSize).apply()
        }
        
        try {
            fft = FFT(fftSize)
        } catch (e: IllegalArgumentException) {
            // 如果FFT初始化失败，使用默认值
            fftSize = 2048
            fft = FFT(fftSize)
            sharedPreferences.edit().putInt(KEY_FFT_SIZE, fftSize).apply()
        }
        binding.visualizerView.fftSize = fftSize
        waterfallView?.fftSize = fftSize
        
        // 清空缓冲区并重置波形缓冲大小为默认
        audioBuffer.clear()
        synchronized(waveformLock) { waveformDataSize = 0; waveformWritePos = 0 }
        maxWaveformBufferSamples = maxOf((sampleRate * 0.02f * 2).toInt(), 8192)
        resetFpsMeasurement()
        // 更新显示
        updateFFTInfo()
        // 保存设置
        sharedPreferences.edit().putInt(KEY_FFT_SIZE, fftSize).apply()
    }

    private fun resetFpsMeasurement() {
        spectrumFrameCount = 0
        lastFpsTimeMs = 0L
        lastDisplayedFps = 0f
    }
    
    private fun updateFFTInfo() {
        // 波形模式时不显示FFT信息
        if (currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE) {
            binding.tvFFTSize.text = ""
            binding.tvFFTSize.visibility = View.GONE
        } else {
            binding.tvFFTSize.visibility = View.VISIBLE
            if (isWaterfallMode) {
                // 瀑布图模式：使用瀑布图专用 FFT 参数
                val freqRes = sampleRate.toFloat() / waterfallFftSize
                val overlapPercent = (waterfallOverlapRatio * 100).toInt()
                binding.tvFFTSize.text = "FFT: $waterfallFftSize (${String.format("%.2f", freqRes)}Hz, 重叠: ${overlapPercent}%, vsync)"
                binding.tvFFTSize.setTextColor(
                    when {
                        waterfallFftSize >= 16384 -> ContextCompat.getColor(this, R.color.peak_color)
                        waterfallFftSize >= 8192 -> ContextCompat.getColor(this, R.color.secondary_light)
                        else -> ContextCompat.getColor(this, R.color.text_primary)
                    }
                )
            } else {
                // 频谱模式：使用频谱 FFT 参数
                val frequencyResolution = sampleRate.toFloat() / fftSize
                val updateFrequency = if (fftSize > sampleRate / 10) {
                    val overlapSize = (fftSize * overlapRatio).toInt()
                    val hopSize = maxOf(fftSize - overlapSize, 1)
                    val hopTimeMs = (hopSize * 1000f) / sampleRate
                    val estimatedFftTimeMs = when {
                        fftSize >= 32768 -> 300f
                        fftSize >= 16384 -> 150f
                        fftSize >= 8192 -> 80f
                        fftSize >= 4096 -> 40f
                        else -> 20f
                    }
                    val uiOverheadMs = if (fftSize >= 16384) 30f else 15f
                    val totalTimeMs = hopTimeMs + estimatedFftTimeMs + uiOverheadMs
                    String.format("%.1f", 1000f / totalTimeMs)
                } else "实时"
                val overlapPercent = (overlapRatio * 100).toInt()
                binding.tvFFTSize.text = "FFT: $fftSize (${String.format("%.2f", frequencyResolution)}Hz, 重叠: ${overlapPercent}%, ~${updateFrequency}FPS)"
                binding.tvFFTSize.setTextColor(
                    when {
                        fftSize >= 16384 -> ContextCompat.getColor(this, R.color.peak_color)
                        fftSize >= 8192 -> ContextCompat.getColor(this, R.color.secondary_light)
                        else -> ContextCompat.getColor(this, R.color.text_primary)
                    }
                )
            }
        }
        
        // 根据模式更新信息显示
        updateModeSpecificInfo()
    }
    
    /**
     * 根据当前模式更新左上角信息显示（根据用户设置的信息项）
     * - 频谱模式：峰值频率、峰值幅度、帧率、FFT/采样率、频率刻度、缩放比例
     * - 示波器模式：时间窗口、触发状态、采样率、缩放比例、信号峰值、历史位置
     * - 瀑布图模式：峰值频率、峰值幅度、时间分辨率、FFT大小、频率刻度、灵敏度
     * 
     * 所有启用的信息项始终显示，没有数据时用"--"占位，保持布局稳定
     */
    private fun updateModeSpecificInfo(peakFreq: Float? = null, peakAmp: Float? = null) {
        val infoLines = mutableListOf<String>()
        
        // 判断是否有有效数据（用于需要实时数据的项）
        val hasValidData = peakFreq != null || peakAmp != null
        
        when {
            isWaterfallMode -> {
                // 瀑布图模式 - 所有启用的项始终显示，无数据用"--"占位
                if ((waterfallInfoFlags and WATERFALL_INFO_PEAK_FREQ) != 0) {
                    val text = if (peakFreq != null && peakFreq > 0) "${String.format("%.1f", peakFreq)}Hz" else "--"
                    infoLines.add("峰值: $text")
                }
                if ((waterfallInfoFlags and WATERFALL_INFO_PEAK_AMP) != 0) {
                    val text = if (peakAmp != null) String.format("%.2f", peakAmp) else "--"
                    infoLines.add("幅度: $text")
                }
                if ((waterfallInfoFlags and WATERFALL_INFO_FFT) != 0) {
                    infoLines.add("FFT: $waterfallFftSize")
                }
                if ((waterfallInfoFlags and WATERFALL_INFO_SCALE_MODE) != 0) {
                    val scaleText = when (currentScale) {
                        AudioVisualizerView.ScaleMode.LINEAR -> "线性"
                        AudioVisualizerView.ScaleMode.LOGARITHMIC -> "对数"
                        AudioVisualizerView.ScaleMode.TWELVE_TET -> "律制"
                    }
                    infoLines.add("刻度: $scaleText")
                }
                if ((waterfallInfoFlags and WATERFALL_INFO_SENSITIVITY) != 0) {
                    infoLines.add("灵敏度: ${String.format("%.1f", waterfallSensitivity)}")
                }
                if ((waterfallInfoFlags and WATERFALL_INFO_TIME_RESOLUTION) != 0) {
                    val overlapSize = (waterfallFftSize * waterfallOverlapRatio).toInt()
                    val hopSize = (waterfallFftSize - overlapSize).coerceAtLeast(1)
                    val timeResMs = hopSize * 1000f / sampleRate
                    infoLines.add("分辨率: ${String.format("%.1f", timeResMs)}ms/行")
                }
            }
            currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE -> {
                // 示波器模式 - 所有启用的项始终显示，无数据用"--"占位
                if ((oscilloscopeInfoFlags and OSCILLOSCOPE_INFO_TIME_SPAN) != 0) {
                    val timeSpanSec = binding.visualizerView.oscilloscopeVisibleTimeSpanSec
                    val timeText = if (timeSpanSec > 0) {
                        val timeSpanMs = timeSpanSec * 1000
                        if (timeSpanMs >= 1) "${String.format("%.1f", timeSpanMs)}ms"
                        else "${String.format("%.1f", timeSpanMs * 1000)}µs"
                    } else "--"
                    infoLines.add("窗口: $timeText")
                }
                if ((oscilloscopeInfoFlags and OSCILLOSCOPE_INFO_TRIGGER) != 0) {
                    val triggerText = if (oscilloscopeTriggerEnabled) {
                        if (oscilloscopeSingleTrigger) {
                            if (binding.visualizerView.isSingleTriggerFrozen()) "单次: 冻结" else "单次: 等待"
                        } else {
                            if (binding.visualizerView.lastTriggerFound) "触发: 同步" else "触发: 等待"
                        }
                    } else "触发: 关"
                    infoLines.add(triggerText)
                }
                if ((oscilloscopeInfoFlags and OSCILLOSCOPE_INFO_SAMPLE_RATE) != 0) {
                    val rateText = if (sampleRate > 0) "${sampleRate}Hz" else "--"
                    infoLines.add("采样: $rateText")
                }
                if ((oscilloscopeInfoFlags and OSCILLOSCOPE_INFO_ZOOM) != 0) {
                    val hPercent = binding.visualizerView.oscilloscopeHorizontalDisplayPercent?.toInt() ?: 100
                    val vPercent = (binding.visualizerView.currentScaleY * 100).toInt()
                    infoLines.add("缩放: ${hPercent}%×${vPercent}%")
                }
                // 获取波形统计数据用于峰值、峰峰值、RMS显示
                val waveformStats = binding.visualizerView.getWaveformStats()
                if ((oscilloscopeInfoFlags and OSCILLOSCOPE_INFO_PEAK) != 0) {
                    val peakText = if (waveformStats != null && waveformStats.peakPositive > 0) {
                        val peakDb = 20 * kotlin.math.log10(waveformStats.peakPositive.toDouble()).toFloat()
                        String.format("%.1f", peakDb) + "dB"
                    } else "--"
                    infoLines.add("峰值: $peakText")
                }
                if ((oscilloscopeInfoFlags and OSCILLOSCOPE_INFO_PEAK_TO_PEAK) != 0) {
                    val ppText = if (waveformStats != null && waveformStats.peakToPeak > 0) {
                        String.format("%.2f", waveformStats.peakToPeak)
                    } else "--"
                    infoLines.add("峰峰值: $ppText")
                }
                if ((oscilloscopeInfoFlags and OSCILLOSCOPE_INFO_RMS) != 0) {
                    val rmsText = if (waveformStats != null && waveformStats.rms > 0) {
                        val rmsDb = 20 * kotlin.math.log10(waveformStats.rms.toDouble()).toFloat()
                        String.format("%.1f", rmsDb) + "dB"
                    } else "--"
                    infoLines.add("RMS: $rmsText")
                }
                if ((oscilloscopeInfoFlags and OSCILLOSCOPE_INFO_POSITION) != 0) {
                    val offsetSamples = binding.visualizerView.oscilloscopeOffsetSamples
                    val posText = if (offsetSamples > 0 && sampleRate > 0) {
                        val offsetMs = offsetSamples.toFloat() / sampleRate * 1000
                        "-${String.format("%.0f", offsetMs)}ms"
                    } else "当前"
                    infoLines.add("位置: $posText")
                }
            }
            else -> {
                // 频谱模式 - 所有启用的项始终显示，无数据用"--"占位
                if ((spectrumInfoFlags and SPECTRUM_INFO_PEAK_FREQ) != 0) {
                    val text = if (peakFreq != null && peakFreq > 0) "${String.format("%.1f", peakFreq)}Hz" else "--"
                    infoLines.add("峰值: $text")
                }
                if ((spectrumInfoFlags and SPECTRUM_INFO_PEAK_AMP) != 0) {
                    val text = if (peakAmp != null) String.format("%.2f", peakAmp) else "--"
                    infoLines.add("幅度: $text")
                }
                if ((spectrumInfoFlags and SPECTRUM_INFO_FPS) != 0) {
                    val fpsText = if (lastDisplayedFps > 0) "${String.format("%.0f", lastDisplayedFps)}FPS" else "--"
                    infoLines.add("帧率: $fpsText")
                }
                if ((spectrumInfoFlags and SPECTRUM_INFO_FFT) != 0) {
                    val fftText = if (fftSize > 0) "$fftSize" else "--"
                    infoLines.add("FFT: $fftText")
                }
                if ((spectrumInfoFlags and SPECTRUM_INFO_SCALE_MODE) != 0) {
                    val scaleText = when (currentScale) {
                        AudioVisualizerView.ScaleMode.LINEAR -> "线性"
                        AudioVisualizerView.ScaleMode.LOGARITHMIC -> "对数"
                        AudioVisualizerView.ScaleMode.TWELVE_TET -> "律制"
                    }
                    infoLines.add("刻度: $scaleText")
                }
                if ((spectrumInfoFlags and SPECTRUM_INFO_ZOOM) != 0) {
                    val sx = (binding.visualizerView.currentScaleX * 100).toInt()
                    val sy = (binding.visualizerView.currentScaleY * 100).toInt()
                    infoLines.add("缩放: ${sx}%×${sy}%")
                }
            }
        }
        
        // 将信息分配到两个 TextView：第一项单独一行，其余项换行显示
        if (infoLines.isNotEmpty()) {
            binding.tvMaxFrequency.text = infoLines.getOrNull(0) ?: ""
            binding.tvPeakAmplitude.text = if (infoLines.size > 1) infoLines.drop(1).joinToString("\n") else ""
        } else {
            binding.tvMaxFrequency.text = ""
            binding.tvPeakAmplitude.text = ""
        }
    }
    
    private fun setupUI() {
        // 模式切换：点击弹出向上的列表菜单
        binding.btnModeSwitch.setOnClickListener { view ->
            showModeSelectionPopup(view)
        }
        
        // 长按模式切换按钮也可返回频谱模式
        binding.btnModeSwitch.setOnLongClickListener {
            if (isWaterfallMode || currentMode != AudioVisualizerView.DisplayMode.SPECTRUM) {
                switchToMode(AudioVisualizerView.DisplayMode.SPECTRUM)
            }
            true
        }
        
        // 录制按钮
        binding.btnRecord.setOnClickListener {
            if (isFileMode) {
                Toast.makeText(this, "文件模式下无法录制", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()
            }
        }
        
        // 启动/停止：暂停或继续当前显示
        binding.btnStartStop.setOnClickListener {
            val wasPaused = isDisplayPaused
            setDisplayPausedState(!isDisplayPaused)
            
            // 瀑布图模式：从暂停恢复时立即刷新显示
            if (isWaterfallMode && wasPaused && !isDisplayPaused) {
                resumeWaterfallScrolling()
            }
        }

        // 更多：打开二级菜单
        binding.btnMore.setOnClickListener { showMoreMenu(it) }
        
        // 增益标签点击打开显示选项；右上角显示水平/垂直缩放比例，长按可拖动位置
        binding.tvGainValue.setOnClickListener { showOptionsBottomSheet() }
        setupScaleInfoDrag()
        
        // 采样率选择（点击采样率文本）
        binding.tvSampleRate.setOnClickListener {
            showSampleRateDialog()
        }
        // FFT 设置（点击 FFT 信息直接打开，与采样率一致更符合直觉）
        binding.tvFFTSize.setOnClickListener { showOptionsBottomSheet() }
        
        // 捏合缩放时更新右上角为水平/垂直缩放比例
        binding.visualizerView.onScaleChanged = { _, _ -> updateScaleDisplay() }
        binding.visualizerView.onOscilloscopeTimeSpanChanged = { visibleTimeSpanSec ->
            // 横轴缩放时更新缓冲长度：保留最近 N 秒数据，不同时间段缓存接起来显示
            val minSamples = (sampleRate * 0.02f).toInt()  // 最小 20ms
            val maxSamplesCap = sampleRate * 60  // 最大 60 秒历史
            maxWaveformBufferSamples = (sampleRate * 60f).toInt()  // 始终保留 60 秒缓冲
                .coerceIn(minSamples, maxSamplesCap)
            
            // 调试：记录缩放时的状态（高频手势下限频）
            if (shouldLogOscilloscopeScale()) {
                val currentDataSize = synchronized(waveformLock) { waveformDataSize }
                AsyncLog.d { "[SCALE-DEBUG] timeSpan=${String.format("%.3f", visibleTimeSpanSec)}s, " +
                    "visibleSamples=${(sampleRate * visibleTimeSpanSec).toInt()}, " +
                    "currentDataSize=$currentDataSize, maxBuffer=$maxWaveformBufferSamples" }
            }
        }
        // 示波器平移时，根据是否在最新数据位置控制“返回最新”按钮显示
        binding.visualizerView.onOscilloscopePanChanged = { offsetSamples ->
            updateOscilloscopeButtonsVisibility(offsetSamples)
            // 暂停时仍允许平移查看历史：按当前偏移量即时刷新一次波形快照
            if (isDisplayPaused &&
                currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE &&
                !isWaterfallMode &&
                !isSoundLevelMeterMode
            ) {
                refreshOscilloscopeSnapshotForPan(offsetSamples)
            } else if (
                currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE &&
                !isWaterfallMode &&
                !isSoundLevelMeterMode
            ) {
                // 运行态平移时也立即请求一次快照，避免等待下一块音频数据
                scheduleOscilloscopeSnapshotUpdate()
            }
        }
        waterfallView?.onScaleChanged = { _, _ -> updateScaleDisplay() }
        
        // 初始化显示
        binding.btnModeSwitch.text = getString(R.string.spectrum_mode)
        binding.btnStartStop.text = getString(R.string.stop_display)
        binding.tvSampleRate.text = "采样率: ${sampleRate}Hz"
        updateScaleDisplay()
        updateFFTInfo()

        // 文件进度条拖拽
        binding.seekBarFileProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val processor = audioFileProcessor ?: return
                val durationUs = processor.getDurationUs()
                if (durationUs <= 0) return
                val positionUs = progress * durationUs / 1000L
                processor.seekTo(positionUs)
                binding.tvFileTimeCurrent.text = formatTimeUs(positionUs)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // 信息面板长按拖动
        setupInfoPanelDrag()
        // “返回最新”按钮长按拖动
        setupJumpToLatestButton()
    }
    
    /** 设置信息面板长按拖动功能 */
    private fun setupInfoPanelDrag() {
        var isDragging = false
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialPanelX = 0f
        var initialPanelY = 0f
        
        val longPressHandler = android.os.Handler(mainLooper)
        val longPressRunnable = Runnable {
            isDragging = true
            binding.infoPanel.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            // 切换到绝对定位模式
            val params = binding.infoPanel.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.leftMargin = binding.infoPanel.left
            params.topMargin = binding.infoPanel.top
            binding.infoPanel.layoutParams = params
        }
        
        binding.infoPanel.setOnTouchListener { view, event ->
            val parent = view.parent as? View ?: return@setOnTouchListener false
            
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialPanelX = view.x
                    initialPanelY = view.y
                    isDragging = false
                    longPressHandler.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    
                    // 如果移动超过阈值，取消长按
                    if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    
                    if (isDragging) {
                        // 计算新位置，限制在父视图范围内
                        val newX = (initialPanelX + dx).coerceIn(0f, (parent.width - view.width).toFloat())
                        val newY = (initialPanelY + dy).coerceIn(0f, (parent.height - view.height).toFloat())
                        
                        view.x = newX
                        view.y = newY
                    }
                    isDragging
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    val wasDragging = isDragging
                    isDragging = false
                    
                    if (wasDragging) {
                        // 保存位置到 SharedPreferences
                        sharedPreferences.edit()
                            .putFloat("info_panel_x", view.x)
                            .putFloat("info_panel_y", view.y)
                            .apply()
                    }
                    wasDragging
                }
                else -> false
            }
        }
        
        // 恢复之前保存的位置
        val savedX = sharedPreferences.getFloatSafe("info_panel_x", -1f)
        val savedY = sharedPreferences.getFloatSafe("info_panel_y", -1f)
        if (savedX >= 0 && savedY >= 0) {
            binding.infoPanel.post {
                val parent = binding.infoPanel.parent as? View ?: return@post
                // 切换到绝对定位模式
                val params = binding.infoPanel.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.leftMargin = 0
                params.topMargin = 0
                binding.infoPanel.layoutParams = params
                
                // 设置位置（确保在范围内）
                val maxX = (parent.width - binding.infoPanel.width).toFloat().coerceAtLeast(0f)
                val maxY = (parent.height - binding.infoPanel.height).toFloat().coerceAtLeast(0f)
                binding.infoPanel.x = savedX.coerceIn(0f, maxX)
                binding.infoPanel.y = savedY.coerceIn(0f, maxY)
            }
        }
    }

    /** 设置“返回最新”按钮长按拖动与点击功能 */
    private fun setupJumpToLatestButton() {
        val button = binding.btnJumpToLatest
        var isDragging = false
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialX = 0f
        var initialY = 0f

        val longPressHandler = android.os.Handler(mainLooper)
        val longPressRunnable = Runnable {
            isDragging = true
            button.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        button.setOnTouchListener { view, event ->
            val parent = view.parent as? View ?: return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialX = view.x
                    initialY = view.y
                    longPressHandler.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        var newX = initialX + dx
                        var newY = initialY + dy
                        // 限制在父视图范围内
                        newX = newX.coerceIn(0f, (parent.width - view.width).toFloat())
                        newY = newY.coerceIn(0f, (parent.height - view.height).toFloat())
                        view.x = newX
                        view.y = newY
                        true
                    } else {
                        false
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    val wasDragging = isDragging
                    isDragging = false
                    if (wasDragging) {
                        // 若与 AUTO 重叠则自动推开
                        btnAuto?.let { auto ->
                            adjustPositionToAvoidOverlap(view, auto, parent, 8.dpToPx())
                        }
                    } else if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        // 作为点击处理：返回最新数据位置
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        binding.visualizerView.oscilloscopeOffsetSamples = 0
                        updateOscilloscopeButtonsVisibility(0)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun formatTimeUs(us: Long): String {
        val totalSec = (us / 1_000_000L).toInt().coerceIn(0, 359999)
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
    
    /** 显示模式选择弹出菜单（向上弹出） */
    private fun showModeSelectionPopup(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.TOP)
        popup.menu.add(0, 1, 0, R.string.spectrum_mode)
        popup.menu.add(0, 2, 1, R.string.oscilloscope_mode)
        popup.menu.add(0, 3, 2, R.string.visualizer_mode)
        popup.menu.add(0, 4, 3, R.string.waterfall_mode)
        popup.menu.add(0, 5, 4, R.string.sound_level_meter_mode)
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> switchToMode(AudioVisualizerView.DisplayMode.SPECTRUM)
                2 -> switchToMode(AudioVisualizerView.DisplayMode.OSCILLOSCOPE)
                3 -> switchToMode(AudioVisualizerView.DisplayMode.VISUALIZER)
                4 -> {
                    // 瀑布图模式：启用高刷新率以保持 120fps/屏幕刷新率
                    exitSoundLevelMeterMode()
                    isWaterfallMode = true
                    setOscilloscopeRefreshRate(true)
                    binding.btnModeSwitch.text = getString(R.string.waterfall_mode)
                    switchToWaterfallView()
                    updateFFTInfo()
                    binding.tvMaxFrequency.text = "峰值频率: --"
                    updateModeSpecificInfo()
                }
                5 -> {
                    // 分贝计模式特殊处理
                    setOscilloscopeRefreshRate(false)
                    exitWaterfallMode()
                    isSoundLevelMeterMode = true
                    binding.btnModeSwitch.text = getString(R.string.sound_level_meter_mode)
                    switchToSoundLevelMeterView()
                    updateModeSpecificInfo()
                }
            }
            true
        }
        popup.show()
    }
    
    /** 切换到指定显示模式 */
    /**
     * 设置示波器模式下的 AUTO 按钮：支持长按拖动
     */
    private fun setupAutoButton() {
        if (btnAuto != null) return
        
        // 按钮尺寸为原来的一半再增大30%（原来大约 80x48dp，一半是 40x24，增大30%后约 52x31）
        val buttonWidth = 52.dpToPx()
        val buttonHeight = 31.dpToPx()
        // 字体大小设置为 10sp，确保 "AUTO" 四个字母能完整显示
        val buttonTextSizeSp = 10f
        
        btnAuto = com.google.android.material.button.MaterialButton(this).apply {
            text = "AUTO"
            textSize = buttonTextSizeSp
            // 移除内边距以适应小尺寸
            setPadding(0, 0, 0, 0)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            layoutParams = FrameLayout.LayoutParams(
                buttonWidth,
                buttonHeight
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = 16.dpToPx()
                bottomMargin = 80.dpToPx() // 默认位置：右下角，避开底部栏
            }
            alpha = 0.8f
            // 初始隐藏，只有示波器模式显示
            visibility = if (currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE) View.VISIBLE else View.GONE
            
            // 恢复之前保存的位置
            if (autoButtonX != -1f && autoButtonY != -1f) {
                x = autoButtonX
                y = autoButtonY
            }
            
            // 点击执行自动缩放并自动设置触发电平
            setOnClickListener {
                if (!isDraggingAutoButton) {
                    binding.visualizerView.autoScaleOscilloscope()
                    // 自动设置触发电平
                    val triggerDb = binding.visualizerView.autoSetTriggerLevel()
                    oscilloscopeTriggerLevelDb = triggerDb
                    // 保存到 SharedPreferences
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_LEVEL, triggerDb).apply()
                    Toast.makeText(this@MainActivity, "已自动调整波形，触发电平: ${triggerDb.toInt()} dB", Toast.LENGTH_SHORT).show()
                    // 震动反馈
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    }
                    if (vibrator.hasVibrator()) {
                        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            }
            
            // 长按开始拖动
            setOnLongClickListener {
                isDraggingAutoButton = true
                alpha = 0.5f
                true
            }
            
            // 拖动处理
            var lastTouchX = 0f
            var lastTouchY = 0f
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDraggingAutoButton) {
                            val dx = event.rawX - lastTouchX
                            val dy = event.rawY - lastTouchY
                            v.x += dx
                            v.y += dy
                            lastTouchX = event.rawX
                            lastTouchY = event.rawY
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDraggingAutoButton) {
                            isDraggingAutoButton = false
                            alpha = 0.8f
                            autoButtonX = v.x
                            autoButtonY = v.y
                            // 若与「最新」重叠则自动推开
                            adjustPositionToAvoidOverlap(v, binding.btnJumpToLatest, binding.visualizerContainer, 8.dpToPx())
                            autoButtonX = v.x
                            autoButtonY = v.y
                        }
                    }
                }
                false // 返回 false 以允许 onClickListener 触发
            }
        }
        
        binding.visualizerContainer.addView(btnAuto)
    }

    /**
     * 松手后若与另一按钮重叠，则沿位移最小的方向推开，保证两按钮至少间隔 gapPx，并限制在 parent 内。
     * @param movedView 刚被拖动的按钮
     * @param otherView 另一个按钮（不可为 null，且应已 layout）
     * @param parent 父容器
     * @param gapPx 两按钮之间最少间隔（像素）
     */
    private fun adjustPositionToAvoidOverlap(
        movedView: View,
        otherView: View,
        parent: View,
        gapPx: Int
    ) {
        if (otherView.visibility != View.VISIBLE) return
        val gap = gapPx.toFloat()
        var ax = movedView.x
        var ay = movedView.y
        val aw = movedView.width.toFloat()
        val ah = movedView.height.toFloat()
        val bx = otherView.x
        val by = otherView.y
        val bw = otherView.width.toFloat()
        val bh = otherView.height.toFloat()
        val aRight = ax + aw
        val aBottom = ay + ah
        val bLeft = bx - gap
        val bRight = bx + bw + gap
        val bTop = by - gap
        val bBottom = by + bh + gap
        if (aRight <= bLeft || ax >= bRight || aBottom <= bTop || ay >= bBottom) return
        val overlapLeft = aRight - bLeft
        val overlapRight = bRight - ax
        val dx = if (overlapLeft <= overlapRight) -overlapLeft else overlapRight
        ax += dx
        val aBottomNew = ay + ah
        val overlapTop = aBottomNew - bTop
        val overlapBottom = bBottom - ay
        val dy = if (overlapTop <= overlapBottom) -overlapTop else overlapBottom
        ay += dy
        ax = ax.coerceIn(0f, (parent.width - movedView.width).toFloat())
        ay = ay.coerceIn(0f, (parent.height - movedView.height).toFloat())
        movedView.x = ax
        movedView.y = ay
    }

    // 扩展函数：dp 转 px
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Float.spToPx(): Float = this * resources.displayMetrics.scaledDensity
    
    /** 统一管理示波器专属按钮（AUTO / 最新）显隐，避免模式切换时遗漏 */
    private fun updateOscilloscopeButtonsVisibility(panOffsetSamples: Int? = null) {
        val isOscilloscopeUiActive =
            currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE &&
            !isWaterfallMode &&
            !isSoundLevelMeterMode
        
        btnAuto?.visibility = if (isOscilloscopeUiActive) View.VISIBLE else View.GONE
        
        val offset = panOffsetSamples ?: binding.visualizerView.oscilloscopeOffsetSamples
        val shouldShowJumpToLatest = isOscilloscopeUiActive && offset > 0
        binding.btnJumpToLatest.visibility = if (shouldShowJumpToLatest) View.VISIBLE else View.GONE
    }

    private data class OscilloscopeSnapshot(
        val data: ShortArray,
        val totalSamples: Long
    )

    /**
     * 从环形缓冲区构建示波器当前窗口快照。
     * 读取范围与 processWaveform 的在线刷新保持一致，保证绘制索引稳定。
     */
    private fun buildOscilloscopeSnapshot(panOffset: Int): OscilloscopeSnapshot {
        val safePanOffset = panOffset.coerceAtLeast(0)
        return synchronized(waveformLock) {
            val totalSamplesSnapshot = totalSamplesReceived
            if (waveformDataSize <= 0) {
                return@synchronized OscilloscopeSnapshot(ShortArray(0), totalSamplesSnapshot)
            }

            val visibleSamples = (sampleRate * binding.visualizerView.oscilloscopeVisibleTimeSpanSec).toInt()
            val triggerSearchMargin = visibleSamples
            val requestedSize = visibleSamples + triggerSearchMargin
            val bufferSize = waveformRingBuffer.size

            val requestEnd = totalSamplesSnapshot - 1 - safePanOffset
            val requestStart = requestEnd - requestedSize + 1
            val dataEnd = totalSamplesSnapshot - 1
            val dataStart = totalSamplesSnapshot - waveformDataSize

            val intersectStart = maxOf(requestStart, dataStart)
            val intersectEnd = minOf(requestEnd, dataEnd)

            if (intersectStart <= intersectEnd) {
                val copyCount = (intersectEnd - intersectStart + 1).toInt()
                val targetArray = ShortArray(copyCount)

                val offsetFromLatest = (totalSamplesSnapshot - intersectStart).toInt()
                val bufferPos = (waveformWritePos - offsetFromLatest + bufferSize) % bufferSize

                if (bufferPos + copyCount <= bufferSize) {
                    System.arraycopy(waveformRingBuffer, bufferPos, targetArray, 0, copyCount)
                } else {
                    val firstPart = bufferSize - bufferPos
                    System.arraycopy(waveformRingBuffer, bufferPos, targetArray, 0, firstPart)
                    System.arraycopy(waveformRingBuffer, 0, targetArray, firstPart, copyCount - firstPart)
                }

                if (shouldLogOscilloscope() && copyCount < requestedSize) {
                    Log.d(
                        "Oscilloscope",
                        "[PARTIAL-DATA] copied $copyCount of $requestedSize samples, " +
                            "panOffset=$safePanOffset, waveformDataSize=$waveformDataSize"
                    )
                }
                OscilloscopeSnapshot(targetArray, totalSamplesSnapshot)
            } else {
                if (shouldLogOscilloscope()) {
                    Log.d(
                        "Oscilloscope",
                        "[NO-DATA] panOffset=$safePanOffset exceeds available data, waveformDataSize=$waveformDataSize"
                    )
                }
                OscilloscopeSnapshot(ShortArray(0), totalSamplesSnapshot)
            }
        }
    }

    /** 将当前环形缓冲区冻结为连续数组，供暂停态平移查看，避免波形跟随新录制数据变化。 */
    private fun capturePausedWaveformSnapshot() {
        synchronized(waveformLock) {
            pausedWaveformTotalSamples = totalSamplesReceived
            if (waveformDataSize <= 0) {
                pausedWaveformSnapshot = ShortArray(0)
                return
            }
            val bufferSize = waveformRingBuffer.size
            val copyCount = waveformDataSize.coerceAtMost(bufferSize)
            val snapshot = ShortArray(copyCount)
            val readStart = (waveformWritePos - copyCount + bufferSize) % bufferSize
            if (readStart + copyCount <= bufferSize) {
                System.arraycopy(waveformRingBuffer, readStart, snapshot, 0, copyCount)
            } else {
                val firstPart = bufferSize - readStart
                System.arraycopy(waveformRingBuffer, readStart, snapshot, 0, firstPart)
                System.arraycopy(waveformRingBuffer, 0, snapshot, firstPart, copyCount - firstPart)
            }
            pausedWaveformSnapshot = snapshot
        }
    }

    /** 从暂停时冻结的数据中按 panOffset 取窗口，保证暂停后滑动不跳到新录制流。 */
    private fun buildOscilloscopePausedSnapshot(panOffset: Int): OscilloscopeSnapshot {
        val safePanOffset = panOffset.coerceAtLeast(0)
        val frozenData = pausedWaveformSnapshot ?: ShortArray(0)
        val frozenTotal = pausedWaveformTotalSamples
        if (frozenData.isEmpty()) return OscilloscopeSnapshot(ShortArray(0), frozenTotal)

        val visibleSamples = (sampleRate * binding.visualizerView.oscilloscopeVisibleTimeSpanSec).toInt()
        val requestedSize = visibleSamples + visibleSamples // 与实时路径保持一致：可见范围 + 触发搜索余量

        val requestEnd = frozenTotal - 1 - safePanOffset
        val requestStart = requestEnd - requestedSize + 1
        val dataEnd = frozenTotal - 1
        val dataStart = frozenTotal - frozenData.size

        val intersectStart = maxOf(requestStart, dataStart)
        val intersectEnd = minOf(requestEnd, dataEnd)
        if (intersectStart > intersectEnd) return OscilloscopeSnapshot(ShortArray(0), frozenTotal)

        val copyCount = (intersectEnd - intersectStart + 1).toInt()
        val startIdx = (intersectStart - dataStart).toInt().coerceIn(0, frozenData.size)
        val endIdx = (startIdx + copyCount).coerceIn(startIdx, frozenData.size)
        return OscilloscopeSnapshot(frozenData.copyOfRange(startIdx, endIdx), frozenTotal)
    }

    /** 暂停状态下，按当前平移偏移刷新波形快照，避免“坐标轴动、波形不动”。 */
    private fun refreshOscilloscopeSnapshotForPan(panOffset: Int) {
        // 暂停态优先使用冻结快照，确保平移时不跟随实时录入数据
        val snapshot = if (isDisplayPaused) {
            if (pausedWaveformSnapshot == null) capturePausedWaveformSnapshot()
            buildOscilloscopePausedSnapshot(panOffset)
        } else {
            buildOscilloscopeSnapshot(panOffset)
        }
        binding.visualizerView.updateWaveform(snapshot.data, snapshot.totalSamples, panOffset.coerceAtLeast(0))
    }

    /**
     * 统一设置显示暂停状态：
     * - 暂停时冻结波形快照
     * - 暂停时临时禁用触发重定位（触发线仍显示）
     * - 恢复时清除冻结快照并恢复触发重定位
     */
    private fun setDisplayPausedState(paused: Boolean) {
        val wasPaused = isDisplayPaused
        isDisplayPaused = paused
        binding.btnStartStop.text = if (isDisplayPaused) getString(R.string.start_display) else getString(R.string.stop_display)
        binding.visualizerView.oscilloscopeTriggerPaused = isDisplayPaused

        if (!wasPaused && isDisplayPaused) {
            capturePausedWaveformSnapshot()
            stopOscilloscopeVsync()
        } else if (wasPaused && !isDisplayPaused) {
            pausedWaveformSnapshot = null
            if (currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE &&
                !isWaterfallMode &&
                !isSoundLevelMeterMode
            ) {
                scheduleOscilloscopeSnapshotUpdate()
                startOscilloscopeVsync()
            }
        }
    }

    private fun switchToMode(mode: AudioVisualizerView.DisplayMode) {
        Log.d("Oscilloscope", "[CALLER] switchToMode: $mode")
        
        // 如果当前是瀑布图模式，先退出
        exitWaterfallMode()
        // 如果当前是分贝计模式，先退出
        exitSoundLevelMeterMode()
        
        // 切换到普通视图
        binding.visualizerView.visibility = View.VISIBLE
        
        currentMode = mode
        binding.visualizerView.displayMode = mode
        
        updateOscilloscopeButtonsVisibility()
        
        // 更新按钮文本
        binding.btnModeSwitch.text = when (mode) {
            AudioVisualizerView.DisplayMode.SPECTRUM -> getString(R.string.spectrum_mode)
            AudioVisualizerView.DisplayMode.OSCILLOSCOPE -> getString(R.string.oscilloscope_mode)
            AudioVisualizerView.DisplayMode.VISUALIZER -> getString(R.string.visualizer_mode)
            AudioVisualizerView.DisplayMode.SOUND_LEVEL_METER -> getString(R.string.sound_level_meter_mode)
        }
        
        // 模式特定处理
        when (mode) {
            AudioVisualizerView.DisplayMode.SPECTRUM -> {
                stopOscilloscopeVsync()
                setOscilloscopeRefreshRate(false)
                binding.tvMaxFrequency.text = "峰值频率: --"
                binding.tvPeakAmplitude.text = "峰值幅度: --"
            }
            AudioVisualizerView.DisplayMode.OSCILLOSCOPE -> {
                // 清空波形缓冲区，避免从其他模式切换时携带大量历史数据导致卡顿
                synchronized(waveformLock) { 
                    waveformDataSize = 0
                    waveformWritePos = 0 
                }
                totalSamplesReceived = 0L
                binding.visualizerView.resetOscilloscopeToDefault()
                setOscilloscopeRefreshRate(true)
                scheduleOscilloscopeSnapshotUpdate()
                startOscilloscopeVsync()
            }
            AudioVisualizerView.DisplayMode.VISUALIZER -> {
                stopOscilloscopeVsync()
                setOscilloscopeRefreshRate(false)
                binding.tvMaxFrequency.text = "峰值频率: --"
                binding.tvPeakAmplitude.text = "峰值幅度: --"
            }
            AudioVisualizerView.DisplayMode.SOUND_LEVEL_METER -> {
                // 分贝计模式不通过这里切换，使用单独的 switchToSoundLevelMeterView()
                stopOscilloscopeVsync()
                setOscilloscopeRefreshRate(false)
            }
        }
        
        updateFFTInfo()
    }
    
    /** 更多菜单：显示选项、FFT 设置、导入/麦克风、播放录制（在按钮上方弹出，避免靠下点不到） */
    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.TOP)
        popup.menuInflater.inflate(R.menu.menu_more, popup.menu)
        popup.menu.findItem(R.id.action_import_file)?.title =
            if (isFileMode) getString(R.string.mic_mode) else getString(R.string.import_audio_file)
        popup.menu.findItem(R.id.action_play_recordings)?.title =
            if (isPlayingRecording) "停止播放" else getString(R.string.play_recordings)
        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_display_options -> { showOptionsBottomSheet(); true }
                R.id.action_import_file -> {
                    if (isFileMode) switchToMicMode() else {
                        if (isRecording) Toast.makeText(this, "请先停止录制", Toast.LENGTH_SHORT).show()
                        else filePickerLauncher.launch("audio/*")
                    }
                    true
                }
                R.id.action_play_recordings -> {
                    if (isPlayingRecording) stopPlayingRecording() else showRecordingListDialog()
                    true
                }
                else -> false
            }
        }
        popup.setForceShowIcon(false)
        popup.show()
    }
    
    /** 显示选项页面：波形颜色、缩放、斜率、增益、频率标记、峰值检测、FFT设置、信号源 */
    private fun showOptionsBottomSheet() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            putExtra("waveformColor", binding.visualizerView.waveformColor)
            putExtra("scaleMode", when (currentScale) {
                AudioVisualizerView.ScaleMode.LINEAR -> 0
                AudioVisualizerView.ScaleMode.LOGARITHMIC -> 1
                AudioVisualizerView.ScaleMode.TWELVE_TET -> 2
            })
            putExtra("spectrumSlope", spectrumSlope)
            putExtra("gain", gain)
            putExtra("showFrequencyMarkers", binding.visualizerView.showFrequencyMarkers)
            putExtra("showPeakDetection", binding.visualizerView.showPeakDetection)
            putExtra("peakThresholdDb", peakDetectionThresholdDb)
            putExtra("peakCount", peakCount)
            putExtra("oscilloscopeStrokeWidth", binding.visualizerView.oscilloscopeStrokeWidthBase)
            putExtra("oscilloscopeGridStrokeWidth", binding.visualizerView.oscilloscopeGridStrokeWidth)
            putExtra(
                "oscilloscopeLargeWindowUsePeakEnvelope",
                binding.visualizerView.oscilloscopeLargeWindowUsePeakEnvelope
            )
            putExtra("showOscilloscopeCenterLine", binding.visualizerView.showOscilloscopeCenterLine)
            putExtra("spectrumGridStrokeWidth", binding.visualizerView.spectrumGridStrokeWidth)
            putExtra("spectrumMarkerStrokeWidth", binding.visualizerView.spectrumMarkerStrokeWidth)
            putExtra("fftSize", fftSize)
            putExtra("overlapRatio", overlapRatio)
            putExtra("sampleRate", sampleRate)
            putExtra("audioSource", currentAudioSource)
            putExtra("triggerEnabled", oscilloscopeTriggerEnabled)
            putExtra("triggerLevelDb", oscilloscopeTriggerLevelDb)
            putExtra("triggerMode", oscilloscopeTriggerMode)
            putExtra("singleTrigger", oscilloscopeSingleTrigger)
            putExtra("triggerHysteresis", oscilloscopeTriggerHysteresis)
            putExtra("triggerHoldoffAuto", oscilloscopeTriggerHoldoffAuto)
            putExtra("triggerHoldoffMs", oscilloscopeTriggerHoldoffMs)
            putExtra("triggerNoiseReject", oscilloscopeTriggerNoiseReject)
            // 滤波器设置
            putExtra("filterEnabled", filterEnabled)
            putExtra("filterType", filterType)
            putExtra("filterCutoff", filterCutoff)
            putExtra("filterCenter", filterCenter)
            putExtra("filterBandwidth", filterBandwidth)
            putExtra("filterOrder", filterOrder)
            putExtra("scaleSensitivity", scaleSensitivity)
            putExtra("waterfallSensitivity", waterfallSensitivity)
            putExtra("waterfallFftSize", waterfallFftSize)
            putExtra("waterfallOverlapRatio", waterfallOverlapRatio)
            putExtra("waterfallColorPalette", waterfallColorPalette)
            putExtra("showInfoPanel", showInfoPanel)
            putExtra("spectrumInfoFlags", spectrumInfoFlags)
            putExtra("oscilloscopeInfoFlags", oscilloscopeInfoFlags)
            putExtra("waterfallInfoFlags", waterfallInfoFlags)
        }
        settingsLauncher.launch(intent)
    }
    
    /** 显示选项弹窗（旧版 Dialog，已废弃，改用 SettingsActivity） */
    @Deprecated("使用 SettingsActivity 替代")
    private fun showOptionsBottomSheetOld() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_options, null)

        val preset1 = dialogView.findViewById<View>(R.id.btnWaveformPreset1)
        val preset2 = dialogView.findViewById<View>(R.id.btnWaveformPreset2)
        val preset3 = dialogView.findViewById<View>(R.id.btnWaveformPreset3)
        val preset4 = dialogView.findViewById<View>(R.id.btnWaveformPreset4)
        val waveformRgbSliders = dialogView.findViewById<View>(R.id.waveformRgbSliders)
        val waveformColorHint = dialogView.findViewById<View>(R.id.waveformColorHint)
        val waveformColorPreview = dialogView.findViewById<View>(R.id.waveformColorPreview)
        val seekBarR = dialogView.findViewById<SeekBar>(R.id.seekBarWaveformR)
        val seekBarG = dialogView.findViewById<SeekBar>(R.id.seekBarWaveformG)
        val seekBarB = dialogView.findViewById<SeekBar>(R.id.seekBarWaveformB)
        val tvR = dialogView.findViewById<TextView>(R.id.tvWaveformR)
        val tvG = dialogView.findViewById<TextView>(R.id.tvWaveformG)
        val tvB = dialogView.findViewById<TextView>(R.id.tvWaveformB)

        val presetColors = intArrayOf(
            ContextCompat.getColor(this, R.color.waveform_preset_1),
            ContextCompat.getColor(this, R.color.waveform_preset_2),
            ContextCompat.getColor(this, R.color.waveform_preset_3),
            ContextCompat.getColor(this, R.color.waveform_preset_4)
        )

        fun applyWaveformColor(color: Int) {
            binding.visualizerView.waveformColor = color
            sharedPreferences.edit().putInt(KEY_WAVEFORM_COLOR, color).apply()
            waveformColorPreview.setBackgroundColor(color)
            seekBarR.progress = Color.red(color)
            seekBarG.progress = Color.green(color)
            seekBarB.progress = Color.blue(color)
            tvR.text = "${Color.red(color)}"
            tvG.text = "${Color.green(color)}"
            tvB.text = "${Color.blue(color)}"
        }

        val currentColor = binding.visualizerView.waveformColor
        waveformColorPreview.setBackgroundColor(currentColor)
        seekBarR.progress = Color.red(currentColor)
        seekBarG.progress = Color.green(currentColor)
        seekBarB.progress = Color.blue(currentColor)
        tvR.text = "${Color.red(currentColor)}"
        tvG.text = "${Color.green(currentColor)}"
        tvB.text = "${Color.blue(currentColor)}"

        preset1.setOnClickListener { applyWaveformColor(presetColors[0]) }
        preset2.setOnClickListener { applyWaveformColor(presetColors[1]) }
        preset3.setOnClickListener { applyWaveformColor(presetColors[2]) }
        preset4.setOnClickListener { applyWaveformColor(presetColors[3]) }

        waveformColorPreview.setOnClickListener {
            val visible = waveformRgbSliders.visibility == View.VISIBLE
            waveformRgbSliders.visibility = if (visible) View.GONE else View.VISIBLE
            waveformColorHint.visibility = if (visible) View.VISIBLE else View.GONE
        }

        fun updateColorFromRgb() {
            val color = Color.rgb(seekBarR.progress, seekBarG.progress, seekBarB.progress)
            binding.visualizerView.waveformColor = color
            sharedPreferences.edit().putInt(KEY_WAVEFORM_COLOR, color).apply()
            waveformColorPreview.setBackgroundColor(color)
        }
        seekBarR.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvR.text = "$p"
                if (fromUser) updateColorFromRgb()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekBarG.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvG.text = "$p"
                if (fromUser) updateColorFromRgb()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekBarB.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvB.text = "$p"
                if (fromUser) updateColorFromRgb()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        listOf(tvR to seekBarR, tvG to seekBarG, tvB to seekBarB).forEach { (tv, seekBar) ->
            tv.setOnClickListener {
                val input = android.widget.EditText(this).apply {
                    setText(tv.text.toString())
                    hint = "0～255"
                    setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                    setPadding(48, 32, 48, 32)
                }
                AlertDialog.Builder(this)
                    .setTitle("RGB")
                    .setMessage("输入 0～255 的整数")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val v = input.text.toString().trim().toIntOrNull()?.coerceIn(0, 255) ?: seekBar.progress
                        seekBar.progress = v
                        tv.text = "$v"
                        updateColorFromRgb()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            tv.isClickable = true
            tv.isFocusable = true
        }

        val toggleScale = dialogView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleScale)
        val toggleSlope = dialogView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleSlope)
        val seekBarGain = dialogView.findViewById<SeekBar>(R.id.seekBarGainSheet)
        val tvGainSheet = dialogView.findViewById<TextView>(R.id.tvGainValueSheet)
        val switchFreq = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchFrequencyMarkerSheet)
        val switchPeak = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchPeakDetectionSheet)

        toggleScale.check(when (currentScale) {
            AudioVisualizerView.ScaleMode.LINEAR -> R.id.btnScaleLinear
            AudioVisualizerView.ScaleMode.LOGARITHMIC -> R.id.btnScaleLog
            AudioVisualizerView.ScaleMode.TWELVE_TET -> R.id.btnScaleTwelveTet
        })
        toggleSlope.check(
            when (spectrumSlope.toInt()) {
                -12 -> R.id.btnSlopeNeg12
                -9 -> R.id.btnSlopeNeg9
                -6 -> R.id.btnSlopeNeg6
                -3 -> R.id.btnSlopeNeg3
                0 -> R.id.btnSlope0
                3 -> R.id.btnSlope3
                6 -> R.id.btnSlope6
                9 -> R.id.btnSlope9
                12 -> R.id.btnSlope12
                else -> R.id.btnSlope0
            }
        )
        seekBarGain.progress = (gain * 100).toInt().coerceIn(0, 200)
        tvGainSheet.text = "${seekBarGain.progress}%"
        switchFreq.isChecked = binding.visualizerView.showFrequencyMarkers
        switchPeak.isChecked = binding.visualizerView.showPeakDetection

        toggleScale.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentScale = when (checkedId) {
                R.id.btnScaleLinear -> AudioVisualizerView.ScaleMode.LINEAR
                R.id.btnScaleLog -> AudioVisualizerView.ScaleMode.LOGARITHMIC
                R.id.btnScaleTwelveTet -> AudioVisualizerView.ScaleMode.TWELVE_TET
                else -> currentScale
            }
            binding.visualizerView.scaleMode = currentScale
            waterfallView?.scaleMode = currentScale
        }
        toggleSlope.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            spectrumSlope = when (checkedId) {
                R.id.btnSlopeNeg12 -> -12f
                R.id.btnSlopeNeg9 -> -9f
                R.id.btnSlopeNeg6 -> -6f
                R.id.btnSlopeNeg3 -> -3f
                R.id.btnSlope0 -> 0f
                R.id.btnSlope3 -> 3f
                R.id.btnSlope6 -> 6f
                R.id.btnSlope9 -> 9f
                R.id.btnSlope12 -> 12f
                else -> 0f
            }
            binding.visualizerView.spectrumSlope = spectrumSlope
        }
        seekBarGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                gain = progress / 100f
                binding.visualizerView.gain = gain
                tvGainSheet.text = "${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvGainSheet.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                setText(tvGainSheet.text.toString().replace("%", ""))
                hint = "0～200"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.gain))
                .setMessage("输入 0～200 的整数（%）")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toIntOrNull()?.coerceIn(0, 200) ?: seekBarGain.progress
                    seekBarGain.progress = v
                    tvGainSheet.text = "${v}%"
                    gain = v / 100f
                    binding.visualizerView.gain = gain
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        tvGainSheet.isClickable = true
        tvGainSheet.isFocusable = true
        switchFreq.setOnCheckedChangeListener { _, isChecked ->
            binding.visualizerView.showFrequencyMarkers = isChecked
        }
        switchPeak.setOnCheckedChangeListener { _, isChecked ->
            binding.visualizerView.showPeakDetection = isChecked
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogView)
        Log.d("DialogDebug", "[显示选项] Dialog created, dialogView size: ${dialogView.width}x${dialogView.height}")
        
        dialog.window?.let { window ->
            Log.d("DialogDebug", "[显示选项] Window before setup: flags=0x${window.attributes.flags.toString(16)}, " +
                    "statusBarColor=0x${window.statusBarColor.toString(16)}, " +
                    "navBarColor=0x${window.navigationBarColor.toString(16)}")
            
            // 根本原因：Dialog 窗口默认不延伸到状态栏/导航栏区域，黑条是后面 Activity 的窗口。
            // FLAG_LAYOUT_NO_LIMITS 让窗口铺满整屏（含系统栏区域），surface 背景才能延伸到顶底。
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            val wlp = window.attributes
            wlp.width = WindowManager.LayoutParams.MATCH_PARENT
            wlp.height = WindowManager.LayoutParams.MATCH_PARENT
            wlp.gravity = Gravity.FILL
            window.attributes = wlp
            
            Log.d("DialogDebug", "[显示选项] Window after setup: flags=0x${window.attributes.flags.toString(16)}, " +
                    "width=${wlp.width}, height=${wlp.height}, gravity=${wlp.gravity}, " +
                    "statusBarColor=0x${window.statusBarColor.toString(16)}, " +
                    "navBarColor=0x${window.navigationBarColor.toString(16)}")
        }
        
        val optionsOverlay = dialogView.findViewById<View>(R.id.optionsOverlay)
        val optionsContentCard = dialogView.findViewById<View>(R.id.optionsContentCard)
        Log.d("DialogDebug", "[显示选项] Before insets: root size=${dialogView.width}x${dialogView.height}, " +
                "root padding=[${dialogView.paddingLeft},${dialogView.paddingTop},${dialogView.paddingRight},${dialogView.paddingBottom}], " +
                "contentCard size=${optionsContentCard.width}x${optionsContentCard.height}, " +
                "contentCard padding=[${optionsContentCard.paddingLeft},${optionsContentCard.paddingTop},${optionsContentCard.paddingRight},${optionsContentCard.paddingBottom}]")
        
        // Dialog 窗口可能收不到系统栏 insets，手动从根布局获取并应用到内容卡片
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(dialogView) { rootView, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val paddingLeft = max(bars.left, cutout.left)
            val paddingTop = max(bars.top, cutout.top)
            val paddingRight = max(bars.right, cutout.right)
            val paddingBottom = max(bars.bottom, cutout.bottom)
            Log.d("DialogDebug", "[显示选项] Root insets: bars=[L:$bars.left T:$bars.top R:$bars.right B:$bars.bottom], " +
                    "cutout=[L:$cutout.left T:$cutout.top R:$cutout.right B:$cutout.bottom], " +
                    "applying to contentCard: [L:$paddingLeft T:$paddingTop R:$paddingRight B:$paddingBottom]")
            optionsContentCard.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            insets
        }
        
        optionsOverlay.setOnTouchListener { _, event ->
            val rect = Rect()
            optionsContentCard.getGlobalVisibleRect(rect)
            val inside = rect.contains(event.rawX.toInt(), event.rawY.toInt())
            when (event.action) {
                MotionEvent.ACTION_UP -> if (!inside) { dialog.dismiss(); true } else false
                else -> !inside
            }
        }
        dialog.setCancelable(true)
        dialog.show()
        
        // 延迟记录，等布局完成后再输出
        dialogView.post {
            Log.d("DialogDebug", "[显示选项] After show: root size=${dialogView.width}x${dialogView.height}, " +
                    "root padding=[${dialogView.paddingLeft},${dialogView.paddingTop},${dialogView.paddingRight},${dialogView.paddingBottom}], " +
                    "root location=[${dialogView.left},${dialogView.top}], " +
                    "contentCard size=${optionsContentCard.width}x${optionsContentCard.height}, " +
                    "contentCard padding=[${optionsContentCard.paddingLeft},${optionsContentCard.paddingTop},${optionsContentCard.paddingRight},${optionsContentCard.paddingBottom}], " +
                    "contentCard location=[${optionsContentCard.left},${optionsContentCard.top}], " +
                    "screen size=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
        }
        
        androidx.core.view.ViewCompat.requestApplyInsets(dialogView)
    }
    
    /**
     * 显示采样率选择对话框
     */
    private fun showSampleRateDialog() {
        val currentIndex = sampleRateOptions.indexOf(sampleRate)
        val selectedIndex = if (currentIndex >= 0) currentIndex else 3 // 默认44100
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_sample_rate))
            .setSingleChoiceItems(sampleRateLabels.toTypedArray(), selectedIndex) { dialog, which ->
                val newRate = sampleRateOptions[which]
                if (newRate != sampleRate) {
                    changeSampleRate(newRate)
                    dialog.dismiss()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 更改采样率
     */
    private fun changeSampleRate(newRate: Int) {
        if (isRecording) {
            Toast.makeText(this, getString(R.string.sample_rate_recording_warning), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 停止当前录制
        audioRecorder.stopRecording()
        
        // 更改采样率
        val success = audioRecorder.setSampleRate(newRate)
        if (success) {
            sampleRate = newRate
            sharedPreferences.edit().putInt(KEY_SAMPLE_RATE, sampleRate).apply()
            
            // 重新初始化FFT
            updateFFT()
            
            // 重新启动音频处理
            startAudioProcessing()
            
            Toast.makeText(this, getString(R.string.sample_rate_changed, sampleRate), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.sample_rate_not_supported), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 更新音频滤波器配置
     */
    private fun updateAudioFilterConfig() {
        audioFilter.configure(
            enabled = filterEnabled,
            filterType = filterType,
            cutoffFreq = filterCutoff,
            centerFreq = filterCenter,
            bandwidth = filterBandwidth,
            order = filterOrder,
            sampleRate = sampleRate.toFloat()
        )
    }
    
    /**
     * 更改音频源
     */
    private fun changeAudioSource(newSource: Int) {
        if (isRecording) {
            Toast.makeText(this, "请先停止录制再更改信号源", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 停止当前录制和系统音频服务
        audioRecorder.stopRecording()
        stopSystemAudioCapture()
        
        // 转换为 AudioSourceType
        val audioSourceType = when (newSource) {
            0 -> AudioSourceType.MIC_RAW
            1 -> AudioSourceType.MIC
            2 -> AudioSourceType.SYSTEM
            else -> AudioSourceType.MIC_RAW
        }
        
        // 如果是系统声音，需要特殊处理
        if (newSource == 2) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Toast.makeText(this, "系统声音捕获需要 Android 10+", Toast.LENGTH_LONG).show()
                return
            }
            
            currentAudioSource = newSource
            sharedPreferences.edit().putInt(KEY_AUDIO_SOURCE, currentAudioSource).apply()
            audioRecorder.setAudioSourceType(audioSourceType)
            
            // 请求 MediaProjection 权限
            requestSystemAudioCapture()
            return
        }
        
        // 麦克风模式
        val success = audioRecorder.setAudioSourceType(audioSourceType)
        if (success) {
            currentAudioSource = newSource
            sharedPreferences.edit().putInt(KEY_AUDIO_SOURCE, currentAudioSource).apply()
            
            // 重新启动音频处理
            startAudioProcessing()
            
            Toast.makeText(this, getString(R.string.audio_source_changed), Toast.LENGTH_SHORT).show()
        }
    }
    
    // ==================== 系统声音捕获相关方法 ====================
    
    /**
     * 请求系统声音捕获权限
     */
    private fun requestSystemAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "系统声音捕获需要 Android 10+", Toast.LENGTH_LONG).show()
            return
        }
        
        // Android 13+ 需要先请求通知权限（前台服务需要显示通知）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.d("SystemAudio", "请求通知权限...")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        
        requestMediaProjectionPermission()
    }
    
    /**
     * 请求 MediaProjection 权限
     */
    private fun requestMediaProjectionPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        
        if (mediaProjectionManager == null) {
            mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        }
        
        val captureIntent = mediaProjectionManager?.createScreenCaptureIntent()
        if (captureIntent != null) {
            Log.d("SystemAudio", "启动 MediaProjection 权限请求...")
            mediaProjectionLauncher.launch(captureIntent)
        } else {
            Log.e("SystemAudio", "无法创建 MediaProjection 请求 Intent")
            Toast.makeText(this, "无法启动系统声音捕获", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 启动 MediaProjection 服务
     */
    private fun startMediaProjectionService(resultCode: Int, resultData: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        
        val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
            putExtra(MediaProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MediaProjectionService.EXTRA_RESULT_DATA, resultData)
        }
        
        pendingSystemAudioStart = true
        
        // 启动前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        // 绑定服务以获取音频数据
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        Log.d("SystemAudio", "MediaProjectionService 已启动")
        Toast.makeText(this, "系统声音捕获已启动", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 开始从系统音频服务收集数据
     */
    private fun startSystemAudioCollection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        
        val service = MediaProjectionService.getInstance()
        if (service == null) {
            Log.w("SystemAudio", "MediaProjectionService 未就绪")
            return
        }
        
        isSystemAudioActive = true
        audioRecorder.notifySystemAudioStarted()

        // 先取消旧采集，避免重复 collect 让同一音频块被多次处理
        systemAudioDataCollectJob?.cancel()
        systemAudioRateCollectJob?.cancel()

        // 从服务收集音频数据
        systemAudioDataCollectJob = lifecycleScope.launch {
            service.audioData.collect { audioData ->
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
                if (isSystemAudioActive) {
                    processAudioData(audioData)
                }
            }
        }

        // 同步系统音频服务的真实采样率，避免“标签时间与波形时间基准不一致”
        systemAudioRateCollectJob = lifecycleScope.launch {
            service.sampleRateFlow.collect { rate ->
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
                sampleRate = rate
                binding.visualizerView.sampleRate = rate
                waterfallView?.sampleRate = rate
                updateFFTInfo()
                binding.tvSampleRate.text = "采样率: ${rate}Hz"
            }
        }
        
        Log.d("SystemAudio", "开始收集系统音频数据")
    }
    
    /**
     * 停止系统声音捕获
     */
    private fun stopSystemAudioCapture() {
        if (!isSystemAudioActive && mediaProjectionService == null) return
        
        isSystemAudioActive = false
        audioRecorder.notifySystemAudioStopped()
        systemAudioDataCollectJob?.cancel()
        systemAudioDataCollectJob = null
        systemAudioRateCollectJob?.cancel()
        systemAudioRateCollectJob = null
        
        try {
            mediaProjectionService?.stopCapture()
            unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w("SystemAudio", "解绑服务时出错: ${e.message}")
        }
        
        mediaProjectionService = null
        
        Log.d("SystemAudio", "系统声音捕获已停止")
    }
    
    
    /**
     * 显示FFT设置页面（已合并到 SettingsActivity）
     */
    private fun showFFTSettingsDialog() {
        // FFT 设置已合并到显示选项页面，直接打开设置页面
        showOptionsBottomSheet()
    }
    
    /**
     * 显示FFT设置对话框（旧版 Dialog，已废弃，改用 SettingsActivity）
     */
    @Deprecated("使用 SettingsActivity 替代")
    private fun showFFTSettingsDialogOld() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_fft_settings, null)
        val radioGroupFFTSizeLeft = dialogView.findViewById<RadioGroup>(R.id.radioGroupFFTSizeLeft)
        val radioGroupFFTSizeRight = dialogView.findViewById<RadioGroup>(R.id.radioGroupFFTSizeRight)
        val radioGroupOverlap = dialogView.findViewById<RadioGroup>(R.id.radioGroupOverlap)
        val tvFFTInfo = dialogView.findViewById<TextView>(R.id.tvFFTInfo)
        
        // 添加FFT大小选项：左列前4项，右列后3项，提高空间利用率
        var selectedFFTIndex = fftSizeOptions.indexOf(fftSize)
        if (selectedFFTIndex < 0) selectedFFTIndex = 2 // 默认2048
        val mid = (fftSizeOptions.size + 1) / 2 // 4 左，3 右
        
        fun addFftRadio(index: Int, size: Int, parent: RadioGroup) {
            val resolution = sampleRate.toFloat() / size
            val performance = when {
                size >= 16384 -> "⚠️高延迟"
                size >= 8192 -> "⚠️延迟较高"
                size >= 4096 -> "✓良好"
                else -> "✓优秀"
            }
            val label = "$size (${String.format("%.0f", resolution)}Hz) $performance"
            val radioButton = RadioButton(this).apply {
                text = label
                id = index
                isChecked = (index == selectedFFTIndex)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 13f
            }
            parent.addView(radioButton)
        }
        fftSizeOptions.forEachIndexed { index, size ->
            val group = if (index < mid) radioGroupFFTSizeLeft else radioGroupFFTSizeRight
            addFftRadio(index, size, group)
        }
        
        fun getSelectedFFTIndex(): Int {
            val leftId = radioGroupFFTSizeLeft.checkedRadioButtonId
            return if (leftId != -1) leftId else radioGroupFFTSizeRight.checkedRadioButtonId
        }
        
        radioGroupFFTSizeLeft.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) radioGroupFFTSizeRight.clearCheck()
            updateFFTSettingsInfoWithSelection(tvFFTInfo, fftSizeOptions[getSelectedFFTIndex()])
        }
        radioGroupFFTSizeRight.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) radioGroupFFTSizeLeft.clearCheck()
            updateFFTSettingsInfoWithSelection(tvFFTInfo, fftSizeOptions[getSelectedFFTIndex()])
        }
        
        // 添加重叠率选项（紧凑）
        var selectedOverlapIndex = overlapRatioOptions.indexOf(overlapRatio)
        if (selectedOverlapIndex < 0) selectedOverlapIndex = 2 // 默认50%
        
        overlapRatioLabels.forEachIndexed { index, label ->
            val radioButton = RadioButton(this).apply {
                text = label
                id = index + 100 // 避免ID冲突
                isChecked = (index == selectedOverlapIndex)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 13f
            }
            radioGroupOverlap.addView(radioButton)
        }
        
        updateFFTSettingsInfo(tvFFTInfo)
        
        // 监听重叠率变化（仅更新对话框内显示）
        radioGroupOverlap.setOnCheckedChangeListener { _, checkedId ->
            val newRatio = overlapRatioOptions[checkedId - 100]
            if (newRatio != overlapRatio) {
                overlapRatio = newRatio
                updateFFTSettingsInfo(tvFFTInfo)
            }
        }
        
        Log.d("DialogDebug", "[FFT设置] Dialog creating, dialogView size: ${dialogView.width}x${dialogView.height}")
        
        val fftSettingsDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(dialogView)
            setCancelable(true)
            window?.let { w ->
                Log.d("DialogDebug", "[FFT设置] Window before setup: flags=0x${w.attributes.flags.toString(16)}, " +
                        "statusBarColor=0x${w.statusBarColor.toString(16)}, " +
                        "navBarColor=0x${w.navigationBarColor.toString(16)}")
                
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.statusBarColor = Color.TRANSPARENT
                w.navigationBarColor = Color.TRANSPARENT
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                val wlp = w.attributes
                wlp.width = WindowManager.LayoutParams.MATCH_PARENT
                wlp.height = WindowManager.LayoutParams.MATCH_PARENT
                wlp.gravity = Gravity.FILL
                w.attributes = wlp
                
                Log.d("DialogDebug", "[FFT设置] Window after setup: flags=0x${w.attributes.flags.toString(16)}, " +
                        "width=${wlp.width}, height=${wlp.height}, gravity=${wlp.gravity}, " +
                        "statusBarColor=0x${w.statusBarColor.toString(16)}, " +
                        "navBarColor=0x${w.navigationBarColor.toString(16)}")
            }
        }
        fun applyFftSettingsAndDismiss() {
            fun getSelectedFFTIndex(): Int {
                val leftId = radioGroupFFTSizeLeft.checkedRadioButtonId
                return if (leftId != -1) leftId else radioGroupFFTSizeRight.checkedRadioButtonId
            }
            val selectedFFTSize = fftSizeOptions[getSelectedFFTIndex()]
            if (selectedFFTSize != fftSize && selectedFFTSize >= 16384) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.performance_warning))
                    .setMessage(getString(R.string.large_fft_warning))
                    .setPositiveButton("确定") { _, _ ->
                        changeFFTSize(selectedFFTSize)
                        val selOverlap = overlapRatioOptions[radioGroupOverlap.checkedRadioButtonId - 100]
                        if (selOverlap != overlapRatio) changeOverlapRatio(selOverlap)
                        fftSettingsDialog.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                if (selectedFFTSize != fftSize) changeFFTSize(selectedFFTSize)
                val selOverlap = overlapRatioOptions[radioGroupOverlap.checkedRadioButtonId - 100]
                if (selOverlap != overlapRatio) changeOverlapRatio(selOverlap)
                fftSettingsDialog.dismiss()
            }
        }
        val fftOverlay = dialogView.findViewById<View>(R.id.fftSettingsOverlay)
        val fftContentCard = dialogView.findViewById<View>(R.id.fftSettingsContentCard)
        
        Log.d("DialogDebug", "[FFT设置] Before insets: root size=${dialogView.width}x${dialogView.height}, " +
                "root padding=[${dialogView.paddingLeft},${dialogView.paddingTop},${dialogView.paddingRight},${dialogView.paddingBottom}], " +
                "contentCard size=${fftContentCard.width}x${fftContentCard.height}, " +
                "contentCard padding=[${fftContentCard.paddingLeft},${fftContentCard.paddingTop},${fftContentCard.paddingRight},${fftContentCard.paddingBottom}]")
        
        // Dialog 窗口可能收不到系统栏 insets，手动从根布局获取并应用到内容卡片
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(dialogView) { rootView, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val paddingLeft = max(bars.left, cutout.left)
            val paddingTop = max(bars.top, cutout.top)
            val paddingRight = max(bars.right, cutout.right)
            val paddingBottom = max(bars.bottom, cutout.bottom)
            Log.d("DialogDebug", "[FFT设置] Root insets: bars=[L:$bars.left T:$bars.top R:$bars.right B:$bars.bottom], " +
                    "cutout=[L:$cutout.left T:$cutout.top R:$cutout.right B:$cutout.bottom], " +
                    "applying to contentCard: [L:$paddingLeft T:$paddingTop R:$paddingRight B:$paddingBottom]")
            fftContentCard.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            insets
        }
        
        fftOverlay.setOnTouchListener { _, event ->
            val rect = Rect()
            fftContentCard.getGlobalVisibleRect(rect)
            val inside = rect.contains(event.rawX.toInt(), event.rawY.toInt())
            when (event.action) {
                MotionEvent.ACTION_UP -> if (!inside) { applyFftSettingsAndDismiss(); true } else false
                else -> !inside
            }
        }
        fftSettingsDialog.show()
        
        // 延迟记录，等布局完成后再输出
        dialogView.post {
            Log.d("DialogDebug", "[FFT设置] After show: root size=${dialogView.width}x${dialogView.height}, " +
                    "root padding=[${dialogView.paddingLeft},${dialogView.paddingTop},${dialogView.paddingRight},${dialogView.paddingBottom}], " +
                    "root location=[${dialogView.left},${dialogView.top}], " +
                    "contentCard size=${fftContentCard.width}x${fftContentCard.height}, " +
                    "contentCard padding=[${fftContentCard.paddingLeft},${fftContentCard.paddingTop},${fftContentCard.paddingRight},${fftContentCard.paddingBottom}], " +
                    "contentCard location=[${fftContentCard.left},${fftContentCard.top}], " +
                    "screen size=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
        }
        
        androidx.core.view.ViewCompat.requestApplyInsets(dialogView)
    }
    
    /**
     * 更新FFT设置信息显示（当前 fftSize）
     */
    private fun updateFFTSettingsInfo(textView: TextView) {
        updateFFTSettingsInfoWithSelection(textView, fftSize)
    }

    /**
     * 按所选 FFT 大小更新对话框内信息（预览，不改变实际 fftSize）
     */
    private fun updateFFTSettingsInfoWithSelection(textView: TextView, selectedSize: Int) {
        val frequencyResolution = sampleRate.toFloat() / selectedSize
        val overlapPercent = (overlapRatio * 100).toInt()
        val updateFrequency = if (selectedSize > sampleRate / 10) {
            val overlapSize = (selectedSize * overlapRatio).toInt()
            val hopSize = maxOf(selectedSize - overlapSize, 1)
            val hopTimeMs = (hopSize * 1000f) / sampleRate
            String.format("%.1f", 1000f / hopTimeMs)
        } else {
            "实时"
        }
        val info = """
            当前设置:
            • FFT大小: $selectedSize
            • 频率分辨率: ${String.format("%.2f", frequencyResolution)}Hz
            • 重叠率: $overlapPercent%
            • 更新频率: ~${updateFrequency}FPS
        """.trimIndent()
        textView.text = info
    }
    
    /**
     * 更改重叠率
     */
    private fun changeOverlapRatio(newRatio: Float) {
        overlapRatio = newRatio
        audioBuffer.clear() // 清空缓冲区，重新开始累积
        synchronized(waveformLock) { waveformDataSize = 0; waveformWritePos = 0 }
        sharedPreferences.edit().putFloat(KEY_OVERLAP_RATIO, overlapRatio).apply()
        resetFpsMeasurement()
        val ratioPercent = (overlapRatio * 100).toInt()
        Toast.makeText(this, "重叠率已更改为: $ratioPercent%", Toast.LENGTH_SHORT).show()
        updateFFTInfo()
    }
    
    /**
     * 更改FFT大小
     */
    private fun changeFFTSize(newSize: Int) {
        fftSize = newSize
        updateFFT()
        Toast.makeText(this, "FFT大小已更改为: $fftSize", Toast.LENGTH_SHORT).show()
    }
    
    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.RECORD_AUDIO
        } else {
            Manifest.permission.RECORD_AUDIO
        }
        
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                // 如果保存的是系统音频源，需要请求 MediaProjection 权限
                if (currentAudioSource == 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestSystemAudioCapture()
                } else {
                    startAudioProcessing()
                }
            }
            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }
    }
    
    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setMessage("需要麦克风权限才能使用音频分析功能")
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 数据刷新链路：AudioRecorder 按小块（368 样本/次）读取 → audioData Flow 发射
     * → processAudioData → 示波器 processWaveform / 频谱 processSpectrum → updateWaveform/updateSpectrum → invalidate()
     * 44.1kHz 下 368 样本/次 ≈ 120 次/秒，配合 120Hz 显示实现丝滑刷新。
     */
    private fun startAudioProcessing() {
        audioRecorder.startRecording()

        // 先取消旧采集，避免重复 collect 导致同一数据块重复处理
        audioDataCollectJob?.cancel()
        sampleRateCollectJob?.cancel()

        audioDataCollectJob = lifecycleScope.launch {
            audioRecorder.audioData.collect { audioData ->
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
                processAudioData(audioData)
            }
        }

        sampleRateCollectJob = lifecycleScope.launch {
            audioRecorder.sampleRateFlow.collect { rate ->
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
                sampleRate = rate
                binding.visualizerView.sampleRate = rate
                waterfallView?.sampleRate = rate
                updateFFTInfo()
                binding.tvSampleRate.text = "采样率: ${rate}Hz"
            }
        }
    }
    
    private fun processAudioData(audioData: ShortArray) {
        // 暂停时仍然处理数据更新缓冲区，只是不更新 UI 显示
        if (shouldLogOscilloscope()) {
            val mode = currentMode
            val wf = isWaterfallMode
            val ad = audioData.size
            AsyncLog.d { "processAudioData: currentMode=$mode, isWaterfallMode=$wf, audioData.size=$ad" }
        }
        when {
            // 分贝计模式：直接处理音频数据
            isSoundLevelMeterMode -> {
                if (!isDisplayPaused) {
                    soundLevelMeterView?.updateAudioData(audioData, sampleRate)
                }
            }
            // 瀑布图模式需要频谱数据，必须走频谱处理
            isWaterfallMode -> processSpectrum(audioData)
            currentMode == AudioVisualizerView.DisplayMode.SPECTRUM -> processSpectrum(audioData)
            currentMode == AudioVisualizerView.DisplayMode.VISUALIZER -> processSpectrum(audioData)  // 可视化模式也需要频谱数据
            currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE -> {
                if (shouldLogOscilloscope()) {
                    AsyncLog.d { "processAudioData: calling processWaveform" }
                }
                processWaveform(audioData)
            }
        }
    }
    
    private fun processSpectrum(audioData: ShortArray) {
        // 瀑布图模式使用 waterfallFftSize，频谱模式使用 fftSize
        val effectiveSize = if (isWaterfallMode) waterfallFftSize else fftSize
        val effectiveOverlap = if (isWaterfallMode) waterfallOverlapRatio else overlapRatio
        // 修复：每次读取的样本数 READ_CHUNK_SAMPLES = 368
        // processSpectrumDirect 会用零填充不足的部分，导致能量损失：
        // - 512 FFT：28% 零填充，能量损失较小，可接受
        // - 1024 FFT：64% 零填充，能量损失明显
        // - 2048+ FFT：80%+ 零填充，能量损失严重
        // 只有 effectiveSize <= 512 才用直接处理，其他都用缓冲区累积足够数据
        if (effectiveSize > 512) {
            processSpectrumWithBuffer(audioData, effectiveSize, effectiveOverlap)
        } else {
            processSpectrumDirect(audioData, effectiveSize)
        }
    }
    
    /**
     * 直接处理频谱（小FFT，无需累积）
     * 保证传入 FFT 的数组长度始终为 effectiveSize，不足则补零。
     */
    private fun processSpectrumDirect(audioData: ShortArray, effectiveSize: Int) {
        val fftInput = DoubleArray(effectiveSize)
        val copySize = min(audioData.size, effectiveSize)
        
        // 归一化音频数据，不足部分保持为 0
        for (i in 0 until copySize) {
            fftInput[i] = audioData[i].toDouble() / Short.MAX_VALUE
        }
        
        // 对整段 fftInput 应用窗函数，保证输出长度 = effectiveSize
        val windowed = WindowFunction.applyWindow(fftInput, WindowFunction.Type.HANNING)
        performFFTAndUpdate(windowed, effectiveSize)
    }
    
    /**
     * 使用累积缓冲区处理频谱（大FFT，优化版本）
     */
    private fun processSpectrumWithBuffer(audioData: ShortArray, effectiveSize: Int, effectiveOverlap: Float) {
        // 添加到缓冲区（优化：直接添加，避免 toList() 转换）
        audioBuffer.addAll(audioData.toList())
        
        // 如果缓冲区有足够的数据，执行FFT
        if (audioBuffer.size >= effectiveSize) {
            // 提取FFT大小的数据（优化：减少除法运算）
            val fftInput = DoubleArray(effectiveSize)
            val scale = 1.0 / Short.MAX_VALUE
            for (i in 0 until effectiveSize) {
                fftInput[i] = audioBuffer[i] * scale
            }
            
            // 应用窗函数
            val windowed = WindowFunction.applyWindow(fftInput, WindowFunction.Type.HANNING)
            
            // 执行FFT并更新显示（在后台线程）
            performFFTAndUpdate(windowed, effectiveSize)
            
            // 移除已用于 FFT 的“步进”部分（hop size），不是重叠量
            // hop size = fftSize - overlapSize = fftSize * (1 - overlapRatio)
            // 重叠率 0 → 移除 fftSize（整帧）；重叠率 0.5 → 移除 fftSize/2
            // 之前错误地用 overlapSize，重叠率 0 时只移除 1 样本，导致每帧几乎相同，画面不动
            val overlapSize = (effectiveSize * effectiveOverlap).toInt()
            val hopSize = (effectiveSize - overlapSize).coerceAtLeast(1)
            if (hopSize < audioBuffer.size) {
                audioBuffer.subList(0, hopSize).clear()
            } else {
                audioBuffer.clear()
            }
        }
    }
    
    /**
     * 执行FFT并更新UI（在后台线程计算，主线程更新UI）
     * @param effectiveFftSize 实际使用的 FFT 大小（瀑布图用 waterfallFftSize，频谱用 fftSize）
     */
    private fun performFFTAndUpdate(fftInput: DoubleArray, effectiveFftSize: Int) {
        // 防御：长度必须与传入的 FFT 大小一致，否则跳过避免崩溃
        if (fftInput.size != effectiveFftSize) return
        
        // 根据 effectiveFftSize 选择对应的 FFT 实例
        val fftToUse = if (effectiveFftSize == waterfallFftSize) getWaterfallFft() else fft
        if (effectiveFftSize != waterfallFftSize && effectiveFftSize != fftSize) return
        
        // 在后台线程执行FFT计算，避免阻塞主线程
        lifecycleScope.launch(Dispatchers.Default) {
            // 执行FFT（耗时操作，在后台线程）
            val fftResult = fftToUse.fft(fftInput)
            val magnitudeSpectrum = fftToUse.computeMagnitudeSpectrum(fftResult)
            
            // 参考幅度：幅度谱已在 computeMagnitudeSpectrum 中按 1/N 归一化
            // 对于归一化输入信号（振幅 1.0），单一频率的 FFT 峰值约为 0.5（因为能量分布在正负频率）
            // 使用固定的参考值，确保不同 FFT 大小下显示的振幅一致
            val referenceMagnitude = 0.5f  // 固定值，不依赖 FFT 大小
            val spectrumFloat = FloatArray(magnitudeSpectrum.size) {
                (magnitudeSpectrum[it] / referenceMagnitude).toFloat().coerceIn(0f, 1f)
            }
            
            // 使用归一化频谱检测峰值（与显示一致），阈值更低以便在中等音量下也能看到峰值
            val peaks = detectPeaksFromNormalized(spectrumFloat)
            val peakFreqs = peaks.map { peak ->
                val freq = (peak.first * sampleRate / effectiveFftSize).toFloat()
                val amplitude = peak.second.coerceIn(0f, 1f)
                Pair(freq, amplitude)
            }
            
            // 切换到主线程更新UI
            withContext(Dispatchers.Main) {
                // 瀑布图模式：存储最新频谱，由固定定时器驱动添加到瀑布图
                if (isWaterfallMode) {
                    latestWaterfallSpectrum = spectrumFloat
                    if (peakFreqs.isNotEmpty()) {
                        latestWaterfallPeakFreq = peakFreqs[0].first
                    }
                    // 暂停时跳过后续处理
                    if (isDisplayPaused) return@withContext
                } else if (isDisplayPaused) {
                    // 非瀑布图模式：暂停时跳过
                    return@withContext
                }
                
                if (!isWaterfallMode) {
                    // 正常模式
                    binding.visualizerView.updateSpectrum(spectrumFloat)
                    binding.visualizerView.updatePeakFrequencies(peakFreqs)
                    // 统计实际刷新率（仅频谱模式）
                    spectrumFrameCount++
                    val now = SystemClock.elapsedRealtime()
                    if (lastFpsTimeMs == 0L) lastFpsTimeMs = now
                    val elapsed = now - lastFpsTimeMs
                    if (elapsed >= 500) {
                        val actualFps = spectrumFrameCount * 1000f / elapsed
                        lastDisplayedFps = actualFps
                        spectrumFrameCount = 0
                        lastFpsTimeMs = now
                        updateFFTInfo()
                    }
                }
                
                // 更新信息显示（频谱和瀑布图模式）
                if (peakFreqs.isNotEmpty()) {
                    val maxPeak = peakFreqs.maxByOrNull { it.second }!!
                    updateModeSpecificInfo(maxPeak.first, maxPeak.second)
                }
            }
        }
    }
    
    /**
     * 切换到瀑布图视图
     */
    private fun updateScaleDisplay() {
        if (isWaterfallMode) {
            val (sx, sy) = waterfallView?.let { it.currentScaleX to it.currentScaleY } ?: (1f to 1f)
            binding.tvGainValue.text = "水平 ${(sx * 100).toInt()}% 垂直 ${(sy * 100).toInt()}%"
        } else if (currentMode == AudioVisualizerView.DisplayMode.OSCILLOSCOPE) {
            // 示波器：水平以默认 20ms 为 100%，双击后显示 100%
            val hPercent = binding.visualizerView.oscilloscopeHorizontalDisplayPercent?.toInt() ?: run {
                // 如果 oscilloscopeHorizontalDisplayPercent 为 null，不应该使用 currentScaleX
                // 因为 currentScaleX 是绘制用的缩放，不是用户看到的百分比
                if (shouldLogOscilloscope()) {
                    AsyncLog.w { "updateScaleDisplay: oscilloscopeHorizontalDisplayPercent is null, using fallback" }
                }
                100 // 默认显示 100%
            }
            val vPercent = (binding.visualizerView.currentScaleY * 100).toInt()
            // 移除高频日志：手势滑动时每 8ms 调用一次，大量日志 I/O 会导致卡顿
            binding.tvGainValue.text = "水平 ${hPercent}% 垂直 ${vPercent}%"
        } else {
            val (sx, sy) = binding.visualizerView.currentScaleX to binding.visualizerView.currentScaleY
            binding.tvGainValue.text = "水平 ${(sx * 100).toInt()}% 垂直 ${(sy * 100).toInt()}%"
        }
    }

    /**
     * 设置缩放信息的长按拖动功能
     */
    private fun setupScaleInfoDrag() {
        val tv = binding.tvGainValue
        
        // 恢复保存的位置
        val savedX = sharedPreferences.getFloat(KEY_SCALE_INFO_X, -1f)
        val savedY = sharedPreferences.getFloat(KEY_SCALE_INFO_Y, -1f)
        if (savedX >= 0f && savedY >= 0f) {
            tv.post {
                tv.x = savedX.coerceIn(0f, (binding.root.width - tv.width).toFloat().coerceAtLeast(0f))
                tv.y = savedY.coerceIn(0f, (binding.root.height - tv.height).toFloat().coerceAtLeast(0f))
            }
        }
        
        // 长按开始拖动
        tv.setOnLongClickListener {
            isScaleInfoDragging = true
            scaleInfoInitialX = tv.x
            scaleInfoInitialY = tv.y
            tv.alpha = 0.7f  // 拖动时半透明提示
            // 震动反馈
            performHapticFeedback()
            true
        }
        
        // 触摸事件处理拖动
        tv.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    scaleInfoDragStartX = event.rawX
                    scaleInfoDragStartY = event.rawY
                    scaleInfoInitialX = tv.x
                    scaleInfoInitialY = tv.y
                    false  // 返回 false 让点击事件继续传递
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScaleInfoDragging) {
                        val dx = event.rawX - scaleInfoDragStartX
                        val dy = event.rawY - scaleInfoDragStartY
                        val newX = (scaleInfoInitialX + dx).coerceIn(0f, (binding.root.width - tv.width).toFloat().coerceAtLeast(0f))
                        val newY = (scaleInfoInitialY + dy).coerceIn(0f, (binding.root.height - tv.height).toFloat().coerceAtLeast(0f))
                        tv.x = newX
                        tv.y = newY
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isScaleInfoDragging) {
                        isScaleInfoDragging = false
                        tv.alpha = 1.0f  // 恢复不透明
                        // 保存位置
                        sharedPreferences.edit()
                            .putFloat(KEY_SCALE_INFO_X, tv.x)
                            .putFloat(KEY_SCALE_INFO_Y, tv.y)
                            .apply()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    /**
     * 触发震动反馈
     */
    private fun performHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            // 忽略震动失败
        }
    }

    private fun switchToWaterfallView() {
        stopOscilloscopeVsync()
        binding.visualizerView.visibility = View.GONE
        binding.waterfallView.visibility = View.VISIBLE
        updateOscilloscopeButtonsVisibility(0)
        waterfallView?.clearHistoryDataOnly() // 只清数据，保留缩放/平移（与频谱、示波器独立）
        waterfallView?.scaleMode = currentScale // 与主视图缩放模式一致
        updateScaleDisplay()
        startWaterfallScrolling() // 启动 vsync 驱动滚动
    }
    
    /**
     * 切换到正常视图
     */
    private fun switchToNormalView() {
        stopWaterfallScrolling() // 停止瀑布图 vsync 滚动
        binding.waterfallView.visibility = View.GONE
        binding.soundLevelMeterView.visibility = View.GONE
        binding.visualizerView.visibility = View.VISIBLE
        if (shouldRunOscilloscopeVsync()) {
            scheduleOscilloscopeSnapshotUpdate()
            startOscilloscopeVsync()
        }
        updateScaleDisplay()
    }
    
    /** 切换到分贝计视图 */
    private fun switchToSoundLevelMeterView() {
        stopOscilloscopeVsync()
        binding.visualizerView.visibility = View.GONE
        binding.waterfallView.visibility = View.GONE
        binding.soundLevelMeterView.visibility = View.VISIBLE
        updateOscilloscopeButtonsVisibility(0)
        // 应用分贝计设置
        soundLevelMeterView?.let { slm ->
            slm.weightingType = when (slmWeighting) {
                0 -> SoundLevelMeterView.WeightingType.A
                1 -> SoundLevelMeterView.WeightingType.C
                2 -> SoundLevelMeterView.WeightingType.Z
                else -> SoundLevelMeterView.WeightingType.FLAT
            }
            slm.responseTime = if (slmResponseTime == 0) 
                SoundLevelMeterView.ResponseTime.FAST 
                else SoundLevelMeterView.ResponseTime.SLOW
            slm.calibrationOffset = slmCalibrationOffset
            slm.showSPL = slmShowSPL
            slm.resetStatistics()
        }
        // 隐藏左上角信息面板（分贝计有自己的信息显示）
        binding.infoPanel.visibility = View.GONE
    }
    
    /** 退出瀑布图模式 */
    private fun exitWaterfallMode() {
        if (isWaterfallMode) {
            stopWaterfallScrolling()
            isWaterfallMode = false
            binding.waterfallView.visibility = View.GONE
        }
    }
    
    /** 退出分贝计模式 */
    private fun exitSoundLevelMeterMode() {
        if (isSoundLevelMeterMode) {
            isSoundLevelMeterMode = false
            binding.soundLevelMeterView.visibility = View.GONE
            // 恢复信息面板显示
            binding.infoPanel.visibility = if (showInfoPanel) View.VISIBLE else View.GONE
        }
    }
    
    private fun processWaveform(audioData: ShortArray) {
        val processStartNs = System.nanoTime()
        
        // 输入数据检查
        DebugLog.check(Tag.WAVEFORM, audioData.isNotEmpty()) { "audioData 为空" }
        DebugLog.checkRange(Tag.WAVEFORM, sampleRate, 8000, 192000, "sampleRate")
        
        if (maxWaveformBufferSamples <= 0) {
            maxWaveformBufferSamples = maxOf((sampleRate * 0.02f * 2).toInt(), 8192)
            DebugLog.d(Tag.WAVEFORM) { "初始化缓冲区大小: $maxWaveformBufferSamples samples" }
        }
        // 获取当前时间窗口，用于计算卡顿阈值
        val currentTimeSpanSec = binding.visualizerView.oscilloscopeVisibleTimeSpanSec
        val jankThresholdMs = (currentTimeSpanSec * 100).toLong()  // 时间窗口的 1/10
        
        // 检测时间间隙：主线程卡顿或 Flow 丢块会导致缓冲断层
        val nowNs = System.nanoTime()
        if (lastWaveformProcessTimeNs > 0L) {
            val gapMs = (nowNs - lastWaveformProcessTimeNs) / 1_000_000
            if (gapMs > 80L) {
                synchronized(waveformLock) { 
                    waveformDataSize = 0
                    waveformWritePos = 0
                }
                if (shouldLogOscilloscope()) {
                    val g = gapMs
                    AsyncLog.w { "[JANK] gap ${g}ms detected, buffer cleared" }
                }
            }
        }
        lastWaveformProcessTimeNs = nowNs

        // 应用滤波器处理
        val filteredData = if (filterEnabled) {
            audioFilter.processBuffer(audioData)
        } else {
            audioData
        }

        // 使用环形缓冲区：高效写入，避免装箱/拆箱和数组移动
        val sizeAfterUpdate = synchronized(waveformLock) {
            // 确保缓冲区足够大（按需扩容，但不频繁）
            val requiredSize = maxWaveformBufferSamples
            if (waveformRingBuffer.size < requiredSize) {
                val newBuffer = ShortArray(requiredSize)
                // 使用 System.arraycopy 高效复制（避免 for 循环逐元素复制）
                if (waveformDataSize > 0) {
                    val oldBuffer = waveformRingBuffer
                    val oldSize = oldBuffer.size
                    val readStart = (waveformWritePos - waveformDataSize + oldSize) % oldSize
                    // 只保留最近 requiredSize 个样本（扩容时可能超过新缓冲区大小）
                    val copyCount = minOf(waveformDataSize, requiredSize)
                    val skipCount = waveformDataSize - copyCount
                    val actualReadStart = (readStart + skipCount) % oldSize
                    
                    if (actualReadStart + copyCount <= oldSize) {
                        // 连续区域，单次复制
                        System.arraycopy(oldBuffer, actualReadStart, newBuffer, 0, copyCount)
                    } else {
                        // 跨越边界，两次复制
                        val firstPart = oldSize - actualReadStart
                        System.arraycopy(oldBuffer, actualReadStart, newBuffer, 0, firstPart)
                        System.arraycopy(oldBuffer, 0, newBuffer, firstPart, copyCount - firstPart)
                    }
                    waveformWritePos = copyCount
                    waveformDataSize = copyCount
                } else {
                    waveformWritePos = 0
                }
                waveformRingBuffer = newBuffer
            }
            
            // 写入新数据（环形）：批量拷贝替代逐样本取模，降低 CPU 开销
            val bufferSize = waveformRingBuffer.size
            val writeCount = filteredData.size
            if (writeCount > 0 && bufferSize > 0) {
                val firstPart = minOf(writeCount, bufferSize - waveformWritePos)
                System.arraycopy(filteredData, 0, waveformRingBuffer, waveformWritePos, firstPart)
                val remain = writeCount - firstPart
                if (remain > 0) {
                    System.arraycopy(filteredData, firstPart, waveformRingBuffer, 0, remain)
                }
                waveformWritePos = (waveformWritePos + writeCount) % bufferSize
            }
            waveformDataSize = minOf(waveformDataSize + filteredData.size, bufferSize)
            totalSamplesReceived += filteredData.size
            waveformDataSize
        }
        
        if (shouldLogOscilloscope()) {
            val sz = sizeAfterUpdate
            val bufMs = (sz.toFloat() / sampleRate * 1000f)
            AsyncLog.d { "processWaveform: ringBuffer.dataSize=$sz, bufferTimeSpan=${String.format("%.2f", bufMs)}ms" }
        }
        
        // 始终更新平移最大偏移量（无论是否暂停，都允许滑动查看历史）
        // 允许平移到 60 秒历史范围（与缓冲区大小一致）
        if (sizeAfterUpdate > 0) {
            val visibleLength = (sampleRate * binding.visualizerView.oscilloscopeVisibleTimeSpanSec).toInt()
            val maxHistorySamples = (sampleRate * 60f).toInt()  // 60 秒历史
            binding.visualizerView.oscilloscopeMaxOffsetSamples = maxOf(0, maxHistorySamples - visibleLength)
        }
        
        // 有数据时只标记 dirty，由 vsync 回调每帧最多消费一次快照更新 UI
        if (sizeAfterUpdate > 0 && !isDisplayPaused) {
            scheduleOscilloscopeSnapshotUpdate()
        }
        
        // 总耗时检测：超过时间窗口的 1/10 则打印详细日志
        val totalMs = (System.nanoTime() - processStartNs) / 1_000_000
        if (totalMs > jankThresholdMs && jankThresholdMs > 0) {
            val timeSpanMs = (currentTimeSpanSec * 1000).toInt()
            val bufferSize = waveformRingBuffer.size
            val dataSize = waveformDataSize
            val inputSize = audioData.size
            Log.w("Oscilloscope", "[JANK-DETAIL] processWaveform took ${totalMs}ms > threshold ${jankThresholdMs}ms (timeSpan=${timeSpanMs}ms), " +
                "bufferSize=$bufferSize, dataSize=$dataSize, inputSize=$inputSize, maxBuffer=$maxWaveformBufferSamples")
        }
        
        // 定期更新示波器信息显示（每 100ms 更新一次，避免频繁刷新）
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastOscilloscopeInfoUpdateMs > 100) {
            lastOscilloscopeInfoUpdateMs = nowMs
            updateModeSpecificInfo()
        }
    }
    
    /**
     * 从归一化频谱（与显示一致）检测峰值，仅当幅度超过设定阈值（dB）才开始检测，返回前 peakCount 个
     */
    private fun detectPeaksFromNormalized(spectrum: FloatArray): List<Pair<Int, Float>> {
        if (spectrum.isEmpty()) return emptyList()
        val threshold = 10f.pow(peakDetectionThresholdDb / 20f)
        
        val peaks = mutableListOf<Pair<Int, Float>>()
        val windowSize = 5
        val minIndex = windowSize
        val maxIndex = spectrum.size - windowSize
        
        for (i in minIndex until maxIndex) {
            val value = spectrum[i]
            if (value < threshold) continue
            var isPeak = true
            for (j in maxOf(i - windowSize, 0) until minOf(i + windowSize + 1, spectrum.size)) {
                if (j != i && spectrum[j] >= value) {
                    isPeak = false
                    break
                }
            }
            if (isPeak) peaks.add(Pair(i, value))
        }
        return peaks.sortedByDescending { it.second }.take(peakCount)
    }
    
    /**
     * 检测频谱峰值（Double 版本，保留供其他用途）
     */
    private fun detectPeaks(spectrum: DoubleArray, threshold: Double = 0.05): List<Pair<Int, Double>> {
        if (spectrum.isEmpty()) return emptyList()
        val maxValue = spectrum.maxOrNull() ?: 0.0
        val adaptiveThreshold = maxOf(threshold, maxValue * 0.1)
        val peaks = mutableListOf<Pair<Int, Double>>()
        val windowSize = 5
        val minIndex = windowSize
        val maxIndex = spectrum.size - windowSize
        for (i in minIndex until maxIndex) {
            val value = spectrum[i]
            if (value < adaptiveThreshold) continue
            var isPeak = true
            for (j in maxOf(i - windowSize, 0) until minOf(i + windowSize + 1, spectrum.size)) {
                if (j != i && spectrum[j] >= value) { isPeak = false; break }
            }
            if (isPeak) peaks.add(Pair(i, value))
        }
        return peaks.sortedByDescending { it.second }.take(10)
    }
    
    private fun startRecording() {
        isRecording = true
        binding.btnRecord.text = getString(R.string.stop_recording)
        audioRecorder.startSavingRecording()
        Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopRecording() {
        isRecording = false
        binding.btnRecord.text = getString(R.string.record_audio)
        
        lifecycleScope.launch {
            val recordingsDir = File(getExternalFilesDir(null), "Recordings")
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }
            
            val timestamp = System.currentTimeMillis()
            val file = File(recordingsDir, "recording_$timestamp.pcm")
            
            val success = audioRecorder.stopAndSaveRecording(file)
            if (success) {
                Toast.makeText(this@MainActivity, 
                    "录制已保存: ${file.name}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, 
                    "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 保存截图（瀑布图模式时保存瀑布图，文件名带 waterfall_ 前缀）
     */
    private fun saveScreenshot() {
        lifecycleScope.launch {
            val screenshotsDir = File(getExternalFilesDir(null), "Screenshots")
            if (!screenshotsDir.exists()) {
                screenshotsDir.mkdirs()
            }
            
            val timestamp = System.currentTimeMillis()
            val prefix = if (isWaterfallMode) "waterfall" else "screenshot"
            val file = File(screenshotsDir, "${prefix}_$timestamp.png")
            
            val targetView = if (isWaterfallMode) binding.waterfallView else binding.visualizerView
            val bitmap = ImageUtils.createBitmapFromView(targetView)
            if (bitmap != null) {
                try {
                    val outputStream = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.flush()
                    outputStream.close()
                    bitmap.recycle()
                    
                    Toast.makeText(
                        this@MainActivity,
                        "截图已保存: ${file.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "保存截图失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    /**
     * 加载音频文件
     */
    private fun loadAudioFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                // 停止当前录制
                if (isRecording) {
                    stopRecording()
                }
                audioRecorder.stopRecording()
                
                // 创建文件处理器
                audioFileProcessor = AudioFileProcessor(this@MainActivity)
                
                // 加载文件
                val success = audioFileProcessor?.loadAudioFile(uri) ?: false
                
                if (success) {
                    isFileMode = true
                    // 文件模式：更多菜单中“导入”会显示为“麦克风模式”
                    showFileProgressBar()

                    // 显示文件信息
                    val fileInfo = audioFileProcessor?.fileInfo?.value
                    fileInfo?.let {
                        val durationSec = it.duration / 1000000
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.file_loaded, "${it.fileName}\n时长: ${durationSec}秒, 采样率: ${it.sampleRate}Hz"),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    // 更新采样率
                    audioFileProcessor?.sampleRateFlow?.value?.let { rate ->
                        sampleRate = rate
                        binding.visualizerView.sampleRate = rate
                        waterfallView?.sampleRate = rate
                        updateFFT()
                    }

                    // 开始处理文件
                    startFileProcessing()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.file_load_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "加载文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 开始文件处理
     */
    private fun startFileProcessing() {
        audioFileProcessor?.startProcessing()
        updateFileProgressBar()

        lifecycleScope.launch {
            while (isFileMode && audioFileProcessor?.isPlaying?.value == true) {
                if (isDisplayPaused) {
                    delay(50)
                    continue
                }
                // 后台时不更新 UI，避免切回时卡死/闪退
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    delay(50)
                    continue
                }
                val audioData = audioFileProcessor?.readNextFrame(4096)
                if (audioData != null && audioData.isNotEmpty()) {
                    processAudioData(audioData)
                    updateFileProgressBar()
                    val delayMs = (audioData.size * 1000L / sampleRate).coerceAtMost(50)
                    delay(delayMs)
                } else {
                    // 文件播放完毕
                    if (isPlayingRecording) {
                        stopPlayingRecording()
                        Toast.makeText(this@MainActivity, "播放完成", Toast.LENGTH_SHORT).show()
                    }
                    break
                }
            }
        }
    }

    private fun showFileProgressBar() {
        binding.fileProgressBar.visibility = View.VISIBLE
        binding.seekBarFileProgress.max = 1000
        binding.seekBarFileProgress.progress = 0
        binding.tvFileTimeCurrent.text = "0:00"
        audioFileProcessor?.let {
            binding.tvFileTimeTotal.text = formatTimeUs(it.getDurationUs())
        }
    }

    private fun updateFileProgressBar() {
        val processor = audioFileProcessor ?: return
        val durationUs = processor.getDurationUs()
        if (durationUs <= 0) return
        val positionUs = processor.getCurrentPosition()
        val progress = (positionUs * 1000L / durationUs).toInt().coerceIn(0, 1000)
        binding.seekBarFileProgress.progress = progress
        binding.tvFileTimeCurrent.text = formatTimeUs(positionUs)
        binding.tvFileTimeTotal.text = formatTimeUs(durationUs)
    }
    
    /**
     * 切换到麦克风模式
     */
    private fun switchToMicMode() {
        audioFileProcessor?.stopProcessing()
        audioFileProcessor?.release()
        audioFileProcessor = null
        isFileMode = false
        binding.fileProgressBar.visibility = View.GONE

        // 重新启动麦克风录制
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            startAudioProcessing()
        }
    }
    
    /**
     * 停止播放录制文件
     */
    private fun stopPlayingRecording() {
        if (isPlayingRecording) {
            isPlayingRecording = false
            audioFileProcessor?.stopProcessing()
            audioFileProcessor?.release()
            audioFileProcessor = null
            currentPlayingFile = null
            
            // 切换回麦克风模式
            switchToMicMode()
        }
    }
    
    /**
     * 显示录制文件列表对话框
     */
    private fun showRecordingListDialog() {
        val recordingsDir = File(getExternalFilesDir(null), "Recordings")
        if (!recordingsDir.exists() || !recordingsDir.isDirectory) {
            Toast.makeText(this, "没有找到录制文件", Toast.LENGTH_SHORT).show()
            return
        }
        
        val files = recordingsDir.listFiles { _, name -> name.endsWith(".pcm") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        
        if (files.isEmpty()) {
            Toast.makeText(this, "没有找到录制文件", Toast.LENGTH_SHORT).show()
            return
        }
        
        val fileNames = files.map { it.name }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("选择要播放的录制文件")
            .setItems(fileNames) { _, which ->
                val selectedFile = files[which]
                playRecordingFile(selectedFile)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 播放录制文件
     */
    private fun playRecordingFile(file: File) {
        lifecycleScope.launch {
            try {
                // 停止当前录制
                if (isRecording) {
                    stopRecording()
                }
                audioRecorder.stopRecording()
                
                // 创建文件处理器
                audioFileProcessor = AudioFileProcessor(this@MainActivity)
                
                // 加载PCM文件（假设采样率为44100Hz）
                val success = audioFileProcessor?.loadPCMFile(file, 44100) ?: false
                
                if (success) {
                    isPlayingRecording = true
                    isFileMode = true
                    currentPlayingFile = file
                    showFileProgressBar()

                    // 更新采样率
                    audioFileProcessor?.sampleRateFlow?.value?.let { rate ->
                        sampleRate = rate
                        binding.visualizerView.sampleRate = rate
                        waterfallView?.sampleRate = rate
                        updateFFT()
                    }

                    // 开始处理文件
                    startFileProcessing()
                } else {
                    Toast.makeText(this@MainActivity, "加载文件失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 切到后台：自动“按下停止”——停止采集/播放并更新 UI 为暂停状态（所有模式一致）
        stopOscilloscopeVsync()
        if (!isFileMode && !isSystemAudioActive) {
            audioRecorder.stopRecording()
        } else if (isFileMode) {
            audioFileProcessor?.stopProcessing()
        }
        setDisplayPausedState(true)
    }

    override fun onResume() {
        super.onResume()
        // 切回前台：自动“按下启动”——恢复采集/播放并更新 UI 为运行状态（所有模式一致）
        setDisplayPausedState(false)
        if (!isFileMode) {
            if (currentAudioSource == 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val service = MediaProjectionService.getInstance()
                if (service != null && !isSystemAudioActive) {
                    startSystemAudioCollection()
                }
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                audioRecorder.startRecording()
            }
        } else if (audioFileProcessor != null && audioFileProcessor?.isPlaying?.value != true) {
            startFileProcessing()
        }
        if (isWaterfallMode) {
            resumeWaterfallScrolling()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopOscilloscopeVsync()
        waterfallPrecomputeExecutor.shutdown()
        audioDataCollectJob?.cancel()
        sampleRateCollectJob?.cancel()
        systemAudioDataCollectJob?.cancel()
        systemAudioRateCollectJob?.cancel()
        // 停止系统音频捕获
        stopSystemAudioCapture()
        audioRecorder.release()
        audioFileProcessor?.release()
    }
}

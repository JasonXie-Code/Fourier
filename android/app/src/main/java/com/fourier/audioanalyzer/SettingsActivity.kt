package com.fourier.audioanalyzer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.fourier.audioanalyzer.view.AudioVisualizerView
import kotlin.math.max

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var sharedPreferences: SharedPreferences
    private val PREFS_NAME = "AudioAnalyzerPrefs"
    private val KEY_FFT_SIZE = "fft_size"
    private val KEY_OVERLAP_RATIO = "overlap_ratio"
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
    // 信息栏显示项设置（位掩码）
    private val KEY_SPECTRUM_INFO_FLAGS = "spectrum_info_flags"
    private val KEY_OSCILLOSCOPE_INFO_FLAGS = "oscilloscope_info_flags"
    private val KEY_WATERFALL_INFO_FLAGS = "waterfall_info_flags"
    
    /** 兼容旧版：部分设置曾以 Int 存储，现为 Float，读取时统一安全解析 */
    private fun getFloatSafe(key: String, default: Float): Float = when (val v = sharedPreferences.all[key]) {
        is Float -> v
        is Int -> v.toFloat()
        else -> default
    }
    
    
    // 信息项位掩码常量
    companion object {
        // 频谱模式信息项
        const val SPECTRUM_INFO_PEAK_FREQ = 1
        const val SPECTRUM_INFO_PEAK_AMP = 2
        const val SPECTRUM_INFO_FPS = 4
        const val SPECTRUM_INFO_FFT = 8
        const val SPECTRUM_INFO_SCALE_MODE = 16
        const val SPECTRUM_INFO_ZOOM = 32
        const val SPECTRUM_INFO_DEFAULT = SPECTRUM_INFO_PEAK_FREQ or SPECTRUM_INFO_PEAK_AMP or SPECTRUM_INFO_FPS or SPECTRUM_INFO_FFT
        
        // 示波器模式信息项
        const val OSCILLOSCOPE_INFO_TIME_SPAN = 1
        const val OSCILLOSCOPE_INFO_TRIGGER = 2
        const val OSCILLOSCOPE_INFO_SAMPLE_RATE = 4
        const val OSCILLOSCOPE_INFO_ZOOM = 8
        const val OSCILLOSCOPE_INFO_PEAK = 16
        const val OSCILLOSCOPE_INFO_POSITION = 32
        const val OSCILLOSCOPE_INFO_PEAK_TO_PEAK = 64
        const val OSCILLOSCOPE_INFO_RMS = 128
        const val OSCILLOSCOPE_INFO_DEFAULT = OSCILLOSCOPE_INFO_TIME_SPAN or OSCILLOSCOPE_INFO_TRIGGER or OSCILLOSCOPE_INFO_SAMPLE_RATE
        
        // 瀑布图模式信息项
        const val WATERFALL_INFO_PEAK_FREQ = 1
        const val WATERFALL_INFO_PEAK_AMP = 2
        const val WATERFALL_INFO_FFT = 8
        const val WATERFALL_INFO_SCALE_MODE = 16
        const val WATERFALL_INFO_SENSITIVITY = 32
        const val WATERFALL_INFO_TIME_RESOLUTION = 64
        const val WATERFALL_INFO_DEFAULT = WATERFALL_INFO_PEAK_FREQ or WATERFALL_INFO_SENSITIVITY
    }
    
    // 用于保存所有当前设置值的变量
    private var currentWaveformColor: Int = 0
    private var currentScaleMode: Int = 0
    private var currentSpectrumSlope: Float = 0f
    private var currentGain: Float = 1f
    private var currentShowFrequencyMarkers: Boolean = true
    private var currentShowPeakDetection: Boolean = true
    private var currentPeakThresholdDb: Float = -60f
    private var currentPeakCount: Int = 10
    private var currentOscilloscopeStrokeWidth: Float = 2f
    private var currentOscilloscopeGridStrokeWidth: Float = 1f
    private var currentOscilloscopeLargeWindowUsePeakEnvelope: Boolean = false
    private var currentShowOscilloscopeCenterLine: Boolean = true
    private var currentSpectrumGridStrokeWidth: Float = 1f
    private var currentSpectrumMarkerStrokeWidth: Float = 1.5f
    private var currentFFTSize: Int = 2048
    private var currentOverlapRatio: Float = 0.5f
    private var currentAudioSource: Int = 0  // 0=MIC_RAW, 1=MIC, 2=SYSTEM
    private var currentTriggerEnabled: Boolean = false
    private var currentTriggerLevelDb: Float = -30f  // -90 ~ 0 dB
    private var currentTriggerMode: Int = 0  // 0=上升沿, 1=下降沿, 2=双沿
    private var currentSingleTrigger: Boolean = false  // 单次触发模式
    private var currentTriggerHysteresis: Float = 5f  // 迟滞量 1-30%
    private var currentTriggerHoldoffAuto: Boolean = true  // 保持时间自动模式
    private var currentTriggerHoldoffMs: Float = 1f  // 手动保持时间 0.1-10ms
    private var currentTriggerNoiseReject: Boolean = false  // 噪声抑制
    // 滤波器设置
    private var currentFilterEnabled: Boolean = false
    private var currentFilterType: Int = 0  // 0=低通, 1=高通, 2=带通, 3=陷波
    private var currentFilterCutoff: Float = 1000f  // 截止频率 Hz
    private var currentFilterCenter: Float = 1000f  // 中心频率 Hz
    private var currentFilterBandwidth: Float = 500f  // 带宽 Hz
    private var currentFilterOrder: Int = 2  // 滤波器阶数 1, 2, 4, 8
    private var currentScaleSensitivity: Float = 1.0f  // 0.5=低, 1.0=中, 2.0=高
    private var currentWaterfallSensitivity: Float = 1.0f  // 0.5~50
    private var currentWaterfallFftSize: Int = 2048  // 瀑布图 FFT 大小
    private var currentWaterfallOverlapRatio: Float = 0f  // 瀑布图重叠率
    private var currentWaterfallColorPalette: Int = 0  // 0=彩虹, 1=灰度, 2=黑红, 3=蓝绿
    private var currentShowInfoPanel: Boolean = true  // 是否显示左上角信息面板
    private var currentSpectrumInfoFlags: Int = SPECTRUM_INFO_DEFAULT
    private var currentOscilloscopeInfoFlags: Int = OSCILLOSCOPE_INFO_DEFAULT
    private var currentWaterfallInfoFlags: Int = WATERFALL_INFO_DEFAULT
    // 可视化模式设置
    private var currentVisualizerBarCount: Int = 32  // 频段数量 8~64
    private var currentVisualizerSensitivity: Float = 1.5f  // 灵敏度 0.5~10.0
    private var currentVisualizerSlope: Float = 3f  // 频谱斜率 -12~12
    private var currentVisualizerBarGap: Float = 0.2f  // 条间距比例 0.1~0.5
    private var currentVisualizerPeakHold: Boolean = true  // 峰值保持
    private var currentVisualizerGradient: Boolean = true  // 渐变色
    // 分贝计设置
    private var currentSlmWeighting: Int = 0  // 0=A, 1=C, 2=Z, 3=Flat
    private var currentSlmResponseTime: Int = 0  // 0=Fast, 1=Slow
    private var currentSlmCalibrationOffset: Float = 94f  // 校准偏移量
    private var currentSlmShowSPL: Boolean = true  // 显示 SPL 还是 dBFS
    
    // FFT大小选项
    private val fftSizeOptions = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768)
    private val waterfallFftSizeOptions = listOf(256, 512, 1024, 2048, 4096, 8192, 16384)
    
    
    // 更新结果 Intent，包含所有当前设置值
    private fun updateResult() {
        val resultIntent = Intent().apply {
            putExtra("waveformColor", currentWaveformColor)
            putExtra("scaleMode", currentScaleMode)
            putExtra("spectrumSlope", currentSpectrumSlope)
            putExtra("gain", currentGain)
            putExtra("showFrequencyMarkers", currentShowFrequencyMarkers)
            putExtra("showPeakDetection", currentShowPeakDetection)
            putExtra("peakThresholdDb", currentPeakThresholdDb)
            putExtra("peakCount", currentPeakCount)
            putExtra("oscilloscopeStrokeWidth", currentOscilloscopeStrokeWidth)
            putExtra("oscilloscopeGridStrokeWidth", currentOscilloscopeGridStrokeWidth)
            putExtra("oscilloscopeLargeWindowUsePeakEnvelope", currentOscilloscopeLargeWindowUsePeakEnvelope)
            putExtra("showOscilloscopeCenterLine", currentShowOscilloscopeCenterLine)
            putExtra("spectrumGridStrokeWidth", currentSpectrumGridStrokeWidth)
            putExtra("spectrumMarkerStrokeWidth", currentSpectrumMarkerStrokeWidth)
            putExtra("fftSize", currentFFTSize)
            putExtra("overlapRatio", currentOverlapRatio)
            putExtra("audioSource", currentAudioSource)
            putExtra("triggerEnabled", currentTriggerEnabled)
            putExtra("triggerLevelDb", currentTriggerLevelDb)
            putExtra("triggerMode", currentTriggerMode)
            putExtra("singleTrigger", currentSingleTrigger)
            putExtra("triggerHysteresis", currentTriggerHysteresis)
            putExtra("triggerHoldoffAuto", currentTriggerHoldoffAuto)
            putExtra("triggerHoldoffMs", currentTriggerHoldoffMs)
            putExtra("triggerNoiseReject", currentTriggerNoiseReject)
            // 滤波器设置
            putExtra("filterEnabled", currentFilterEnabled)
            putExtra("filterType", currentFilterType)
            putExtra("filterCutoff", currentFilterCutoff)
            putExtra("filterCenter", currentFilterCenter)
            putExtra("filterBandwidth", currentFilterBandwidth)
            putExtra("filterOrder", currentFilterOrder)
            putExtra("scaleSensitivity", currentScaleSensitivity)
            putExtra("waterfallSensitivity", currentWaterfallSensitivity)
            putExtra("waterfallFftSize", currentWaterfallFftSize)
            putExtra("waterfallOverlapRatio", currentWaterfallOverlapRatio)
            putExtra("waterfallColorPalette", currentWaterfallColorPalette)
            putExtra("showInfoPanel", currentShowInfoPanel)
            putExtra("spectrumInfoFlags", currentSpectrumInfoFlags)
            putExtra("oscilloscopeInfoFlags", currentOscilloscopeInfoFlags)
            putExtra("waterfallInfoFlags", currentWaterfallInfoFlags)
            // 可视化模式设置
            putExtra("visualizerBarCount", currentVisualizerBarCount)
            putExtra("visualizerSensitivity", currentVisualizerSensitivity)
            putExtra("visualizerSlope", currentVisualizerSlope)
            putExtra("visualizerBarGap", currentVisualizerBarGap)
            putExtra("visualizerPeakHold", currentVisualizerPeakHold)
            putExtra("visualizerGradient", currentVisualizerGradient)
            // 分贝计设置
            putExtra("slmWeighting", currentSlmWeighting)
            putExtra("slmResponseTime", currentSlmResponseTime)
            putExtra("slmCalibrationOffset", currentSlmCalibrationOffset)
            putExtra("slmShowSPL", currentSlmShowSPL)
        }
        setResult(Activity.RESULT_OK, resultIntent)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // 边到边显示
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        // 应用 insets 到内容
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                max(bars.left, cutout.left),
                max(bars.top, cutout.top),
                max(bars.right, cutout.right),
                max(bars.bottom, cutout.bottom)
            )
            insets
        }
        
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // 从 Intent 获取当前值，若无则从 SharedPreferences 读取（保证下次打开与上次保存一致）
        val defaultWaveformColor = ContextCompat.getColor(this, R.color.waveform_color)
        currentWaveformColor = intent.getIntExtra("waveformColor", sharedPreferences.getInt(KEY_WAVEFORM_COLOR, defaultWaveformColor))
        currentScaleMode = intent.getIntExtra("scaleMode", sharedPreferences.getInt(KEY_SCALE_MODE, 0))
        currentSpectrumSlope = intent.getFloatExtra("spectrumSlope", getFloatSafe(KEY_SPECTRUM_SLOPE, 0f))
        currentGain = intent.getFloatExtra("gain", getFloatSafe(KEY_GAIN, 1f))
        currentShowFrequencyMarkers = intent.getBooleanExtra("showFrequencyMarkers", sharedPreferences.getBoolean(KEY_SHOW_FREQUENCY_MARKERS, true))
        currentShowPeakDetection = intent.getBooleanExtra("showPeakDetection", sharedPreferences.getBoolean(KEY_SHOW_PEAK_DETECTION, true))
        currentPeakThresholdDb = intent.getFloatExtra("peakThresholdDb", getFloatSafe(KEY_PEAK_THRESHOLD_DB, -60f))
        currentPeakCount = intent.getIntExtra("peakCount", sharedPreferences.getInt(KEY_PEAK_COUNT, 10)).coerceIn(1, 10)
        currentOscilloscopeStrokeWidth = intent.getFloatExtra("oscilloscopeStrokeWidth", getFloatSafe(KEY_OSCILLOSCOPE_STROKE_WIDTH, 2f)).coerceIn(1f, 10f)
        currentOscilloscopeGridStrokeWidth = intent.getFloatExtra("oscilloscopeGridStrokeWidth", getFloatSafe(KEY_OSCILLOSCOPE_GRID_STROKE_WIDTH, 1f)).coerceIn(0.5f, 5f)
        currentOscilloscopeLargeWindowUsePeakEnvelope = intent.getBooleanExtra(
            "oscilloscopeLargeWindowUsePeakEnvelope",
            sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_LARGE_WINDOW_USE_PEAK_ENVELOPE, false)
        )
        currentShowOscilloscopeCenterLine = intent.getBooleanExtra("showOscilloscopeCenterLine", sharedPreferences.getBoolean(KEY_SHOW_OSCILLOSCOPE_CENTER_LINE, true))
        currentSpectrumGridStrokeWidth = intent.getFloatExtra("spectrumGridStrokeWidth", getFloatSafe(KEY_SPECTRUM_GRID_STROKE_WIDTH, 1f)).coerceIn(0.5f, 5f)
        currentSpectrumMarkerStrokeWidth = intent.getFloatExtra("spectrumMarkerStrokeWidth", getFloatSafe(KEY_SPECTRUM_MARKER_STROKE_WIDTH, 1.5f)).coerceIn(0.5f, 5f)
        currentFFTSize = intent.getIntExtra("fftSize", sharedPreferences.getInt(KEY_FFT_SIZE, 2048))
        currentOverlapRatio = intent.getFloatExtra("overlapRatio", getFloatSafe(KEY_OVERLAP_RATIO, 0.5f))
        currentAudioSource = intent.getIntExtra("audioSource", sharedPreferences.getInt(KEY_AUDIO_SOURCE, 0))
        currentTriggerEnabled = intent.getBooleanExtra("triggerEnabled", sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_TRIGGER_ENABLED, false))
        currentTriggerLevelDb = intent.getFloatExtra("triggerLevelDb", getFloatSafe(KEY_OSCILLOSCOPE_TRIGGER_LEVEL, -30f))
        currentTriggerMode = intent.getIntExtra("triggerMode", sharedPreferences.getInt(KEY_OSCILLOSCOPE_TRIGGER_MODE, 0))
        currentSingleTrigger = intent.getBooleanExtra("singleTrigger", sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_SINGLE_TRIGGER, false))
        currentTriggerHysteresis = intent.getFloatExtra("triggerHysteresis", getFloatSafe(KEY_OSCILLOSCOPE_TRIGGER_HYSTERESIS, 5f)).coerceIn(1f, 30f)
        currentTriggerHoldoffAuto = intent.getBooleanExtra("triggerHoldoffAuto", sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_AUTO, true))
        currentTriggerHoldoffMs = intent.getFloatExtra("triggerHoldoffMs", getFloatSafe(KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_MS, 1f)).coerceIn(0.1f, 10f)
        currentTriggerNoiseReject = intent.getBooleanExtra("triggerNoiseReject", sharedPreferences.getBoolean(KEY_OSCILLOSCOPE_TRIGGER_NOISE_REJECT, false))
        // 滤波器设置
        currentFilterEnabled = intent.getBooleanExtra("filterEnabled", sharedPreferences.getBoolean(KEY_FILTER_ENABLED, false))
        currentFilterType = intent.getIntExtra("filterType", sharedPreferences.getInt(KEY_FILTER_TYPE, 0))
        currentFilterCutoff = intent.getFloatExtra("filterCutoff", getFloatSafe(KEY_FILTER_CUTOFF, 1000f)).coerceIn(20f, 20000f)
        currentFilterCenter = intent.getFloatExtra("filterCenter", getFloatSafe(KEY_FILTER_CENTER, 1000f)).coerceIn(20f, 20000f)
        currentFilterBandwidth = intent.getFloatExtra("filterBandwidth", getFloatSafe(KEY_FILTER_BANDWIDTH, 500f)).coerceIn(10f, 5000f)
        currentFilterOrder = intent.getIntExtra("filterOrder", sharedPreferences.getInt(KEY_FILTER_ORDER, 2))
        currentScaleSensitivity = intent.getFloatExtra("scaleSensitivity", getFloatSafe(KEY_SCALE_SENSITIVITY, 1.0f))
        currentWaterfallSensitivity = intent.getFloatExtra("waterfallSensitivity", getFloatSafe(KEY_WATERFALL_SENSITIVITY, 1.0f)).coerceIn(0.5f, 50f)
        currentWaterfallFftSize = intent.getIntExtra("waterfallFftSize", sharedPreferences.getInt(KEY_WATERFALL_FFT_SIZE, 2048))
        currentWaterfallOverlapRatio = intent.getFloatExtra(
            "waterfallOverlapRatio",
            getFloatSafe(KEY_WATERFALL_OVERLAP_RATIO, 0f)
        )
        currentWaterfallColorPalette = intent.getIntExtra("waterfallColorPalette", sharedPreferences.getInt(KEY_WATERFALL_COLOR_PALETTE, 0)).coerceIn(0, 3)
        currentShowInfoPanel = intent.getBooleanExtra("showInfoPanel", sharedPreferences.getBoolean(KEY_SHOW_INFO_PANEL, true))
        currentSpectrumInfoFlags = intent.getIntExtra("spectrumInfoFlags", sharedPreferences.getInt(KEY_SPECTRUM_INFO_FLAGS, SPECTRUM_INFO_DEFAULT))
        currentOscilloscopeInfoFlags = intent.getIntExtra("oscilloscopeInfoFlags", sharedPreferences.getInt(KEY_OSCILLOSCOPE_INFO_FLAGS, OSCILLOSCOPE_INFO_DEFAULT))
        currentWaterfallInfoFlags = intent.getIntExtra("waterfallInfoFlags", sharedPreferences.getInt(KEY_WATERFALL_INFO_FLAGS, WATERFALL_INFO_DEFAULT))
        // 可视化模式设置
        currentVisualizerBarCount = intent.getIntExtra("visualizerBarCount", sharedPreferences.getInt(KEY_VISUALIZER_BAR_COUNT, 32)).coerceIn(8, 64)
        currentVisualizerSensitivity = intent.getFloatExtra("visualizerSensitivity", getFloatSafe(KEY_VISUALIZER_SENSITIVITY, 1.5f)).coerceIn(0.5f, 10.0f)
        currentVisualizerSlope = intent.getFloatExtra("visualizerSlope", getFloatSafe(KEY_VISUALIZER_SLOPE, 3f)).coerceIn(-12f, 12f)
        currentVisualizerBarGap = intent.getFloatExtra("visualizerBarGap", getFloatSafe(KEY_VISUALIZER_BAR_GAP, 0.2f)).coerceIn(0.1f, 0.5f)
        currentVisualizerPeakHold = intent.getBooleanExtra("visualizerPeakHold", sharedPreferences.getBoolean(KEY_VISUALIZER_PEAK_HOLD, true))
        currentVisualizerGradient = intent.getBooleanExtra("visualizerGradient", sharedPreferences.getBoolean(KEY_VISUALIZER_GRADIENT, true))
        // 分贝计设置
        currentSlmWeighting = intent.getIntExtra("slmWeighting", sharedPreferences.getInt(KEY_SLM_WEIGHTING, 0))
        currentSlmResponseTime = intent.getIntExtra("slmResponseTime", sharedPreferences.getInt(KEY_SLM_RESPONSE_TIME, 0))
        currentSlmCalibrationOffset = intent.getFloatExtra("slmCalibrationOffset", getFloatSafe(KEY_SLM_CALIBRATION_OFFSET, 94f))
        currentSlmShowSPL = intent.getBooleanExtra("slmShowSPL", sharedPreferences.getBoolean(KEY_SLM_SHOW_SPL, true))
        val currentSampleRate = intent.getIntExtra("sampleRate", 44100)
        
        setupWaveformColor(currentWaveformColor)
        setupScale(currentScaleMode)
        setupSlope(currentSpectrumSlope)
        setupGain(currentGain)
        setupSwitches(currentShowFrequencyMarkers, currentShowPeakDetection)
        setupPeakOptions(currentPeakThresholdDb, currentPeakCount)
        setupSpectrumGridStrokeWidth(currentSpectrumGridStrokeWidth)
        setupSpectrumMarkerStrokeWidth(currentSpectrumMarkerStrokeWidth)
        setupOscilloscopeStrokeWidth(currentOscilloscopeStrokeWidth)
        setupOscilloscopeGridStrokeWidth(currentOscilloscopeGridStrokeWidth)
        setupOscilloscopeEnvelopeMode(currentOscilloscopeLargeWindowUsePeakEnvelope)
        setupOscilloscopeCenterLine(currentShowOscilloscopeCenterLine)
        setupScaleSensitivity(currentScaleSensitivity)
        setupOscilloscopeTrigger(currentTriggerEnabled, currentTriggerLevelDb, currentTriggerMode, currentSingleTrigger)
        setupOscilloscopeFilter()
        setupWaterfallSensitivity(currentWaterfallSensitivity)
        setupWaterfallColorPalette(currentWaterfallColorPalette)
        setupWaterfallFftSettings(currentWaterfallFftSize, currentSampleRate)
        setupWaterfallOverlap(currentWaterfallOverlapRatio)
        setupShowInfoPanel(currentShowInfoPanel)
        setupSpectrumInfoItems()
        setupOscilloscopeInfoItems()
        setupWaterfallInfoItems()
        setupVisualizerSettings()
        setupSoundLevelMeterSettings()
        setupAudioSource(currentAudioSource)
        setupFFTSettings(currentFFTSize, currentOverlapRatio, currentSampleRate)
        
        // 设置选项卡切换逻辑
        setupTabs()
        
        // 初始化结果 Intent
        updateResult()
    }
    
    private fun setupTabs() {
        val tabGroup = findViewById<RadioGroup>(R.id.tabGroup)
        val panelGeneral = findViewById<View>(R.id.panelGeneral)
        val panelSpectrum = findViewById<View>(R.id.panelSpectrum)
        val panelOscilloscope = findViewById<View>(R.id.panelOscilloscope)
        val panelWaterfall = findViewById<View>(R.id.panelWaterfall)
        val panelVisualizer = findViewById<View>(R.id.panelVisualizer)
        val panelSoundLevelMeter = findViewById<View>(R.id.panelSoundLevelMeter)
        
        fun showPanel(panelId: Int) {
            panelGeneral.visibility = if (panelId == R.id.tabGeneral) View.VISIBLE else View.GONE
            panelSpectrum.visibility = if (panelId == R.id.tabSpectrum) View.VISIBLE else View.GONE
            panelOscilloscope.visibility = if (panelId == R.id.tabOscilloscope) View.VISIBLE else View.GONE
            panelWaterfall.visibility = if (panelId == R.id.tabWaterfall) View.VISIBLE else View.GONE
            panelVisualizer.visibility = if (panelId == R.id.tabVisualizer) View.VISIBLE else View.GONE
            panelSoundLevelMeter.visibility = if (panelId == R.id.tabSoundLevelMeter) View.VISIBLE else View.GONE
        }
        
        tabGroup.setOnCheckedChangeListener { _, checkedId ->
            showPanel(checkedId)
        }
        
        // 初始显示通用面板
        showPanel(R.id.tabGeneral)
    }
    
    private fun setupWaveformColor(currentColor: Int) {
        val preset1 = findViewById<View>(R.id.btnWaveformPreset1)
        val preset2 = findViewById<View>(R.id.btnWaveformPreset2)
        val preset3 = findViewById<View>(R.id.btnWaveformPreset3)
        val preset4 = findViewById<View>(R.id.btnWaveformPreset4)
        val waveformRgbSliders = findViewById<View>(R.id.waveformRgbSliders)
        val waveformColorHint = findViewById<View>(R.id.waveformColorHint)
        val waveformColorPreview = findViewById<View>(R.id.waveformColorPreview)
        val seekBarR = findViewById<SeekBar>(R.id.seekBarWaveformR)
        val seekBarG = findViewById<SeekBar>(R.id.seekBarWaveformG)
        val seekBarB = findViewById<SeekBar>(R.id.seekBarWaveformB)
        val tvR = findViewById<TextView>(R.id.tvWaveformR)
        val tvG = findViewById<TextView>(R.id.tvWaveformG)
        val tvB = findViewById<TextView>(R.id.tvWaveformB)
        
        val presetColors = intArrayOf(
            ContextCompat.getColor(this, R.color.waveform_preset_1),
            ContextCompat.getColor(this, R.color.waveform_preset_2),
            ContextCompat.getColor(this, R.color.waveform_preset_3),
            ContextCompat.getColor(this, R.color.waveform_preset_4)
        )
        
        fun applyWaveformColor(color: Int) {
            currentWaveformColor = color
            sharedPreferences.edit().putInt(KEY_WAVEFORM_COLOR, color).apply()
            waveformColorPreview.setBackgroundColor(color)
            seekBarR.progress = Color.red(color)
            seekBarG.progress = Color.green(color)
            seekBarB.progress = Color.blue(color)
            tvR.text = "${Color.red(color)}"
            tvG.text = "${Color.green(color)}"
            tvB.text = "${Color.blue(color)}"
            updateResult()
        }
        
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
            currentWaveformColor = color
            sharedPreferences.edit().putInt(KEY_WAVEFORM_COLOR, color).apply()
            waveformColorPreview.setBackgroundColor(color)
            updateResult()
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
        listOf(tvR to "R", tvG to "G", tvB to "B").forEach { (tv, label) ->
            tv.setOnClickListener {
                val seekBar = when (label) { "R" -> seekBarR; "G" -> seekBarG; else -> seekBarB }
                val input = EditText(this).apply {
                    setText(tv.text.toString())
                    hint = "0～255"
                    setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                    setPadding(48, 32, 48, 32)
                }
                AlertDialog.Builder(this)
                    .setTitle("RGB $label")
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
    }
    
    private fun setupScale(currentScale: Int) {
        val toggleScale = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleScale)
        toggleScale.check(when (currentScale) {
            0 -> R.id.btnScaleLinear
            1 -> R.id.btnScaleLog
            2 -> R.id.btnScaleTwelveTet
            else -> R.id.btnScaleLinear
        })
        toggleScale.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentScaleMode = when (checkedId) {
                R.id.btnScaleLinear -> 0
                R.id.btnScaleLog -> 1
                R.id.btnScaleTwelveTet -> 2
                else -> 0
            }
            sharedPreferences.edit().putInt(KEY_SCALE_MODE, currentScaleMode).apply()
            updateResult()
        }
    }
    
    private fun setupSlope(currentSlope: Float) {
        val toggleSlopeRow1 = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleSlopeRow1)
        val toggleSlopeRow2 = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleSlopeRow2)
        val toggleSlopeRow3 = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleSlopeRow3)
        
        val selectedButtonId = when (currentSlope.toInt()) {
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
        
        // 根据选中的按钮ID，在对应的行中选中
        when (selectedButtonId) {
            R.id.btnSlopeNeg12, R.id.btnSlopeNeg9, R.id.btnSlopeNeg6 -> toggleSlopeRow1.check(selectedButtonId)
            R.id.btnSlopeNeg3, R.id.btnSlope0, R.id.btnSlope3 -> toggleSlopeRow2.check(selectedButtonId)
            R.id.btnSlope6, R.id.btnSlope9, R.id.btnSlope12 -> toggleSlopeRow3.check(selectedButtonId)
        }
        
        // 处理选择变化的监听器，确保三行之间互斥
        fun handleSlopeSelection(checkedId: Int, isChecked: Boolean) {
            if (!isChecked) return
            
            // 清除其他行的选择
            when (checkedId) {
                R.id.btnSlopeNeg12, R.id.btnSlopeNeg9, R.id.btnSlopeNeg6 -> {
                    toggleSlopeRow2.clearChecked()
                    toggleSlopeRow3.clearChecked()
                }
                R.id.btnSlopeNeg3, R.id.btnSlope0, R.id.btnSlope3 -> {
                    toggleSlopeRow1.clearChecked()
                    toggleSlopeRow3.clearChecked()
                }
                R.id.btnSlope6, R.id.btnSlope9, R.id.btnSlope12 -> {
                    toggleSlopeRow1.clearChecked()
                    toggleSlopeRow2.clearChecked()
                }
            }
            
            currentSpectrumSlope = when (checkedId) {
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
            sharedPreferences.edit().putFloat(KEY_SPECTRUM_SLOPE, currentSpectrumSlope).apply()
            updateResult()
        }
        
        toggleSlopeRow1.addOnButtonCheckedListener { _, checkedId, isChecked ->
            handleSlopeSelection(checkedId, isChecked)
        }
        toggleSlopeRow2.addOnButtonCheckedListener { _, checkedId, isChecked ->
            handleSlopeSelection(checkedId, isChecked)
        }
        toggleSlopeRow3.addOnButtonCheckedListener { _, checkedId, isChecked ->
            handleSlopeSelection(checkedId, isChecked)
        }
    }
    
    private fun setupGain(currentGain: Float) {
        val seekBarGain = findViewById<SeekBar>(R.id.seekBarGainSheet)
        val tvGainSheet = findViewById<TextView>(R.id.tvGainValueSheet)
        seekBarGain.progress = (currentGain * 100).toInt().coerceIn(0, 200)
        tvGainSheet.text = "${seekBarGain.progress}%"
        seekBarGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvGainSheet.text = "${progress}%"
                if (fromUser) {
                    this@SettingsActivity.currentGain = progress / 100f
                    sharedPreferences.edit().putFloat(KEY_GAIN, this@SettingsActivity.currentGain).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvGainSheet.setOnClickListener {
            val input = EditText(this).apply {
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
                    this@SettingsActivity.currentGain = v / 100f
                    sharedPreferences.edit().putFloat(KEY_GAIN, this@SettingsActivity.currentGain).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        tvGainSheet.isClickable = true
        tvGainSheet.isFocusable = true
    }
    
    private fun setupSwitches(showFreqMarkers: Boolean, showPeakDetection: Boolean) {
        val switchFreq = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchFrequencyMarkerSheet)
        val switchPeak = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchPeakDetectionSheet)
        switchFreq.isChecked = showFreqMarkers
        switchPeak.isChecked = showPeakDetection
        switchFreq.setOnCheckedChangeListener { _, isChecked ->
            currentShowFrequencyMarkers = isChecked
            sharedPreferences.edit().putBoolean(KEY_SHOW_FREQUENCY_MARKERS, isChecked).apply()
            updateResult()
        }
        switchPeak.setOnCheckedChangeListener { _, isChecked ->
            currentShowPeakDetection = isChecked
            sharedPreferences.edit().putBoolean(KEY_SHOW_PEAK_DETECTION, isChecked).apply()
            updateResult()
        }
    }
    
    private fun setupPeakOptions(thresholdDb: Float, peakCount: Int) {
        val seekBarThreshold = findViewById<SeekBar>(R.id.seekBarPeakThresholdDb)
        val tvThreshold = findViewById<TextView>(R.id.tvPeakThresholdDb)
        val seekBarCount = findViewById<SeekBar>(R.id.seekBarPeakCount)
        val tvCount = findViewById<TextView>(R.id.tvPeakCount)
        
        // 阈值：-90～0 dB，SeekBar progress = -thresholdDb（60 表示 -60 dB）
        val thresholdProgress = (-thresholdDb).toInt().coerceIn(0, 90)
        seekBarThreshold.progress = thresholdProgress
        tvThreshold.text = "${-thresholdProgress}"
        
        fun applyThresholdFromDb(db: Float) {
            val d = db.coerceIn(-90f, 0f)
            currentPeakThresholdDb = d
            val p = (-d).toInt().coerceIn(0, 90)
            seekBarThreshold.progress = p
            tvThreshold.text = "${d.toInt()}"
            sharedPreferences.edit().putFloat(KEY_PEAK_THRESHOLD_DB, currentPeakThresholdDb).apply()
            updateResult()
        }
        
        seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyThresholdFromDb(-progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvThreshold.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvThreshold.text.toString().trim())
                hint = "-90～0"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.peak_threshold_db))
                .setMessage("输入 -90～0 的整数（dB）")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toIntOrNull()?.toFloat()?.coerceIn(-90f, 0f) ?: currentPeakThresholdDb
                    applyThresholdFromDb(v)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        // 峰值数量：1～10，SeekBar progress 0～9 对应 1～10
        val countClamped = peakCount.coerceIn(1, 10)
        seekBarCount.progress = countClamped - 1
        tvCount.text = "$countClamped"
        
        fun applyPeakCount(count: Int) {
            val c = count.coerceIn(1, 10)
            currentPeakCount = c
            seekBarCount.progress = c - 1
            tvCount.text = "$c"
            sharedPreferences.edit().putInt(KEY_PEAK_COUNT, currentPeakCount).apply()
            updateResult()
        }
        
        seekBarCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyPeakCount(progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvCount.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvCount.text.toString())
                hint = "1～10"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.peak_count))
                .setMessage("输入 1～10 的整数")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val c = input.text.toString().trim().toIntOrNull()?.coerceIn(1, 10) ?: currentPeakCount
                    applyPeakCount(c)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun setupOscilloscopeStrokeWidth(currentWidth: Float) {
        val seekBar = findViewById<SeekBar>(R.id.seekBarOscilloscopeStrokeWidth)
        val tvValue = findViewById<TextView>(R.id.tvOscilloscopeStrokeWidth)
        // 1.0～10.0，SeekBar progress 0～90 对应 1.0～10.0
        val progress = ((currentWidth.coerceIn(1f, 10f) - 1f) * 10f).toInt().coerceIn(0, 90)
        seekBar.progress = progress
        tvValue.text = String.format("%.1f", 1f + progress / 10f)

        fun applyStrokeWidth(width: Float) {
            val w = width.coerceIn(1f, 10f)
            currentOscilloscopeStrokeWidth = w
            val p = ((w - 1f) * 10f).toInt().coerceIn(0, 90)
            seekBar.progress = p
            tvValue.text = String.format("%.1f", w)
            sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_STROKE_WIDTH, currentOscilloscopeStrokeWidth).apply()
            updateResult()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvValue.text = String.format("%.1f", 1f + p / 10f)
                if (fromUser) applyStrokeWidth(1f + p / 10f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        tvValue.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvValue.text.toString())
                hint = "1.0～10.0"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.oscilloscope_stroke_width))
                .setMessage("输入 1.0～10.0 的数值（可含小数）")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(1f, 10f) ?: currentOscilloscopeStrokeWidth
                    applyStrokeWidth(v)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
    
    private fun setupOscilloscopeGridStrokeWidth(currentWidth: Float) {
        val seekBar = findViewById<SeekBar>(R.id.seekBarOscilloscopeGridStrokeWidth)
        val tvValue = findViewById<TextView>(R.id.tvOscilloscopeGridStrokeWidth)
        // 0.5～5.0，SeekBar progress 0～45 对应 0.5～5.0
        val progress = ((currentWidth.coerceIn(0.5f, 5f) - 0.5f) * 10f).toInt().coerceIn(0, 45)
        seekBar.progress = progress
        tvValue.text = String.format("%.1f", 0.5f + progress / 10f)

        fun applyStrokeWidth(width: Float) {
            val w = width.coerceIn(0.5f, 5f)
            currentOscilloscopeGridStrokeWidth = w
            val p = ((w - 0.5f) * 10f).toInt().coerceIn(0, 45)
            seekBar.progress = p
            tvValue.text = String.format("%.1f", w)
            sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_GRID_STROKE_WIDTH, currentOscilloscopeGridStrokeWidth).apply()
            updateResult()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvValue.text = String.format("%.1f", 0.5f + p / 10f)
                if (fromUser) applyStrokeWidth(0.5f + p / 10f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        tvValue.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvValue.text.toString())
                hint = "0.5～5.0"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.grid_stroke_width))
                .setMessage(getString(R.string.grid_stroke_width_hint))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(0.5f, 5f) ?: currentOscilloscopeGridStrokeWidth
                    applyStrokeWidth(v)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
    
    private fun setupOscilloscopeCenterLine(show: Boolean) {
        val switch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchOscilloscopeCenterLine)
        switch.isChecked = show
        
        switch.setOnCheckedChangeListener { _, isChecked ->
            currentShowOscilloscopeCenterLine = isChecked
            sharedPreferences.edit().putBoolean(KEY_SHOW_OSCILLOSCOPE_CENTER_LINE, isChecked).apply()
            updateResult()
        }
    }

    private fun setupOscilloscopeEnvelopeMode(usePeakEnvelope: Boolean) {
        val switch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchOscilloscopePeakEnvelope)
        switch.isChecked = usePeakEnvelope

        switch.setOnCheckedChangeListener { _, isChecked ->
            currentOscilloscopeLargeWindowUsePeakEnvelope = isChecked
            sharedPreferences.edit()
                .putBoolean(KEY_OSCILLOSCOPE_LARGE_WINDOW_USE_PEAK_ENVELOPE, isChecked)
                .apply()
            updateResult()
        }
    }
    
    private fun setupSpectrumGridStrokeWidth(currentWidth: Float) {
        val seekBar = findViewById<SeekBar>(R.id.seekBarSpectrumGridStrokeWidth)
        val tvValue = findViewById<TextView>(R.id.tvSpectrumGridStrokeWidth)
        // 0.5～5.0，SeekBar progress 0～45 对应 0.5～5.0
        val progress = ((currentWidth.coerceIn(0.5f, 5f) - 0.5f) * 10f).toInt().coerceIn(0, 45)
        seekBar.progress = progress
        tvValue.text = String.format("%.1f", 0.5f + progress / 10f)

        fun applyStrokeWidth(width: Float) {
            val w = width.coerceIn(0.5f, 5f)
            currentSpectrumGridStrokeWidth = w
            val p = ((w - 0.5f) * 10f).toInt().coerceIn(0, 45)
            seekBar.progress = p
            tvValue.text = String.format("%.1f", w)
            sharedPreferences.edit().putFloat(KEY_SPECTRUM_GRID_STROKE_WIDTH, currentSpectrumGridStrokeWidth).apply()
            updateResult()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvValue.text = String.format("%.1f", 0.5f + p / 10f)
                if (fromUser) applyStrokeWidth(0.5f + p / 10f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        tvValue.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvValue.text.toString())
                hint = "0.5～5.0"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.grid_stroke_width))
                .setMessage(getString(R.string.grid_stroke_width_hint))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(0.5f, 5f) ?: currentSpectrumGridStrokeWidth
                    applyStrokeWidth(v)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
    
    private fun setupSpectrumMarkerStrokeWidth(currentWidth: Float) {
        val seekBar = findViewById<SeekBar>(R.id.seekBarSpectrumMarkerStrokeWidth)
        val tvValue = findViewById<TextView>(R.id.tvSpectrumMarkerStrokeWidth)
        // 0.5～5.0，SeekBar progress 0～45 对应 0.5～5.0
        val progress = ((currentWidth.coerceIn(0.5f, 5f) - 0.5f) * 10f).toInt().coerceIn(0, 45)
        seekBar.progress = progress
        tvValue.text = String.format("%.1f", 0.5f + progress / 10f)

        fun applyStrokeWidth(width: Float) {
            val w = width.coerceIn(0.5f, 5f)
            currentSpectrumMarkerStrokeWidth = w
            val p = ((w - 0.5f) * 10f).toInt().coerceIn(0, 45)
            seekBar.progress = p
            tvValue.text = String.format("%.1f", w)
            sharedPreferences.edit().putFloat(KEY_SPECTRUM_MARKER_STROKE_WIDTH, currentSpectrumMarkerStrokeWidth).apply()
            updateResult()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvValue.text = String.format("%.1f", 0.5f + p / 10f)
                if (fromUser) applyStrokeWidth(0.5f + p / 10f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        tvValue.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvValue.text.toString())
                hint = "0.5～5.0"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.marker_stroke_width))
                .setMessage(getString(R.string.grid_stroke_width_hint))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(0.5f, 5f) ?: currentSpectrumMarkerStrokeWidth
                    applyStrokeWidth(v)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
    
    private fun setupScaleSensitivity(sensitivity: Float) {
        val toggleGroup = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleScaleSensitivity)
        
        // 设置初始选中状态
        val checkedId = when {
            sensitivity <= 0.6f -> R.id.btnSensitivityLow
            sensitivity >= 1.5f -> R.id.btnSensitivityHigh
            else -> R.id.btnSensitivityMedium
        }
        toggleGroup.check(checkedId)
        
        toggleGroup.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentScaleSensitivity = when (buttonId) {
                R.id.btnSensitivityLow -> 0.5f
                R.id.btnSensitivityMedium -> 1.0f
                R.id.btnSensitivityHigh -> 2.0f
                else -> 1.0f
            }
            sharedPreferences.edit().putFloat(KEY_SCALE_SENSITIVITY, currentScaleSensitivity).apply()
            updateResult()
        }
    }
    
    private fun setupOscilloscopeTrigger(triggerEnabled: Boolean, triggerLevelDb: Float, triggerMode: Int = 0, singleTrigger: Boolean = false) {
        val switchTrigger = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchOscilloscopeTrigger)
        val switchSingleTrigger = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSingleTrigger)
        val seekBarTriggerLevel = findViewById<SeekBar>(R.id.seekBarTriggerLevel)
        val tvTriggerLevel = findViewById<TextView>(R.id.tvTriggerLevel)
        val toggleTriggerMode = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleTriggerMode)
        
        // 设置当前值
        switchTrigger.isChecked = triggerEnabled
        switchSingleTrigger.isChecked = singleTrigger
        // triggerLevelDb: -90 ~ 0 dB → progress: 0 ~ 90 (90 = 0 dB, 0 = -90 dB)
        val progress = (triggerLevelDb + 90f).toInt().coerceIn(0, 90)
        seekBarTriggerLevel.progress = progress
        tvTriggerLevel.text = "${triggerLevelDb.toInt()} dB"
        
        // 设置触发模式
        currentTriggerMode = triggerMode
        currentSingleTrigger = singleTrigger
        toggleTriggerMode.check(when (triggerMode) {
            0 -> R.id.btnTriggerRising
            1 -> R.id.btnTriggerFalling
            2 -> R.id.btnTriggerBoth
            else -> R.id.btnTriggerRising
        })
        
        // 根据开关状态设置控件可用性
        fun updateTriggerControlsEnabled(enabled: Boolean) {
            seekBarTriggerLevel.isEnabled = enabled
            tvTriggerLevel.alpha = if (enabled) 1f else 0.5f
            toggleTriggerMode.isEnabled = enabled
            switchSingleTrigger.isEnabled = enabled
            switchSingleTrigger.alpha = if (enabled) 1f else 0.5f
            for (i in 0 until toggleTriggerMode.childCount) {
                toggleTriggerMode.getChildAt(i).isEnabled = enabled
                toggleTriggerMode.getChildAt(i).alpha = if (enabled) 1f else 0.5f
            }
        }
        updateTriggerControlsEnabled(triggerEnabled)
        
        fun applyTriggerLevelDb(db: Float) {
            val d = db.coerceIn(-90f, 0f)
            currentTriggerLevelDb = d
            val p = (d + 90f).toInt().coerceIn(0, 90)
            seekBarTriggerLevel.progress = p
            tvTriggerLevel.text = "${d.toInt()} dB"
            sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_LEVEL, d).apply()
            updateResult()
        }
        
        switchTrigger.setOnCheckedChangeListener { _, isChecked ->
            currentTriggerEnabled = isChecked
            updateTriggerControlsEnabled(isChecked)
            sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_TRIGGER_ENABLED, isChecked).apply()
            updateResult()
        }
        
        switchSingleTrigger.setOnCheckedChangeListener { _, isChecked ->
            currentSingleTrigger = isChecked
            sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_SINGLE_TRIGGER, isChecked).apply()
            updateResult()
        }
        
        toggleTriggerMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentTriggerMode = when (checkedId) {
                R.id.btnTriggerRising -> 0
                R.id.btnTriggerFalling -> 1
                R.id.btnTriggerBoth -> 2
                else -> 0
            }
            sharedPreferences.edit().putInt(KEY_OSCILLOSCOPE_TRIGGER_MODE, currentTriggerMode).apply()
            updateResult()
        }
        
        seekBarTriggerLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // progress: 0 ~ 90 → triggerLevelDb: -90 ~ 0 dB
                val levelDb = progress - 90f
                tvTriggerLevel.text = "${levelDb.toInt()} dB"
                if (fromUser) {
                    currentTriggerLevelDb = levelDb
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_LEVEL, levelDb).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 点击数值可输入
        tvTriggerLevel.setOnClickListener {
            val input = EditText(this).apply {
                setText(currentTriggerLevelDb.toInt().toString())
                hint = "-90 ~ 0"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.trigger_level))
                .setMessage(getString(R.string.trigger_level_input_hint))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toIntOrNull()?.toFloat()?.coerceIn(-90f, 0f) ?: currentTriggerLevelDb
                    applyTriggerLevelDb(v)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        // ========== 触发稳定性设置 ==========
        
        // 迟滞量设置
        val seekBarHysteresis = findViewById<SeekBar>(R.id.seekBarTriggerHysteresis)
        val tvHysteresis = findViewById<TextView>(R.id.tvTriggerHysteresis)
        
        // 设置当前值 (1-30% -> progress 0-29)
        seekBarHysteresis.progress = (currentTriggerHysteresis - 1f).toInt().coerceIn(0, 29)
        tvHysteresis.text = "${currentTriggerHysteresis.toInt()}%"
        
        seekBarHysteresis.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val hysteresis = (progress + 1).toFloat()
                tvHysteresis.text = "${hysteresis.toInt()}%"
                if (fromUser) {
                    currentTriggerHysteresis = hysteresis
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_HYSTERESIS, hysteresis).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvHysteresis.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvHysteresis.text.toString().replace("%", ""))
                hint = "1～30"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.trigger_hysteresis))
                .setMessage("输入 1～30 的整数（%）")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toIntOrNull()?.coerceIn(1, 30) ?: currentTriggerHysteresis.toInt()
                    currentTriggerHysteresis = v.toFloat()
                    seekBarHysteresis.progress = (v - 1).coerceIn(0, 29)
                    tvHysteresis.text = "${v}%"
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_HYSTERESIS, v.toFloat()).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        tvHysteresis.isClickable = true
        tvHysteresis.isFocusable = true
        
        // 触发保持时间设置
        val toggleHoldoffMode = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleTriggerHoldoffMode)
        val layoutHoldoffManual = findViewById<View>(R.id.layoutTriggerHoldoffManual)
        val seekBarHoldoff = findViewById<SeekBar>(R.id.seekBarTriggerHoldoff)
        val tvHoldoff = findViewById<TextView>(R.id.tvTriggerHoldoff)
        
        // 格式化保持时间显示
        fun formatHoldoff(ms: Float): String {
            return if (ms < 1f) {
                String.format("%.1f ms", ms)
            } else {
                String.format("%.1f ms", ms)
            }
        }
        
        // 设置当前模式
        toggleHoldoffMode.check(if (currentTriggerHoldoffAuto) R.id.btnHoldoffAuto else R.id.btnHoldoffManual)
        layoutHoldoffManual.visibility = if (currentTriggerHoldoffAuto) View.GONE else View.VISIBLE
        
        // holdoff: 0.1-10ms -> progress 0-100 (对数刻度更好用)
        // progress = (log10(ms) + 1) * 50，即 0.1ms->0, 1ms->50, 10ms->100
        fun holdoffToProgress(ms: Float): Int {
            return ((kotlin.math.log10(ms.coerceIn(0.1f, 10f)) + 1f) * 50f).toInt().coerceIn(0, 100)
        }
        fun progressToHoldoff(progress: Int): Float {
            return Math.pow(10.0, (progress / 50.0) - 1.0).toFloat().coerceIn(0.1f, 10f)
        }
        
        seekBarHoldoff.progress = holdoffToProgress(currentTriggerHoldoffMs)
        tvHoldoff.text = formatHoldoff(currentTriggerHoldoffMs)
        
        toggleHoldoffMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentTriggerHoldoffAuto = (checkedId == R.id.btnHoldoffAuto)
            layoutHoldoffManual.visibility = if (currentTriggerHoldoffAuto) View.GONE else View.VISIBLE
            sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_AUTO, currentTriggerHoldoffAuto).apply()
            updateResult()
        }
        
        seekBarHoldoff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val holdoffMs = progressToHoldoff(progress)
                tvHoldoff.text = formatHoldoff(holdoffMs)
                if (fromUser) {
                    currentTriggerHoldoffMs = holdoffMs
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_MS, holdoffMs).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvHoldoff.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvHoldoff.text.toString().replace(" ms", "").trim())
                hint = "0.1～10"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.trigger_holdoff))
                .setMessage("输入 0.1～10 的数值（毫秒）")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(0.1f, 10f) ?: currentTriggerHoldoffMs
                    currentTriggerHoldoffMs = v
                    seekBarHoldoff.progress = holdoffToProgress(v)
                    tvHoldoff.text = formatHoldoff(v)
                    sharedPreferences.edit().putFloat(KEY_OSCILLOSCOPE_TRIGGER_HOLDOFF_MS, v).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        tvHoldoff.isClickable = true
        tvHoldoff.isFocusable = true
        
        // 噪声抑制开关
        val switchNoiseReject = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchTriggerNoiseReject)
        switchNoiseReject.isChecked = currentTriggerNoiseReject
        
        switchNoiseReject.setOnCheckedChangeListener { _, isChecked ->
            currentTriggerNoiseReject = isChecked
            sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_TRIGGER_NOISE_REJECT, isChecked).apply()
            updateResult()
        }
        
        // 更新触发稳定性控件的可用状态
        fun updateStabilityControlsEnabled(enabled: Boolean) {
            seekBarHysteresis.isEnabled = enabled
            tvHysteresis.alpha = if (enabled) 1f else 0.5f
            toggleHoldoffMode.isEnabled = enabled
            for (i in 0 until toggleHoldoffMode.childCount) {
                toggleHoldoffMode.getChildAt(i).isEnabled = enabled
                toggleHoldoffMode.getChildAt(i).alpha = if (enabled) 1f else 0.5f
            }
            seekBarHoldoff.isEnabled = enabled && !currentTriggerHoldoffAuto
            tvHoldoff.alpha = if (enabled && !currentTriggerHoldoffAuto) 1f else 0.5f
            switchNoiseReject.isEnabled = enabled
            switchNoiseReject.alpha = if (enabled) 1f else 0.5f
        }
        updateStabilityControlsEnabled(triggerEnabled)
        
        // 更新原有的 updateTriggerControlsEnabled 以包含稳定性控件
        val originalUpdateTriggerControlsEnabled = { enabled: Boolean ->
            seekBarTriggerLevel.isEnabled = enabled
            tvTriggerLevel.alpha = if (enabled) 1f else 0.5f
            toggleTriggerMode.isEnabled = enabled
            switchSingleTrigger.isEnabled = enabled
            switchSingleTrigger.alpha = if (enabled) 1f else 0.5f
            for (i in 0 until toggleTriggerMode.childCount) {
                toggleTriggerMode.getChildAt(i).isEnabled = enabled
                toggleTriggerMode.getChildAt(i).alpha = if (enabled) 1f else 0.5f
            }
            updateStabilityControlsEnabled(enabled)
        }
        
        // 重新设置触发开关的监听器以包含稳定性控件
        switchTrigger.setOnCheckedChangeListener { _, isChecked ->
            currentTriggerEnabled = isChecked
            originalUpdateTriggerControlsEnabled(isChecked)
            sharedPreferences.edit().putBoolean(KEY_OSCILLOSCOPE_TRIGGER_ENABLED, isChecked).apply()
            updateResult()
        }
    }
    
    private fun setupOscilloscopeFilter() {
        val switchFilterEnabled = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchFilterEnabled)
        val toggleFilterPreset = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleFilterPreset)
        val toggleFilterPreset2 = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleFilterPreset2)
        val toggleFilterType = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleFilterType)
        val layoutFilterCutoff = findViewById<View>(R.id.layoutFilterCutoff)
        val layoutFilterCenter = findViewById<View>(R.id.layoutFilterCenter)
        val seekBarFilterCutoff = findViewById<SeekBar>(R.id.seekBarFilterCutoff)
        val tvFilterCutoff = findViewById<TextView>(R.id.tvFilterCutoff)
        val seekBarFilterCenter = findViewById<SeekBar>(R.id.seekBarFilterCenter)
        val tvFilterCenter = findViewById<TextView>(R.id.tvFilterCenter)
        val seekBarFilterBandwidth = findViewById<SeekBar>(R.id.seekBarFilterBandwidth)
        val tvFilterBandwidth = findViewById<TextView>(R.id.tvFilterBandwidth)
        val toggleFilterOrder = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleFilterOrder)
        
        // 频率转换：对数刻度 20Hz ~ 20000Hz
        fun freqToProgress(freq: Float): Int {
            val minLog = kotlin.math.ln(20f)
            val maxLog = kotlin.math.ln(20000f)
            val freqLog = kotlin.math.ln(freq.coerceIn(20f, 20000f))
            return ((freqLog - minLog) / (maxLog - minLog) * 100).toInt().coerceIn(0, 100)
        }
        
        fun progressToFreq(progress: Int): Float {
            val minLog = kotlin.math.ln(20f)
            val maxLog = kotlin.math.ln(20000f)
            val freqLog = minLog + (maxLog - minLog) * progress / 100f
            return kotlin.math.exp(freqLog).coerceIn(20f, 20000f)
        }
        
        // 带宽转换：对数刻度 10Hz ~ 5000Hz
        fun bandwidthToProgress(bw: Float): Int {
            val minLog = kotlin.math.ln(10f)
            val maxLog = kotlin.math.ln(5000f)
            val bwLog = kotlin.math.ln(bw.coerceIn(10f, 5000f))
            return ((bwLog - minLog) / (maxLog - minLog) * 100).toInt().coerceIn(0, 100)
        }
        
        fun progressToBandwidth(progress: Int): Float {
            val minLog = kotlin.math.ln(10f)
            val maxLog = kotlin.math.ln(5000f)
            val bwLog = minLog + (maxLog - minLog) * progress / 100f
            return kotlin.math.exp(bwLog).coerceIn(10f, 5000f)
        }
        
        fun formatFreq(freq: Float): String {
            return if (freq >= 1000) String.format("%.1f kHz", freq / 1000) else String.format("%.0f Hz", freq)
        }
        
        // 根据滤波器类型显示/隐藏参数区域
        fun updateFilterParamsVisibility(filterType: Int) {
            when (filterType) {
                0, 1 -> {  // 低通、高通
                    layoutFilterCutoff.visibility = View.VISIBLE
                    layoutFilterCenter.visibility = View.GONE
                }
                2, 3 -> {  // 带通、陷波
                    layoutFilterCutoff.visibility = View.GONE
                    layoutFilterCenter.visibility = View.VISIBLE
                }
            }
        }
        
        // 更新滤波器控件可用状态
        fun updateFilterControlsEnabled(enabled: Boolean) {
            val alpha = if (enabled) 1f else 0.5f
            toggleFilterPreset.isEnabled = enabled
            toggleFilterPreset2.isEnabled = enabled
            toggleFilterType.isEnabled = enabled
            toggleFilterOrder.isEnabled = enabled
            seekBarFilterCutoff.isEnabled = enabled
            seekBarFilterCenter.isEnabled = enabled
            seekBarFilterBandwidth.isEnabled = enabled
            for (group in listOf(toggleFilterPreset, toggleFilterPreset2, toggleFilterType, toggleFilterOrder)) {
                for (i in 0 until group.childCount) {
                    group.getChildAt(i).isEnabled = enabled
                    group.getChildAt(i).alpha = alpha
                }
            }
            tvFilterCutoff.alpha = alpha
            tvFilterCenter.alpha = alpha
            tvFilterBandwidth.alpha = alpha
        }
        
        // 应用预设设置
        fun applyPreset(preset: Int) {
            when (preset) {
                0 -> {  // 无（关闭滤波）
                    currentFilterEnabled = false
                    switchFilterEnabled.isChecked = false
                }
                1 -> {  // 人声 (300Hz - 3400Hz 带通)
                    currentFilterEnabled = true
                    currentFilterType = 2  // 带通
                    currentFilterCenter = 1700f
                    currentFilterBandwidth = 3100f
                    switchFilterEnabled.isChecked = true
                    toggleFilterType.check(R.id.btnFilterBandpass)
                    seekBarFilterCenter.progress = freqToProgress(currentFilterCenter)
                    tvFilterCenter.text = formatFreq(currentFilterCenter)
                    seekBarFilterBandwidth.progress = bandwidthToProgress(currentFilterBandwidth)
                    tvFilterBandwidth.text = formatFreq(currentFilterBandwidth)
                }
                2 -> {  // 低音 (低通 300Hz)
                    currentFilterEnabled = true
                    currentFilterType = 0  // 低通
                    currentFilterCutoff = 300f
                    switchFilterEnabled.isChecked = true
                    toggleFilterType.check(R.id.btnFilterLowpass)
                    seekBarFilterCutoff.progress = freqToProgress(currentFilterCutoff)
                    tvFilterCutoff.text = formatFreq(currentFilterCutoff)
                }
                3 -> {  // 高音 (高通 3000Hz)
                    currentFilterEnabled = true
                    currentFilterType = 1  // 高通
                    currentFilterCutoff = 3000f
                    switchFilterEnabled.isChecked = true
                    toggleFilterType.check(R.id.btnFilterHighpass)
                    seekBarFilterCutoff.progress = freqToProgress(currentFilterCutoff)
                    tvFilterCutoff.text = formatFreq(currentFilterCutoff)
                }
            }
            updateFilterParamsVisibility(currentFilterType)
            updateFilterControlsEnabled(currentFilterEnabled)
            sharedPreferences.edit()
                .putBoolean(KEY_FILTER_ENABLED, currentFilterEnabled)
                .putInt(KEY_FILTER_TYPE, currentFilterType)
                .putFloat(KEY_FILTER_CUTOFF, currentFilterCutoff)
                .putFloat(KEY_FILTER_CENTER, currentFilterCenter)
                .putFloat(KEY_FILTER_BANDWIDTH, currentFilterBandwidth)
                .apply()
            updateResult()
        }
        
        // 初始化 UI 状态
        switchFilterEnabled.isChecked = currentFilterEnabled
        updateFilterControlsEnabled(currentFilterEnabled)
        
        toggleFilterType.check(when (currentFilterType) {
            0 -> R.id.btnFilterLowpass
            1 -> R.id.btnFilterHighpass
            2 -> R.id.btnFilterBandpass
            3 -> R.id.btnFilterNotch
            else -> R.id.btnFilterLowpass
        })
        updateFilterParamsVisibility(currentFilterType)
        
        seekBarFilterCutoff.progress = freqToProgress(currentFilterCutoff)
        tvFilterCutoff.text = formatFreq(currentFilterCutoff)
        seekBarFilterCenter.progress = freqToProgress(currentFilterCenter)
        tvFilterCenter.text = formatFreq(currentFilterCenter)
        seekBarFilterBandwidth.progress = bandwidthToProgress(currentFilterBandwidth)
        tvFilterBandwidth.text = formatFreq(currentFilterBandwidth)
        
        toggleFilterOrder.check(when (currentFilterOrder) {
            1 -> R.id.btnFilterOrder1
            2 -> R.id.btnFilterOrder2
            4 -> R.id.btnFilterOrder4
            8 -> R.id.btnFilterOrder8
            else -> R.id.btnFilterOrder2
        })
        
        // 默认选中"无"预设
        toggleFilterPreset.check(R.id.btnFilterPresetNone)
        
        // 事件监听
        switchFilterEnabled.setOnCheckedChangeListener { _, isChecked ->
            currentFilterEnabled = isChecked
            updateFilterControlsEnabled(isChecked)
            sharedPreferences.edit().putBoolean(KEY_FILTER_ENABLED, isChecked).apply()
            updateResult()
        }
        
        // 预设选择监听器（两行互斥）
        var preset1Listener: com.google.android.material.button.MaterialButtonToggleGroup.OnButtonCheckedListener? = null
        var preset2Listener: com.google.android.material.button.MaterialButtonToggleGroup.OnButtonCheckedListener? = null
        
        preset1Listener = com.google.android.material.button.MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                toggleFilterPreset2.removeOnButtonCheckedListener(preset2Listener!!)
                toggleFilterPreset2.clearChecked()
                toggleFilterPreset2.addOnButtonCheckedListener(preset2Listener!!)
                
                val preset = when (checkedId) {
                    R.id.btnFilterPresetNone -> 0
                    R.id.btnFilterPresetVoice -> 1
                    R.id.btnFilterPresetBass -> 2
                    else -> 0
                }
                applyPreset(preset)
            }
        }
        
        preset2Listener = com.google.android.material.button.MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                toggleFilterPreset.removeOnButtonCheckedListener(preset1Listener!!)
                toggleFilterPreset.clearChecked()
                toggleFilterPreset.addOnButtonCheckedListener(preset1Listener!!)
                
                val preset = when (checkedId) {
                    R.id.btnFilterPresetTreble -> 3
                    else -> 0
                }
                applyPreset(preset)
            }
        }
        
        toggleFilterPreset.addOnButtonCheckedListener(preset1Listener)
        toggleFilterPreset2.addOnButtonCheckedListener(preset2Listener)
        
        toggleFilterType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentFilterType = when (checkedId) {
                R.id.btnFilterLowpass -> 0
                R.id.btnFilterHighpass -> 1
                R.id.btnFilterBandpass -> 2
                R.id.btnFilterNotch -> 3
                else -> 0
            }
            updateFilterParamsVisibility(currentFilterType)
            sharedPreferences.edit().putInt(KEY_FILTER_TYPE, currentFilterType).apply()
            updateResult()
        }
        
        seekBarFilterCutoff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val freq = progressToFreq(progress)
                tvFilterCutoff.text = formatFreq(freq)
                if (fromUser) {
                    currentFilterCutoff = freq
                    sharedPreferences.edit().putFloat(KEY_FILTER_CUTOFF, freq).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        tvFilterCutoff.setOnClickListener {
            val input = EditText(this).apply {
                setText(currentFilterCutoff.toInt().toString())
                hint = getString(R.string.filter_cutoff_freq_hint)
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.filter_cutoff_freq))
                .setMessage(getString(R.string.filter_cutoff_freq_hint))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(20f, 20000f) ?: currentFilterCutoff
                    currentFilterCutoff = v
                    seekBarFilterCutoff.progress = freqToProgress(v)
                    tvFilterCutoff.text = formatFreq(v)
                    sharedPreferences.edit().putFloat(KEY_FILTER_CUTOFF, v).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        seekBarFilterCenter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val freq = progressToFreq(progress)
                tvFilterCenter.text = formatFreq(freq)
                if (fromUser) {
                    currentFilterCenter = freq
                    sharedPreferences.edit().putFloat(KEY_FILTER_CENTER, freq).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        tvFilterCenter.setOnClickListener {
            val input = EditText(this).apply {
                setText(currentFilterCenter.toInt().toString())
                hint = getString(R.string.filter_center_freq_hint)
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.filter_center_freq))
                .setMessage(getString(R.string.filter_center_freq_hint))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(20f, 20000f) ?: currentFilterCenter
                    currentFilterCenter = v
                    seekBarFilterCenter.progress = freqToProgress(v)
                    tvFilterCenter.text = formatFreq(v)
                    sharedPreferences.edit().putFloat(KEY_FILTER_CENTER, v).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        seekBarFilterBandwidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val bw = progressToBandwidth(progress)
                tvFilterBandwidth.text = formatFreq(bw)
                if (fromUser) {
                    currentFilterBandwidth = bw
                    sharedPreferences.edit().putFloat(KEY_FILTER_BANDWIDTH, bw).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        tvFilterBandwidth.setOnClickListener {
            val input = EditText(this).apply {
                setText(currentFilterBandwidth.toInt().toString())
                hint = getString(R.string.filter_bandwidth_hint)
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.filter_bandwidth))
                .setMessage(getString(R.string.filter_bandwidth_hint))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(10f, 5000f) ?: currentFilterBandwidth
                    currentFilterBandwidth = v
                    seekBarFilterBandwidth.progress = bandwidthToProgress(v)
                    tvFilterBandwidth.text = formatFreq(v)
                    sharedPreferences.edit().putFloat(KEY_FILTER_BANDWIDTH, v).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        toggleFilterOrder.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentFilterOrder = when (checkedId) {
                R.id.btnFilterOrder1 -> 1
                R.id.btnFilterOrder2 -> 2
                R.id.btnFilterOrder4 -> 4
                R.id.btnFilterOrder8 -> 8
                else -> 2
            }
            sharedPreferences.edit().putInt(KEY_FILTER_ORDER, currentFilterOrder).apply()
            updateResult()
        }
    }
    
    private fun setupSpectrumInfoItems() {
        val cbPeakFreq = findViewById<android.widget.CheckBox>(R.id.cbSpectrumInfoPeakFreq)
        val cbPeakAmp = findViewById<android.widget.CheckBox>(R.id.cbSpectrumInfoPeakAmp)
        val cbFps = findViewById<android.widget.CheckBox>(R.id.cbSpectrumInfoFps)
        val cbFft = findViewById<android.widget.CheckBox>(R.id.cbSpectrumInfoFft)
        val cbScaleMode = findViewById<android.widget.CheckBox>(R.id.cbSpectrumInfoScaleMode)
        val cbZoom = findViewById<android.widget.CheckBox>(R.id.cbSpectrumInfoZoom)
        
        // 设置初始状态
        cbPeakFreq.isChecked = (currentSpectrumInfoFlags and SPECTRUM_INFO_PEAK_FREQ) != 0
        cbPeakAmp.isChecked = (currentSpectrumInfoFlags and SPECTRUM_INFO_PEAK_AMP) != 0
        cbFps.isChecked = (currentSpectrumInfoFlags and SPECTRUM_INFO_FPS) != 0
        cbFft.isChecked = (currentSpectrumInfoFlags and SPECTRUM_INFO_FFT) != 0
        cbScaleMode.isChecked = (currentSpectrumInfoFlags and SPECTRUM_INFO_SCALE_MODE) != 0
        cbZoom.isChecked = (currentSpectrumInfoFlags and SPECTRUM_INFO_ZOOM) != 0
        
        // 监听器
        val updateFlags = { 
            currentSpectrumInfoFlags = 
                (if (cbPeakFreq.isChecked) SPECTRUM_INFO_PEAK_FREQ else 0) or
                (if (cbPeakAmp.isChecked) SPECTRUM_INFO_PEAK_AMP else 0) or
                (if (cbFps.isChecked) SPECTRUM_INFO_FPS else 0) or
                (if (cbFft.isChecked) SPECTRUM_INFO_FFT else 0) or
                (if (cbScaleMode.isChecked) SPECTRUM_INFO_SCALE_MODE else 0) or
                (if (cbZoom.isChecked) SPECTRUM_INFO_ZOOM else 0)
            sharedPreferences.edit().putInt(KEY_SPECTRUM_INFO_FLAGS, currentSpectrumInfoFlags).apply()
            updateResult()
        }
        cbPeakFreq.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbPeakAmp.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbFps.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbFft.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbScaleMode.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbZoom.setOnCheckedChangeListener { _, _ -> updateFlags() }
    }
    
    private fun setupOscilloscopeInfoItems() {
        val cbTimeSpan = findViewById<android.widget.CheckBox>(R.id.cbOscilloscopeInfoTimeSpan)
        val cbTrigger = findViewById<android.widget.CheckBox>(R.id.cbOscilloscopeInfoTrigger)
        val cbSampleRate = findViewById<android.widget.CheckBox>(R.id.cbOscilloscopeInfoSampleRate)
        val cbZoom = findViewById<android.widget.CheckBox>(R.id.cbOscilloscopeInfoZoom)
        val cbPeak = findViewById<android.widget.CheckBox>(R.id.cbOscilloscopeInfoPeak)
        val cbPeakToPeak = findViewById<android.widget.CheckBox>(R.id.cbOscilloscopeInfoPeakToPeak)
        val cbRms = findViewById<android.widget.CheckBox>(R.id.cbOscilloscopeInfoRms)
        val cbPosition = findViewById<android.widget.CheckBox>(R.id.cbOscilloscopeInfoPosition)
        
        // 设置初始状态
        cbTimeSpan.isChecked = (currentOscilloscopeInfoFlags and OSCILLOSCOPE_INFO_TIME_SPAN) != 0
        cbTrigger.isChecked = (currentOscilloscopeInfoFlags and OSCILLOSCOPE_INFO_TRIGGER) != 0
        cbSampleRate.isChecked = (currentOscilloscopeInfoFlags and OSCILLOSCOPE_INFO_SAMPLE_RATE) != 0
        cbZoom.isChecked = (currentOscilloscopeInfoFlags and OSCILLOSCOPE_INFO_ZOOM) != 0
        cbPeak.isChecked = (currentOscilloscopeInfoFlags and OSCILLOSCOPE_INFO_PEAK) != 0
        cbPeakToPeak.isChecked = (currentOscilloscopeInfoFlags and OSCILLOSCOPE_INFO_PEAK_TO_PEAK) != 0
        cbRms.isChecked = (currentOscilloscopeInfoFlags and OSCILLOSCOPE_INFO_RMS) != 0
        cbPosition.isChecked = (currentOscilloscopeInfoFlags and OSCILLOSCOPE_INFO_POSITION) != 0
        
        // 监听器
        val updateFlags = {
            currentOscilloscopeInfoFlags =
                (if (cbTimeSpan.isChecked) OSCILLOSCOPE_INFO_TIME_SPAN else 0) or
                (if (cbTrigger.isChecked) OSCILLOSCOPE_INFO_TRIGGER else 0) or
                (if (cbSampleRate.isChecked) OSCILLOSCOPE_INFO_SAMPLE_RATE else 0) or
                (if (cbZoom.isChecked) OSCILLOSCOPE_INFO_ZOOM else 0) or
                (if (cbPeak.isChecked) OSCILLOSCOPE_INFO_PEAK else 0) or
                (if (cbPeakToPeak.isChecked) OSCILLOSCOPE_INFO_PEAK_TO_PEAK else 0) or
                (if (cbRms.isChecked) OSCILLOSCOPE_INFO_RMS else 0) or
                (if (cbPosition.isChecked) OSCILLOSCOPE_INFO_POSITION else 0)
            sharedPreferences.edit().putInt(KEY_OSCILLOSCOPE_INFO_FLAGS, currentOscilloscopeInfoFlags).apply()
            updateResult()
        }
        cbTimeSpan.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbTrigger.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbSampleRate.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbZoom.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbPeak.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbPeakToPeak.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbRms.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbPosition.setOnCheckedChangeListener { _, _ -> updateFlags() }
    }
    
    private fun setupWaterfallInfoItems() {
        val cbPeakFreq = findViewById<android.widget.CheckBox>(R.id.cbWaterfallInfoPeakFreq)
        val cbPeakAmp = findViewById<android.widget.CheckBox>(R.id.cbWaterfallInfoPeakAmp)
        val cbFft = findViewById<android.widget.CheckBox>(R.id.cbWaterfallInfoFft)
        val cbScaleMode = findViewById<android.widget.CheckBox>(R.id.cbWaterfallInfoScaleMode)
        val cbSensitivity = findViewById<android.widget.CheckBox>(R.id.cbWaterfallInfoSensitivity)
        val cbTimeResolution = findViewById<android.widget.CheckBox>(R.id.cbWaterfallInfoTimeResolution)
        
        cbPeakFreq.isChecked = (currentWaterfallInfoFlags and WATERFALL_INFO_PEAK_FREQ) != 0
        cbPeakAmp.isChecked = (currentWaterfallInfoFlags and WATERFALL_INFO_PEAK_AMP) != 0
        cbFft.isChecked = (currentWaterfallInfoFlags and WATERFALL_INFO_FFT) != 0
        cbScaleMode.isChecked = (currentWaterfallInfoFlags and WATERFALL_INFO_SCALE_MODE) != 0
        cbSensitivity.isChecked = (currentWaterfallInfoFlags and WATERFALL_INFO_SENSITIVITY) != 0
        cbTimeResolution.isChecked = (currentWaterfallInfoFlags and WATERFALL_INFO_TIME_RESOLUTION) != 0
        
        val updateFlags = {
            currentWaterfallInfoFlags =
                (if (cbPeakFreq.isChecked) WATERFALL_INFO_PEAK_FREQ else 0) or
                (if (cbPeakAmp.isChecked) WATERFALL_INFO_PEAK_AMP else 0) or
                (if (cbFft.isChecked) WATERFALL_INFO_FFT else 0) or
                (if (cbScaleMode.isChecked) WATERFALL_INFO_SCALE_MODE else 0) or
                (if (cbSensitivity.isChecked) WATERFALL_INFO_SENSITIVITY else 0) or
                (if (cbTimeResolution.isChecked) WATERFALL_INFO_TIME_RESOLUTION else 0)
            sharedPreferences.edit().putInt(KEY_WATERFALL_INFO_FLAGS, currentWaterfallInfoFlags).apply()
            updateResult()
        }
        cbPeakFreq.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbPeakAmp.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbFft.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbScaleMode.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbSensitivity.setOnCheckedChangeListener { _, _ -> updateFlags() }
        cbTimeResolution.setOnCheckedChangeListener { _, _ -> updateFlags() }
    }
    
    private fun setupWaterfallColorPalette(palette: Int) {
        val radioGroup = findViewById<RadioGroup>(R.id.radioGroupWaterfallColorPalette)
        when (palette.coerceIn(0, 3)) {
            0 -> radioGroup.check(R.id.radioWaterfallColorRainbow)
            1 -> radioGroup.check(R.id.radioWaterfallColorGrayscale)
            2 -> radioGroup.check(R.id.radioWaterfallColorBlackRed)
            3 -> radioGroup.check(R.id.radioWaterfallColorBlueGreen)
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentWaterfallColorPalette = when (checkedId) {
                R.id.radioWaterfallColorRainbow -> 0
                R.id.radioWaterfallColorGrayscale -> 1
                R.id.radioWaterfallColorBlackRed -> 2
                R.id.radioWaterfallColorBlueGreen -> 3
                else -> 0
            }
            sharedPreferences.edit().putInt(KEY_WATERFALL_COLOR_PALETTE, currentWaterfallColorPalette).apply()
            updateResult()
        }
    }
    
    private fun setupWaterfallSensitivity(sensitivity: Float) {
        val seekBar = findViewById<SeekBar>(R.id.seekBarWaterfallSensitivity)
        val tvValue = findViewById<TextView>(R.id.tvWaterfallSensitivity)
        
        fun updateDisplay(value: Float) {
            tvValue.text = String.format("%.1f", value)
        }
        
        // 灵敏度：0.5~50，progress = (sensitivity - 0.5) * 10
        seekBar.progress = ((sensitivity - 0.5f) * 10).toInt().coerceIn(0, 495)
        updateDisplay(sensitivity)
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.5f + progress / 10f
                updateDisplay(value)
                if (fromUser) {
                    currentWaterfallSensitivity = value
                    sharedPreferences.edit().putFloat(KEY_WATERFALL_SENSITIVITY, value).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvValue.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvValue.text.toString())
                hint = "0.5～50"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.waterfall_sensitivity))
                .setMessage("输入 0.5～50 的数值")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(0.5f, 50f) ?: currentWaterfallSensitivity
                    currentWaterfallSensitivity = v
                    seekBar.progress = ((v - 0.5f) * 10).toInt().coerceIn(0, 495)
                    updateDisplay(v)
                    sharedPreferences.edit().putFloat(KEY_WATERFALL_SENSITIVITY, v).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        tvValue.isClickable = true
        tvValue.isFocusable = true
    }
    
    private fun setupWaterfallFftSettings(currentFftSize: Int, currentSampleRate: Int) {
        val radioLeft = findViewById<RadioGroup>(R.id.radioGroupWaterfallFftLeft)
        val radioRight = findViewById<RadioGroup>(R.id.radioGroupWaterfallFftRight)
        
        var selectedIndex = waterfallFftSizeOptions.indexOf(currentFftSize)
        if (selectedIndex < 0) selectedIndex = 3 // 默认2048
        val mid = (waterfallFftSizeOptions.size + 1) / 2
        
        fun addFftRadio(index: Int, size: Int, parent: RadioGroup) {
            val resolution = currentSampleRate.toFloat() / size
            val label = "$size (${String.format("%.0f", resolution)}Hz)"
            val radio = RadioButton(this).apply {
                text = label
                id = index
                isChecked = (index == selectedIndex)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                textSize = 13f
            }
            parent.addView(radio)
        }
        waterfallFftSizeOptions.forEachIndexed { index, size ->
            val group = if (index < mid) radioLeft else radioRight
            addFftRadio(index, size, group)
        }
        
        var leftListener: RadioGroup.OnCheckedChangeListener? = null
        var rightListener: RadioGroup.OnCheckedChangeListener? = null
        
        leftListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) {
                radioRight.setOnCheckedChangeListener(null)
                radioRight.clearCheck()
                radioRight.setOnCheckedChangeListener(rightListener)
                
                currentWaterfallFftSize = waterfallFftSizeOptions[checkedId]
                sharedPreferences.edit().putInt(KEY_WATERFALL_FFT_SIZE, currentWaterfallFftSize).apply()
                updateResult()
            }
        }
        
        rightListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) {
                radioLeft.setOnCheckedChangeListener(null)
                radioLeft.clearCheck()
                radioLeft.setOnCheckedChangeListener(leftListener)
                
                currentWaterfallFftSize = waterfallFftSizeOptions[checkedId]
                sharedPreferences.edit().putInt(KEY_WATERFALL_FFT_SIZE, currentWaterfallFftSize).apply()
                updateResult()
            }
        }
        
        radioLeft.setOnCheckedChangeListener(leftListener)
        radioRight.setOnCheckedChangeListener(rightListener)
    }

    private fun setupWaterfallOverlap(currentOverlap: Float) {
        val seekBar = findViewById<SeekBar>(R.id.seekBarWaterfallOverlap)
        val tvValue = findViewById<TextView>(R.id.tvWaterfallOverlapValue)
        
        val percentInit = (currentOverlap * 100).toInt().coerceIn(0, 100)
        seekBar.progress = percentInit
        tvValue.text = "$percentInit%"
        
        fun applyOverlapFromPercent(percent: Int) {
            val ratio = (percent.coerceIn(0, 100) / 100f)
            currentWaterfallOverlapRatio = ratio
            seekBar.progress = percent.coerceIn(0, 100)
            tvValue.text = "${percent.coerceIn(0, 100)}%"
            sharedPreferences.edit().putFloat(KEY_WATERFALL_OVERLAP_RATIO, currentWaterfallOverlapRatio).apply()
            updateResult()
        }
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvValue.text = "$progress%"
                    applyOverlapFromPercent(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        tvValue.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvValue.text.toString().replace("%", ""))
                hint = "0–100"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.overlap_ratio))
                .setMessage("输入 0–100 的整数")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val p = input.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: (currentWaterfallOverlapRatio * 100).toInt().coerceIn(0, 100)
                    applyOverlapFromPercent(p)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
    
    private fun setupShowInfoPanel(show: Boolean) {
        val switch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchShowInfoPanel)
        switch.isChecked = show
        
        switch.setOnCheckedChangeListener { _, isChecked ->
            currentShowInfoPanel = isChecked
            sharedPreferences.edit().putBoolean(KEY_SHOW_INFO_PANEL, isChecked).apply()
            updateResult()
        }
    }
    
    private fun setupAudioSource(currentSource: Int) {
        val radioGroup = findViewById<RadioGroup>(R.id.radioGroupAudioSource)
        val radioMicRaw = findViewById<RadioButton>(R.id.radioAudioSourceMicRaw)
        val radioMic = findViewById<RadioButton>(R.id.radioAudioSourceMic)
        val radioSystem = findViewById<RadioButton>(R.id.radioAudioSourceSystem)
        
        // 设置当前选中的音频源
        when (currentSource) {
            0 -> radioMicRaw.isChecked = true
            1 -> radioMic.isChecked = true
            2 -> radioSystem.isChecked = true
            else -> radioMicRaw.isChecked = true
        }
        
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentAudioSource = when (checkedId) {
                R.id.radioAudioSourceMicRaw -> 0
                R.id.radioAudioSourceMic -> 1
                R.id.radioAudioSourceSystem -> 2
                else -> 0
            }
            sharedPreferences.edit().putInt(KEY_AUDIO_SOURCE, currentAudioSource).apply()
            updateResult()
        }
    }
    
    private fun setupFFTSettings(currentFFTSize: Int, currentOverlapRatio: Float, currentSampleRate: Int) {
        val radioGroupFFTSizeLeft = findViewById<RadioGroup>(R.id.radioGroupFFTSizeLeft)
        val radioGroupFFTSizeRight = findViewById<RadioGroup>(R.id.radioGroupFFTSizeRight)
        val seekBarOverlap = findViewById<SeekBar>(R.id.seekBarOverlap)
        val tvOverlapValue = findViewById<TextView>(R.id.tvOverlapValue)
        val tvFFTInfo = findViewById<TextView>(R.id.tvFFTInfo)
        
        // 添加FFT大小选项：左列前4项，右列后3项
        var selectedFFTIndex = fftSizeOptions.indexOf(currentFFTSize)
        if (selectedFFTIndex < 0) selectedFFTIndex = 2 // 默认2048
        val mid = (fftSizeOptions.size + 1) / 2 // 4 左，3 右
        
        fun addFftRadio(index: Int, size: Int, parent: RadioGroup) {
            val resolution = currentSampleRate.toFloat() / size
            val label = "$size (${String.format("%.0f", resolution)}Hz)"
            val radioButton = RadioButton(this).apply {
                text = label
                id = index
                isChecked = (index == selectedFFTIndex)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
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
        
        var leftListener: RadioGroup.OnCheckedChangeListener? = null
        var rightListener: RadioGroup.OnCheckedChangeListener? = null
        
        leftListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) {
                // 临时移除右组的监听器，避免清除时触发
                radioGroupFFTSizeRight.setOnCheckedChangeListener(null)
                radioGroupFFTSizeRight.clearCheck()
                radioGroupFFTSizeRight.setOnCheckedChangeListener(rightListener)
                
                this@SettingsActivity.currentFFTSize = fftSizeOptions[checkedId]
                updateFFTSettingsInfo(tvFFTInfo, this@SettingsActivity.currentFFTSize, currentSampleRate, this@SettingsActivity.currentOverlapRatio)
                sharedPreferences.edit().putInt(KEY_FFT_SIZE, this@SettingsActivity.currentFFTSize).apply()
                updateResult()
            }
        }
        
        rightListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) {
                // 临时移除左组的监听器，避免清除时触发
                radioGroupFFTSizeLeft.setOnCheckedChangeListener(null)
                radioGroupFFTSizeLeft.clearCheck()
                radioGroupFFTSizeLeft.setOnCheckedChangeListener(leftListener)
                
                this@SettingsActivity.currentFFTSize = fftSizeOptions[checkedId]
                updateFFTSettingsInfo(tvFFTInfo, this@SettingsActivity.currentFFTSize, currentSampleRate, this@SettingsActivity.currentOverlapRatio)
                sharedPreferences.edit().putInt(KEY_FFT_SIZE, this@SettingsActivity.currentFFTSize).apply()
                updateResult()
            }
        }
        
        radioGroupFFTSizeLeft.setOnCheckedChangeListener(leftListener)
        radioGroupFFTSizeRight.setOnCheckedChangeListener(rightListener)
        
        // 重叠率：滑块 0–100%，右侧显示数值，点击可输入
        val overlapPercentInit = (currentOverlapRatio * 100).toInt().coerceIn(0, 100)
        seekBarOverlap.progress = overlapPercentInit
        tvOverlapValue.text = "$overlapPercentInit%"
        
        fun applyOverlapFromPercent(percent: Int) {
            val ratio = (percent.coerceIn(0, 100) / 100f)
            this@SettingsActivity.currentOverlapRatio = ratio
            seekBarOverlap.progress = percent.coerceIn(0, 100)
            tvOverlapValue.text = "${percent.coerceIn(0, 100)}%"
            sharedPreferences.edit().putFloat(KEY_OVERLAP_RATIO, this@SettingsActivity.currentOverlapRatio).apply()
            val selectedFFTIndex = getSelectedFFTIndex()
            val fftSizeForInfo = if (selectedFFTIndex != -1) fftSizeOptions[selectedFFTIndex] else this@SettingsActivity.currentFFTSize
            updateFFTSettingsInfo(tvFFTInfo, fftSizeForInfo, currentSampleRate, this@SettingsActivity.currentOverlapRatio)
            updateResult()
        }
        
        seekBarOverlap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvOverlapValue.text = "$progress%"
                    applyOverlapFromPercent(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        tvOverlapValue.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvOverlapValue.text.toString().replace("%", ""))
                hint = "0–100"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.overlap_ratio))
                .setMessage("输入 0–100 的整数")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val p = input.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: (currentOverlapRatio * 100).toInt().coerceIn(0, 100)
                    applyOverlapFromPercent(p)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        updateFFTSettingsInfo(tvFFTInfo, currentFFTSize, currentSampleRate, currentOverlapRatio)
    }
    
    private fun updateFFTSettingsInfo(textView: TextView, fftSize: Int, sampleRate: Int, overlapRatio: Float) {
        val resolution = sampleRate.toFloat() / fftSize
        val overlapPercent = (overlapRatio * 100).toInt().coerceIn(0, 100)
        val updateFrequency = if (fftSize > sampleRate / 10) {
            val overlapSize = (fftSize * overlapRatio).toInt()
            val hopSize = maxOf(fftSize - overlapSize, 1)
            val hopTimeMs = (hopSize * 1000f) / sampleRate
            
            // 估算 FFT 计算时间（经验值，基于实际测试）
            // 大 FFT 计算时间显著影响更新率
            val estimatedFftTimeMs = when {
                fftSize >= 32768 -> 300f  // 32768点FFT约需300ms
                fftSize >= 16384 -> 150f  // 16384点FFT约需150ms
                fftSize >= 8192 -> 80f    // 8192点FFT约需80ms
                fftSize >= 4096 -> 40f    // 4096点FFT约需40ms
                else -> 20f               // 小FFT计算快
            }
            
            // UI更新和其他开销估算
            val uiOverheadMs = when {
                fftSize >= 16384 -> 30f   // 大数据量UI更新慢
                else -> 15f
            }
            
            // 总时间 = 数据等待时间 + FFT计算时间 + UI更新时间
            val totalTimeMs = hopTimeMs + estimatedFftTimeMs + uiOverheadMs
            String.format("%.1f", 1000f / totalTimeMs)
        } else {
            "实时"
        }
        textView.text = """
            当前设置:
            FFT大小: $fftSize
            频率分辨率: ${String.format("%.2f", resolution)} Hz
            重叠率: $overlapPercent%
            更新率: $updateFrequency Hz
        """.trimIndent()
    }
    
    
    private fun setupVisualizerSettings() {
        val seekBarBarCount = findViewById<SeekBar>(R.id.seekBarVisualizerBarCount)
        val tvBarCount = findViewById<TextView>(R.id.tvVisualizerBarCount)
        val seekBarSensitivity = findViewById<SeekBar>(R.id.seekBarVisualizerSensitivity)
        val tvSensitivity = findViewById<TextView>(R.id.tvVisualizerSensitivity)
        val seekBarSlope = findViewById<SeekBar>(R.id.seekBarVisualizerSlope)
        val tvSlope = findViewById<TextView>(R.id.tvVisualizerSlope)
        val seekBarBarGap = findViewById<SeekBar>(R.id.seekBarVisualizerBarGap)
        val tvBarGap = findViewById<TextView>(R.id.tvVisualizerBarGap)
        val switchPeakHold = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchVisualizerPeakHold)
        val switchGradient = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchVisualizerGradient)
        
        // 频段数：8~64，progress = barCount - 8
        seekBarBarCount.progress = (currentVisualizerBarCount - 8).coerceIn(0, 56)
        tvBarCount.text = "${currentVisualizerBarCount}"
        
        seekBarBarCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val barCount = progress + 8
                tvBarCount.text = "$barCount"
                if (fromUser) {
                    currentVisualizerBarCount = barCount
                    sharedPreferences.edit().putInt(KEY_VISUALIZER_BAR_COUNT, barCount).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvBarCount.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvBarCount.text.toString())
                hint = "8～64"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.visualizer_bar_count))
                .setMessage("输入 8～64 的整数")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toIntOrNull()?.coerceIn(8, 64) ?: currentVisualizerBarCount
                    currentVisualizerBarCount = v
                    seekBarBarCount.progress = (v - 8).coerceIn(0, 56)
                    tvBarCount.text = "$v"
                    sharedPreferences.edit().putInt(KEY_VISUALIZER_BAR_COUNT, v).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        tvBarCount.isClickable = true
        tvBarCount.isFocusable = true
        
        // 灵敏度：0.5~10.0，progress = (sensitivity - 0.5) * 10
        seekBarSensitivity.progress = ((currentVisualizerSensitivity - 0.5f) * 10).toInt().coerceIn(0, 95)
        tvSensitivity.text = String.format("%.1f", currentVisualizerSensitivity)
        
        seekBarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sensitivity = 0.5f + progress / 10f
                tvSensitivity.text = String.format("%.1f", sensitivity)
                if (fromUser) {
                    currentVisualizerSensitivity = sensitivity
                    sharedPreferences.edit().putFloat(KEY_VISUALIZER_SENSITIVITY, sensitivity).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvSensitivity.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvSensitivity.text.toString())
                hint = "0.5～10"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.visualizer_sensitivity))
                .setMessage("输入 0.5～10 的数值")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(0.5f, 10f) ?: currentVisualizerSensitivity
                    currentVisualizerSensitivity = v
                    seekBarSensitivity.progress = ((v - 0.5f) * 10).toInt().coerceIn(0, 95)
                    tvSensitivity.text = String.format("%.1f", v)
                    sharedPreferences.edit().putFloat(KEY_VISUALIZER_SENSITIVITY, v).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        tvSensitivity.isClickable = true
        tvSensitivity.isFocusable = true
        
        // 频谱斜率：-12~12，progress = slope + 12
        seekBarSlope.progress = (currentVisualizerSlope + 12).toInt().coerceIn(0, 24)
        tvSlope.text = "${currentVisualizerSlope.toInt()}"
        
        seekBarSlope.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val slope = progress - 12f
                tvSlope.text = "${slope.toInt()}"
                if (fromUser) {
                    currentVisualizerSlope = slope
                    sharedPreferences.edit().putFloat(KEY_VISUALIZER_SLOPE, slope).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvSlope.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvSlope.text.toString())
                hint = "-12～12"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.visualizer_slope))
                .setMessage("输入 -12～12 的整数")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toIntOrNull()?.coerceIn(-12, 12) ?: currentVisualizerSlope.toInt()
                    currentVisualizerSlope = v.toFloat()
                    seekBarSlope.progress = (v + 12).coerceIn(0, 24)
                    tvSlope.text = "$v"
                    sharedPreferences.edit().putFloat(KEY_VISUALIZER_SLOPE, v.toFloat()).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        tvSlope.isClickable = true
        tvSlope.isFocusable = true
        
        // 条间距：0.1~0.5，progress = (gap - 0.1) * 100
        seekBarBarGap.progress = ((currentVisualizerBarGap - 0.1f) * 100).toInt().coerceIn(0, 40)
        tvBarGap.text = "${(currentVisualizerBarGap * 100).toInt()}%"
        
        seekBarBarGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gap = 0.1f + progress / 100f
                tvBarGap.text = "${(gap * 100).toInt()}%"
                if (fromUser) {
                    currentVisualizerBarGap = gap
                    sharedPreferences.edit().putFloat(KEY_VISUALIZER_BAR_GAP, gap).apply()
                    updateResult()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        tvBarGap.setOnClickListener {
            val input = EditText(this).apply {
                setText(tvBarGap.text.toString().replace("%", ""))
                hint = "10～50"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.visualizer_bar_gap))
                .setMessage("输入 10～50 的整数（%）")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val pct = input.text.toString().trim().toIntOrNull()?.coerceIn(10, 50) ?: (currentVisualizerBarGap * 100).toInt().coerceIn(10, 50)
                    val v = pct / 100f
                    currentVisualizerBarGap = v
                    seekBarBarGap.progress = ((v - 0.1f) * 100).toInt().coerceIn(0, 40)
                    tvBarGap.text = "${pct}%"
                    sharedPreferences.edit().putFloat(KEY_VISUALIZER_BAR_GAP, v).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        tvBarGap.isClickable = true
        tvBarGap.isFocusable = true
        
        // 峰值保持开关
        switchPeakHold.isChecked = currentVisualizerPeakHold
        switchPeakHold.setOnCheckedChangeListener { _, isChecked ->
            currentVisualizerPeakHold = isChecked
            sharedPreferences.edit().putBoolean(KEY_VISUALIZER_PEAK_HOLD, isChecked).apply()
            updateResult()
        }
        
        // 渐变色开关
        switchGradient.isChecked = currentVisualizerGradient
        switchGradient.setOnCheckedChangeListener { _, isChecked ->
            currentVisualizerGradient = isChecked
            sharedPreferences.edit().putBoolean(KEY_VISUALIZER_GRADIENT, isChecked).apply()
            updateResult()
        }
    }
    
    private fun setupSoundLevelMeterSettings() {
        val toggleWeighting = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleSlmWeighting)
        val toggleResponseTime = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleSlmResponseTime)
        val tvCalibration = findViewById<TextView>(R.id.tvSlmCalibration)
        val switchShowSPL = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSlmShowSPL)
        
        // 加权类型选择
        val weightingId = when (currentSlmWeighting) {
            0 -> R.id.btnSlmWeightingA
            1 -> R.id.btnSlmWeightingC
            2 -> R.id.btnSlmWeightingZ
            else -> R.id.btnSlmWeightingFlat
        }
        toggleWeighting.check(weightingId)
        
        toggleWeighting.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSlmWeighting = when (checkedId) {
                    R.id.btnSlmWeightingA -> 0
                    R.id.btnSlmWeightingC -> 1
                    R.id.btnSlmWeightingZ -> 2
                    else -> 3
                }
                sharedPreferences.edit().putInt(KEY_SLM_WEIGHTING, currentSlmWeighting).apply()
                updateResult()
            }
        }
        
        // 响应时间选择
        val responseId = if (currentSlmResponseTime == 0) R.id.btnSlmResponseFast else R.id.btnSlmResponseSlow
        toggleResponseTime.check(responseId)
        
        toggleResponseTime.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSlmResponseTime = if (checkedId == R.id.btnSlmResponseFast) 0 else 1
                sharedPreferences.edit().putInt(KEY_SLM_RESPONSE_TIME, currentSlmResponseTime).apply()
                updateResult()
            }
        }
        
        // 校准值显示与点击编辑
        tvCalibration.text = "%.0f dB".format(currentSlmCalibrationOffset)
        tvCalibration.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                setText("%.0f".format(currentSlmCalibrationOffset))
                hint = "70 ~ 120"
                setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                setPadding(48, 32, 48, 32)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.slm_calibration))
                .setMessage(getString(R.string.slm_calibration_guide_text))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().toFloatOrNull()?.coerceIn(50f, 130f) ?: currentSlmCalibrationOffset
                    currentSlmCalibrationOffset = v
                    tvCalibration.text = "%.0f dB".format(v)
                    sharedPreferences.edit().putFloat(KEY_SLM_CALIBRATION_OFFSET, v).apply()
                    updateResult()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        // 显示 SPL 开关
        switchShowSPL.isChecked = currentSlmShowSPL
        switchShowSPL.setOnCheckedChangeListener { _, isChecked ->
            currentSlmShowSPL = isChecked
            sharedPreferences.edit().putBoolean(KEY_SLM_SHOW_SPL, isChecked).apply()
            updateResult()
        }
    }
    
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}

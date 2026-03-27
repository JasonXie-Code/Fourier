package com.fourier.audioanalyzer.view

import android.content.Context
import android.graphics.*
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.fourier.audioanalyzer.R
import com.fourier.audioanalyzer.util.AsyncLog
import com.fourier.audioanalyzer.util.DebugLog
import com.fourier.audioanalyzer.util.DebugLog.Tag
import kotlin.math.*

/**
 * 音频可视化视图
 * 支持频谱显示和波形示波器显示
 * 双指捏合可缩放横坐标或纵坐标（横向捏合缩横轴，纵向捏合缩纵轴），双击归位
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private var spectrumData: FloatArray? = null
    private var waveformData: ShortArray? = null
    private var waveformTotalSamples: Long = 0L  // 累计样本数，用于稳定采样网格
    private var lastWaveformTotalSamples: Long = 0L  // 上次绘制时的累计样本数，用于检测数据变化
    private var peakFrequencies: List<Pair<Float, Float>> = emptyList()
    /** 波形 Path 复用，避免每帧分配，减轻 GC 压力 */
    private val waveformPath = Path()
    /** 绘制工作区复用，避免在 drawWaveform 热路径重复分配数组 */
    private var largeYMaxWorkspace = FloatArray(0)
    private var largeYMinWorkspace = FloatArray(0)
    private var largeNormMaxWorkspace = FloatArray(0)
    private var largeNormMinWorkspace = FloatArray(0)
    private var largeXWorkspace = FloatArray(0)
    private var firSrcMinWorkspace = FloatArray(0)
    private var firSrcMaxWorkspace = FloatArray(0)
    private var smallXWorkspace = FloatArray(0)
    private var smallYWorkspace = FloatArray(0)
    /** 上次波形数据更新时间（纳秒），用于时间插值实现匀速滚动 */
    private var lastWaveformUpdateNs: Long = 0L
    /** 是否有新数据需要绘制 */
    private var hasNewWaveformData: Boolean = false
    /** 避免同一帧内重复 postOnAnimation 导致无效刷新 */
    private var frameInvalidatePosted = false

    private fun requestFrameInvalidate() {
        if (frameInvalidatePosted) return
        frameInvalidatePosted = true
        postOnAnimation {
            frameInvalidatePosted = false
            invalidate()
        }
    }

    private fun ensureLargeWaveformWorkspace(size: Int) {
        if (largeYMaxWorkspace.size < size) largeYMaxWorkspace = FloatArray(size)
        if (largeYMinWorkspace.size < size) largeYMinWorkspace = FloatArray(size)
        if (largeNormMaxWorkspace.size < size) largeNormMaxWorkspace = FloatArray(size)
        if (largeNormMinWorkspace.size < size) largeNormMinWorkspace = FloatArray(size)
        if (largeXWorkspace.size < size) largeXWorkspace = FloatArray(size)
        if (firSrcMinWorkspace.size < size) firSrcMinWorkspace = FloatArray(size)
        if (firSrcMaxWorkspace.size < size) firSrcMaxWorkspace = FloatArray(size)
    }

    private fun ensureSmallWaveformWorkspace(size: Int) {
        if (smallXWorkspace.size < size) smallXWorkspace = FloatArray(size)
        if (smallYWorkspace.size < size) smallYWorkspace = FloatArray(size)
    }
    
    // 日志节流：频繁输出的日志每2秒才输出一次
    // 日志节流：每2秒最多输出一次，通过 AsyncLog 在后台执行 I/O 不阻塞主线程
    private var lastLogTimeMs: Long = 0
    private var lastJankLogTimeMs: Long = 0
    private var lastWaveformCoverageLogTimeMs: Long = 0
    private var lastWaveformCoverageCriticalLogTimeMs: Long = 0
    private var lastPanOffsetSyncLogTimeMs: Long = 0
    private var panOffsetSyncCount: Int = 0
    private var panOffsetSyncMaxDiff: Int = 0
    private var panOffsetSyncLastDataPanOffset: Int = 0
    private var panOffsetSyncLastUserPanOffset: Int = 0
    private var panOffsetSyncLastDiff: Int = 0
    private val logThrottleIntervalMs = 2000L
    private val coverageCriticalLogIntervalMs = 5000L
    private fun shouldLog(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLogTimeMs >= logThrottleIntervalMs) {
            lastLogTimeMs = now
            return true
        }
        return false
    }
    private fun shouldLogJank(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastJankLogTimeMs >= logThrottleIntervalMs) {
            lastJankLogTimeMs = now
            return true
        }
        return false
    }
    /**
     * 波形覆盖诊断专用节流：
     * - 普通覆盖问题：沿用全局 2s
     * - 右侧空白等关键问题：5s 输出一次，避免逐帧刷屏
     */
    private fun shouldLogWaveformCoverage(isCritical: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (isCritical) {
            if (now - lastWaveformCoverageCriticalLogTimeMs >= coverageCriticalLogIntervalMs) {
                lastWaveformCoverageCriticalLogTimeMs = now
                return true
            }
            return false
        }
        if (now - lastWaveformCoverageLogTimeMs >= logThrottleIntervalMs) {
            lastWaveformCoverageLogTimeMs = now
            return true
        }
        return false
    }
    /**
     * PanOffset 同步异常聚合日志：
     * - 将短时间内的连续不一致合并成一条日志，避免高频刷屏
     * - 保留次数、最大差异和最近一次差异，便于诊断
     */
    private fun recordPanOffsetSyncDiff(dataPanOffset: Int, userPanOffset: Int, diffSamples: Int) {
        if (diffSamples <= 100) return
        panOffsetSyncCount++
        if (diffSamples > panOffsetSyncMaxDiff) panOffsetSyncMaxDiff = diffSamples
        panOffsetSyncLastDataPanOffset = dataPanOffset
        panOffsetSyncLastUserPanOffset = userPanOffset
        panOffsetSyncLastDiff = diffSamples

        val now = System.currentTimeMillis()
        if (now - lastPanOffsetSyncLogTimeMs >= logThrottleIntervalMs) {
            val maxDiffMs = panOffsetSyncMaxDiff / sampleRate.toFloat() * 1000f
            val lastDiffMs = panOffsetSyncLastDiff / sampleRate.toFloat() * 1000f
            Log.w(
                "PanOffsetSync",
                "[AGG] count=$panOffsetSyncCount, maxDiff=${panOffsetSyncMaxDiff} samples (${String.format("%.1f", maxDiffMs)}ms), " +
                    "last=dataPanOffset=$panOffsetSyncLastDataPanOffset, userPanOffset=$panOffsetSyncLastUserPanOffset, " +
                    "diff=$panOffsetSyncLastDiff samples (${String.format("%.1f", lastDiffMs)}ms)"
            )
            lastPanOffsetSyncLogTimeMs = now
            panOffsetSyncCount = 0
            panOffsetSyncMaxDiff = 0
        }
    }
    
    /** 横轴缩放 (>1 放大)，供外部显示用；示波器模式下为时间跨度推导值 */
    val currentScaleX: Float get() = if (displayMode == DisplayMode.OSCILLOSCOPE) getOscilloscopeScaleX() ?: 1f else spectrumScaleX
    /** 纵轴缩放 (>1 放大)，供外部显示用 */
    val currentScaleY: Float get() = if (displayMode == DisplayMode.OSCILLOSCOPE) oscilloscopeScaleY else spectrumScaleY
    /** 示波器模式：水平缩放显示用，默认 100ms = 100%，大于 100% 表示可见时间更短（放大） */
    val oscilloscopeHorizontalDisplayPercent: Float?
        get() {
            if (displayMode == DisplayMode.OSCILLOSCOPE) {
                val percent = (oscilloscopeTimeSpanDefaultSec / oscilloscopeVisibleTimeSpanSec) * 100f
                if (shouldLog()) {
                    val def = oscilloscopeTimeSpanDefaultSec
                    val cur = oscilloscopeVisibleTimeSpanSec
                    val pct = percent
                    AsyncLog.d { "oscilloscopeHorizontalDisplayPercent: default=${def}s, current=${cur}s, percent=${String.format("%.1f", pct)}%" }
                }
                return percent
            }
            return null
        }
    /** 调试：统一记录对 oscilloscopeVisibleTimeSpanSec 的写入，便于定位根本原因 */
    private fun logSetTimeSpan(source: String, newValueSec: Float) {
        // 移除高频日志：滑动缩放时每 8ms 调用一次，大量日志 I/O 会导致卡顿
        // 如需调试，可临时取消注释：
        // Log.d("Oscilloscope", "[SET timeSpan] source=$source -> ${newValueSec}s (${newValueSec * 1000}ms)")
    }

    /** 重置示波器横轴为默认 100ms、纵轴为 100%，切换进波形模式时调用 */
    fun resetOscilloscopeToDefault() {
        oscilloscopeVisibleTimeSpanSec = oscilloscopeTimeSpanDefaultSec
        logSetTimeSpan("resetOscilloscopeToDefault", oscilloscopeVisibleTimeSpanSec)
        oscilloscopeScaleY = 1f
        oscilloscopeOffsetY = 0f
        oscilloscopeOffsetSamples = 0  // 重置平移偏移
        onOscilloscopeTimeSpanChanged?.invoke(oscilloscopeVisibleTimeSpanSec)
        AsyncLog.d { "resetOscilloscopeToDefault: done, ignoreNext=$ignoreNextOscilloscopeHorizontalScale" }
        onScaleChanged?.invoke(getOscilloscopeScaleX() ?: 1f, oscilloscopeScaleY)
        invalidate()
    }

    /**
     * 示波器一键 AUTO：自动调整纵向和横向缩放，使波形清晰可见
     * 目标：让波形峰值占据屏幕高度的 80%
     */
    fun autoScaleOscilloscope() {
        val data = waveformData ?: return
        if (data.isEmpty()) return
        
        // 1. 自动调整纵轴 (线性映射)
        // 寻找当前缓冲区内的最大绝对值
        var maxAbs = 0
        for (sample in data) {
            val abs = kotlin.math.abs(sample.toInt())
            if (abs > maxAbs) maxAbs = abs
        }
        
        if (maxAbs > 0) {
            // 波形绘制时：normalizedSample = (sample * gain / Short.MAX_VALUE).coerceIn(-1f, 1f)
            // 然后 canvas.scale(scaleY) 放大
            // 最终屏幕偏移 = normalizedSample * (drawHeight/2) * scaleY
            // 
            // 目标：让峰值占据屏幕高度的 80%（即正负峰值各占 40%）
            // 所以 normalizedSample * scaleY = 0.8
            
            val rawNorm = (maxAbs.toFloat() / Short.MAX_VALUE) * gain
            // 模拟波形绘制时的 coerceIn 限制
            val effectiveNorm = rawNorm.coerceIn(0.001f, 1f)
            // 计算目标缩放：让 effectiveNorm * scaleY = 0.8
            val targetScaleY = 0.8f / effectiveNorm
            oscilloscopeScaleY = targetScaleY.coerceIn(oscilloscopeScaleYMin, oscilloscopeScaleYMax)
            
            Log.d("Oscilloscope", "AUTO: maxAbs=$maxAbs, rawNorm=$rawNorm, effectiveNorm=$effectiveNorm, targetScaleY=$targetScaleY, finalScaleY=$oscilloscopeScaleY")
        } else {
            oscilloscopeScaleY = 1.0f
        }
        
        // 2. 自动调整横轴 (基于触发时估算的周期)
        // 只在当前时间窗口明显不合适时才调整，避免多次按 AUTO 导致时间窗口越来越小
        if (estimatedPeriodSamples > 0) {
            val periodTimeSec = estimatedPeriodSamples.toFloat() / sampleRate
            val currentCycles = oscilloscopeVisibleTimeSpanSec / periodTimeSec
            
            // 只在当前显示的周期数不在 2~8 个范围内时才调整
            if (currentCycles < 2f || currentCycles > 8f) {
                // 目标显示约 4 个完整周期
                val targetTimeSpan = (estimatedPeriodSamples * 4f) / sampleRate
                // 设置最小时间窗口为 2ms，避免时间窗口过小
                oscilloscopeVisibleTimeSpanSec = targetTimeSpan.coerceAtLeast(0.002f)
                onOscilloscopeTimeSpanChanged?.invoke(oscilloscopeVisibleTimeSpanSec)
            }
        } else if (oscilloscopeVisibleTimeSpanSec < 0.002f || oscilloscopeVisibleTimeSpanSec > 0.1f) {
            // 如果没检测到稳定周期，且当前时间窗口不在合理范围内，才重置为默认 10ms
            oscilloscopeVisibleTimeSpanSec = 0.01f
            onOscilloscopeTimeSpanChanged?.invoke(oscilloscopeVisibleTimeSpanSec)
        }
        
        // 3. 重置偏移量，使波形居中且贴合触发点
        oscilloscopeOffsetY = 0f
        oscilloscopeOffsetSamples = 0
        
        onScaleChanged?.invoke(getOscilloscopeScaleX() ?: 1f, oscilloscopeScaleY)
        invalidate()
        
        Log.d("Oscilloscope", "AUTO: scaleY=$oscilloscopeScaleY, timeSpan=$oscilloscopeVisibleTimeSpanSec")
    }
    
    /**
     * 波形统计数据
     * @param peakPositive 正峰值（归一化，0~1）
     * @param peakNegative 负峰值（归一化，-1~0）
     * @param peakToPeak 峰峰值（归一化，0~2）
     * @param rms 有效值/RMS（归一化，0~1）
     */
    data class WaveformStats(
        val peakPositive: Float,
        val peakNegative: Float,
        val peakToPeak: Float,
        val rms: Float
    )
    
    /**
     * 获取当前可见波形的统计信息（峰值、峰峰值、RMS）
     * 基于当前可见的波形数据计算
     * @return WaveformStats 包含峰值、峰峰值和 RMS 值，如果没有数据则返回 null
     */
    fun getWaveformStats(): WaveformStats? {
        val data = waveformData ?: return null
        if (data.isEmpty()) return null
        
        // 获取可见范围的数据
        val visibleLength = getOscilloscopeVisibleLength(data)
        val visibleStart = maxOf(0, data.size - visibleLength - oscilloscopeOffsetSamples)
        val visibleEnd = minOf(data.size, visibleStart + visibleLength)
        
        if (visibleStart >= visibleEnd) return null
        
        var maxValue = Short.MIN_VALUE.toInt()
        var minValue = Short.MAX_VALUE.toInt()
        var sumSquares = 0.0
        var count = 0
        
        for (i in visibleStart until visibleEnd) {
            val sample = data[i].toInt()
            if (sample > maxValue) maxValue = sample
            if (sample < minValue) minValue = sample
            sumSquares += sample.toDouble() * sample
            count++
        }
        
        if (count == 0) return null
        
        // 归一化到 -1 ~ 1 范围，并应用增益
        val normalizer = Short.MAX_VALUE.toFloat()
        val peakPositive = (maxValue / normalizer) * gain
        val peakNegative = (minValue / normalizer) * gain
        val peakToPeak = ((maxValue - minValue) / normalizer) * gain
        val rms = (sqrt(sumSquares / count).toFloat() / normalizer) * gain
        
        return WaveformStats(
            peakPositive = peakPositive.coerceIn(-1f, 1f),
            peakNegative = peakNegative.coerceIn(-1f, 1f),
            peakToPeak = peakToPeak.coerceIn(0f, 2f),
            rms = rms.coerceIn(0f, 1f)
        )
    }
    
    /**
     * 自动设置触发电平：基于当前信号的最大振幅设置合适的触发电平
     * 触发电平设置为最大振幅的约 50%（-6dB）
     * @return 设置的触发电平（dB）
     */
    fun autoSetTriggerLevel(): Float {
        val data = waveformData ?: return oscilloscopeTriggerLevelDb
        if (data.isEmpty()) return oscilloscopeTriggerLevelDb
        
        // 找到最大振幅
        var maxAbs = 0
        for (sample in data) {
            val abs = kotlin.math.abs(sample.toInt())
            if (abs > maxAbs) maxAbs = abs
        }
        
        if (maxAbs > 0) {
            // 将振幅转换为 dB
            val normalizedMax = maxAbs.toFloat() / Short.MAX_VALUE
            val maxDb = 20 * kotlin.math.log10(normalizedMax)
            // 设置触发电平为最大振幅的约 50%（-6dB）
            val triggerDb = (maxDb - 6f).coerceIn(-90f, 0f)
            oscilloscopeTriggerLevelDb = triggerDb
            Log.d("Oscilloscope", "AUTO Trigger: maxAbs=$maxAbs, maxDb=$maxDb, triggerDb=$triggerDb")
            return triggerDb
        }
        
        return oscilloscopeTriggerLevelDb
    }
    
    /** 捏合/双击导致缩放变化时回调，用于右上角显示水平/垂直缩放比例 */
    var onScaleChanged: ((scaleX: Float, scaleY: Float) -> Unit)? = null
    /** 示波器横轴时间跨度变化时回调（秒），用于外部更新波形缓冲采样点数，确保显示精确 */
    var onOscilloscopeTimeSpanChanged: ((visibleTimeSpanSec: Float) -> Unit)? = null
    /** 示波器平移偏移变化时回调（样本数），用于外部显示“返回最新”按钮等 UI 元素 */
    var onOscilloscopePanChanged: ((offsetSamples: Int) -> Unit)? = null
    /** 频谱模式：缩放与平移（独立于示波器） */
    private var spectrumScaleX = 1f
    private var spectrumScaleY = 1f
    private var spectrumOffsetX = 0f
    private var spectrumOffsetY = 0f
    /** 调试：帧计数器，用于定期输出日志 */
    private var spectrumDebugFrameCounter = 0L
    /** 频谱横轴：true = 以右边缘为锚点（左滑缩放后），false = 以左边缘为锚点（右滑缩放后） */
    private var spectrumAnchorRight = false
    /** 示波器模式：纵轴缩放与平移（横轴固定左为 0，独立于频谱） */
    private var oscilloscopeScaleY = 1f
    private var oscilloscopeOffsetY = 0f
    /** 示波器模式：横轴平移偏移（样本数），正值表示查看更早的历史数据 */
    var oscilloscopeOffsetSamples = 0
        set(value) {
            // 统一在此处夹紧范围并通知外部 UI
            val clamped = value.coerceIn(0, oscilloscopeMaxOffsetSamples)
            field = clamped
            onOscilloscopePanChanged?.invoke(clamped)
        }
    /** 示波器最大可平移的样本数（由外部缓冲区大小决定） */
    var oscilloscopeMaxOffsetSamples = 0
    /** 缩放灵敏度：0.5 = 低灵敏度，1.0 = 中等，2.0 = 高灵敏度 */
    var scaleSensitivity = 1.0f
    
    /** 频谱纵轴最小缩放：0.5 使可见范围最大约 120dB */
    private val minScale = 0.5f
    private val maxScale = 16f  // 频谱纵轴最大 1600%
    /** 频谱 Y 轴边界保护：顶部 0dB 和底部 -120dB 距屏幕边缘保持此 dB 的余量，防止边界进入画面 */
    private val DB_BOUNDARY_MARGIN = 1f
    /** 示波器纵轴最大缩放，允许放大到 100000%（观察微弱信号，如 -80dB 约 0.01% 满量程） */
    private val oscilloscopeScaleYMax = 1000f
    /** 示波器纵轴最小缩放，允许缩小到能看到 0 dB（约 1/316） */
    private val oscilloscopeScaleYMin = 0.003f
    /** 示波器纵轴边界：0dB 对应的振幅值（允许看到 0dB 位置） */
    private val oscilloscopeMaxAmplitude = 1.0f  // 0dB = 振幅 1.0
    
    /** 示波器横轴：整个屏幕默认显示 100ms（0.1 秒） */
    private val oscilloscopeTimeSpanDefaultSec = 0.1f  // 100ms
    /** 示波器横轴：最小时间跨度 1ms */
    private val oscilloscopeTimeSpanMinSec = 0.001f  // 1ms
    /**
     * 示波器横轴：最大时间跨度
     *
     * 说明：
     * - 缓冲区在 MainActivity 中始终保留最近 60 秒波形数据
     * - 这里将可见时间窗口的上限从 10 秒放大到 60 秒，方便一次浏览完整 60 秒历史
     */
    private val oscilloscopeTimeSpanMaxSec = 60f
    /** 示波器横轴：当前窗口显示的时间跨度（秒），捏合横轴时修改 */
    var oscilloscopeVisibleTimeSpanSec = oscilloscopeTimeSpanDefaultSec
        set(value) {
            field = value.coerceIn(oscilloscopeTimeSpanMinSec, oscilloscopeTimeSpanMaxSec)
        }
    /** 刚切换到示波器时忽略下一次水平缩放，避免“手势延续”把刚重置的 20ms 立刻改掉（根本原因） */
    private var ignoreNextOscilloscopeHorizontalScale = false
    
    /** 双指手势起始间距，用于判断横向/纵向缩放 */
    private var initialSpanX = 0f
    private var initialSpanY = 0f
    /** 捏合缩放手势进行中，用于阻止 onScroll 同时修改偏移 */
    private var isScaleGestureActive = false
    /** 手动平移中：用于在拖动时暂时关闭触发重定位，避免波形相对网格打滑 */
    private var isOscilloscopeManualPanning = false
    
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            initialSpanX = detector.currentSpanX
            initialSpanY = detector.currentSpanY
            isScaleGestureActive = true
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaleGestureActive = false
            // 清除忽略标志
            if (ignoreNextOscilloscopeHorizontalScale) {
                ignoreNextOscilloscopeHorizontalScale = false
            }
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            
            // 统一使用平方关系让缩放更灵敏
            val effectiveFactor = factor.toDouble().pow(3.0).toFloat()  // 立方关系，更灵敏
            
            if (initialSpanX >= initialSpanY) {
                // 横向缩放
                if (displayMode == DisplayMode.OSCILLOSCOPE) {
                    // 根本原因修复：忽略整个手势序列，直到 SCALE_END
                    if (ignoreNextOscilloscopeHorizontalScale) {
                        val f = factor
                        val ts = oscilloscopeVisibleTimeSpanSec
                        AsyncLog.d { "[IGNORE] skipping horizontal scale in ignored gesture sequence (factor=$f), timeSpan unchanged=${ts}s" }
                        invalidate()
                        return true
                    }
                    oscilloscopeVisibleTimeSpanSec = (oscilloscopeVisibleTimeSpanSec / effectiveFactor)
                        .coerceIn(oscilloscopeTimeSpanMinSec, oscilloscopeTimeSpanMaxSec)
                    logSetTimeSpan("onScale(horizontal)", oscilloscopeVisibleTimeSpanSec)
                    onOscilloscopeTimeSpanChanged?.invoke(oscilloscopeVisibleTimeSpanSec)
                    onScaleChanged?.invoke(getOscilloscopeScaleX() ?: 1f, oscilloscopeScaleY)
                } else {
                    // 频谱双指横轴缩放：以双指中点 focusX 为锚点，保持焦点处频率不动
                    val focusX = detector.focusX
                    val oldScaleX = spectrumScaleX
                    val newScaleX = (spectrumScaleX * effectiveFactor).coerceIn(1f, maxScale)
                    val drawW = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
                    if (newScaleX != oldScaleX) {
                        // 坐标公式：screenX = paddingLeft + spectrumOffsetX + scaleX × (contentX − paddingLeft)
                        // 逆变换：contentX = paddingLeft + (screenX − paddingLeft − spectrumOffsetX) / scaleX
                        val pl = paddingLeft.toFloat()
                        val contentXAtFocus = pl + (focusX - pl - spectrumOffsetX) / oldScaleX
                        val newOffsetX = focusX - pl - newScaleX * (contentXAtFocus - pl)
                        spectrumScaleX = newScaleX
                        val minOffsetX = drawW * (1f - spectrumScaleX)
                        val oldOffsetX = spectrumOffsetX
                        spectrumOffsetX = newOffsetX.coerceIn(minOffsetX, 0f)
                        DebugLog.d(Tag.GESTURE, 200L) {
                            "频谱X缩放: focusX=${"%.1f".format(focusX)} " +
                            "scale ${"%.3f".format(oldScaleX)}→${"%.3f".format(spectrumScaleX)} " +
                            "offset ${"%.1f".format(oldOffsetX)}→${"%.1f".format(spectrumOffsetX)} " +
                            "(rawFactor=${"%.4f".format(factor)} effFactor=${"%.4f".format(effectiveFactor)})"
                        }
                    }
                    onScaleChanged?.invoke(spectrumScaleX, spectrumScaleY)
                }
            } else {
                // 纵向缩放
                if (displayMode == DisplayMode.OSCILLOSCOPE) {
                    val oldScaleY = oscilloscopeScaleY
                    oscilloscopeScaleY = (oscilloscopeScaleY * effectiveFactor).coerceIn(oscilloscopeScaleYMin, oscilloscopeScaleYMax)
                    // 缩放后需要调整偏移，确保 +3dB 不能进入屏幕内
                    if (oscilloscopeScaleY != oldScaleY) {
                        val drawH = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
                        val offsetYMin = (1f - oscilloscopeMaxAmplitude * oscilloscopeScaleY) * drawH / 2f
                        val offsetYMax = (oscilloscopeMaxAmplitude * oscilloscopeScaleY - 1f) * drawH / 2f
                        if (offsetYMin <= offsetYMax) {
                            oscilloscopeOffsetY = oscilloscopeOffsetY.coerceIn(offsetYMin, offsetYMax)
                        } else {
                            oscilloscopeOffsetY = 0f
                        }
                    }
                    onScaleChanged?.invoke(getOscilloscopeScaleX() ?: 1f, oscilloscopeScaleY)
                } else {
                    // 频谱纵轴缩放
                    val oldScaleY = spectrumScaleY
                    spectrumScaleY = (spectrumScaleY * effectiveFactor).coerceIn(minScale, maxScale)
                    if (spectrumScaleY != oldScaleY) {
                        DebugLog.d(Tag.GESTURE, 200L) {
                            "频谱Y缩放(约束前): scale ${"%.3f".format(oldScaleY)}→${"%.3f".format(spectrumScaleY)}, " +
                            "offsetY=${"%.1f".format(spectrumOffsetY)} " +
                            "(rawFactor=${"%.4f".format(factor)} effFactor=${"%.4f".format(effectiveFactor)})"
                        }
                        clampSpectrumOffsetY("onScale")
                    }
                    onScaleChanged?.invoke(spectrumScaleX, spectrumScaleY)
                }
            }
            invalidate()
            return true
        }
    })
    
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            DebugLog.d(Tag.GESTURE) { "双击重置: mode=$displayMode" }
            if (displayMode == DisplayMode.OSCILLOSCOPE) {
                oscilloscopeOffsetY = 0f
                oscilloscopeOffsetSamples = 0  // 重置平移偏移
                oscilloscopeVisibleTimeSpanSec = oscilloscopeTimeSpanDefaultSec
                logSetTimeSpan("onDoubleTap", oscilloscopeVisibleTimeSpanSec)
                oscilloscopeScaleY = 1f
                onOscilloscopeTimeSpanChanged?.invoke(oscilloscopeVisibleTimeSpanSec)
                onScaleChanged?.invoke(getOscilloscopeScaleX() ?: 1f, oscilloscopeScaleY)
            } else {
                spectrumOffsetX = 0f
                spectrumOffsetY = 0f
                spectrumScaleX = 1f
                spectrumScaleY = 1f
                spectrumAnchorRight = false
                onScaleChanged?.invoke(spectrumScaleX, spectrumScaleY)
            }
            invalidate()
            return true
        }
        
        override fun onLongPress(e: MotionEvent) {
            // 示波器模式下长按：重新激活单次触发
            if (displayMode == DisplayMode.OSCILLOSCOPE && oscilloscopeSingleTriggerMode) {
                rearmSingleTrigger()
                // 触觉反馈
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // 单指滑动：平移视图（同时支持X和Y方向）
            val drawW = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
            val drawH = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
            
            if (displayMode == DisplayMode.OSCILLOSCOPE) {
                isOscilloscopeManualPanning = true
                // 示波器模式：X平移时间轴，Y平移振幅偏移
                // 使用当前可见时间窗口计算滑动比例，让滑动更直观
                val visibleSamples = (sampleRate * oscilloscopeVisibleTimeSpanSec).toInt()
                val samplesPerPixel = visibleSamples.toFloat() / drawW.coerceAtLeast(1f)
                // 向右滑动 (distanceX < 0) = 查看更早的数据（正偏移）
                // 取反 distanceX，让右滑增加偏移（查看更早数据），左滑减少偏移（查看更新数据）
                val deltaSamples = (-distanceX * samplesPerPixel).toInt()
                // 使用属性 setter 统一处理边界与回调
                oscilloscopeOffsetSamples = oscilloscopeOffsetSamples + deltaSamples
                // Y方向平移振幅偏移
                // 限制：+3dB（振幅 ±oscilloscopeMaxAmplitude）不能进入屏幕内
                oscilloscopeOffsetY -= distanceY
                val offsetYMin = (1f - oscilloscopeMaxAmplitude * oscilloscopeScaleY) * drawH / 2f
                val offsetYMax = (oscilloscopeMaxAmplitude * oscilloscopeScaleY - 1f) * drawH / 2f
                if (offsetYMin <= offsetYMax) {
                    oscilloscopeOffsetY = oscilloscopeOffsetY.coerceIn(offsetYMin, offsetYMax)
                } else {
                    // 整个 ±3dB 范围都在屏幕内，固定到中心
                    oscilloscopeOffsetY = 0f
                }
                onScaleChanged?.invoke(getOscilloscopeScaleX() ?: 1f, oscilloscopeScaleY)
            } else {
                // 频谱模式：单指滑动平移视图
                // X 方向平移：捏合缩放期间禁止，避免与 onScale 的锚点计算相互干扰
                if (!isScaleGestureActive) {
                    spectrumOffsetX -= distanceX
                    val minOffsetX = drawW * (1f - spectrumScaleX)
                    spectrumOffsetX = spectrumOffsetX.coerceIn(minOffsetX, 0f)
                }
                
                // Y 方向平移：捏合缩放期间禁止，避免与 onScale 的约束相互干扰
                if (!isScaleGestureActive) {
                    val beforeY = spectrumOffsetY
                    spectrumOffsetY -= distanceY
                    DebugLog.d(Tag.GESTURE, 100L) {
                        "频谱Y滑动(约束前): distanceY=${"%.1f".format(distanceY)}, " +
                        "offsetY: ${"%.1f".format(beforeY)}→${"%.1f".format(spectrumOffsetY)}"
                    }
                    clampSpectrumOffsetY("onScroll")
                }
                
                onScaleChanged?.invoke(spectrumScaleX, spectrumScaleY)
            }
            invalidate()
            return true
        }
    })
    
    /**
     * 限制 spectrumOffsetY 到合法范围：
     * 确保顶部可见 dB 始终 < dB_MAX（0dB）且底部可见 dB 始终 > dB_MIN_EXTENDED（-120dB），
     * 通过 DB_BOUNDARY_MARGIN 将边界推出画面。
     *
     * 坐标关系：
     *   dbOffset = spectrumOffsetY * visibleDbRange / drawHeight
     *   centerDb = spectrumDefaultCenterDb + dbOffset
     *   topDb    = centerDb + halfRange   （需 < dB_MAX - DB_BOUNDARY_MARGIN）
     *   bottomDb = centerDb - halfRange   （需 > dB_MIN_EXTENDED + DB_BOUNDARY_MARGIN）
     *
     * @param caller 调用方名称，用于日志追踪
     */
    private fun clampSpectrumOffsetY(caller: String = "?") {
        val drawH = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val visibleDbRange = 60f / spectrumScaleY
        val halfRange = visibleDbRange / 2f
        // 有效可视范围上限（留出双边 margin 后剩余的 dB 跨度）
        val effectiveTotalRange = kotlin.math.abs(dB_MIN_EXTENDED - dB_MAX) - 2f * DB_BOUNDARY_MARGIN
        val beforeOffset = spectrumOffsetY

        if (visibleDbRange >= effectiveTotalRange) {
            // 可见范围超过有效显示范围，强制归零（onDraw 会把 topDb/bottomDb 固定到带 margin 的全局范围）
            spectrumOffsetY = 0f
            DebugLog.d(Tag.GESTURE, 200L) {
                "[$caller] Y约束(全域): scaleY=${"%.3f".format(spectrumScaleY)}, " +
                "visibleDbRange=${"%.1f".format(visibleDbRange)} >= 有效范围${"%.1f".format(effectiveTotalRange)}, " +
                "offsetY: ${"%.1f".format(beforeOffset)}→0, " +
                "渲染范围将固定为[${"%.1f".format(dB_MIN_EXTENDED + DB_BOUNDARY_MARGIN)}, ${"%.1f".format(dB_MAX - DB_BOUNDARY_MARGIN)}]dB"
            }
        } else {
            // centerDb 的允许范围使得：
            //   topDb    = centerDb + halfRange <= dB_MAX - DB_BOUNDARY_MARGIN
            //   bottomDb = centerDb - halfRange >= dB_MIN_EXTENDED + DB_BOUNDARY_MARGIN
            val centerDbMin = dB_MIN_EXTENDED + halfRange + DB_BOUNDARY_MARGIN
            val centerDbMax = dB_MAX - halfRange - DB_BOUNDARY_MARGIN
            val offsetYMin = (centerDbMin - spectrumDefaultCenterDb) * drawH / visibleDbRange
            val offsetYMax = (centerDbMax - spectrumDefaultCenterDb) * drawH / visibleDbRange
            spectrumOffsetY = spectrumOffsetY.coerceIn(offsetYMin, offsetYMax)
            // 计算约束后对应的实际渲染 topDb/bottomDb，供日志确认
            val dbOffsetAfter = spectrumOffsetY * visibleDbRange / drawH
            val centerDbAfter = spectrumDefaultCenterDb + dbOffsetAfter
            val topDbAfter    = centerDbAfter + halfRange
            val bottomDbAfter = centerDbAfter - halfRange
            DebugLog.d(Tag.GESTURE, 200L) {
                "[$caller] Y约束: scaleY=${"%.3f".format(spectrumScaleY)}, " +
                "offsetY: ${"%.1f".format(beforeOffset)}→${"%.1f".format(spectrumOffsetY)} " +
                "[允许范围 ${"%.1f".format(offsetYMin)}..${"%.1f".format(offsetYMax)}], " +
                "渲染: topDb=${"%.2f".format(topDbAfter)}, bottomDb=${"%.2f".format(bottomDbAfter)}"
            }
        }
    }

    /** 示波器模式下由时间跨度推导的横轴 scaleX（供绘制与标签用）；非示波器或无数据时返回 null */
    private fun getOscilloscopeScaleX(): Float? {
        val data = waveformData ?: return null
        if (data.isEmpty()) return null
        val calculatedVisibleLength = (sampleRate * oscilloscopeVisibleTimeSpanSec).toInt()
        val visibleLength = calculatedVisibleLength.coerceAtLeast(1)
        val actualTimeSpanMs = (visibleLength.toFloat() / sampleRate * 1000f)
        
        val drawWidth = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        
        // 修复：scaleX 直接基于 visibleLength 计算
        // 绘制代码中 x 坐标范围是 [0, visibleLength]，所以 scaleX 也应该基于 visibleLength
        // 之前用 contentWidth（会大于 visibleLength）导致波形不能覆盖完整屏幕
        val scaleX = drawWidth / visibleLength.toFloat()
        
        if (shouldLog()) {
            val span = oscilloscopeVisibleTimeSpanSec
            val calc = calculatedVisibleLength
            val vis = visibleLength
            val ds = data.size
            val dw = drawWidth
            val sx = scaleX
            val actual = actualTimeSpanMs
            AsyncLog.d { "getOscilloscopeScaleX: span=${span}s, calcLen=$calc, visLen=$vis, data.size=$ds, drawWidth=$dw, scaleX=$sx, actualTimeSpan=${String.format("%.2f", actual)}ms" }
        }
        return scaleX
    }
    
    /** 示波器模式下当前可见采样数（由时间跨度决定，不限制为 data.size，不足时左侧显示空白） */
    private fun getOscilloscopeVisibleLength(data: ShortArray): Int {
        val calculatedLength = (sampleRate * oscilloscopeVisibleTimeSpanSec).toInt()
        return calculatedLength.coerceAtLeast(1)
    }
    
    var displayMode: DisplayMode = DisplayMode.SPECTRUM
        set(value) {
            val oldValue = field
            field = value
            // 切换到示波器模式时，重置为默认 100ms，并忽略下一次水平缩放（防止手势延续覆盖）
            if (value == DisplayMode.OSCILLOSCOPE && oldValue != DisplayMode.OSCILLOSCOPE) {
                oscilloscopeVisibleTimeSpanSec = oscilloscopeTimeSpanDefaultSec
                logSetTimeSpan("displayMode->OSCILLOSCOPE", oscilloscopeVisibleTimeSpanSec)
                oscilloscopeScaleY = 1f
                oscilloscopeOffsetY = 0f
                oscilloscopeOffsetSamples = 0  // 重置平移偏移
                ignoreNextOscilloscopeHorizontalScale = true
                onOscilloscopeTimeSpanChanged?.invoke(oscilloscopeVisibleTimeSpanSec)
                val ts = oscilloscopeVisibleTimeSpanSec
                AsyncLog.d { "[IGNORE] set true (displayMode->OSCILLOSCOPE), timeSpan=${ts}s" }
                // 强制立即绘制一次，确保用户看到正确的 100ms
                post {
                    invalidate()
                }
            }
            val old = oldValue
            val v = value
            AsyncLog.d { "displayMode changed: $old -> $v" }
            invalidate()
        }
    
    var scaleMode: ScaleMode = ScaleMode.LINEAR
        set(value) {
            field = value
            invalidate()
        }
    
    var spectrumSlope: Float = 0f // 频谱斜率（dB/octave），范围 -12～12，步进 3，默认 0
        set(value) {
            field = value
            invalidate()
        }
    
    var sampleRate: Int = 44100
    var fftSize: Int = 2048
    var gain: Float = 1.0f
    var showFrequencyMarkers: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    var showPeakDetection: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    
    private val gridLines = 10
    /** 四边留白：左（纵轴标签）、上、右（峰值标签）、下（横轴标签），避免标签被裁切 */
    private val paddingLeft = 72f
    private val paddingTop = 36f
    private val paddingRight = 48f
    private val paddingBottom = 52f

    /** 纵轴 dB 范围：0 dB（顶）～ dB_MIN（底），频谱默认窗口 */
    private val dB_MAX = 0f
    private val dB_MIN = -60f
    /** 频谱模式默认纵轴中心（屏幕垂直中央对应此 dB），使 -80 dB 在中央便于看到内容 */
    private val spectrumDefaultCenterDb = -80f
    /** 频谱可显示的最低 dB（拖动时可看到更低音量） */
    private val dB_MIN_EXTENDED = -120f

    /** 对数横轴最小频率 (Hz)，避免 log(0) */
    private val logScaleMinFreq = 20f

    private fun amplitudeToDb(amplitude: Float): Float {
        if (amplitude <= 0f) return dB_MIN
        return (20f * log10(amplitude.coerceIn(1e-10f, 1f))).coerceIn(dB_MIN, dB_MAX)
    }

    /** 频谱用：不截断在 -60dB，最低到 dB_MIN_EXTENDED */
    private fun amplitudeToDbSpectrum(amplitude: Float): Float {
        if (amplitude <= 0f) return dB_MIN_EXTENDED
        return (20f * log10(amplitude.coerceIn(1e-10f, 1f))).coerceIn(dB_MIN_EXTENDED, dB_MAX)
    }

    /** dB → 内容 Y；dBTop/dBBottom 为当前可见窗口，默认 0～-60 */
    private fun dbToY(db: Float, drawHeight: Float, dBTop: Float = dB_MAX, dBBottom: Float = dB_MIN): Float {
        val range = dBTop - dBBottom
        if (range <= 0f) return paddingTop + drawHeight / 2f
        val t = (db - dBBottom) / range
        return paddingTop + drawHeight * (1f - t.coerceIn(0f, 1f))
    }

    /** 示波器：归一化振幅 [-1,1] → 内容 Y，0 在中心、+1 在顶、-1 在底（波形保持线性形状，轴标 dB） */
    private fun amplitudeToY(normalizedAmplitude: Float, drawHeight: Float): Float {
        val centerY = paddingTop + drawHeight / 2f
        return centerY - normalizedAmplitude.coerceIn(-1f, 1f) * (drawHeight / 2f)
    }

    /** 示波器纵轴：幅度最大 0 dB；默认 100% 缩放时顶部/底部为 -30 dB，中心为 -∞。linear 不压顶在 1，使 0 dB 落在屏幕外、顶/底只显示 -30 dB。 */
    private fun oscilloscopeDbToYTop(db: Float, drawHeight: Float): Float {
        val linear = 10f.pow(db / 20f).coerceAtLeast(0.0f)
        return paddingTop + (1f - linear) * (drawHeight / 2f)
    }
    /** 示波器纵轴：下半区 dB→Y，同上。 */
    private fun oscilloscopeDbToYBottom(db: Float, drawHeight: Float): Float {
        val linear = 10f.pow(db / 20f).coerceAtLeast(0.0f)
        return paddingTop + drawHeight - (1f - linear) * (drawHeight / 2f)
    }
    
    /** 示波器模式线条粗细（1.0～10.0），默认 2.0；频谱模式不受影响 */
    var oscilloscopeStrokeWidthBase: Float = 2f
    /**
     * 大时间窗口（每像素 >= 1 样本）的包络算法：
     * false=RMS 包络（默认，更接近小窗口观感）
     * true=min/max 峰值包络（更强调瞬时峰值，视觉更“粗”）
     */
    var oscilloscopeLargeWindowUsePeakEnvelope: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    /**
     * 大时间窗口显示平滑系数（0~1）：
     * - 越小越稳（抖动更少）但响应更慢
     * - 越大越跟手（响应更快）但更容易抖动
     * 时序对齐修复后，0.5 既快速响应又消除列间噪声
     */
    var oscilloscopeLargeWindowSmoothingAlpha: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.01f, 1f)
            // 参数变化后清空缓存，避免旧平滑状态造成残影
            largeWindowPrevYMin = null
            largeWindowPrevYMax = null
            invalidate()
        }
    /** 大时间窗口是否启用 FIR 抗混叠（先低通，再渲染包络） */
    var oscilloscopeLargeWindowFirEnabled: Boolean = true
        set(value) {
            field = value
            largeWindowPrevYMin = null
            largeWindowPrevYMax = null
            invalidate()
        }
    // 大窗口包络的上一帧缓存（仅用于显示低通，不影响原始数据）
    private var largeWindowPrevYMin: FloatArray? = null
    private var largeWindowPrevYMax: FloatArray? = null
    /** 上帧量化后的显示右边界（绝对样本索引），用于计算列偏移以对齐 EMA 时序 */
    private var largeWindowPrevDisplayEndAbsIdx: Long = Long.MIN_VALUE
    /** 上帧的列宽样本数；interval 发生变化时强制重置 EMA */
    private var largeWindowPrevSamplesPerInterval: Int = 0
    /** drawWaveform 本帧计算出的触发偏移（样本数），供 getOscilloscopeVisibleRange 保证量化条件一致 */
    private var lastDrawnTriggerOffset: Int = 0
    
    // ==================== 可视化模式属性 ====================
    /** 可视化模式：频段数量（8～64），默认 32 */
    var visualizerBarCount: Int = 32
        set(value) {
            field = value.coerceIn(8, 64)
            visualizerBarValues = FloatArray(field)
            visualizerPeakValues = FloatArray(field)
            visualizerPeakHoldTimes = LongArray(field)
            invalidate()
        }
    
    /** 可视化模式：灵敏度（0.5～10.0），默认 1.5（作为整体信号增益） */
    var visualizerSensitivity: Float = 1.5f
        set(value) {
            field = value.coerceIn(0.5f, 10.0f)
            invalidate()
        }
    
    /** 可视化模式：频谱斜率（dB/octave），用于补偿高频衰减，默认 3 */
    var visualizerSlope: Float = 3f
        set(value) {
            field = value.coerceIn(-12f, 12f)
            invalidate()
        }
    
    /** 可视化模式：条间距比例（0.1～0.5），默认 0.2 */
    var visualizerBarGap: Float = 0.2f
        set(value) {
            field = value.coerceIn(0.1f, 0.5f)
            invalidate()
        }
    
    /** 可视化模式：是否显示峰值保持，默认 true */
    var visualizerPeakHold: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    
    /** 可视化模式：是否使用渐变色，默认 true */
    var visualizerGradient: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    
    /** 可视化模式：每个频段的当前值（0～1） */
    private var visualizerBarValues = FloatArray(32)
    
    /** 可视化模式：每个频段的峰值（0～1） */
    private var visualizerPeakValues = FloatArray(32)
    
    /** 可视化模式：峰值保持时间（毫秒） */
    private var visualizerPeakHoldTimes = LongArray(32)
    
    /** 可视化模式：峰值保持时长（毫秒） */
    private val visualizerPeakHoldDurationMs = 1500L
    
    /** 可视化模式：峰值下落速度（每秒） */
    private val visualizerPeakFallSpeed = 0.5f
    
    /** 可视化模式：条形下落速度（每秒） */
    private val visualizerBarFallSpeed = 2.0f
    
    /** 可视化模式：条形画笔 */
    private val visualizerBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    /** 可视化模式：峰值指示画笔 */
    private val visualizerPeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    /** 可视化模式：上次更新时间 */
    private var visualizerLastUpdateTime = 0L
    
    /** 频谱模式网格线粗细（0.5～5.0），默认 1.0 */
    var spectrumGridStrokeWidth: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 5f)
            invalidate()
        }
    
    /** 频谱模式频率标记线粗细（0.5～5.0），默认 1.5 */
    var spectrumMarkerStrokeWidth: Float = 1.5f
        set(value) {
            field = value.coerceIn(0.5f, 5f)
            invalidate()
        }
    
    /** 示波器模式网格线粗细（0.5～5.0），默认 1.0 */
    var oscilloscopeGridStrokeWidth: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 5f)
            invalidate()
        }
    
    /** 示波器模式：是否显示中心线（-∞ dB / 零电平线） */
    var showOscilloscopeCenterLine: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    
    /** 示波器中心线颜色（默认白色） */
    var oscilloscopeCenterLineColor: Int = Color.WHITE
        set(value) {
            field = value
            centerLinePaint.color = value
            invalidate()
        }
    
    /** 示波器中心线粗细（默认 2.0） */
    var oscilloscopeCenterLineWidth: Float = 2f
        set(value) {
            field = value.coerceIn(0.5f, 5f)
            invalidate()
        }
    
    /** 示波器触发同步：启用触发 */
    var oscilloscopeTriggerEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    /** 暂停时临时屏蔽触发重定位（不影响触发线显示） */
    var oscilloscopeTriggerPaused: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    
    /** 示波器触发电平（dB）：-90 ~ 0 dB，会转换为归一化振幅用于触发检测 */
    var oscilloscopeTriggerLevelDb: Float = -30f
        set(value) {
            field = value.coerceIn(-90f, 0f)
            invalidate()
        }
    
    /** 示波器触发模式：0=上升沿, 1=下降沿, 2=双沿 */
    var oscilloscopeTriggerMode: Int = 0
        set(value) {
            field = value.coerceIn(0, 2)
            invalidate()
        }
    
    /** 触发迟滞量（百分比）：1-30%，防止噪声导致的误触发 */
    var oscilloscopeTriggerHysteresis: Float = 5f
        set(value) {
            field = value.coerceIn(1f, 30f)
            invalidate()
        }
    
    /** 触发保持时间模式：true=自动, false=手动 */
    var oscilloscopeTriggerHoldoffAuto: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    
    /** 触发保持时间（毫秒）：手动模式下生效，0.1-10ms */
    var oscilloscopeTriggerHoldoffMs: Float = 1f
        set(value) {
            field = value.coerceIn(0.1f, 10f)
            invalidate()
        }
    
    /** 噪声抑制：启用后对触发检测数据进行平滑 */
    var oscilloscopeTriggerNoiseReject: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    
    /** 触发状态：true=已找到触发点, false=等待触发 */
    var lastTriggerFound: Boolean = false
        private set
    
    /** 上一次成功触发的时间戳（用于 holdoff） */
    private var lastTriggerTimeMs: Long = 0L
    
    /** 上一次成功触发的数据索引（用于触发点跟踪） */
    private var lastTriggerDataIndex: Int = -1
    
    /** 估算的信号周期（采样数），用于自动 holdoff */
    private var estimatedPeriodSamples: Int = 0
    
    /** 周期估算置信度（0-1），用于判断周期是否稳定 */
    private var periodConfidence: Float = 0f
    
    /** 稳定周期计数器：连续多少帧使用了相似的周期 */
    private var stablePeriodCount: Int = 0
    
    /** 上一帧的估算周期，用于检测周期稳定性 */
    private var lastEstimatedPeriod: Int = 0
    
    /** 锁定的触发相位（0-1），当周期稳定时锁定相位以保持波形位置一致 */
    private var lockedTriggerPhase: Float = -1f
    
    /** 周期锁定：连续多少帧使用相似的周期后进入锁定状态 */
    private var periodLockCounter: Int = 0
    private val periodLockThreshold = 5  // 连续5帧周期稳定后锁定
    
    /** 周期稳定性容差（百分比），周期变化在此范围内视为稳定 */
    private val periodStabilityTolerance = 0.15f  // 15%
    
    /** 单次触发模式：启用后只触发一次，然后冻结显示 */
    var oscilloscopeSingleTriggerMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                // 启用单次触发时，激活等待状态
                singleTriggerArmed = true
                singleTriggerFrozen = false
            } else {
                // 关闭单次触发时，清除所有相关状态
                singleTriggerArmed = false
                singleTriggerFrozen = false
                frozenWaveformData = null
            }
            invalidate()
        }
    
    /** 单次触发：是否已激活（等待触发） */
    private var singleTriggerArmed: Boolean = false
    
    /** 单次触发：是否已冻结（触发后停止更新） */
    private var singleTriggerFrozen: Boolean = false
    
    /** 单次触发：冻结时保存的波形数据 */
    private var frozenWaveformData: ShortArray? = null
    
    /** 单次触发：冻结时保存的总样本数 */
    private var frozenTotalSamples: Long = 0L
    
    /** 单次触发状态回调：通知外部触发状态变化 */
    var onSingleTriggerStateChanged: ((armed: Boolean, frozen: Boolean) -> Unit)? = null
    
    /** 重新激活单次触发（长按后调用） */
    fun rearmSingleTrigger() {
        if (oscilloscopeSingleTriggerMode) {
            singleTriggerArmed = true
            singleTriggerFrozen = false
            frozenWaveformData = null
            onSingleTriggerStateChanged?.invoke(true, false)
            invalidate()
        }
    }
    
    /** 获取单次触发是否已冻结 */
    fun isSingleTriggerFrozen(): Boolean = singleTriggerFrozen
    
    /** 将 dB 转换为归一化振幅（0 dB = 1.0, -90 dB ≈ 0.00003） */
    private fun dbToAmplitude(db: Float): Float {
        return 10f.pow(db / 20f)
    }

    /** 波形/频谱线条颜色，可由外部设置；同时影响频谱与示波器模式 */
    var waveformColor: Int
        get() = _waveformColor
        set(value) {
            _waveformColor = value
            invalidate()
        }
    private var _waveformColor: Int
    
    /**
     * 对数据应用简单的滑动平均滤波（用于噪声抑制）
     * @param data 原始数据
     * @param windowSize 滤波窗口大小（奇数）
     * @return 滤波后的数据
     */
    private fun applyNoiseFilter(data: ShortArray, windowSize: Int = 5): ShortArray {
        if (data.size < windowSize) return data
        val halfWindow = windowSize / 2
        val filtered = ShortArray(data.size)
        
        for (i in data.indices) {
            var sum = 0L
            var count = 0
            for (j in maxOf(0, i - halfWindow)..minOf(data.size - 1, i + halfWindow)) {
                sum += data[j]
                count++
            }
            filtered[i] = (sum / count).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return filtered
    }
    
    /**
     * Sin(x)/x 插值（专业示波器标准方法）
     * 
     * 基于奈奎斯特采样定理：如果信号是带限的（最高频率 < 采样率/2），
     * 可以通过 sinc 函数完美重建原始连续波形。
     * 
     * 重建公式：y(t) = Σ sample[n] × sinc(t - n)
     * 其中 sinc(x) = sin(πx) / (πx)，sinc(0) = 1
     * 
     * @param data 采样数据
     * @param exactIdx 精确的浮点索引位置
     * @param gain 增益系数
     * @return 插值后的值
     */
    private fun sincInterpolate(data: ShortArray, exactIdx: Float, gain: Float): Float {
        if (data.isEmpty()) return 0f
        
        // 超出数据范围时返回 0
        if (exactIdx < 0 || exactIdx >= data.size) return 0f
        
        val intIdx = exactIdx.toInt()
        val frac = exactIdx - intIdx
        
        // 如果正好落在采样点上，直接返回
        if (kotlin.math.abs(frac) < 0.0001f) {
            return data[intIdx].toFloat() * gain
        }
        
        // sinc 插值窗口大小（两侧各取多少个采样点）
        // 窗口越大越精确，但计算量也越大
        // 专业示波器通常用 8-16 个点，这里用 8 个点平衡精度和性能
        val windowSize = 8
        
        var result = 0.0
        var weightSum = 0.0
        
        // 对窗口内的每个采样点进行加权求和
        for (n in -windowSize..windowSize) {
            val sampleIdx = intIdx + n
            if (sampleIdx < 0 || sampleIdx >= data.size) continue
            
            val x = frac - n  // 距离当前采样点的距离
            
            // 计算 sinc(x) = sin(πx) / (πx)
            // 使用 Lanczos 窗函数改进，减少振铃效应
            val sincValue = if (kotlin.math.abs(x) < 0.0001f) {
                1.0
            } else {
                val pix = Math.PI * x
                val sinc = kotlin.math.sin(pix) / pix
                
                // Lanczos 窗：sinc(x/a) 其中 a = windowSize
                // 这样可以减少 sinc 插值的振铃（Gibbs 现象）
                val lanczosArg = x / windowSize
                val lanczos = if (kotlin.math.abs(lanczosArg) < 1.0) {
                    val piLanczos = Math.PI * lanczosArg
                    kotlin.math.sin(piLanczos) / piLanczos
                } else {
                    0.0
                }
                sinc * lanczos
            }
            
            result += data[sampleIdx].toDouble() * sincValue
            weightSum += sincValue
        }
        
        // 归一化（理论上 weightSum 应该接近 1，但由于窗口截断可能略有偏差）
        return if (weightSum > 0.001) {
            (result / weightSum * gain).toFloat()
        } else {
            data[intIdx].toFloat() * gain
        }
    }
    
    /**
     * 使用自相关函数估算信号周期（适用于非正弦波）
     * 自相关比边沿检测更鲁棒，能处理方波、三角波等复杂波形
     * 
     * @param data 波形数据
     * @param searchStart 搜索起始位置
     * @param searchEnd 搜索结束位置
     * @param minPeriod 最小周期（采样数）
     * @param maxPeriod 最大周期（采样数）
     * @return Pair<周期, 置信度(0-1)>，若未找到有效周期返回 Pair(0, 0)
     */
    private fun estimatePeriodByAutocorrelation(
        data: ShortArray, 
        searchStart: Int, 
        searchEnd: Int,
        minPeriod: Int = 10,
        maxPeriod: Int = 8000
    ): Pair<Int, Float> {
        val safeStart = searchStart.coerceIn(0, data.size - 1)
        val safeEnd = searchEnd.coerceIn(safeStart + 1, data.size)
        val length = safeEnd - safeStart
        
        // 需要足够的数据来估算周期（至少 2 个周期 + 余量）
        if (length < minPeriod * 3) return Pair(0, 0f)
        
        val effectiveMaxPeriod = minOf(maxPeriod, length / 2)
        if (effectiveMaxPeriod < minPeriod) return Pair(0, 0f)
        
        // 计算零延迟的自相关（用于归一化）
        var autoCorr0 = 0.0
        for (i in safeStart until safeEnd) {
            val v = data[i].toDouble()
            autoCorr0 += v * v
        }
        if (autoCorr0 < 1e-10) return Pair(0, 0f)  // 信号太弱
        
        // 在 [minPeriod, maxPeriod] 范围内搜索自相关峰值
        // 使用粗搜索 + 细搜索加速
        var bestLag = 0
        var bestCorr = -1.0
        
        // 粗搜索：每 4 个样本采样一次
        val coarseStep = 4
        for (lag in minPeriod until effectiveMaxPeriod step coarseStep) {
            var corr = 0.0
            val end = minOf(safeEnd, safeStart + length - lag)
            for (i in safeStart until end) {
                corr += data[i].toDouble() * data[i + lag].toDouble()
            }
            // 归一化
            corr /= autoCorr0
            
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        
        // 细搜索：在粗搜索结果附近精确查找
        val fineStart = maxOf(minPeriod, bestLag - coarseStep)
        val fineEnd = minOf(effectiveMaxPeriod, bestLag + coarseStep + 1)
        for (lag in fineStart until fineEnd) {
            var corr = 0.0
            val end = minOf(safeEnd, safeStart + length - lag)
            for (i in safeStart until end) {
                corr += data[i].toDouble() * data[i + lag].toDouble()
            }
            corr /= autoCorr0
            
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        
        // 置信度 = 归一化自相关值（0-1）
        // 对于完美周期信号，自相关峰值接近 1
        val confidence = bestCorr.coerceIn(0.0, 1.0).toFloat()
        
        return Pair(bestLag, confidence)
    }
    
    /**
     * 查找触发点：根据触发模式在数据中寻找触发位置（专业示波器级实现）
     * 
     * 增强功能：
     * 1. 可调迟滞量 —— 根据 oscilloscopeTriggerHysteresis 设置（1-30%）
     * 2. 噪声抑制 —— 可选的滑动平均滤波
     * 3. 自相关周期估算 —— 对非正弦波更准确
     * 4. 周期锁定机制 —— 当检测到稳定周期后，强制使用周期整数倍的 holdoff
     * 5. 相位锁定 —— 稳定后锁定触发相位，保持波形位置一致
     * 
     * @param data 波形数据
     * @param triggerLevelDb 触发电平（dB，-90 ~ 0）
     * @param searchStart 搜索起始位置
     * @param searchEnd 搜索结束位置
     * @param mode 触发模式：0=上升沿, 1=下降沿, 2=双沿
     * @return 触发点索引，若未找到则返回 -1
     */
    private fun findTriggerPoint(data: ShortArray, triggerLevelDb: Float, searchStart: Int, searchEnd: Int, mode: Int = 0): Int {
        if (data.isEmpty() || searchStart >= searchEnd - 1) return -1
        
        val safeStart = searchStart.coerceIn(0, data.size - 2)
        val safeEnd = searchEnd.coerceIn(safeStart + 1, data.size)
        if (safeStart >= safeEnd - 1) return -1
        
        // 噪声抑制：对触发检测数据应用滤波
        val effectiveData = if (oscilloscopeTriggerNoiseReject) {
            applyNoiseFilter(data, 5)
        } else {
            data
        }
        
        val maxAmplitude = Short.MAX_VALUE.toFloat()
        val triggerAmplitude = dbToAmplitude(triggerLevelDb)
        
        // 触发阈值需要考虑 gain（增益）
        val triggerThreshold = (triggerAmplitude * maxAmplitude / gain).toInt()
        
        // 使用用户设置的迟滞量（百分比转换为实际值）
        val hysteresisPercent = oscilloscopeTriggerHysteresis / 100f
        val hysteresis = (triggerThreshold * hysteresisPercent).toInt()
            .coerceAtLeast((0.005f * maxAmplitude / gain).toInt())  // 最小 0.5% 满量程
        val armThresholdRising = triggerThreshold - hysteresis
        val armThresholdFalling = triggerThreshold + hysteresis
        
        // 收集所有触发点
        val triggerPoints = mutableListOf<Int>()
        var armedRising = false
        var armedFalling = false
        
        // 初始化状态
        val firstSample = effectiveData[safeStart].toInt()
        if (firstSample < armThresholdRising) armedRising = true
        if (firstSample > armThresholdFalling) armedFalling = true
        
        for (i in safeStart until safeEnd - 1) {
            val current = effectiveData[i].toInt()
            val next = effectiveData[i + 1].toInt()
            
            when (mode) {
                0 -> { // 上升沿
                    if (current < armThresholdRising) armedRising = true
                    if (armedRising && current < triggerThreshold && next >= triggerThreshold) {
                        triggerPoints.add(i + 1)
                        armedRising = false
                    }
                }
                1 -> { // 下降沿
                    if (current > armThresholdFalling) armedFalling = true
                    if (armedFalling && current > triggerThreshold && next <= triggerThreshold) {
                        triggerPoints.add(i + 1)
                        armedFalling = false
                    }
                }
                2 -> { // 双沿
                    if (current < armThresholdRising) armedRising = true
                    if (current > armThresholdFalling) armedFalling = true
                    
                    if (armedRising && current < triggerThreshold && next >= triggerThreshold) {
                        triggerPoints.add(i + 1)
                        armedRising = false
                    }
                    if (armedFalling && current > triggerThreshold && next <= triggerThreshold) {
                        triggerPoints.add(i + 1)
                        armedFalling = false
                    }
                }
            }
        }
        
        if (triggerPoints.isEmpty()) return -1
        
        // === 改进的周期估算：结合边沿检测和自相关 ===
        var currentPeriod = 0
        var currentConfidence = 0f
        
        // 方法1：从边沿间隔估算（快速但对复杂波形可能不准）
        if (triggerPoints.size >= 2) {
            val periods = mutableListOf<Int>()
            for (i in 1 until triggerPoints.size) {
                periods.add(triggerPoints[i] - triggerPoints[i - 1])
            }
            periods.sort()
            val edgePeriod = periods[periods.size / 2]
            
            // 检查边沿间隔的一致性（标准差/均值）
            if (periods.size >= 2) {
                val mean = periods.average()
                val variance = periods.map { (it - mean) * (it - mean) }.average()
                val stdDev = kotlin.math.sqrt(variance)
                val cv = stdDev / mean  // 变异系数
                
                // 变异系数 < 0.15 表示周期稳定
                if (cv < 0.15) {
                    currentPeriod = edgePeriod
                    currentConfidence = (1f - cv.toFloat()).coerceIn(0f, 1f)
                }
            }
        }
        
        // 方法2：自相关（对复杂波形更准确，但计算量大）
        // 仅在边沿检测置信度低时使用
        if (currentConfidence < 0.7f && safeEnd - safeStart >= 200) {
            val (acPeriod, acConfidence) = estimatePeriodByAutocorrelation(
                effectiveData, safeStart, safeEnd,
                minPeriod = 10,
                maxPeriod = minOf(8000, (safeEnd - safeStart) / 2)
            )
            
            if (acConfidence > currentConfidence) {
                currentPeriod = acPeriod
                currentConfidence = acConfidence
            }
        }
        
        // === 周期稳定性跟踪 ===
        if (currentPeriod > 0) {
            // 检查与上一帧周期的差异
            val periodDiff = kotlin.math.abs(currentPeriod - lastEstimatedPeriod)
            val periodTolerance = maxOf(2, (currentPeriod * 0.05f).toInt())  // 5% 容差
            
            if (lastEstimatedPeriod > 0 && periodDiff <= periodTolerance) {
                // 周期稳定，增加计数
                stablePeriodCount++
                // 使用加权平均平滑周期估算
                estimatedPeriodSamples = ((estimatedPeriodSamples * 0.7f + currentPeriod * 0.3f).toInt())
                    .coerceAtLeast(1)
                periodConfidence = (periodConfidence * 0.7f + currentConfidence * 0.3f)
            } else {
                // 周期变化，重置稳定计数
                stablePeriodCount = 0
                estimatedPeriodSamples = currentPeriod
                periodConfidence = currentConfidence
                lockedTriggerPhase = -1f  // 解锁相位
            }
            lastEstimatedPeriod = currentPeriod
        }
        
        // === 计算 holdoff（采样数）===
        val holdoffSamples = if (oscilloscopeTriggerHoldoffAuto) {
            if (estimatedPeriodSamples > 0) {
                // 周期稳定时（连续 5 帧以上相似），使用严格的周期对齐
                // 这是解决非正弦波抖动的关键！
                if (stablePeriodCount >= 5 && periodConfidence > 0.6f) {
                    // 使用整数周期作为 holdoff，确保每次触发在相同相位
                    estimatedPeriodSamples
                } else {
                    // 周期不稳定时，使用保守的 holdoff
                    val holdoffRatio = when {
                        estimatedPeriodSamples < sampleRate / 1000 -> 0.6f  // 高频
                        estimatedPeriodSamples < sampleRate / 100 -> 0.7f   // 中频
                        else -> 0.8f  // 低频
                    }
                    (estimatedPeriodSamples * holdoffRatio).toInt()
                }
            } else {
                (sampleRate * 0.0005f).toInt()  // 默认 0.5ms
            }
        } else {
            (sampleRate * oscilloscopeTriggerHoldoffMs / 1000f).toInt()
        }
        
        // === 应用 holdoff 过滤触发点 ===
        val filteredTriggerPoints = mutableListOf<Int>()
        var lastAcceptedIndex = Int.MIN_VALUE
        for (idx in triggerPoints) {
            if (idx - lastAcceptedIndex >= holdoffSamples) {
                filteredTriggerPoints.add(idx)
                lastAcceptedIndex = idx
            }
        }
        
        if (filteredTriggerPoints.isEmpty()) {
            return triggerPoints.last()
        }
        
        // === 改进的触发点选择：周期对齐优先 ===
        val selectedIndex: Int
        
        if (stablePeriodCount >= 5 && estimatedPeriodSamples > 0 && lastTriggerDataIndex >= 0) {
            // 周期稳定模式：选择与上一触发点相差整数个周期的触发点
            // 这确保复杂波形在屏幕上的位置保持一致
            var bestIndex = filteredTriggerPoints.last()
            var bestScore = Float.MAX_VALUE
            
            for (idx in filteredTriggerPoints) {
                val diff = idx - lastTriggerDataIndex
                if (diff <= 0) continue  // 只考虑向前的触发点
                
                // 计算与整数周期的偏差
                val periodCount = (diff.toFloat() / estimatedPeriodSamples).roundToInt()
                if (periodCount <= 0) continue
                
                val idealDiff = periodCount * estimatedPeriodSamples
                val deviation = kotlin.math.abs(diff - idealDiff).toFloat()
                
                // 评分：偏差越小越好，同时偏好较近的触发点
                val score = deviation + (periodCount - 1) * 0.5f * estimatedPeriodSamples
                
                if (score < bestScore) {
                    bestScore = score
                    bestIndex = idx
                }
            }
            
            selectedIndex = bestIndex
        } else if (lastTriggerDataIndex >= 0 && filteredTriggerPoints.size > 1) {
            // 非稳定模式：选择与上一次触发点最近的
            var bestIndex = filteredTriggerPoints.last()
            var minDistance = Int.MAX_VALUE
            
            for (idx in filteredTriggerPoints) {
                val distance = kotlin.math.abs(idx - lastTriggerDataIndex)
                if (distance < minDistance) {
                    minDistance = distance
                    bestIndex = idx
                }
            }
            selectedIndex = bestIndex
        } else {
            // 首次触发或只有一个触发点
            selectedIndex = filteredTriggerPoints.last()
        }
        
        lastTriggerDataIndex = selectedIndex
        return selectedIndex
    }
    
    /** 将浮点数四舍五入到最近的整数 */
    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
    
    /** 兼容性方法：调用新的触发查找方法 */
    private fun findRisingEdgeTrigger(data: ShortArray, triggerLevelDb: Float, searchStart: Int, searchEnd: Int): Int {
        return findTriggerPoint(data, triggerLevelDb, searchStart, searchEnd, oscilloscopeTriggerMode)
    }
    
    /** 触发诊断日志的节流控制 */
    private var lastTriggerDiagnosticTime = 0L
    private val triggerDiagnosticIntervalMs = 2000L  // 每 2 秒最多输出一次诊断日志
    
    /**
     * 触发诊断：当触发未成功但数据中存在超过阈值的样本时，分析并打印详细原因
     * 
     * @param data 波形数据
     * @param triggerLevelDb 触发电平（dB）
     * @param searchStart 搜索起始位置
     * @param searchEnd 搜索结束位置
     * @param mode 触发模式
     * @param triggerFound 是否找到触发点
     */
    private fun diagnoseTriggerIssue(
        data: ShortArray, 
        triggerLevelDb: Float, 
        searchStart: Int, 
        searchEnd: Int, 
        mode: Int,
        triggerFound: Boolean
    ) {
        // 节流控制：避免频繁输出日志
        val now = System.currentTimeMillis()
        if (now - lastTriggerDiagnosticTime < triggerDiagnosticIntervalMs) return
        
        val maxAmplitude = Short.MAX_VALUE.toFloat()
        val triggerAmplitude = dbToAmplitude(triggerLevelDb)
        val triggerThreshold = (triggerAmplitude * maxAmplitude).toInt()
        val hysteresis = maxOf((triggerThreshold * 0.15f).toInt(), (0.01f * maxAmplitude).toInt())
        val armThresholdRising = triggerThreshold - hysteresis
        val armThresholdFalling = triggerThreshold + hysteresis
        
        // 确保搜索范围有效
        val safeStart = searchStart.coerceIn(0, data.size - 2)
        val safeEnd = searchEnd.coerceIn(safeStart + 1, data.size)
        
        // 使用采样统计（最多检查 500 个样本）以避免卡顿
        val sampleCount = safeEnd - safeStart
        val step = maxOf(1, sampleCount / 500)  // 最多采样 500 个点
        
        var maxSample = Short.MIN_VALUE.toInt()
        var minSample = Short.MAX_VALUE.toInt()
        var countAboveThreshold = 0
        var countBelowArmRising = 0
        var countAboveArmFalling = 0
        var risingEdgeCount = 0
        var fallingEdgeCount = 0
        var armedRisingOccurred = false
        var armedFallingOccurred = false
        var checkedSamples = 0
        
        var i = safeStart
        while (i < safeEnd) {
            val sample = data[i].toInt()
            if (sample > maxSample) maxSample = sample
            if (sample < minSample) minSample = sample
            if (sample >= triggerThreshold) countAboveThreshold++
            if (sample < armThresholdRising) {
                countBelowArmRising++
                armedRisingOccurred = true
            }
            if (sample > armThresholdFalling) {
                countAboveArmFalling++
                armedFallingOccurred = true
            }
            
            // 检测边沿
            if (i < safeEnd - 1) {
                val next = data[i + 1].toInt()
                if (sample < triggerThreshold && next >= triggerThreshold) risingEdgeCount++
                if (sample > triggerThreshold && next <= triggerThreshold) fallingEdgeCount++
            }
            checkedSamples++
            i += step
        }
        
        // 将样本值转换为 dB 用于显示
        val maxSampleDb = if (maxSample > 0) 20 * kotlin.math.log10(maxSample / maxAmplitude) else -90f
        val minSampleDb = if (minSample < 0) 20 * kotlin.math.log10(-minSample / maxAmplitude) else -90f
        
        // 判断问题原因
        val modeStr = when (mode) {
            0 -> "上升沿"
            1 -> "下降沿"
            2 -> "双沿"
            else -> "未知"
        }
        
        val searchRangeValid = safeStart < safeEnd - 1
        val hasDataAboveThreshold = countAboveThreshold > 0
        
        // 构建诊断信息
        val diagBuilder = StringBuilder()
        diagBuilder.appendLine("========== 触发诊断 ==========")
        diagBuilder.appendLine("触发状态: ${if (triggerFound) "✓ 已触发" else "✗ 等待中"}")
        diagBuilder.appendLine("触发模式: $modeStr")
        diagBuilder.appendLine("触发电平: ${triggerLevelDb.toInt()} dB (阈值=$triggerThreshold)")
        diagBuilder.appendLine("迟滞范围: 预备↑<$armThresholdRising, 预备↓>$armThresholdFalling")
        diagBuilder.appendLine("")
        diagBuilder.appendLine("搜索范围: [$safeStart, $safeEnd) 共 ${safeEnd - safeStart} 样本 (采样 $checkedSamples 个)")
        diagBuilder.appendLine("数据范围: data.size=${data.size}")
        diagBuilder.appendLine("搜索范围有效: $searchRangeValid")
        diagBuilder.appendLine("")
        diagBuilder.appendLine("数据统计:")
        diagBuilder.appendLine("  最大值: $maxSample (${String.format("%.1f", maxSampleDb)} dB)")
        diagBuilder.appendLine("  最小值: $minSample (${String.format("%.1f", minSampleDb)} dB)")
        diagBuilder.appendLine("  超过触发阈值: $countAboveThreshold 样本")
        diagBuilder.appendLine("  低于上升预备阈值: $countBelowArmRising 样本")
        diagBuilder.appendLine("  高于下降预备阈值: $countAboveArmFalling 样本")
        diagBuilder.appendLine("  简单上升沿穿越: $risingEdgeCount 次")
        diagBuilder.appendLine("  简单下降沿穿越: $fallingEdgeCount 次")
        diagBuilder.appendLine("")
        
        // 分析未触发的原因
        if (!triggerFound) {
            diagBuilder.appendLine("【未触发原因分析】")
            
            if (!searchRangeValid) {
                diagBuilder.appendLine("  ✗ 搜索范围无效 (start=$searchStart >= end=$searchEnd)")
            }
            
            if (!hasDataAboveThreshold) {
                diagBuilder.appendLine("  ✗ 数据最大值 ($maxSample) 未达到触发阈值 ($triggerThreshold)")
                diagBuilder.appendLine("    建议: 降低触发电平或增大音量")
            } else {
                // 数据超过阈值但未触发
                when (mode) {
                    0 -> { // 上升沿
                        if (!armedRisingOccurred) {
                            diagBuilder.appendLine("  ✗ 信号始终高于预备阈值 ($armThresholdRising)")
                            diagBuilder.appendLine("    原因: 状态机未进入预备状态，无法检测上升沿")
                            diagBuilder.appendLine("    建议: 等待信号回落或降低触发电平")
                        } else if (risingEdgeCount == 0) {
                            diagBuilder.appendLine("  ✗ 无上升沿穿越 (信号未向上穿过阈值)")
                            diagBuilder.appendLine("    原因: 信号可能只是平稳地保持在阈值以上")
                        } else {
                            diagBuilder.appendLine("  ✗ 有上升沿但状态机未触发")
                            diagBuilder.appendLine("    原因: 可能是穿越发生在进入预备状态之前")
                        }
                    }
                    1 -> { // 下降沿
                        if (!armedFallingOccurred) {
                            diagBuilder.appendLine("  ✗ 信号始终低于预备阈值 ($armThresholdFalling)")
                            diagBuilder.appendLine("    原因: 状态机未进入预备状态，无法检测下降沿")
                            diagBuilder.appendLine("    建议: 等待信号升高或提高触发电平")
                        } else if (fallingEdgeCount == 0) {
                            diagBuilder.appendLine("  ✗ 无下降沿穿越 (信号未向下穿过阈值)")
                        } else {
                            diagBuilder.appendLine("  ✗ 有下降沿但状态机未触发")
                            diagBuilder.appendLine("    原因: 可能是穿越发生在进入预备状态之前")
                        }
                    }
                    2 -> { // 双沿
                        if (!armedRisingOccurred && !armedFallingOccurred) {
                            diagBuilder.appendLine("  ✗ 信号在预备区间内，两个状态机都未激活")
                        } else if (risingEdgeCount == 0 && fallingEdgeCount == 0) {
                            diagBuilder.appendLine("  ✗ 无任何边沿穿越")
                        } else {
                            diagBuilder.appendLine("  ✗ 有边沿但状态机时序不匹配")
                        }
                    }
                }
            }
        }
        
        diagBuilder.appendLine("================================")
        
        // 输出日志
        Log.w("TriggerDiag", diagBuilder.toString())
        lastTriggerDiagnosticTime = now
    }
    
    /** 触发线画笔 */
    private val triggerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)  // 虚线
    }
    
    /**
     * 绘制触发电平线：在示波器模式下显示水平线标识触发电平
     * 
     * 优化说明：
     * - 正半轴（主触发区）：实线，颜色鲜明
     * - 负半轴（参考线）：虚线，颜色较淡
     * 这样用户可以清楚地知道触发只在正半轴生效
     */
    /**
     * 在屏幕空间绘制触发电平线（canvas.restore() 之后调用）
     * 需要手动计算考虑缩放和偏移后的屏幕坐标
     */
    private fun drawTriggerLevelLineScreenSpace(
        canvas: Canvas, 
        drawWidth: Float, 
        drawHeight: Float,
        contentCenterY: Float,
        scaleY: Float,
        offsetY: Float
    ) {
        // 直接使用触发电平 dB 计算 Y 坐标，与 Y 轴标签使用相同的函数
        val triggerDb = oscilloscopeTriggerLevelDb
        val yTopContent = oscilloscopeDbToYTop(triggerDb, drawHeight)
        val yBottomContent = oscilloscopeDbToYBottom(triggerDb, drawHeight)
        
        // 转换到屏幕空间：使用与 Y 轴标签相同的公式
        // contentToScreenY(y, centerY, offY, sy) = centerY + offY + sy * (y - centerY)
        val yTopScreen = contentCenterY + offsetY + scaleY * (yTopContent - contentCenterY)
        val yBottomScreen = contentCenterY + offsetY + scaleY * (yBottomContent - contentCenterY)
        
        // 绘制区域边界
        val left = paddingLeft.toFloat()
        val right = paddingLeft + drawWidth
        
        // 正半轴主触发线：实线，颜色鲜明
        triggerLinePaint.pathEffect = null  // 实线
        triggerLinePaint.strokeWidth = 2.5f
        triggerLinePaint.color = if (lastTriggerFound) {
            Color.argb(230, 0, 220, 0)  // 绿色（已触发）
        } else {
            Color.argb(230, 255, 165, 0)  // 橙色（等待触发）
        }
        canvas.drawLine(left, yTopScreen, right, yTopScreen, triggerLinePaint)
        
        // 负半轴参考线：虚线，颜色较淡（仅作为对称参考，不参与触发）
        triggerLinePaint.pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)  // 虚线
        triggerLinePaint.strokeWidth = 1.5f
        triggerLinePaint.color = Color.argb(100, 128, 128, 128)  // 灰色半透明
        canvas.drawLine(left, yBottomScreen, right, yBottomScreen, triggerLinePaint)
        
        // 恢复默认虚线样式供下次使用
        triggerLinePaint.pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
        triggerLinePaint.strokeWidth = 2f
    }
    
    /** 数据不足提示的画笔 */
    private val insufficientDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    
    /**
     * 绘制数据不足的提示
     */
    private fun drawInsufficientDataHint(canvas: Canvas, drawWidth: Float, drawHeight: Float, dataSize: Int, visibleLength: Int) {
        if (dataSize >= visibleLength) return
        
        val fillPercent = (dataSize * 100f / visibleLength).toInt()
        val hint = "数据加载中... $fillPercent%"
        
        canvas.drawText(hint, paddingLeft + drawWidth / 2f, paddingTop + drawHeight / 2f, insufficientDataPaint)
    }
    private val gridColor: Int
    private val chartTextColor: Int
    private val peakColor: Int
    private val frequencyMarkerColor: Int
    
    init {
        val ts = oscilloscopeVisibleTimeSpanSec
        val def = oscilloscopeTimeSpanDefaultSec
        AsyncLog.d { "[INIT] view created, oscilloscopeVisibleTimeSpanSec=${ts}s (${ts * 1000}ms) default=${def}s" }
        _waveformColor = ContextCompat.getColor(context, R.color.waveform_color)
        gridColor = ContextCompat.getColor(context, R.color.grid_color)
        chartTextColor = ContextCompat.getColor(context, R.color.text_primary)
        peakColor = ContextCompat.getColor(context, R.color.peak_color)
        frequencyMarkerColor = ContextCompat.getColor(context, R.color.primary_light)
        
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        
        gridPaint.color = gridColor
        gridPaint.strokeWidth = 1f
        
        textPaint.color = chartTextColor
        textPaint.textSize = 24f
        textPaint.textAlign = Paint.Align.LEFT
        
        peakPaint.color = peakColor
        peakPaint.strokeWidth = 3f
        peakPaint.style = Paint.Style.FILL  // 改为填充模式，使峰值圆点更明显
    }
    
    /**
     * 更新频谱数据
     */
    fun updateSpectrum(data: FloatArray) {
        spectrumData = data
        requestFrameInvalidate()
    }
    
    /** 数据对应的实际平移偏移（由 MainActivity 传入，用于数据索引计算） */
    private var waveformDataPanOffset: Int = 0
    
    /**
     * 更新波形数据
     * @param data 波形数据数组
     * @param totalSamples 累计接收的样本数（用于稳定采样网格）
     * @param dataPanOffset 这批数据对应的实际平移偏移（样本数），用于数据索引计算
     */
    fun updateWaveform(data: ShortArray, totalSamples: Long = 0L, dataPanOffset: Int = 0) {
        // 检测数据是否真正变化（通过累计样本数判断）
        if (totalSamples == waveformTotalSamples && waveformData != null && dataPanOffset == waveformDataPanOffset) {
            // 数据未变化，不需要重绘
            return
        }
        val prevTotalSamples = waveformTotalSamples
        val prevPanOffset = waveformDataPanOffset
        
        // 直接使用传入的数组引用，避免重复复制
        // 调用方 (MainActivity) 保证每次传递的是独立的数组
        waveformData = data
        waveformTotalSamples = totalSamples
        waveformDataPanOffset = dataPanOffset
        // 仅在显示语义发生跳变时清空平滑缓存：
        // - 平移偏移变化（用户拖动 / 返回最新）
        // - 累计样本数回退（重置录制等）
        if (dataPanOffset != prevPanOffset || totalSamples < prevTotalSamples) {
            largeWindowPrevYMin = null
            largeWindowPrevYMax = null
            largeWindowPrevDisplayEndAbsIdx = Long.MIN_VALUE
            largeWindowPrevSamplesPerInterval = 0
        }
        lastWaveformUpdateNs = System.nanoTime()
        hasNewWaveformData = true
        val dataTimeSpanMs = (data.size.toFloat() / sampleRate * 1000f)
        if (shouldLog()) {
            val ds = data.size
            val sr = sampleRate
            val span = dataTimeSpanMs
            AsyncLog.d { "updateWaveform: data.size=$ds samples, sampleRate=$sr, dataTimeSpan=${String.format("%.2f", span)}ms, dataPanOffset=$dataPanOffset" }
        }
        requestFrameInvalidate()
    }
    
    /**
     * 更新峰值频率
     */
    fun updatePeakFrequencies(peaks: List<Pair<Float, Float>>) {
        peakFrequencies = peaks
        requestFrameInvalidate()
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isOscilloscopeManualPanning = false
            }
        }
        when (event.pointerCount) {
            2 -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        gestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)
        return true
    }
    
    /** 将内容坐标 x 转为屏幕坐标（缩放平移后），用于在屏幕空间绘制不变形的标签 */
    private fun contentToScreenX(x: Float, centerX: Float, offX: Float, sx: Float) =
        centerX + offX + sx * (x - centerX)
    /** 频谱模式：以左边为锚点缩放，加上平移偏移 */
    private fun contentToScreenXSpectrum(contentX: Float, drawWidth: Float): Float =
        paddingLeft + spectrumOffsetX + spectrumScaleX * (contentX - paddingLeft)
    private fun contentToScreenY(y: Float, centerY: Float, offY: Float, sy: Float) =
        centerY + offY + sy * (y - centerY)

    /**
     * 示波器模式：当前可见时间范围（秒）与缓冲区间，用于横轴标签与网格。
     * 考虑 scaleX 与 offsetX，只覆盖当前窗口。
     */
    private data class OscilloscopeVisibleRange(
        val leftTimeSec: Float,
        val rightTimeSec: Float,
        val visibleStart: Int,
        val visibleLength: Int,
        val leftContentX: Float,
        val rightContentX: Float
    ) {
        val spanSec: Float get() = rightTimeSec - leftTimeSec
    }

    /** 示波器横轴：最右为当前时刻或平移后的时刻，最左为负时间（过去），支持平移查看历史 */
    private fun getOscilloscopeVisibleRange(
        drawWidth: Float, centerX: Float, width: Float
    ): OscilloscopeVisibleRange? {
        val data = waveformData ?: return null
        if (data.isEmpty()) return null
        getOscilloscopeScaleX() ?: return null
        val visibleLength = getOscilloscopeVisibleLength(data)
        val visibleStart = maxOf(0, data.size - visibleLength)
        val leftContentX = paddingLeft.toFloat()
        val rightContentX = paddingLeft + drawWidth
        
        // 坐标轴与波形必须使用同一份偏移与量化策略，避免拖动时出现相对滑移。
        // 小窗口使用连续偏移；大窗口（每像素>=1样本）按像素列区间对齐。
        val panOffsetSamples = waveformDataPanOffset.coerceAtLeast(0)
        val latestAbsIdx = waveformTotalSamples - 1L
        val displayEndAbsIdx = latestAbsIdx - panOffsetSamples.toLong()
        // 以屏幕列为固定栅格：每列一个显示区间，避免点数策略切换带来的抖动。
        val numPoints = drawWidth.toInt().coerceAtLeast(2)
        val step = visibleLength.toFloat() / numPoints.coerceAtLeast(1)

        val rightTimeSec: Float
        val leftTimeSec: Float
        if (step >= 1f) {
            val samplesPerInterval = ceil(step).toLong().coerceAtLeast(1L)
            // 与 drawWaveform 保持一致：
            // 仅在“实时跟随”场景启用区间量化，固定分桶边界，避免大窗口下每帧微抖。
            // 手动平移或触发定位时保留连续映射，保证拖动/定位手感。
            // 与 drawWaveform 保持一致：触发实际位移时(lastDrawnTriggerOffset!=0)才关闭量化
            // 修复抖动：历史回看(panOffset>0)在手势结束后也应继续量化，
            // 否则大时间窗口会退回连续映射，出现微抖。
            val useQuantizedRange = !isOscilloscopeManualPanning &&
                lastDrawnTriggerOffset == 0
            val displayEndAligned = if (useQuantizedRange) {
                Math.floorDiv(displayEndAbsIdx, samplesPerInterval) * samplesPerInterval
            } else {
                displayEndAbsIdx
            }
            val rawStartAbsIdx = displayEndAligned - visibleLength.toLong()
            val displayStartAbsIdx = if (useQuantizedRange) {
                Math.floorDiv(rawStartAbsIdx, samplesPerInterval) * samplesPerInterval
            } else {
                rawStartAbsIdx
            }
            rightTimeSec = (displayEndAligned - latestAbsIdx).toFloat() / sampleRate
            // 时间轴与波形横向映射都以 visibleLength 为准，避免量化后 span 漂移造成
            // “时间标签正确但波形对应时间略短/略长”的视觉不一致。
            val visibleSpanSec = visibleLength.toFloat() / sampleRate
            leftTimeSec = rightTimeSec - visibleSpanSec
        } else {
            val spanSec = visibleLength.toFloat() / sampleRate
            val panOffsetSec = panOffsetSamples.toFloat() / sampleRate
            rightTimeSec = -panOffsetSec
            leftTimeSec = rightTimeSec - spanSec
        }
        
        return OscilloscopeVisibleRange(
            leftTimeSec = leftTimeSec,
            rightTimeSec = rightTimeSec,
            visibleStart = visibleStart,
            visibleLength = visibleLength,
            leftContentX = leftContentX,
            rightContentX = rightContentX
        )
    }

    /** 根据可见时间跨度选择步长（秒）与格式：10µs～10s；小时间窗口内保证约 5～10 条时间网格 */
    private fun oscilloscopeTimeStepAndFormat(spanSec: Float): Pair<Float, (Float) -> String> {
        return when {
            spanSec >= 5f -> Pair(1f) { t -> "%.0fs".format(t) }
            spanSec >= 2f -> Pair(0.5f) { t -> "%.1fs".format(t) }
            spanSec >= 1f -> Pair(0.2f) { t -> "%.1fs".format(t) }
            spanSec >= 0.5f -> Pair(0.1f) { t -> "%.2fs".format(t) }
            spanSec >= 0.1f -> Pair(0.02f) { t -> "%.2fs".format(t) }
            spanSec >= 0.05f -> Pair(0.01f) { t -> "%.0fms".format(t * 1000) }
            spanSec >= 0.01f -> Pair(0.002f) { t -> "%.1fms".format(t * 1000) }   // 10ms 窗口约 5 条网格（0,2,4,6,8,10ms）
            spanSec >= 0.002f -> Pair(0.0005f) { t -> "%.2fms".format(t * 1000) } // 2ms 窗口约 4 条
            spanSec >= 0.0005f -> Pair(0.0001f) { t -> "%.2fms".format(t * 1000) } // 0.5ms 约 5 条
            spanSec >= 0.0001f -> Pair(0.00002f) { t -> "%.0fµs".format(t * 1_000_000) } // 0.1ms 约 5 条
            spanSec >= 0.00002f -> Pair(0.000005f) { t -> "%.0fµs".format(t * 1_000_000) } // 20µs 约 4 条
            else -> Pair(0.000002f) { t -> "%.0fµs".format(t * 1_000_000) }       // 10µs 约 5 条
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        val drawWidth = width - paddingLeft - paddingRight
        val drawHeight = height - paddingTop - paddingBottom
        val contentCenterX = paddingLeft + drawWidth / 2f
        val contentCenterY = paddingTop + drawHeight / 2f
        var insufficientHintDataSize = -1
        var insufficientHintVisibleLength = -1
        
        canvas.drawColor(Color.BLACK)
        
        // 坐标轴框：始终固定在屏幕边缘，不参与缩放
        gridPaint.style = Paint.Style.STROKE
        canvas.drawRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom, gridPaint)
        
        // 仅对图表内容（网格+数据）应用缩放与平移；坐标轴与文字在屏幕空间绘制
        // 示波器：横轴最左固定在屏幕边缘
        // 频谱：以左边为锚点缩放，平移用于查看不同频率范围（边界不能内缩）
        val effectiveScaleX = if (displayMode == DisplayMode.OSCILLOSCOPE) getOscilloscopeScaleX() ?: 1f else spectrumScaleX
        val effectiveOffsetX = if (displayMode == DisplayMode.SPECTRUM) spectrumOffsetX else 0f
        // 频谱模式：Y 轴缩放完全由 visibleDbTop/visibleDbBottom 负责（dbToY 已将 dB 映射到满屏高度），
        // canvas 不再额外 scale Y，否则 scaleY<1 时内容被双重压缩，上下出现空白——0dB/-120dB 进入画面。
        val effectiveScaleY = if (displayMode == DisplayMode.OSCILLOSCOPE) oscilloscopeScaleY else 1f
        
        // 频谱模式：通过 visibleDbTop/visibleDbBottom 控制可见 dB 范围，不使用 Y 轴画布平移
        val effectiveOffsetY: Float
        val visibleDbTop: Float
        val visibleDbBottom: Float
        if (displayMode == DisplayMode.SPECTRUM) {
            // 可见 dB 范围 = 60 / scaleY（scaleY=1 时显示 60dB，scaleY=2 时显示 30dB）
            val visibleDbRange = 60f / spectrumScaleY
            val totalDbRange = kotlin.math.abs(dB_MIN_EXTENDED - dB_MAX)  // 120dB
            
            // 将 spectrumOffsetY（像素）转换为 dB 偏移
            // spectrumOffsetY > 0 表示向上滑动（显示更高 dB），< 0 表示向下滑动（显示更低 dB）
            val dbOffset = spectrumOffsetY * visibleDbRange / drawHeight
            
            // 计算可见范围的中心点
            var centerDb = spectrumDefaultCenterDb + dbOffset
            
            // 计算顶部和底部 dB 值
            val halfRange = visibleDbRange / 2f
            var topDb = centerDb + halfRange
            var bottomDb = centerDb - halfRange
            
            // 调试：在边界强制执行之前，若 topDb 已接近 0dB（距 margin 不足 5dB），输出警告
            // 这说明 clampSpectrumOffsetY() 没能把 offsetY 限制在合法范围内，或者存在其他修改 offsetY 的路径
            if (topDb > dB_MAX - DB_BOUNDARY_MARGIN - 5f) {
                DebugLog.w(Tag.GESTURE, 300L) {
                    "频谱Y接近上边界(强制前): topDb=${"%.3f".format(topDb)}, bottomDb=${"%.3f".format(bottomDb)}, " +
                    "scaleY=${"%.3f".format(spectrumScaleY)}, offsetY=${"%.1f".format(spectrumOffsetY)}, " +
                    "dbOffset=${"%.2f".format(dbOffset)}, visibleDbRange=${"%.1f".format(visibleDbRange)}"
                }
            }
            if (bottomDb < dB_MIN_EXTENDED + DB_BOUNDARY_MARGIN + 5f) {
                DebugLog.w(Tag.GESTURE, 300L) {
                    "频谱Y接近下边界(强制前): topDb=${"%.3f".format(topDb)}, bottomDb=${"%.3f".format(bottomDb)}, " +
                    "scaleY=${"%.3f".format(spectrumScaleY)}, offsetY=${"%.1f".format(spectrumOffsetY)}, " +
                    "dbOffset=${"%.2f".format(dbOffset)}, visibleDbRange=${"%.1f".format(visibleDbRange)}"
                }
            }

            // 边界限制：0dB 和 -120dB 始终不进入画面（保留 DB_BOUNDARY_MARGIN 的余量）
            val effectiveTotalRange = totalDbRange - 2f * DB_BOUNDARY_MARGIN
            if (visibleDbRange >= effectiveTotalRange) {
                // 可见范围超过有效显示范围，固定到带 margin 的全局范围
                topDb    = dB_MAX - DB_BOUNDARY_MARGIN
                bottomDb = dB_MIN_EXTENDED + DB_BOUNDARY_MARGIN
                centerDb = (topDb + bottomDb) / 2f
            } else {
                // 限制顶部：topDb < 0dB（留出 DB_BOUNDARY_MARGIN）
                if (topDb > dB_MAX - DB_BOUNDARY_MARGIN) {
                    topDb    = dB_MAX - DB_BOUNDARY_MARGIN
                    centerDb = topDb - halfRange
                    bottomDb = centerDb - halfRange
                }
                // 限制底部：bottomDb > -120dB（留出 DB_BOUNDARY_MARGIN）
                if (bottomDb < dB_MIN_EXTENDED + DB_BOUNDARY_MARGIN) {
                    bottomDb = dB_MIN_EXTENDED + DB_BOUNDARY_MARGIN
                    centerDb = bottomDb + halfRange
                    topDb    = centerDb + halfRange
                }
            }
            
            visibleDbTop = topDb
            visibleDbBottom = bottomDb
            // 频谱模式不使用 Y 轴画布平移，所有 Y 轴变化通过 visibleDbTop/visibleDbBottom 实现
            effectiveOffsetY = 0f
            
            // 调试：每 60 帧输出一次当前状态（约每秒一次）
            spectrumDebugFrameCounter++
            if (spectrumDebugFrameCounter % 60 == 0L) {
                android.util.Log.d("SpectrumBounds", "状态: visibleDb=[%.1f, %.1f], centerDb=%.1f, scaleY=%.2f, dbOffset=%.1f"
                    .format(visibleDbTop, visibleDbBottom, centerDb, spectrumScaleY, dbOffset))
            }
            
            // 调试：边界强制执行后的最终检查
            // 正常情况下不应触发；若触发，说明 onDraw 内部的 boundary enforcement 代码有 bug
            if (visibleDbTop > dB_MAX - DB_BOUNDARY_MARGIN + 0.01f) {
                DebugLog.e(tag = Tag.GESTURE, message = {
                    "【BUG】边界强制后仍违反(顶): visibleDbTop=${"%.3f".format(visibleDbTop)} 应 < ${"%.1f".format(dB_MAX - DB_BOUNDARY_MARGIN)} | " +
                    "scaleY=${"%.3f".format(spectrumScaleY)}, offsetY=${"%.1f".format(spectrumOffsetY)}, " +
                    "dbOffset=${"%.3f".format(dbOffset)}, visibleDbRange=${"%.2f".format(visibleDbRange)}, " +
                    "effectiveTotalRange=${"%.2f".format(effectiveTotalRange)}"
                })
            }
            if (visibleDbBottom < dB_MIN_EXTENDED + DB_BOUNDARY_MARGIN - 0.01f) {
                DebugLog.e(tag = Tag.GESTURE, message = {
                    "【BUG】边界强制后仍违反(底): visibleDbBottom=${"%.3f".format(visibleDbBottom)} 应 > ${"%.1f".format(dB_MIN_EXTENDED + DB_BOUNDARY_MARGIN)} | " +
                    "scaleY=${"%.3f".format(spectrumScaleY)}, offsetY=${"%.1f".format(spectrumOffsetY)}, " +
                    "dbOffset=${"%.3f".format(dbOffset)}, visibleDbRange=${"%.2f".format(visibleDbRange)}, " +
                    "effectiveTotalRange=${"%.2f".format(effectiveTotalRange)}"
                })
            }
        } else if (displayMode == DisplayMode.OSCILLOSCOPE) {
            effectiveOffsetY = oscilloscopeOffsetY
            visibleDbTop = -50f
            visibleDbBottom = -50f
        } else {
            effectiveOffsetY = 0f
            visibleDbTop = -50f
            visibleDbBottom = -50f
        }
        
        val anchorX = when (displayMode) {
            DisplayMode.OSCILLOSCOPE -> 0f
            DisplayMode.SPECTRUM, DisplayMode.VISUALIZER, DisplayMode.SOUND_LEVEL_METER -> paddingLeft.toFloat()
        }
        val translateX = when (displayMode) {
            DisplayMode.OSCILLOSCOPE -> paddingLeft.toFloat()
            DisplayMode.SPECTRUM, DisplayMode.VISUALIZER, DisplayMode.SOUND_LEVEL_METER -> paddingLeft.toFloat() + effectiveOffsetX
        }
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        canvas.translate(translateX, contentCenterY + effectiveOffsetY)
        canvas.scale(effectiveScaleX, effectiveScaleY)
        canvas.translate(-anchorX, -contentCenterY)
        
        // 缩放会导致笔触一起被放大，网格/线条变粗。补偿为“屏幕空间不变”的线宽（频谱与示波器均适用）。
        val savedGridStroke = gridPaint.strokeWidth
        val savedWaveformStroke = paint.strokeWidth
        val savedPeakStroke = peakPaint.strokeWidth
        // 示波器：使用几何平均数 sqrt(scaleX * scaleY) 补偿，确保两个方向线宽均衡
        // 频谱：使用 max(scaleX, scaleY) 补偿
        val strokeScale = if (displayMode == DisplayMode.OSCILLOSCOPE) {
            kotlin.math.sqrt(effectiveScaleX * effectiveScaleY)
        } else {
            max(effectiveScaleX, effectiveScaleY)
        }
        val strokeDivisor = if (displayMode == DisplayMode.OSCILLOSCOPE) strokeScale.coerceAtLeast(0.1f) else strokeScale.coerceAtLeast(1f)
        // 使用可配置的网格线粗细
        val gridBaseWidth = if (displayMode == DisplayMode.OSCILLOSCOPE) oscilloscopeGridStrokeWidth else spectrumGridStrokeWidth
        gridPaint.strokeWidth = gridBaseWidth / strokeDivisor
        paint.strokeWidth = (if (displayMode == DisplayMode.OSCILLOSCOPE) oscilloscopeStrokeWidthBase else 2f) / strokeDivisor
        if (displayMode == DisplayMode.SPECTRUM) {
            peakPaint.strokeWidth = 3f / strokeDivisor
            
            // 调试：验证边界限制是否正确
            // 由于现在不使用 Y 轴画布平移，visibleDbTop/visibleDbBottom 直接控制映射范围
            // 只要 visibleDbTop <= 0 且 visibleDbBottom >= -120，边界就是正确的
            if (visibleDbTop > dB_MAX + 0.1f || visibleDbBottom < dB_MIN_EXTENDED - 0.1f) {
                android.util.Log.e("SpectrumBounds", "边界违反: visibleDb=[%.1f, %.1f], 应在 [0, -120] 范围内"
                    .format(visibleDbTop, visibleDbBottom))
            }
        }
        
        // visibleDbTop/visibleDbBottom 已在前面计算并限制，直接使用
        drawGrid(canvas, width, height, drawWidth, drawHeight, visibleDbTop, visibleDbBottom, effectiveScaleX, effectiveScaleY)
        
        when (displayMode) {
            DisplayMode.SPECTRUM -> {
                spectrumData?.let { drawSpectrum(canvas, it, drawWidth, drawHeight, visibleDbTop, visibleDbBottom) }
            }
            DisplayMode.OSCILLOSCOPE -> {
                waveformData?.let { data ->
                    val drawStartNs = System.nanoTime()
                    val visibleLength = getOscilloscopeVisibleLength(data)
                    
                    drawWaveform(canvas, data, drawWidth, drawHeight)
                    
                    // 数据不足时显示提示
                    if (data.size < visibleLength) {
                        insufficientHintDataSize = data.size
                        insufficientHintVisibleLength = visibleLength
                    }
                    
                    val drawMs = (System.nanoTime() - drawStartNs) / 1_000_000
                    // 动态阈值：时间窗口的 1/10，但至少 8ms
                    val jankThresholdMs = maxOf(8L, (oscilloscopeVisibleTimeSpanSec * 100).toLong())
                    if (drawMs > jankThresholdMs && shouldLogJank()) {
                        val vis = getOscilloscopeVisibleLength(data)
                        val step = vis.toFloat() / (drawWidth.toInt().coerceAtLeast(2))
                        val mode = if (step >= 1f) "envelope" else "single-point"
                        AsyncLog.w {
                            "[JANK-DRAW] drawWaveform took ${drawMs}ms > threshold ${jankThresholdMs}ms " +
                                "(timeSpan=${(oscilloscopeVisibleTimeSpanSec * 1000).toInt()}ms), data.size=${data.size}, visibleLength=$vis, mode=$mode"
                        }
                    }
                    
                    // 更新上次绘制的样本数
                    lastWaveformTotalSamples = waveformTotalSamples
                    hasNewWaveformData = false
                }
                // 以屏幕刷新率持续重绘（120Hz 或设备刷新率），不依赖音频回调频率
                if (displayMode == DisplayMode.OSCILLOSCOPE) requestFrameInvalidate()
            }
            DisplayMode.VISUALIZER -> {
                spectrumData?.let { data ->
                    drawVisualizer(canvas, data, drawWidth, drawHeight)
                    // 持续重绘以实现动画效果
                    if (displayMode == DisplayMode.VISUALIZER) requestFrameInvalidate()
                }
            }
            DisplayMode.SOUND_LEVEL_METER -> {
                // 分贝计模式使用独立的 SoundLevelMeterView，此处不绘制
            }
        }
        
        if (showFrequencyMarkers && displayMode == DisplayMode.SPECTRUM) {
            drawFrequencyMarkerLines(canvas, drawWidth, drawHeight, strokeDivisor)
        }
        
        gridPaint.strokeWidth = savedGridStroke
        paint.strokeWidth = savedWaveformStroke
        peakPaint.strokeWidth = savedPeakStroke
        
        canvas.restore()
        
        // 示波器模式：在屏幕空间绘制触发电平线（不受缩放/平移影响）
        if (displayMode == DisplayMode.OSCILLOSCOPE && oscilloscopeTriggerEnabled) {
            drawTriggerLevelLineScreenSpace(canvas, drawWidth, drawHeight, contentCenterY, effectiveScaleY, effectiveOffsetY)
        }
        // 提示文字必须在屏幕坐标系绘制，避免被缩放矩阵拉伸。
        if (displayMode == DisplayMode.OSCILLOSCOPE &&
            insufficientHintDataSize >= 0 &&
            insufficientHintVisibleLength > 0
        ) {
            drawInsufficientDataHint(
                canvas,
                drawWidth,
                drawHeight,
                insufficientHintDataSize,
                insufficientHintVisibleLength
            )
        }
        
        drawYAxisDbLabelsScreenSpace(canvas, drawHeight, contentCenterX, contentCenterY, height, visibleDbTop, visibleDbBottom)
        // 频谱模式：仅保留一组横轴频率标签（有频率标记时用标记标签，否则用网格刻度标签）
        if (displayMode == DisplayMode.SPECTRUM && showFrequencyMarkers) {
            drawFrequencyMarkerLabelsScreenSpace(canvas, drawWidth, contentCenterX, contentCenterY, width, height)
        } else {
            drawXAxisLabelsScreenSpace(canvas, drawWidth, drawHeight, contentCenterX, contentCenterY, width, height)
        }
        if (showPeakDetection && displayMode == DisplayMode.SPECTRUM) {
            drawPeakCirclesScreenSpace(canvas, drawWidth, drawHeight, contentCenterX, contentCenterY, visibleDbTop, visibleDbBottom)
            drawPeakLabelsScreenSpace(canvas, drawWidth, drawHeight, contentCenterX, contentCenterY, visibleDbTop, visibleDbBottom)
        }
    }
    
    /** 纵轴标签最小间距，避免互相遮挡 */
    private val minVerticalLabelGap = 18f

    /**
     * 纵轴标签：频谱为 dB（可见窗口）；示波器为 dB（顶/底 0 dB、中心 -∞ dB）；过近时自动隐藏避免重叠
     */
    private fun drawYAxisDbLabelsScreenSpace(
        canvas: Canvas, drawHeight: Float,
        centerX: Float, centerY: Float, height: Float,
        visibleDbTop: Float, visibleDbBottom: Float
    ) {
        textPaint.textSize = 18f
        textPaint.textAlign = Paint.Align.RIGHT
        val labelX = paddingLeft - 6f
        // 频谱模式不使用 Y 轴画布平移，effectiveOffsetY = 0
        val effectiveOffsetY = if (displayMode == DisplayMode.SPECTRUM) 0f else oscilloscopeOffsetY
        // 频谱模式：Y 标签位置直接取 dbToY 返回值（已是满屏坐标），不需要额外 scale
        val effectiveScaleY = if (displayMode == DisplayMode.SPECTRUM) 1f else oscilloscopeScaleY
        when (displayMode) {
            DisplayMode.SPECTRUM -> {
                val items = gridDbValuesInRange(visibleDbTop, visibleDbBottom).mapNotNull { db ->
                    val contentY = dbToY(db, drawHeight, visibleDbTop, visibleDbBottom)
                    val screenY = contentToScreenY(contentY, centerY, effectiveOffsetY, effectiveScaleY)
                    if (screenY < paddingTop - 2f || screenY > height - paddingBottom + 2f) null
                    else Pair(screenY, "${db.toInt()} dB")
                }.sortedBy { it.first }
                var lastY = -1000f
                for ((screenY, label) in items) {
                    if (screenY >= lastY + minVerticalLabelGap) {
                        canvas.drawText(label, labelX, screenY + 6f, textPaint)
                        lastY = screenY
                    }
                }
            }
            DisplayMode.VISUALIZER -> {
                // 可视化模式不显示 Y 轴标签（条形图自带视觉效果）
            }
            DisplayMode.OSCILLOSCOPE -> {
                // 示波器：基于 dB 值的对数特性生成标签，确保均匀间距
                val visibleTop = paddingTop.toFloat()
                val visibleBottom = height - paddingBottom
                val visibleHeight = visibleBottom - visibleTop
                val contentCenterY = paddingTop + drawHeight / 2f
                val contentTop = paddingTop.toFloat()
                val contentBottom = paddingTop + drawHeight
                
                // 从内容 Y 坐标反算 dB 值（返回 null 表示超出内容范围）
                fun contentYToDb(contentY: Float): Float? {
                    // 超出内容空间范围
                    if (contentY < contentTop || contentY > contentBottom) return null
                    return if (contentY <= contentCenterY) {
                        val linear = 1f - (contentY - paddingTop) / (drawHeight / 2f)
                        if (linear > 0.0001f) 20f * kotlin.math.log10(linear) else -100f
                    } else {
                        val linear = 1f - (paddingTop + drawHeight - contentY) / (drawHeight / 2f)
                        if (linear > 0.0001f) 20f * kotlin.math.log10(linear) else -100f
                    }
                }
                
                // 判断内容 Y 坐标是在上半部分还是下半部分
                fun isUpperHalf(contentY: Float): Boolean = contentY <= contentCenterY
                
                // 生成合理的 dB 值列表
                fun generateOscilloscopeDbValues(): List<Float> {
                    val dbValues = mutableListOf<Float>()
                    
                    // 固定 3 dB 步长（dB 是对数单位，步长代表固定的幅度比例，不应随缩放变化）
                    val stepDb = 3f
                    
                    // 从 0 dB 开始，向下生成 dB 值
                    var currentDb = 0f
                    while (currentDb >= -100f) {
                        dbValues.add(currentDb)
                        currentDb -= stepDb
                    }
                    
                    return dbValues
                }
                
                val dbValues = generateOscilloscopeDbValues()
                
                // 分别收集上半部分和下半部分的标签（独立计算间距，支持非对称平移）
                val upperLabels = mutableListOf<Pair<Float, String>>()
                val lowerLabels = mutableListOf<Pair<Float, String>>()
                
                for (db in dbValues) {
                    val upperContentY = oscilloscopeDbToYTop(db, drawHeight)
                    val lowerContentY = oscilloscopeDbToYBottom(db, drawHeight)
                    
                    val upperScreenY = contentToScreenY(upperContentY, centerY, effectiveOffsetY, effectiveScaleY)
                    val lowerScreenY = contentToScreenY(lowerContentY, centerY, effectiveOffsetY, effectiveScaleY)
                    
                    if (upperScreenY >= visibleTop - 10f && upperScreenY <= visibleBottom + 10f) {
                        upperLabels.add(Pair(upperScreenY, "${db.toInt()} dB"))
                    }
                    if (db != 0f && lowerScreenY >= visibleTop - 10f && lowerScreenY <= visibleBottom + 10f) {
                        lowerLabels.add(Pair(lowerScreenY, "${db.toInt()} dB"))
                    }
                }
                
                // 中心线的屏幕位置
                val centerScreenY = contentToScreenY(contentCenterY, centerY, effectiveOffsetY, effectiveScaleY)
                val centerVisible = centerScreenY >= visibleTop - 10f && centerScreenY <= visibleBottom + 10f
                
                // 上半部分：从上往下绘制（Y 从小到大），独立计算间距
                // 如果负无穷标签可见，上半部分标签不能太靠近它
                var lastUpperY = -1000f
                for ((screenY, label) in upperLabels.sortedBy { it.first }) {
                    // 检查是否与负无穷标签太近（负无穷标签优先显示）
                    val tooCloseToCenter = centerVisible && screenY >= centerScreenY - minVerticalLabelGap
                    if (screenY >= lastUpperY + minVerticalLabelGap && !tooCloseToCenter) {
                        canvas.drawText(label, labelX, screenY + 6f, textPaint)
                        lastUpperY = screenY
                    }
                }
                
                // 中心线标签（-∞ dB）- 必须一直显示
                if (centerVisible) {
                    canvas.drawText("−∞ dB", labelX, centerScreenY + 6f, textPaint)
                }
                
                // 下半部分：从下往上绘制（Y 从大到小），独立计算间距
                // 如果负无穷标签可见，下半部分标签不能太靠近它
                var lastLowerY = 10000f
                for ((screenY, label) in lowerLabels.sortedByDescending { it.first }) {
                    // 检查是否与负无穷标签太近（负无穷标签优先显示）
                    val tooCloseToCenter = centerVisible && screenY <= centerScreenY + minVerticalLabelGap
                    if (screenY <= lastLowerY - minVerticalLabelGap && !tooCloseToCenter) {
                        canvas.drawText(label, labelX, screenY + 6f, textPaint)
                        lastLowerY = screenY
                    }
                }
            }
            DisplayMode.SOUND_LEVEL_METER -> {
                // 分贝计模式使用独立的 SoundLevelMeterView
            }
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    /** 横轴标签最小间距，避免互相遮挡 */
    private val minHorizontalLabelGap = 10f

    /**
     * 横轴刻度标签：屏幕空间绘制（频率或时间）；过近时自动隐藏避免重叠
     */
    private fun drawXAxisLabelsScreenSpace(
        canvas: Canvas, drawWidth: Float, drawHeight: Float,
        centerX: Float, centerY: Float, width: Float, height: Float
    ) {
        val maxFreq = sampleRate / 2f
        val bottomY = height - paddingBottom + 22f
        textPaint.textSize = 14f
        textPaint.textAlign = Paint.Align.CENTER
        val labelMargin = 4f
        when (displayMode) {
            DisplayMode.SPECTRUM -> {
                val items = gridFrequencies(maxFreq).mapNotNull { freq ->
                    val contentX = freqToX(freq, maxFreq, drawWidth)
                    val screenX = contentToScreenXSpectrum(contentX, drawWidth)
                    // 十二平均律模式显示音高，其他模式显示频率
                    val label = if (scaleMode == ScaleMode.TWELVE_TET) {
                        frequencyToNoteName(freq)
                    } else {
                        when { freq >= 1000 -> "${(freq / 1000).toInt()}k"; else -> "${freq.toInt()}" }
                    }
                    val halfW = textPaint.measureText(label) / 2f
                    if (screenX - halfW < paddingLeft + labelMargin || screenX + halfW > width - paddingRight - labelMargin) null
                    else Triple(screenX, label, halfW)
                }.sortedBy { it.first }
                val labelGap = if (scaleMode == ScaleMode.TWELVE_TET && items.isNotEmpty()) {
                    val availableWidth = width - paddingLeft - paddingRight - labelMargin * 2
                    val totalLabelWidth = items.sumOf { (it.third * 2).toDouble() }.toFloat()
                    val gapIfAllShown = if (items.size > 1) (availableWidth - totalLabelWidth) / (items.size - 1) else availableWidth
                    if (gapIfAllShown >= 2f) 2f else minHorizontalLabelGap
                } else {
                    minHorizontalLabelGap
                }
                var lastRight = paddingLeft - labelGap
                for ((screenX, label, halfW) in items) {
                    val left = screenX - halfW
                    if (left >= lastRight + labelGap) {
                        canvas.drawText(label, screenX, bottomY, textPaint)
                        lastRight = screenX + halfW
                    }
                }
            }
            DisplayMode.OSCILLOSCOPE -> {
                val oscScaleX = getOscilloscopeScaleX() ?: 1f
                val range = getOscilloscopeVisibleRange(drawWidth, centerX, width)
                if (range != null && range.spanSec > 0f) {
                    val (stepSec, formatTime) = oscilloscopeTimeStepAndFormat(range.spanSec)
                    val leftT = range.leftTimeSec
                    val rightT = range.rightTimeSec
                    val items = mutableListOf<Triple<Float, String, Float>>()
                    var tSec = kotlin.math.ceil(leftT / stepSec) * stepSec
                    // 时间轴：最右=0s，最左=负时间；contentX = (tSec - leftTimeSec)/spanSec * visibleLength
                    while (tSec <= rightT + stepSec * 0.001f) {
                        val contentX = (tSec - leftT) / range.spanSec * range.visibleLength
                        val screenX = paddingLeft + contentToScreenX(contentX, 0f, 0f, oscScaleX)
                        val label = formatTime(kotlin.math.abs(tSec))  // 不显示负号，用绝对值
                        val halfW = textPaint.measureText(label) / 2f
                        if (screenX - halfW >= paddingLeft + labelMargin && screenX + halfW <= width - paddingRight - labelMargin)
                            items.add(Triple(screenX, label, halfW))
                        tSec += stepSec
                    }
                    items.sortBy { it.first }
                    var lastRight = paddingLeft - minHorizontalLabelGap
                    for ((screenX, label, halfW) in items) {
                        if (screenX - halfW >= lastRight + minHorizontalLabelGap) {
                            canvas.drawText(label, screenX, bottomY, textPaint)
                            lastRight = screenX + halfW
                        }
                    }
                }
            }
            DisplayMode.VISUALIZER -> {
                // 可视化模式不显示 X 轴标签（条形图自带视觉效果）
            }
            DisplayMode.SOUND_LEVEL_METER -> {
                // 分贝计模式使用独立的 SoundLevelMeterView
            }
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * 生成横轴频率刻度：线性/对数为 10,20,..., 十二平均律为半音频率
     */
    private fun gridFrequencies(maxFreq: Float): List<Float> {
        if (scaleMode == ScaleMode.TWELVE_TET) {
            val minF = maxOf(logScaleMinFreq, maxFreq / 2048f)
            val nMin = kotlin.math.ceil(12 * (ln(minF / twelveTetRefFreq) / ln(2f))).toInt().coerceAtLeast(0)
            val nMax = kotlin.math.floor(12 * (ln(maxFreq / twelveTetRefFreq) / ln(2f))).toInt()
            return (nMin..nMax).map { n -> twelveTetRefFreq * 2f.pow(n / 12f) }.filter { it <= maxFreq }
        }
        val list = mutableListOf<Float>()
        // 10, 20, 30, ..., 90
        var f = 10f
        while (f <= maxFreq && f < 100f) {
            list.add(f)
            f += 10f
        }
        // 100, 200, ..., 900
        f = 100f
        while (f <= maxFreq && f < 1000f) {
            list.add(f)
            f += 100f
        }
        // 1000, 2000, 3000, ...
        f = 1000f
        while (f <= maxFreq) {
            list.add(f)
            f += 1000f
        }
        // 缩放时补充 5,15,...,95 及 150,250,...,950 等（仅频谱横轴）
        if (spectrumScaleX > 1.5f) {
            var g = 5f
            while (g <= maxFreq && g < 100f) {
                list.add(g)
                g += 10f
            }
            g = 150f
            while (g <= maxFreq && g < 1000f) {
                list.add(g)
                g += 100f
            }
            g = 1500f
            while (g <= maxFreq) {
                list.add(g)
                g += 1000f
            }
            list.sort()
        }
        return list.distinct().filter { it <= maxFreq }
    }

    /**
     * 生成纵轴 dB 刻度，缩放时补充更密
     */
    private fun gridDbValues(): List<Float> {
        val base = listOf(0f, -10f, -20f, -30f, -40f, -50f, -60f)
        val sy = if (displayMode == DisplayMode.OSCILLOSCOPE) oscilloscopeScaleY else spectrumScaleY
        if (sy <= 1.5f) return base
        val dense = base.flatMap { listOf(it, it - 5f) }.distinct().sortedDescending()
        return dense.filter { it >= dB_MIN }
    }

    /**
     * 可见 dB 窗口内的刻度（用于频谱拖动后的网格与标签）。
     * 示波器用固定 3 dB 步长（dB 是对数单位，步长代表固定的幅度比例，不应随缩放变化）。
     */
    private fun gridDbValuesInRange(dBTop: Float, dBBottom: Float): List<Float> {
        val sy = if (displayMode == DisplayMode.OSCILLOSCOPE) oscilloscopeScaleY else spectrumScaleY
        if (displayMode == DisplayMode.OSCILLOSCOPE) {
            // 固定 3 dB 步长（dB 是对数单位，步长代表固定的幅度比例，不应随缩放变化）
            val stepDb = 3f
            val list = mutableListOf<Float>()
            var db = (dBTop / stepDb).toInt() * stepDb
            while (db >= dBBottom) {
                list.add(db)
                db -= stepDb
            }
            return list.sortedDescending()
        }
        val step = if (sy > 1.5f) 5f else 10f
        val list = mutableListOf<Float>()
        var db = kotlin.math.ceil(dBBottom / step) * step
        while (db <= dBTop) {
            list.add(db)
            db += step
        }
        return list.sortedDescending()
    }

    /** 内容空间内“贯穿”用的超大范围，由 clip 裁切后不显示尽头 */
    private val gridExtent = 1e6f

    /**
     * 绘制网格：频谱为频率竖线 + dB 横线（可见窗口）；示波器为等分竖线 + 振幅 -1/0/1 横线。
     * 示波器模式下竖线用 scaleX、横线用 scaleY 分别补偿线宽，使横向缩放不影响网格粗细。
     * 竖线、横线均用超大范围绘制，由 clip 裁切，纵向/横向贯穿屏幕不显示尽头。
     */
    private fun drawGrid(canvas: Canvas, width: Float, height: Float,
                        drawWidth: Float, drawHeight: Float,
                        visibleDbTop: Float, visibleDbBottom: Float,
                        effectiveScaleX: Float, effectiveScaleY: Float) {
        val maxFreq = sampleRate / 2f

        when (displayMode) {
            DisplayMode.SPECTRUM -> {
                for (freq in gridFrequencies(maxFreq)) {
                    val x = freqToX(freq, maxFreq, drawWidth)
                    if (x < paddingLeft - 1f || x > width - paddingRight + 1f) continue
                    canvas.drawLine(x, -gridExtent, x, gridExtent, gridPaint)
                }
                for (db in gridDbValuesInRange(visibleDbTop, visibleDbBottom)) {
                    val y = dbToY(db, drawHeight, visibleDbTop, visibleDbBottom)
                    if (y < paddingTop - 1f || y > height - paddingBottom + 1f) continue
                    canvas.drawLine(-gridExtent, y, gridExtent, y, gridPaint)
                }
            }
            DisplayMode.OSCILLOSCOPE -> {
                val range = getOscilloscopeVisibleRange(drawWidth, paddingLeft + drawWidth / 2f, width)
                // 竖线：线宽只按 scaleX 补偿，横向缩放不影响粗细；纵向贯穿由 clip 裁切
                gridPaint.strokeWidth = oscilloscopeGridStrokeWidth / effectiveScaleX.coerceAtLeast(0.01f)
                if (range != null && range.spanSec > 0f) {
                    val (stepSec, _) = oscilloscopeTimeStepAndFormat(range.spanSec)
                    var tSec = kotlin.math.ceil(range.leftTimeSec / stepSec) * stepSec
                    val visibleLen = range.visibleLength.toFloat()
                    while (tSec <= range.rightTimeSec + stepSec * 0.001f) {
                        val contentX = (tSec - range.leftTimeSec) / range.spanSec * visibleLen
                        if (contentX >= -1f && contentX <= visibleLen + 1f)
                            canvas.drawLine(contentX, -gridExtent, contentX, gridExtent, gridPaint)
                        tSec += stepSec
                    }
                }
                // 横线：基于 dB 值的对数特性生成，确保均匀分布
                gridPaint.strokeWidth = oscilloscopeGridStrokeWidth / effectiveScaleY.coerceAtLeast(0.01f)
                val contentCenterY = paddingTop + drawHeight / 2f
                val contentTop = paddingTop.toFloat()
                val contentBottom = paddingTop + drawHeight
                // 横线需要按 X 缩放反向补偿；否则时间窗口很大（scaleX 很小）时只会显示短线段
                val horizontalExtent = (gridExtent / effectiveScaleX.coerceAtLeast(1e-6f)).coerceAtMost(1e9f)
                
                // 生成合理的 dB 值列表
                fun generateOscilloscopeDbValues(): List<Float> {
                    val dbValues = mutableListOf<Float>()
                    
                    // 固定 3 dB 步长（dB 是对数单位，步长代表固定的幅度比例，不应随缩放变化）
                    val stepDb = 3f
                    
                    // 从 0 dB 开始，向下生成 dB 值
                    var currentDb = 0f
                    while (currentDb >= -100f) {
                        dbValues.add(currentDb)
                        currentDb -= stepDb
                    }
                    
                    return dbValues
                }
                
                // 用于去重（避免同一位置绘制多条线）
                val drawnLines = mutableSetOf<Int>()  // 存储已绘制的内容 Y 坐标（取整）
                val dbValues = generateOscilloscopeDbValues()
                
                // 绘制中心线（-∞ dB / 零电平位置）- 使用专门的白色粗线，层级低于波形
                val centerLineKey = contentCenterY.toInt()
                if (showOscilloscopeCenterLine && centerLineKey !in drawnLines) {
                    drawnLines.add(centerLineKey)
                    // 使用专门的中心线画笔，线宽需要补偿缩放
                    val savedCenterLineWidth = centerLinePaint.strokeWidth
                    centerLinePaint.strokeWidth = oscilloscopeCenterLineWidth / effectiveScaleY.coerceAtLeast(0.01f)
                    canvas.drawLine(-horizontalExtent, contentCenterY, horizontalExtent, contentCenterY, centerLinePaint)
                    centerLinePaint.strokeWidth = savedCenterLineWidth
                } else if (centerLineKey !in drawnLines) {
                    // 即使不显示明显的中心线，也要标记为已绘制，避免普通网格线覆盖
                    drawnLines.add(centerLineKey)
                }
                
                // 绘制基于 dB 值的横线（每个 dB 值都绘制，不再基于整数坐标去重）
                // 因为放大时，内容坐标上 1 像素的差距在屏幕上可能对应几百像素
                for (db in dbValues) {
                    // 计算上半部分和下半部分的内容 Y 坐标
                    val upperContentY = oscilloscopeDbToYTop(db, drawHeight)
                    val lowerContentY = oscilloscopeDbToYBottom(db, drawHeight)
                    
                    // 绘制上半部分横线（跳过中心线位置）
                    if (upperContentY < contentCenterY - 0.5f) {
                        canvas.drawLine(-horizontalExtent, upperContentY, horizontalExtent, upperContentY, gridPaint)
                    }
                    
                    // 绘制下半部分横线（0 dB 只需要绘制一条，且跳过中心线位置）
                    if (db != 0f && lowerContentY > contentCenterY + 0.5f) {
                        canvas.drawLine(-horizontalExtent, lowerContentY, horizontalExtent, lowerContentY, gridPaint)
                    }
                }
            }
            DisplayMode.VISUALIZER -> {
                // 可视化模式不显示网格（条形图自带视觉效果）
            }
            DisplayMode.SOUND_LEVEL_METER -> {
                // 分贝计模式使用独立的 SoundLevelMeterView
            }
        }
    }
    
    /** 十二平均律参考频率 A0 = 27.5 Hz，半音步进 2^(1/12) */
    private val twelveTetRefFreq = 27.5f
    
    /**
     * 将频率转换为音高名称（十二平均律）
     * @param freq 频率（Hz）
     * @return 音高名称，如 "C4", "A4", "C#5" 等
     */
    private fun frequencyToNoteName(freq: Float): String {
        // 计算半音数（以 A0 = 0）
        // A0 = 27.5 Hz 是参考频率
        val semitones = 12f * (ln(freq / twelveTetRefFreq) / ln(2f))
        val semitoneIndex = semitones.roundToInt()
        
        // 音名数组：C, C#, D, D#, E, F, F#, G, G#, A, A#, B
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        
        // A0 对应 semitoneIndex = 0，A 是数组中的第 9 个（索引 9）
        // 计算八度：A0 是第 0 个八度，每 12 个半音为一个八度
        // 当 semitoneIndex = 0 时，octave = 0, noteIndex = 9 (A)
        // 当 semitoneIndex = 12 时，octave = 1, noteIndex = 9 (A)
        val octave = semitoneIndex / 12
        val noteIndex = (semitoneIndex % 12 + 9) % 12  // +9 因为 A0 是第 9 个音
        
        return "${noteNames[noteIndex]}$octave"
    }

    /**
     * 将频率转换为横坐标 x（考虑当前 scaleMode）
     */
    private fun freqToX(freq: Float, maxFreq: Float, drawWidth: Float): Float {
        return when (scaleMode) {
            ScaleMode.LINEAR -> paddingLeft + (freq / maxFreq).coerceIn(0f, 1f) * drawWidth
            ScaleMode.LOGARITHMIC -> {
                val minF = maxOf(logScaleMinFreq, maxFreq / 2048f)
                val f = freq.coerceIn(minF, maxFreq)
                val logMin = log10(minF)
                val logMax = log10(maxFreq)
                val t = (log10(f) - logMin) / (logMax - logMin)
                paddingLeft + t.coerceIn(0f, 1f) * drawWidth
            }
            ScaleMode.TWELVE_TET -> {
                val minF = maxOf(logScaleMinFreq, maxFreq / 2048f)
                val f = freq.coerceIn(minF, maxFreq)
                // 半音数（以 A0 为 0）：n = 12 * log2(f / 27.5)
                val n = 12f * (ln(f / twelveTetRefFreq) / ln(2f))
                val nMin = 12f * (ln(minF / twelveTetRefFreq) / ln(2f))
                val nMax = 12f * (ln(maxFreq / twelveTetRefFreq) / ln(2f))
                val t = (n - nMin) / (nMax - nMin).coerceAtLeast(0.001f)
                paddingLeft + t.coerceIn(0f, 1f) * drawWidth
            }
        }
    }
    
    /**
     * 绘制频谱；可见 dB 窗口由 visibleDbTop/visibleDbBottom 决定，可随拖动平移
     */
    private fun drawSpectrum(canvas: Canvas, data: FloatArray, 
                            drawWidth: Float, drawHeight: Float,
                            visibleDbTop: Float, visibleDbBottom: Float) {
        if (data.isEmpty()) return
        
        paint.color = waveformColor
        // strokeWidth 由 onDraw 按缩放补偿，此处不覆盖
        
        val path = Path()
        val dataSize = data.size
        val maxFreq = sampleRate / 2f
        
        for (i in data.indices) {
            val freq = (i.toFloat() + 0.5f) / dataSize * maxFreq
            val x = when (scaleMode) {
                ScaleMode.LINEAR -> paddingLeft + (i.toFloat() / dataSize) * drawWidth
                ScaleMode.LOGARITHMIC, ScaleMode.TWELVE_TET -> freqToX(freq, maxFreq, drawWidth)
            }
            
            val frequency = freq
            val slopeFactor = 10f.pow(-spectrumSlope * log10(frequency / 1000f + 1f) / 20f)
            
            val amplitude = data[i] * gain * slopeFactor
            val amplitudeClamped = amplitude.coerceIn(0f, 1f)
            val db = amplitudeToDbSpectrum(amplitudeClamped)
            val y = dbToY(db, drawHeight, visibleDbTop, visibleDbBottom)
            
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        canvas.drawPath(path, paint)
        
        // 填充波形下方区域：计算变换后视图底部在内容坐标系中的 Y 值
        // 屏幕底部 screenY = paddingTop + drawHeight
        // 注意：频谱模式下 effectiveOffsetY = 0，所以 offsetY 参数为 0
        val contentCenterY = paddingTop + drawHeight / 2f
        val screenBottom = paddingTop + drawHeight
        val fillBottomY = contentCenterY + (screenBottom - contentCenterY) / spectrumScaleY
        // 额外扩展以确保覆盖，防止边缘出现空隙
        val extendedBottomY = fillBottomY + drawHeight
        
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(0x33, Color.red(waveformColor), Color.green(waveformColor), Color.blue(waveformColor))
        path.lineTo(paddingLeft + drawWidth, extendedBottomY)
        path.lineTo(paddingLeft, extendedBottomY)
        path.close()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
    }
    
    /** 在内容空间位置 contentIndex 处取值：data 对应最右 data.size 个样本，左侧不足为 0；scrollOffset 为自上次更新以来的样本偏移，实现匀速滚动 */
    private fun sampleAtContentIndex(data: ShortArray, contentIndex: Int, visibleLength: Int, scrollOffsetSamples: Int = 0): Short {
        val dataEnd = (visibleLength - scrollOffsetSamples).coerceAtLeast(0)
        if (contentIndex >= dataEnd) return 0  // 右侧“未来”区域为 0
        val dataStart = (visibleLength - data.size - scrollOffsetSamples).coerceAtLeast(0)
        if (contentIndex < dataStart) return 0  // 左侧不足为 0
        val idx = (contentIndex - dataStart).coerceIn(0, data.size - 1)
        return data[idx]
    }

    /** 将归一化样本 [-1,1] 转为 Y 坐标（示波器 dB 映射） */
    /**
     * 将归一化振幅 (-1 ~ +1) 转换为 Y 坐标 (线性映射)
     * +1 对应屏幕顶部，-1 对应屏幕底部，0 对应中心线
     */
    private fun sampleToY(normalizedSample: Float, drawHeight: Float): Float {
        // 线性映射：中心为 drawHeight/2，振幅 ±1 对应 ±drawHeight/2
        val centerY = paddingTop + drawHeight / 2f
        // normalizedSample: +1 → 顶部, -1 → 底部
        return centerY - normalizedSample * (drawHeight / 2f)
    }

    /**
     * 绘制波形：Y 为线性振幅（0 中心、+1 顶、-1 底），纵轴标尺为 dB（顶/底 0 dB、中心 -∞ dB）。
     * 水平缩放时改变可见时间窗口：放大(scaleX>1)显示更精细，缩小(scaleX<1)显示更多时间。
     * 
     * 优化：大时间窗口时使用最大值-最小值下采样，保留波形峰谷特征，线条更清晰；
     * 小时间窗口时单点采样；Path 复用减轻 GC 压力。
     * 
     * 触发同步：启用时，在数据中寻找上升沿触发点，从该点开始显示波形，使波形稳定。
     * 平移支持：oscilloscopeOffsetSamples 控制查看历史数据的偏移量。
     */
    // 卡顿检测相关
    private var lastDrawnTotalSamples: Long = 0L
    private var lastDrawnTimestampNs: Long = 0L
    private var stuckStartTimeNs: Long = 0L
    private var lastStuckLogTimeMs: Long = 0L
    
    private fun drawWaveform(canvas: Canvas, data: ShortArray, 
                            drawWidth: Float, drawHeight: Float) {
        // 单次触发模式：如果已冻结，使用保存的波形数据
        val effectiveData = if (oscilloscopeSingleTriggerMode && singleTriggerFrozen && frozenWaveformData != null) {
            frozenWaveformData!!
        } else {
            data
        }
        
        if (effectiveData.isEmpty()) return
        
        // === 卡顿检测 ===
        val currentTimeNs = System.nanoTime()
        val currentTotalSamples = waveformTotalSamples
        val currentDataPanOffset = waveformDataPanOffset
        
        // 检测波形是否"卡住"：数据没有变化
        val isDataUnchanged = (currentTotalSamples == lastDrawnTotalSamples && lastDrawnTotalSamples > 0)
        
        if (isDataUnchanged) {
            // 数据没变，检查是否超过 100ms
            if (stuckStartTimeNs == 0L) {
                stuckStartTimeNs = currentTimeNs
            }
            val stuckDurationMs = (currentTimeNs - stuckStartTimeNs) / 1_000_000
            
            if (stuckDurationMs >= 100) {
                // 卡顿超过 100ms，打印详细日志（每 2 秒节流）
                val now = System.currentTimeMillis()
                if (now - lastStuckLogTimeMs >= 2000) {
                    lastStuckLogTimeMs = now
                    
                    val timeSinceLastUpdateMs = (currentTimeNs - lastWaveformUpdateNs) / 1_000_000
                    Log.w("WaveformStuck", "=== 波形卡顿检测 ===")
                    Log.w("WaveformStuck", "卡顿时长: ${stuckDurationMs}ms (阈值: 100ms)")
                    Log.w("WaveformStuck", "距上次数据更新: ${timeSinceLastUpdateMs}ms")
                    Log.w("WaveformStuck", "[数据状态] totalSamples=$currentTotalSamples, dataSize=${effectiveData.size}, dataPanOffset=$currentDataPanOffset")
                    Log.w("WaveformStuck", "[触发状态] enabled=$oscilloscopeTriggerEnabled, found=$lastTriggerFound, singleMode=$oscilloscopeSingleTriggerMode, frozen=$singleTriggerFrozen")
                    Log.w("WaveformStuck", "[平移状态] panOffset=$oscilloscopeOffsetSamples, maxOffset=$oscilloscopeMaxOffsetSamples")
                    Log.w("WaveformStuck", "[显示参数] timeSpan=${oscilloscopeVisibleTimeSpanSec}s, sampleRate=$sampleRate")
                    
                    // 诊断可能的原因
                    when {
                        oscilloscopeSingleTriggerMode && singleTriggerFrozen -> {
                            Log.w("WaveformStuck", "[原因] 单次触发已冻结，波形故意不动")
                        }
                        timeSinceLastUpdateMs > 200 -> {
                            Log.w("WaveformStuck", "[原因] 数据源停止更新，可能是:")
                            Log.w("WaveformStuck", "  1. 音频录制暂停/停止")
                            Log.w("WaveformStuck", "  2. MainActivity.processWaveform 未被调用")
                            Log.w("WaveformStuck", "  3. 主线程阻塞导致数据处理延迟")
                        }
                        !hasNewWaveformData -> {
                            Log.w("WaveformStuck", "[原因] hasNewWaveformData=false，updateWaveform 未被调用")
                        }
                        else -> {
                            Log.w("WaveformStuck", "[原因] 未知，数据有更新但 totalSamples 未变化")
                        }
                    }
                }
            }
        } else {
            // 数据有变化，重置卡顿计时
            stuckStartTimeNs = 0L
            lastDrawnTotalSamples = currentTotalSamples
            lastDrawnTimestampNs = currentTimeNs
        }
        
        // 调试：检查数据是否全为零
        var maxAbsValue: Short = 0
        val checkCount = minOf(effectiveData.size, 2000)
        for (i in 0 until checkCount) {
            val abs = if (effectiveData[i] >= 0) effectiveData[i] else (-effectiveData[i]).toShort()
            if (abs > maxAbsValue) maxAbsValue = abs
        }
        if (maxAbsValue == 0.toShort() && effectiveData.size > 100 && shouldLogJank()) {
            AsyncLog.w { "[DRAW-ZERO] waveform data is all zeros! size=${effectiveData.size}, " +
                "timeSpan=${String.format("%.3f", oscilloscopeVisibleTimeSpanSec)}s, " +
                "panOffset=$oscilloscopeOffsetSamples, scaleY=$oscilloscopeScaleY" }
        }
        
        paint.color = waveformColor
        val maxAmplitude = Short.MAX_VALUE.toFloat()
        val visibleLength = getOscilloscopeVisibleLength(effectiveData)
        
        // 关键修复：使用数据复制时的 dataPanOffset，而不是当前的 oscilloscopeOffsetSamples
        // 这样确保绘制范围和数据范围一致，避免用户快速滑动时的不同步问题
        val panOffset = waveformDataPanOffset.coerceAtLeast(0)
        
        // 调试日志：检测 panOffset 不同步问题
        val currentUserPanOffset = oscilloscopeOffsetSamples
        val panOffsetDiff = kotlin.math.abs(currentUserPanOffset - panOffset)
        recordPanOffsetSyncDiff(panOffset, currentUserPanOffset, panOffsetDiff)
        
        // 实际用于显示的数据长度（不能超过 effectiveData.size）
        val actualDisplayLength = minOf(visibleLength, effectiveData.size)
        
        // 触发同步：计算触发偏移
        // triggerOffset 表示"从最新数据往回跳过多少样本"
        // 屏幕位置 contentIdx 对应数据索引 = effectiveData.size - (panOffset + triggerOffset) - visibleLength + contentIdx
        var triggerOffset = 0
        lastTriggerFound = false
        
        // 单次触发已冻结时跳过触发搜索，使用固定偏移
        if (oscilloscopeSingleTriggerMode && singleTriggerFrozen) {
            lastTriggerFound = true
            // 冻结时不需要重新计算 triggerOffset，波形数据已固定
        }
        // 触发搜索条件：
        // 1. 触发功能已启用
        // 2. 时间窗口 <= 100ms（大时间窗口下触发无意义且影响性能）
        // 3. 有足够的数据来搜索（至少一个屏幕的数据）
        // 注：历史回看(panOffset>0)允许触发；拖动手势进行中禁用触发，避免边拖边跳。
        //     搜索范围限制在当前可见窗口附近。
        else if (
            oscilloscopeTriggerEnabled &&
            !oscilloscopeTriggerPaused &&
            !isOscilloscopeManualPanning &&
            oscilloscopeVisibleTimeSpanSec <= 0.1f &&
            effectiveData.size >= actualDisplayLength
        ) {
            // 触发点应该出现在屏幕左侧约 10% 处
            val triggerScreenPosition = (actualDisplayLength * 0.1f).toInt()
            
            // 搜索范围限定在当前视口附近（考虑 panOffset 偏移到历史数据的情况）：
            // 可见窗口在 effectiveData 中的末端索引 = effectiveData.size - panOffset
            // 搜索终点：视口末端往前留出 triggerScreenPosition，保证触发点落在屏幕 10% 位置后还有 90% 数据
            val viewEndInBuffer = (effectiveData.size - panOffset).coerceIn(0, effectiveData.size)
            val searchStart = 0
            val searchEnd = maxOf(1, viewEndInBuffer - actualDisplayLength + triggerScreenPosition)
            
            if (searchEnd > searchStart) {
                val triggerIndex = findTriggerPoint(effectiveData, oscilloscopeTriggerLevelDb, searchStart, searchEnd, oscilloscopeTriggerMode)
                if (triggerIndex >= 0) {
                    lastTriggerFound = true
                    // 计算 triggerOffset 使得：
                    // triggerIndex = effectiveData.size - (panOffset + triggerOffset) - visibleLength + triggerScreenPosition
                    // triggerOffset = effectiveData.size - panOffset - visibleLength + triggerScreenPosition - triggerIndex
                    val rawTriggerOffset = effectiveData.size - panOffset - visibleLength + triggerScreenPosition - triggerIndex
                    // 确保 triggerOffset >= 0（不能显示"未来"的数据）
                    // 确保 triggerOffset <= effectiveData.size - visibleLength - panOffset（不能超出数据范围）
                    val maxTriggerOffset = maxOf(0, effectiveData.size - visibleLength - panOffset)
                    triggerOffset = rawTriggerOffset.coerceIn(0, maxTriggerOffset)
                    
                    // 单次触发模式：首次触发成功后冻结
                    if (oscilloscopeSingleTriggerMode && singleTriggerArmed && !singleTriggerFrozen) {
                        singleTriggerFrozen = true
                        singleTriggerArmed = false
                        frozenWaveformData = effectiveData.copyOf()
                        frozenTotalSamples = waveformTotalSamples
                        onSingleTriggerStateChanged?.invoke(false, true)
                        Log.d("SingleTrigger", "单次触发成功，波形已冻结")
                    }
                    
                    // 调试日志（节流）
                    val now = System.currentTimeMillis()
                    if (now - lastTriggerDiagnosticTime >= triggerDiagnosticIntervalMs) {
                        Log.d("TriggerDiag", "触发成功: triggerIndex=$triggerIndex, triggerOffset=$triggerOffset, " +
                            "rawOffset=$rawTriggerOffset, maxOffset=$maxTriggerOffset")
                        lastTriggerDiagnosticTime = now
                    }
                }
                
                // 调试诊断：当触发未成功时分析原因（节流输出）
                if (!lastTriggerFound) {
                    diagnoseTriggerIssue(effectiveData, oscilloscopeTriggerLevelDb, searchStart, searchEnd, oscilloscopeTriggerMode, lastTriggerFound)
                }
            } else {
                // 搜索范围无效
                val now = System.currentTimeMillis()
                if (now - lastTriggerDiagnosticTime >= triggerDiagnosticIntervalMs) {
                    Log.w("TriggerDiag", "搜索范围无效: searchStart=$searchStart >= searchEnd=$searchEnd, effectiveData.size=${effectiveData.size}, actualDisplayLength=$actualDisplayLength")
                    lastTriggerDiagnosticTime = now
                }
            }
        } else if (oscilloscopeTriggerEnabled) {
            // 数据不足以搜索触发点
            val now = System.currentTimeMillis()
            if (now - lastTriggerDiagnosticTime >= triggerDiagnosticIntervalMs) {
                Log.w("TriggerDiag", "数据不足: effectiveData.size=${effectiveData.size} < minDataForTrigger($actualDisplayLength), visibleLength=$visibleLength")
                lastTriggerDiagnosticTime = now
            }
        }
        
        // 记录本帧触发偏移供 getOscilloscopeVisibleRange 使用，保证时间轴量化条件与波形一致
        lastDrawnTriggerOffset = triggerOffset
        
        // 数据从右端开始显示，使用反向映射确保波形稳定
        // 数据有效范围：contentIdx >= visibleLength - effectiveData.size
        // 平移和触发模式下应用偏移
        val totalOffset = panOffset + triggerOffset
        val effectiveDataSizeForDisplay = maxOf(0, if (totalOffset > 0) {
            effectiveData.size - totalOffset
        } else {
            effectiveData.size
        })
        val dataStartInContent = maxOf(0, visibleLength - effectiveDataSizeForDisplay)
        
        // 平滑滚动：禁用
        // 原因：数据更新频率（~120Hz）已接近屏幕刷新率，无需插值
        // 优点：无右侧空白，无延迟
        // 缺点：相位不同步时偶尔有一帧静止（肉眼难察觉）
        val smoothScrollSamples = 0f
        
        // 将样本偏移转换为像素偏移（用于 canvas translate）
        val pixelsPerSample = drawWidth / visibleLength
        val smoothScrollPixels = smoothScrollSamples * pixelsPerSample
        
        // 渲染栅格固定为屏幕像素列：
        // - 采集数据保持 16-bit 原始精度
        // - 显示层按像素列重采样，采集与渲染解耦
        val numPoints = drawWidth.toInt().coerceAtLeast(2)
        val step = visibleLength.toFloat() / numPoints.coerceAtLeast(1)
        
        waveformPath.rewind()

        // 根本解决波形抖动：量化滚动 + 绝对时间区间对齐
        // 核心思想：让屏幕像素与「绝对时间区间」严格对齐
        // 每个像素显示固定的「绝对时间区间」，当数据滚动超过一个区间时，波形整体向左跳一格
        // 这样「已绘制的波形形状永远不变」，只是整体位置变化
        if (step >= 1f) {
            // 大时间窗口：使用量化滚动
            val samplesPerInterval = kotlin.math.ceil(step).toInt().coerceAtLeast(1)
            
            // 当前显示范围的绝对样本索引
            val totalSamples = waveformTotalSamples
            
            // 关键修复：显示范围右边界需要考虑触发偏移
            // - 无触发时：右边界 = totalSamples - panOffset（显示最新数据）
            // - 有触发时：右边界需要回退 triggerOffset，因为我们要显示触发点附近的数据而非最新数据
            // 当数据不足时（effectiveData.size < visibleLength），左侧显示空白，右侧贴着数据
            val effectiveTriggerOffset = if (oscilloscopeTriggerEnabled) triggerOffset else 0
            
            // 单次触发冻结时使用冻结时的总样本数
            val effectiveTotalSamples = if (oscilloscopeSingleTriggerMode && singleTriggerFrozen) frozenTotalSamples else totalSamples
            
            // 计算显示范围（绝对样本索引）
            // 显示的右边界 = 数据的有效右边界（考虑平移和触发偏移后可用的最新数据位置）
            // 注意：最新样本的索引是 totalSamples - 1，不是 totalSamples
            val dataEndAbsIdx = effectiveTotalSamples - 1 - panOffset - effectiveTriggerOffset
            
            // 内容空间宽度 = numPoints * samplesPerInterval（用于 scaleX 计算和 x 坐标）
            val contentWidth = numPoints * samplesPerInterval
            
            // 仅在“实时跟随”场景启用区间量化：
            // 1) 终点量化：消除每帧尾桶变化导致的微抖
            // 2) 起点量化：固定分桶边界，避免全局重分桶
            // 手动平移或触发定位时使用连续映射，避免交互步进感。
            // 修复：检查 triggerOffset==0（触发是否实际位移了视图）而非 !oscilloscopeTriggerEnabled
            // 大窗口(>100ms)触发永不生效，但 oscilloscopeTriggerEnabled 可为 true，旧条件错误关闭量化
            // 修复抖动：即使回看历史，只要当前不在拖动中，也使用量化范围稳定波形。
            val useQuantizedRange = !isOscilloscopeManualPanning &&
                triggerOffset == 0
            val intervalLong = samplesPerInterval.toLong()
            val displayEndAbsIdx = if (useQuantizedRange) {
                Math.floorDiv(dataEndAbsIdx, intervalLong) * intervalLong
            } else {
                dataEndAbsIdx
            }
            val rawStartAbsIdx = displayEndAbsIdx - visibleLength
            val displayStartAbsIdx = if (useQuantizedRange) {
                Math.floorDiv(rawStartAbsIdx, intervalLong) * intervalLong
            } else {
                rawStartAbsIdx
            }
            
            // 数据的有效范围（effectiveData 对应的是 dataPanOffset 后的数据区间）
            // effectiveData 范围：[totalSamples-1 - dataPanOffset - effectiveData.size + 1, totalSamples-1 - dataPanOffset]
            // effectiveData[i] 对应绝对样本索引 = totalSamples - 1 - dataPanOffset - effectiveData.size + 1 + i
            val dataPanOffset = waveformDataPanOffset
            val dataStartAbsIdx = effectiveTotalSamples - 1 - dataPanOffset - effectiveData.size + 1
            
            // 常规调试日志（仅节流输出）
            if (shouldLog()) {
                Log.d("WaveformDebug", "[RANGE] totalSamples=$effectiveTotalSamples, visibleLength=$visibleLength, " +
                    "contentWidth=$contentWidth, effectiveData.size=${effectiveData.size}, panOffset=$panOffset, dataPanOffset=$dataPanOffset, samplesPerInterval=$samplesPerInterval")
                Log.d("WaveformDebug", "[DISPLAY] start=$displayStartAbsIdx, end=$displayEndAbsIdx, " +
                    "dataStart=$dataStartAbsIdx, numPoints=$numPoints")
            }
            
            // 调试：检测异常的绘制方向（从左往右 = 时间方向反了）
            // 正常情况：displayStartAbsIdx < displayEndAbsIdx（左边早，右边新）
            if (displayStartAbsIdx >= displayEndAbsIdx) {
                Log.e("WaveformDirection", "=== 异常：波形从左往右绘制（时间方向反转）===")
                Log.e("WaveformDirection", "displayStartAbsIdx($displayStartAbsIdx) >= displayEndAbsIdx($displayEndAbsIdx)")
                Log.e("WaveformDirection", "[原因分析]")
                Log.e("WaveformDirection", "  dataEndAbsIdx = totalSamples($effectiveTotalSamples) - 1 - panOffset($panOffset) - triggerOffset($effectiveTriggerOffset) = $dataEndAbsIdx")
                Log.e("WaveformDirection", "  displayEndAbsIdx = dataEndAbsIdx = $displayEndAbsIdx")
                Log.e("WaveformDirection", "  rawStartAbsIdx = displayEndAbsIdx - visibleLength = ${displayEndAbsIdx - visibleLength}")
                Log.e("WaveformDirection", "  displayStartAbsIdx = 量化后 = $displayStartAbsIdx")
                when {
                    effectiveTotalSamples <= 0 -> Log.e("WaveformDirection", "  → totalSamples 为 0 或负数，数据未初始化")
                    panOffset > effectiveTotalSamples -> Log.e("WaveformDirection", "  → panOffset($panOffset) > totalSamples($effectiveTotalSamples)，平移超出范围")
                    visibleLength <= 0 -> Log.e("WaveformDirection", "  → visibleLength($visibleLength) <= 0，时间窗口异常")
                    else -> Log.e("WaveformDirection", "  → 未知原因，请检查索引计算")
                }
            }
            
            // 计算实际需要绘制的区间数（基于 visibleLength，而非 contentWidth）
            // 这确保我们只绘制有数据的部分
            val actualIntervals = kotlin.math.ceil(visibleLength.toFloat() / samplesPerInterval).toInt()
            
            // 使用复用工作区，避免每帧分配
            val workspaceSize = actualIntervals + 1
            ensureLargeWaveformWorkspace(workspaceSize)
            val yMaxArray = largeYMaxWorkspace
            val yMinArray = largeYMinWorkspace
            val normMaxArray = largeNormMaxWorkspace
            val normMinArray = largeNormMinWorkspace
            val xArray = largeXWorkspace
            
            // 调试：统计无数据的区间
            var noDataIntervalCount = 0
            var firstNoDataInterval = -1
            var lastNoDataInterval = -1
            
            // 修复：x 坐标基于 visibleLength，确保波形覆盖整个屏幕
            // 区间 i 对应的 x 位置 = i * visibleLength / actualIntervals
            // 这样 x 范围是 [0, visibleLength]，与 canvas 变换一致
            
            // 数据的有效范围（绝对索引）
            val dataOffset = effectiveTotalSamples - 1 - dataPanOffset - effectiveData.size + 1
            val dataEndAbsIdxActual = dataOffset + effectiveData.size - 1
            
            for (i in 0..actualIntervals) {
                // 像素 i 对应的绝对时间区间
                val absIntervalStart = displayStartAbsIdx + i.toLong() * samplesPerInterval
                val absIntervalEnd = minOf(absIntervalStart + samplesPerInterval, displayEndAbsIdx)
                
                // 转换为数据数组索引（effectiveData 对应 dataPanOffset 后的数据区间）
                val dataIdxStart = (absIntervalStart - dataOffset).toInt().coerceIn(0, effectiveData.size)
                val dataIdxEnd = (absIntervalEnd - dataOffset).toInt().coerceIn(0, effectiveData.size)
                
                var minSample: Short = 0
                var maxSample: Short = 0
                var sumSample = 0.0
                var sumSquares = 0.0
                var sampleCount = 0
                var hasData = false
                
                // 遍历该区间内的所有数据点，找出 min/max
                if (dataIdxStart < dataIdxEnd) {
                    for (dataIdx in dataIdxStart until dataIdxEnd) {
                        val v = effectiveData[dataIdx]
                        val vf = v.toDouble()
                        if (!hasData) {
                            minSample = v
                            maxSample = v
                            hasData = true
                        } else {
                            if (v < minSample) minSample = v
                            if (v > maxSample) maxSample = v
                        }
                        sumSample += vf
                        sumSquares += vf * vf
                        sampleCount++
                    }
                }
                
                // 统计无数据区间
                if (!hasData) {
                    noDataIntervalCount++
                    if (firstNoDataInterval < 0) firstNoDataInterval = i
                    lastNoDataInterval = i
                }
                
                val normMin: Float
                val normMax: Float
                if (hasData && sampleCount > 0) {
                    if (oscilloscopeLargeWindowUsePeakEnvelope) {
                        normMin = (minSample.toFloat() * gain / maxAmplitude).coerceIn(-1f, 1f)
                        normMax = (maxSample.toFloat() * gain / maxAmplitude).coerceIn(-1f, 1f)
                    } else {
                        // RMS 包络：用均值作为中心，±RMS 作为厚度，避免噪声因取极值而显著变粗
                        val mean = sumSample / sampleCount
                        val rms = sqrt(sumSquares / sampleCount)
                        val centerNorm = (mean.toFloat() * gain / maxAmplitude).coerceIn(-1f, 1f)
                        val halfBandNorm = (rms.toFloat() * gain / maxAmplitude).coerceIn(0f, 1f)
                        normMin = (centerNorm - halfBandNorm).coerceIn(-1f, 1f)
                        normMax = (centerNorm + halfBandNorm).coerceIn(-1f, 1f)
                    }
                } else {
                    normMin = 0f
                    normMax = 0f
                }
                
                // x 坐标：基于 visibleLength，确保覆盖整个屏幕
                // 区间 i 对应 x = i * visibleLength / actualIntervals
                val contentIdx = (i.toFloat() * visibleLength / actualIntervals).toInt()
                xArray[i] = contentIdx.toFloat()
                normMinArray[i] = normMin
                normMaxArray[i] = normMax
            }

            // 大窗口抗混叠 FIR：先在列域做低通，再映射到屏幕坐标。
            // 说明：
            // - 3 点核：[1,2,1]/4，轻量平滑
            // - 5 点核：[1,4,6,4,1]/16，强平滑（当每列样本很多时启用）
            if (oscilloscopeLargeWindowFirEnabled && actualIntervals >= 2) {
                val useFiveTap = samplesPerInterval >= 16
                val srcMin = firSrcMinWorkspace
                val srcMax = firSrcMaxWorkspace
                System.arraycopy(normMinArray, 0, srcMin, 0, workspaceSize)
                System.arraycopy(normMaxArray, 0, srcMax, 0, workspaceSize)
                for (i in 0..actualIntervals) {
                    val l2 = (i - 2).coerceAtLeast(0)
                    val l1 = (i - 1).coerceAtLeast(0)
                    val r1 = (i + 1).coerceAtMost(actualIntervals)
                    val r2 = (i + 2).coerceAtMost(actualIntervals)
                    val filteredMin = if (useFiveTap) {
                        (srcMin[l2] + 4f * srcMin[l1] + 6f * srcMin[i] + 4f * srcMin[r1] + srcMin[r2]) / 16f
                    } else {
                        (srcMin[l1] + 2f * srcMin[i] + srcMin[r1]) / 4f
                    }
                    val filteredMax = if (useFiveTap) {
                        (srcMax[l2] + 4f * srcMax[l1] + 6f * srcMax[i] + 4f * srcMax[r1] + srcMax[r2]) / 16f
                    } else {
                        (srcMax[l1] + 2f * srcMax[i] + srcMax[r1]) / 4f
                    }
                    normMinArray[i] = filteredMin.coerceIn(-1f, 1f)
                    normMaxArray[i] = filteredMax.coerceIn(-1f, 1f)
                    if (normMinArray[i] > normMaxArray[i]) {
                        val m = (normMinArray[i] + normMaxArray[i]) * 0.5f
                        normMinArray[i] = m
                        normMaxArray[i] = m
                    }
                }
            }

            for (i in 0..actualIntervals) {
                yMinArray[i] = sampleToY(normMinArray[i], drawHeight)
                yMaxArray[i] = sampleToY(normMaxArray[i], drawHeight)
            }
            
            // 调试日志：波形未覆盖完整屏幕时打印
            // 右侧空白是严重问题，强制打印（不受节流限制）
            val hasRightGap = lastNoDataInterval == actualIntervals
            if (noDataIntervalCount > 0 && shouldLogWaveformCoverage(hasRightGap)) {
                val gapPercent = noDataIntervalCount * 100 / (actualIntervals + 1)

                val displayRange = displayEndAbsIdx - displayStartAbsIdx
                val dataRange = effectiveData.size
                val reason = when {
                    displayStartAbsIdx < dataOffset -> {
                        val gap = dataOffset - displayStartAbsIdx
                        "左侧空白: 显示起点($displayStartAbsIdx) < 数据起点($dataOffset), 缺少 $gap 样本; 可能原因: 刚启动数据不足/panOffset过大"
                    }
                    displayEndAbsIdx > dataEndAbsIdxActual -> {
                        val gap = displayEndAbsIdx - dataEndAbsIdxActual
                        "右侧空白: 显示终点($displayEndAbsIdx) > 数据终点($dataEndAbsIdxActual), 缺少 $gap 样本; 可能原因: 索引计算错误/dataPanOffset与panOffset不一致"
                    }
                    else -> {
                        "中间有空洞: 数据数组可能包含零值区域"
                    }
                }

                Log.w(
                    "WaveformCoverage",
                    "[LARGE] noData=$noDataIntervalCount/${actualIntervals + 1}(${gapPercent}%), " +
                        "first=$firstNoDataInterval, last=$lastNoDataInterval, " +
                        "display=[$displayStartAbsIdx,$displayEndAbsIdx] span=$displayRange, " +
                        "data=[$dataOffset,$dataEndAbsIdxActual] size=$dataRange, " +
                        "panOffset=$panOffset, dataPanOffset=$dataPanOffset, totalSamples=$effectiveTotalSamples, " +
                        "reason=$reason"
                )
            }
            
            // 大窗口显示低通（EMA）：只平滑显示结果，不改原始采集数据。
            // 仅在“实时跟随”启用；手动平移/触发等场景禁用，保证定位准确。
            val enableEnvelopeSmoothing = !isOscilloscopeManualPanning &&
                panOffset <= 0 &&
                triggerOffset == 0
            if (enableEnvelopeSmoothing) {
                val prevMin = largeWindowPrevYMin
                val prevMax = largeWindowPrevYMax
                val prevSpi = largeWindowPrevSamplesPerInterval
                val prevEnd = largeWindowPrevDisplayEndAbsIdx
                if (prevMin != null && prevMax != null &&
                    prevMin.size == yMinArray.size && prevMax.size == yMaxArray.size &&
                    prevSpi == samplesPerInterval && prevEnd != Long.MIN_VALUE
                ) {
                    // 时序对齐：上帧列(i+colShift)与本帧列i显示同一绝对时间段
                    // 若用 prevMin[i] 直接混合（旧写法），量化窗口每次前移k列时
                    // 会把 k 列前的旧数据掺入当前数据，形成"鬼影"抖动
                    val colShift = ((displayEndAbsIdx - prevEnd) / samplesPerInterval)
                        .toInt().coerceIn(0, actualIntervals + 1)
                    val a = oscilloscopeLargeWindowSmoothingAlpha
                    val invA = 1f - a
                    for (i in yMinArray.indices) {
                        val prevIdx = i + colShift
                        if (prevIdx < prevMin.size) {
                            yMinArray[i] = prevMin[prevIdx] * invA + yMinArray[i] * a
                            yMaxArray[i] = prevMax[prevIdx] * invA + yMaxArray[i] * a
                        }
                        // prevIdx >= size：新出现的列无历史，直接使用当前值（无混合残影）
                    }
                }
                largeWindowPrevYMin = yMinArray.copyOf()
                largeWindowPrevYMax = yMaxArray.copyOf()
                largeWindowPrevDisplayEndAbsIdx = displayEndAbsIdx
                largeWindowPrevSamplesPerInterval = samplesPerInterval
            } else {
                largeWindowPrevYMin = null
                largeWindowPrevYMax = null
                largeWindowPrevDisplayEndAbsIdx = Long.MIN_VALUE
                largeWindowPrevSamplesPerInterval = 0
            }

            // 计算亚像素偏移（可选：设为0则完全量化滚动，波形形状100%稳定但滚动有跳跃感）
            // 设为实际偏移则平滑滚动但可能有轻微形状变化
            val subPixelOffset = 0f  // 量化滚动：形状绝对稳定
            // 闭合路径：上沿→右→下沿→左→闭合
            waveformPath.moveTo(xArray[0], yMaxArray[0])
            for (i in 1..actualIntervals) waveformPath.lineTo(xArray[i], yMaxArray[i])
            waveformPath.lineTo(xArray[actualIntervals], yMinArray[actualIntervals])
            for (i in actualIntervals - 1 downTo 0) waveformPath.lineTo(xArray[i], yMinArray[i])
            waveformPath.close()
            // 先填充实心带，再描边增强轮廓
            val savedStyle = paint.style
            val savedColor = paint.color
            
            // 应用亚像素偏移实现平滑滚动（基于缓存的区间对齐）
            canvas.save()
            canvas.translate(-subPixelOffset, 0f)
            
            paint.style = Paint.Style.FILL
            val fillAlpha = if (oscilloscopeLargeWindowUsePeakEnvelope) 0xCC else 0x88
            paint.color = Color.argb(fillAlpha, Color.red(waveformColor), Color.green(waveformColor), Color.blue(waveformColor))
            canvas.drawPath(waveformPath, paint)

            // 包络边沿描边：仅按 scaleY 补偿，让 Y 方向笔触固定 ~1.5 屏幕像素。
            // 不能用 sqrt(scaleX*scaleY)：大时间窗口时 scaleX 极小，该公式会使 Y 向笔触
            // 随时间窗口放大而暴增（约 strokeBase / sqrt(scaleX) 屏幕像素），导致
            // 零信号区域也呈现十几像素厚的"假带宽"。
            val savedStrokeWidthLocal = paint.strokeWidth
            val envelopeEdgeStrokeWidth = oscilloscopeStrokeWidthBase / oscilloscopeScaleY.coerceAtLeast(0.001f)
            paint.strokeWidth = envelopeEdgeStrokeWidth
            paint.style = Paint.Style.STROKE
            paint.color = savedColor
            canvas.drawPath(waveformPath, paint)
            paint.strokeWidth = savedStrokeWidthLocal
            
            canvas.restore()
            paint.style = savedStyle
            return
        } else {
            // 小时间窗口：使用 Sin(x)/x 插值（专业示波器标准方法）
            // 基于奈奎斯特采样定理，对带限信号可以完美重建原始波形
            // 平移与触发可叠加：先按 panOffset 回看历史，再叠加 triggerOffset 锁定触发点。
            val totalOffset = panOffset + triggerOffset
            
            // 调试：统计超出数据范围的点
            var outOfRangeLeftCount = 0
            var outOfRangeRightCount = 0
            var firstOutOfRangeIdx = -1
            var lastOutOfRangeIdx = -1
            
            // 收集所有绘制点的坐标（复用工作区）
            val smallWorkspaceSize = numPoints + 1
            ensureSmallWaveformWorkspace(smallWorkspaceSize)
            val xPoints = smallXWorkspace
            val yPoints = smallYWorkspace
            
            // 计算数据基准索引（屏幕左边缘对应的数据位置）
            val dataBaseIdx = effectiveData.size - totalOffset - visibleLength
            
            for (i in 0..numPoints) {
                // 精确的浮点位置（在 visibleLength 范围内）
                val exactContentIdx = if (i == numPoints) (visibleLength - 1).toFloat() else i * step
                
                // 对应的精确数据位置（浮点）
                val exactDataIdx = dataBaseIdx + exactContentIdx
                
                // 使用 Sin(x)/x 插值重建波形
                val sample = sincInterpolate(effectiveData, exactDataIdx, gain)
                
                // 检查是否超出数据范围
                if (exactDataIdx < 0) {
                    outOfRangeLeftCount++
                    if (firstOutOfRangeIdx < 0) firstOutOfRangeIdx = i
                } else if (exactDataIdx >= effectiveData.size) {
                    outOfRangeRightCount++
                    lastOutOfRangeIdx = i
                }
                
                val normalizedSample = (sample / maxAmplitude).coerceIn(-1f, 1f)
                // 使用浮点 x 坐标，而不是整数化的 contentIdx，避免阶梯效果
                // 将绘制点均匀分布在 visibleLength 范围内
                xPoints[i] = exactContentIdx
                yPoints[i] = sampleToY(normalizedSample, drawHeight)
            }
            
            // 使用三次贝塞尔曲线连接插值后的点，进一步提升视觉平滑度
            // 当采样点数量适中时启用曲线绘制（太密集时直线足够，太稀疏时也启用曲线）
            // step > 0.5 表示采样点稀疏；visibleLength < numPoints 表示每个采样点分摊到多个像素
            val useCurveDrawing = numPoints >= 4 && (step > 0.5f || visibleLength < numPoints)
            
            if (useCurveDrawing) {
                waveformPath.moveTo(xPoints[0], yPoints[0])
                
                for (i in 1..numPoints) {
                    // 获取相邻四个点用于计算贝塞尔控制点
                    val p0x = if (i > 1) xPoints[i - 2] else xPoints[0]
                    val p0y = if (i > 1) yPoints[i - 2] else yPoints[0]
                    val p1x = xPoints[i - 1]
                    val p1y = yPoints[i - 1]
                    val p2x = xPoints[i]
                    val p2y = yPoints[i]
                    val p3x = if (i < numPoints) xPoints[i + 1] else xPoints[numPoints]
                    val p3y = if (i < numPoints) yPoints[i + 1] else yPoints[numPoints]
                    
                    // Catmull-Rom 到 Bezier 控制点转换
                    val cp1x = p1x + (p2x - p0x) / 6f
                    val cp1y = p1y + (p2y - p0y) / 6f
                    val cp2x = p2x - (p3x - p1x) / 6f
                    val cp2y = p2y - (p3y - p1y) / 6f
                    
                    waveformPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y)
                }
            } else {
                // 采样点足够密集，直接用直线连接
                waveformPath.moveTo(xPoints[0], yPoints[0])
                for (i in 1..numPoints) {
                    waveformPath.lineTo(xPoints[i], yPoints[i])
                }
            }
            
            // 调试日志
            val totalOutOfRange = outOfRangeLeftCount + outOfRangeRightCount
            if (totalOutOfRange > 0 && shouldLogWaveformCoverage(outOfRangeRightCount > 0)) {
                val totalPoints = numPoints + 1
                val gapPercent = totalOutOfRange * 100 / totalPoints
                val rightDataIdx = effectiveData.size - totalOffset - 1
                val leftDataIdx = effectiveData.size - totalOffset - visibleLength
                val reason = when {
                    outOfRangeRightCount > 0 -> {
                        "右侧空白: 数据不够填满屏幕右边; 可能原因: triggerOffset过大/effectiveData过小/dataPanOffset与panOffset不一致"
                    }
                    outOfRangeLeftCount > 0 -> {
                        "左侧空白: 数据不够填满屏幕左边（可能刚启动）"
                    }
                    else -> "未知"
                }
                Log.w(
                    "WaveformCoverage",
                    "[SMALL] outOfRange=$totalOutOfRange/$totalPoints(${gapPercent}%), " +
                        "left=$outOfRangeLeftCount, right=$outOfRangeRightCount, " +
                        "dataSize=${effectiveData.size}, visibleLength=$visibleLength, triggerOffset=$totalOffset, " +
                        "panOffset=$panOffset, dataPanOffset=$waveformDataPanOffset, totalSamples=$waveformTotalSamples, " +
                        "dataIdxRange=[$leftDataIdx,$rightDataIdx], reason=$reason"
                )
            }
        }
        
        // 应用平滑滚动位移（向左移动）
        canvas.save()
        canvas.translate(-smoothScrollPixels, 0f)
        canvas.drawPath(waveformPath, paint)
        canvas.restore()
    }
    
    /**
     * 频率标记用到的频率列表：缩放时补充更密，与网格刻度逻辑一致。
     * 律制模式下放大时使用半音刻度，线性/对数模式补充 5,15,...,150,250,... 等
     */
    private fun frequencyMarkerFrequencies(maxFreq: Float): List<Float> {
        if (scaleMode == ScaleMode.TWELVE_TET && spectrumScaleX > 1.5f) {
            return gridFrequencies(maxFreq)
        }
        val base = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
        if (spectrumScaleX <= 1.5f) {
            return base.filter { it <= maxFreq }
        }
        val list = base.filter { it <= maxFreq }.toMutableList()
        var g = 5f
        while (g <= maxFreq && g < 100f) {
            list.add(g)
            g += 10f
        }
        g = 150f
        while (g <= maxFreq && g < 1000f) {
            list.add(g)
            g += 100f
        }
        g = 1500f
        while (g <= maxFreq) {
            list.add(g)
            g += 1000f
        }
        return list.distinct().sorted().filter { it <= maxFreq }
    }

    /** 频率标记线（在变换空间内绘制，与数据对齐；线宽按 strokeDivisor 补偿，缩放/平移时粗细不变）
     *  使用 gridExtent 超大范围绘制，由 clip 裁切，确保纵向贯穿不显示尽头 */
    private fun drawFrequencyMarkerLines(canvas: Canvas, drawWidth: Float, drawHeight: Float, strokeDivisor: Float) {
        val frequencies = frequencyMarkerFrequencies(sampleRate / 2f)
        val maxFreq = sampleRate / 2f
        val savedColor = gridPaint.color
        val savedStrokeWidth = gridPaint.strokeWidth
        gridPaint.color = frequencyMarkerColor
        gridPaint.strokeWidth = spectrumMarkerStrokeWidth / strokeDivisor.coerceAtLeast(0.01f)
        for (freq in frequencies) {
            if (freq > maxFreq) continue
            val x = freqToX(freq, maxFreq, drawWidth)
            // 使用 gridExtent 确保线条在缩放/平移时不会看到尽头
            canvas.drawLine(x, -gridExtent, x, gridExtent, gridPaint)
        }
        gridPaint.color = savedColor
        gridPaint.strokeWidth = savedStrokeWidth
    }

    /** 频率标记文字：屏幕空间绘制，不变形；过近时自动隐藏避免重叠 */
    private fun drawFrequencyMarkerLabelsScreenSpace(
        canvas: Canvas, drawWidth: Float,
        centerX: Float, centerY: Float, width: Float, height: Float
    ) {
        val frequencies = frequencyMarkerFrequencies(sampleRate / 2f)
        val maxFreq = sampleRate / 2f
        val bottomY = height - paddingBottom + 22f
        textPaint.textSize = 18f
        textPaint.textAlign = Paint.Align.CENTER
        val labelMargin = 4f
        val items = frequencies.filter { it <= maxFreq }.mapNotNull { freq ->
            val contentX = freqToX(freq, maxFreq, drawWidth)
            val screenX = contentToScreenXSpectrum(contentX, drawWidth)
            // 十二平均律模式显示音高，其他模式显示频率
            val label = if (scaleMode == ScaleMode.TWELVE_TET) {
                frequencyToNoteName(freq)
            } else {
                when {
                    freq >= 1000 -> "${freq / 1000}kHz"
                    else -> "${freq.toInt()}Hz"
                }
            }
            val halfW = textPaint.measureText(label) / 2f
            if (screenX - halfW < paddingLeft + labelMargin || screenX + halfW > width - paddingRight - labelMargin) null
            else Triple(screenX, label, halfW)
        }.sortedBy { it.first }
        var lastRight = paddingLeft - minHorizontalLabelGap
        for ((screenX, label, halfW) in items) {
            val left = screenX - halfW
            if (left >= lastRight + minHorizontalLabelGap) {
                canvas.drawText(label, screenX, bottomY, textPaint)
                lastRight = screenX + halfW
            }
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    /** 峰值圆点：在屏幕空间绘制（restore 之后），固定像素大小，确保始终可见 */
    private fun drawPeakCirclesScreenSpace(
        canvas: Canvas, drawWidth: Float, drawHeight: Float,
        centerX: Float, centerY: Float,
        visibleDbTop: Float, visibleDbBottom: Float
    ) {
        if (peakFrequencies.isEmpty()) return
        val maxFreq = sampleRate / 2f
        val savedStyle = peakPaint.style
        peakPaint.style = Paint.Style.FILL
        peakPaint.strokeWidth = 2f
        for ((freq, amplitude) in peakFrequencies) {
            val contentX = freqToX(freq, maxFreq, drawWidth)
            val slopeFactor = 10f.pow(-spectrumSlope * log10(freq / 1000f + 1f) / 20f)
            val amp = (amplitude * gain * slopeFactor).coerceIn(0f, 1f)
            val db = amplitudeToDbSpectrum(amp)
            val contentY = dbToY(db, drawHeight, visibleDbTop, visibleDbBottom)
            val screenX = contentToScreenXSpectrum(contentX, drawWidth)
            // 频谱模式 Y 轴无 canvas scale，dbToY 已返回满屏坐标，contentToScreenY 用 scale=1
            val screenY = contentToScreenY(contentY, centerY, 0f, 1f)
            if (screenX < paddingLeft - 2f || screenX > width - paddingRight + 2f) continue
            if (screenY < paddingTop - 2f || screenY > height - paddingBottom + 2f) continue
            canvas.drawCircle(screenX, screenY, 8f, peakPaint)
        }
        peakPaint.style = savedStyle
    }

    /** 峰值标签：屏幕空间绘制，不变形；与频谱曲线一致应用 gain 与 spectrumSlope */
    private fun drawPeakLabelsScreenSpace(
        canvas: Canvas, drawWidth: Float, drawHeight: Float,
        centerX: Float, centerY: Float,
        visibleDbTop: Float, visibleDbBottom: Float
    ) {
        if (peakFrequencies.isEmpty()) return
        val maxFreq = sampleRate / 2f
        textPaint.textSize = 18f
        textPaint.textAlign = Paint.Align.LEFT
        for ((freq, amplitude) in peakFrequencies) {
            val contentX = freqToX(freq, maxFreq, drawWidth)
            val slopeFactor = 10f.pow(-spectrumSlope * log10(freq / 1000f + 1f) / 20f)
            val amp = (amplitude * gain * slopeFactor).coerceIn(0f, 1f)
            val db = amplitudeToDbSpectrum(amp)
            val contentY = dbToY(db, drawHeight, visibleDbTop, visibleDbBottom)
            val screenX = contentToScreenXSpectrum(contentX, drawWidth)
            // 频谱模式 Y 轴无 canvas scale，dbToY 已返回满屏坐标，contentToScreenY 用 scale=1
            val screenY = contentToScreenY(contentY, centerY, 0f, 1f)
            if (screenX < paddingLeft - 2f || screenX > width - paddingRight + 2f) continue
            if (screenY < paddingTop - 2f || screenY > height - paddingBottom + 2f) continue
            // 十二平均律模式显示音高，其他模式显示频率
            val label = if (scaleMode == ScaleMode.TWELVE_TET) {
                frequencyToNoteName(freq)
            } else {
                when {
                    freq >= 1000 -> "${String.format("%.1f", freq / 1000)}kHz"
                    else -> "${freq.toInt()}Hz"
                }
            }
            canvas.drawText(label, screenX + 10f, screenY - 10f, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }
    
    // 可视化模式调试计数器
    private var visualizerDebugCounter = 0
    
    /**
     * 绘制可视化模式（复古 LED 频谱条）
     * 将频谱数据分成多个频段，用固定 20 行的 LED 格子显示每个频段的能量
     * 颜色分布：底部 10 行绿色，中间 7 行黄色，顶部 3 行红色
     */
    private fun drawVisualizer(canvas: Canvas, data: FloatArray, drawWidth: Float, drawHeight: Float) {
        if (data.isEmpty()) {
            android.util.Log.w("Oscilloscope", "Visualizer: data is empty!")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        val deltaTime = if (visualizerLastUpdateTime > 0) {
            (currentTime - visualizerLastUpdateTime) / 1000f
        } else {
            0f
        }
        visualizerLastUpdateTime = currentTime
        
        // 确保数组大小正确
        if (visualizerBarValues.size != visualizerBarCount) {
            visualizerBarValues = FloatArray(visualizerBarCount)
            visualizerPeakValues = FloatArray(visualizerBarCount)
            visualizerPeakHoldTimes = LongArray(visualizerBarCount)
        }
        
        val maxFreq = sampleRate / 2f
        val dataSize = data.size
        
        // 使用对数分布来分配频段，低频更多，高频更少（符合人耳感知）
        val minFreq = 20f  // 最低频率 20Hz
        val logMin = ln(minFreq)
        val logMax = ln(maxFreq)
        val logRange = logMax - logMin
        
        // dB 范围固定；灵敏度作为“对数旋钮”映射到整体增益
        // 旋钮范围 0.5~10.0 -> 增益范围约 0.5x ~ 63x（-6dB ~ +36dB）
        val minSensitivity = 0.5f
        val maxSensitivity = 10.0f
        val sensitivityKnob = visualizerSensitivity.coerceIn(minSensitivity, maxSensitivity)
        val knobT = ((sensitivityKnob - minSensitivity) / (maxSensitivity - minSensitivity)).coerceIn(0f, 1f)
        val sensitivityGainDb = -6f + knobT * 42f
        val sensitivityGain = 10f.pow(sensitivityGainDb / 20f)
        val dbMin = -80f
        val dbMax = -10f  // 最高 -10dB
        val dbRange = dbMax - dbMin
        
        // 调试：检查输入数据范围
        var dataMin = Float.MAX_VALUE
        var dataMax = Float.MIN_VALUE
        for (v in data) {
            if (v < dataMin) dataMin = v
            if (v > dataMax) dataMax = v
        }
        
        // 计算每个频段的能量
        var debugMaxDb = -100f
        var debugMaxValue = 0f
        
        for (i in 0 until visualizerBarCount) {
            // 对数分布的频率范围
            val freqLow = exp(logMin + logRange * i / visualizerBarCount)
            val freqHigh = exp(logMin + logRange * (i + 1) / visualizerBarCount)
            
            // 将频率转换为 FFT bin 索引
            val binLow = ((freqLow / maxFreq) * dataSize).toInt().coerceIn(0, dataSize - 1)
            val binHigh = ((freqHigh / maxFreq) * dataSize).toInt().coerceIn(binLow + 1, dataSize)
            
            // 计算该频段的最大幅度值
            var maxAmplitude = 0f
            for (bin in binLow until binHigh) {
                val amplitude = data[bin] * gain * sensitivityGain
                // 应用频谱斜率补偿（高频增益）
                val freq = (bin.toFloat() / dataSize) * maxFreq
                val slopeFactor = 10f.pow(visualizerSlope * log10((freq / 1000f).coerceAtLeast(0.02f)) / 20f)
                val adjusted = amplitude * slopeFactor
                if (adjusted > maxAmplitude) {
                    maxAmplitude = adjusted
                }
            }
            
            // 转换为 dB 并映射到 0~1 范围
            val db = if (maxAmplitude > 0.00001f) {
                20f * log10(maxAmplitude)
            } else {
                -100f
            }
            if (db > debugMaxDb) debugMaxDb = db
            
            // 将 dB 映射到 0~1：dbMin -> 0, dbMax -> 1
            val targetValue = ((db - dbMin) / dbRange).coerceIn(0f, 1f)
            if (targetValue > debugMaxValue) debugMaxValue = targetValue
            
            // 平滑下落效果
            val currentValue = visualizerBarValues[i]
            visualizerBarValues[i] = if (targetValue > currentValue) {
                // 快速上升
                targetValue
            } else {
                // 缓慢下落
                (currentValue - visualizerBarFallSpeed * deltaTime).coerceAtLeast(targetValue)
            }
            
            // 峰值保持
            if (visualizerBarValues[i] > visualizerPeakValues[i]) {
                visualizerPeakValues[i] = visualizerBarValues[i]
                visualizerPeakHoldTimes[i] = currentTime
            } else if (currentTime - visualizerPeakHoldTimes[i] > visualizerPeakHoldDurationMs) {
                // 峰值开始下落
                visualizerPeakValues[i] = (visualizerPeakValues[i] - visualizerPeakFallSpeed * deltaTime)
                    .coerceAtLeast(visualizerBarValues[i])
            }
        }
        
        // 每 300 帧输出一次调试信息，避免高刷新率设备刷屏
        visualizerDebugCounter++
        if (visualizerDebugCounter % 300 == 0) {
            DebugLog.d(Tag.AUDIO) { 
                "Visualizer DEBUG: dataSize=$dataSize, dataRange=[$dataMin, $dataMax], " +
                "gain=$gain, sensitivity=$visualizerSensitivity, sensitivityGain=${String.format("%.2f", sensitivityGain)}x, dbMin=$dbMin, " +
                "maxDb=$debugMaxDb, maxValue=$debugMaxValue, maxBarValue=${visualizerBarValues.maxOrNull()}"
            }
        }
        
        // ========== 复古 LED 格子绘制 ==========
        val totalRows = 21  // 固定 21 行 LED 格子（多一行方便显示 0dB 标签）
        val greenRows = 10  // 底部 10 行绿色
        val yellowRows = 7  // 中间 7 行黄色
        // val redRows = 4  // 顶部 4 行红色
        
        // 定义颜色（复古 LED 风格，稍微暗一点的饱和色）
        val greenColor = Color.rgb(0, 220, 0)      // 亮绿色
        val yellowColor = Color.rgb(255, 200, 0)   // 橙黄色
        val redColor = Color.rgb(255, 50, 50)      // 亮红色
        val dimGreenColor = Color.rgb(0, 40, 0)    // 暗绿色（未亮起）
        val dimYellowColor = Color.rgb(50, 40, 0)  // 暗黄色（未亮起）
        val dimRedColor = Color.rgb(50, 10, 10)    // 暗红色（未亮起）
        
        // 绘制区域 - 为标签留出空间
        val labelPaddingLeft = 45f   // 左侧标签空间
        val labelPaddingBottom = 25f // 底部标签空间
        val left = paddingLeft.toFloat() + labelPaddingLeft
        val top = paddingTop.toFloat()
        val bottom = top + drawHeight - labelPaddingBottom
        val effectiveHeight = bottom - top
        val effectiveWidth = drawWidth - labelPaddingLeft
        
        // 计算每个格子的尺寸
        val barWidthWithGap = effectiveWidth / visualizerBarCount
        val horizontalGap = barWidthWithGap * visualizerBarGap
        val barWidth = barWidthWithGap - horizontalGap
        
        // 垂直方向：格子高度和间隙
        val verticalGapRatio = 0.15f  // 垂直间隙占比
        val cellHeightWithGap = effectiveHeight / totalRows
        val verticalGap = cellHeightWithGap * verticalGapRatio
        val cellHeight = cellHeightWithGap - verticalGap
        
        visualizerBarPaint.shader = null  // 不使用渐变，使用固定颜色
        
        for (i in 0 until visualizerBarCount) {
            val barLeft = left + i * barWidthWithGap + horizontalGap / 2
            val barRight = barLeft + barWidth
            val barValue = visualizerBarValues[i]
            
            // 计算应该亮起多少行（0~20）
            val litRows = (barValue * totalRows).toInt().coerceIn(0, totalRows)
            
            // 计算峰值行
            val peakRow = if (visualizerPeakHold && visualizerPeakValues[i] > 0.01f) {
                (visualizerPeakValues[i] * totalRows).toInt().coerceIn(0, totalRows - 1)
            } else {
                -1
            }
            
            // 从底部向上绘制每一行
            for (row in 0 until totalRows) {
                // 计算该行的位置（row 0 在底部，row 19 在顶部）
                val cellBottom = bottom - row * cellHeightWithGap
                val cellTop = cellBottom - cellHeight
                
                // 确定该行的颜色区域
                val isLit = row < litRows
                val isPeak = row == peakRow
                
                // 根据行号确定颜色
                val baseColor: Int
                val dimColor: Int
                when {
                    row >= greenRows + yellowRows -> {
                        // 顶部红色区域（行 17-19，即第 18-20 行）
                        baseColor = redColor
                        dimColor = dimRedColor
                    }
                    row >= greenRows -> {
                        // 中间黄色区域（行 10-16，即第 11-17 行）
                        baseColor = yellowColor
                        dimColor = dimYellowColor
                    }
                    else -> {
                        // 底部绿色区域（行 0-9，即第 1-10 行）
                        baseColor = greenColor
                        dimColor = dimGreenColor
                    }
                }
                
                // 设置颜色并绘制
                visualizerBarPaint.color = when {
                    isPeak -> Color.WHITE  // 峰值指示器为白色
                    isLit -> baseColor     // 亮起的格子
                    else -> dimColor       // 未亮起的格子（暗色背景）
                }
                
                canvas.drawRect(barLeft, cellTop, barRight, cellBottom, visualizerBarPaint)
            }
        }
        
        // ========== 绘制坐标标签 ==========
        textPaint.textSize = 12f
        textPaint.color = Color.WHITE
        
        // 左侧 dB 标签：每 2 行显示一次，位于格子中心
        textPaint.textAlign = Paint.Align.RIGHT
        val dbPerRow = dbRange / totalRows
        for (row in 0 until totalRows step 2) {
            // 计算该行对应的 dB 值
            val rowDb = dbMin + (row + 0.5f) * dbPerRow  // +0.5 使标签对齐格子中心
            val cellCenterY = bottom - row * cellHeightWithGap - cellHeightWithGap / 2
            val dbText = "${rowDb.toInt()} dB"
            canvas.drawText(dbText, left - 5f, cellCenterY + 5f, textPaint)
        }
        
        // 底部频率标签：优先尝试每个条都显示；空间不足时回退到隔一个显示
        textPaint.textAlign = Paint.Align.CENTER
        val labelPadding = 4f
        val chartRight = left + effectiveWidth

        fun buildFreqLabels(step: Int): List<Triple<Float, String, Float>> {
            val items = ArrayList<Triple<Float, String, Float>>()
            for (i in 0 until visualizerBarCount step step) {
                val freqLow = exp(logMin + logRange * i / visualizerBarCount)
                val freqHigh = exp(logMin + logRange * (i + 1) / visualizerBarCount)
                val freqCenter = (freqLow + freqHigh) / 2
                val barCenterX = left + i * barWidthWithGap + barWidthWithGap / 2
                val freqText = when {
                    freqCenter >= 1000 -> "${(freqCenter / 1000).toInt()}k"
                    freqCenter >= 100 -> "${freqCenter.toInt()}"
                    else -> "${freqCenter.toInt()}"
                }
                val halfW = textPaint.measureText(freqText) / 2f
                items.add(Triple(barCenterX, freqText, halfW))
            }
            return items
        }

        fun canPlaceAll(items: List<Triple<Float, String, Float>>): Boolean {
            var lastRight = left + labelPadding
            for ((centerX, _, halfW) in items) {
                val labelLeft = centerX - halfW
                val labelRight = centerX + halfW
                if (labelLeft < left + labelPadding || labelRight > chartRight - labelPadding) return false
                if (labelLeft < lastRight + labelPadding) return false
                lastRight = labelRight
            }
            return true
        }

        val allLabels = buildFreqLabels(step = 1)
        val labelsToDraw = if (canPlaceAll(allLabels)) allLabels else buildFreqLabels(step = 2)
        for ((centerX, label, _) in labelsToDraw) {
            canvas.drawText(label, centerX, bottom + 18f, textPaint)
        }
    }
    
    enum class DisplayMode {
        SPECTRUM,           // 频谱模式
        OSCILLOSCOPE,       // 示波器模式
        VISUALIZER,         // 可视化模式（功放机式频谱条）
        SOUND_LEVEL_METER   // 分贝计模式
    }
    
    enum class ScaleMode {
        LINEAR,        // 线性缩放
        LOGARITHMIC,   // 对数缩放
        TWELVE_TET     // 十二平均律（横轴按半音等分，每八度等宽）
    }
}

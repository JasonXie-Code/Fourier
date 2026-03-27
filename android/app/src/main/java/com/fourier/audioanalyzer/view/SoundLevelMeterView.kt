package com.fourier.audioanalyzer.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.fourier.audioanalyzer.R
import kotlin.math.*

/**
 * 分贝计视图（横屏专用）
 * 
 * 布局：
 * +------------------------------------------------------------------+
 * | [当前dB大数字]  |  [柱状指示器]              | [统计信息]          |
 * |   72.3 dB(A)  |  ████████░░░░░░░░░░░░░     | 最大: 85.2 dB      |
 * |               |  30   50   70   90   110   | 最小: 45.1 dB      |
 * | 峰值: 85.2    |  [历史波形图]               | 平均: 68.7 dB      |
 * |               |                            | Leq: 70.2 dB       |
 * +------------------------------------------------------------------+
 */
class SoundLevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ========== 画笔 ==========
    private val mainValuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val statPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val historyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakHoldPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ========== 分贝计量方式 ==========
    enum class WeightingType {
        A,      // A 加权 (dBA) - 模拟人耳响应
        C,      // C 加权 (dBC) - 近似平坦，用于峰值
        Z,      // Z 加权 (dBZ) - 完全平坦，无加权
        FLAT    // 未加权，同 dBFS
    }

    // ========== 响应时间 ==========
    enum class ResponseTime {
        FAST,   // 125ms
        SLOW    // 1000ms
    }

    // ========== 设置项 ==========
    var weightingType: WeightingType = WeightingType.A
        set(value) {
            field = value
            invalidate()
        }

    var responseTime: ResponseTime = ResponseTime.FAST
        set(value) {
            field = value
            // 更新时间常数（毫秒）：Fast=125ms, Slow=1000ms
            responseTimeConstantMs = if (value == ResponseTime.FAST) 125f else 1000f
            // Slow 模式下左侧大字每秒更新一次
            displayValueRefreshIntervalMs = if (value == ResponseTime.FAST) 0L else 1000L
        }

    /** 校准偏移量（dB）：SPL = dBFS + calibrationOffset */
    var calibrationOffset: Float = 94f  // 默认假设满量程对应 94 dB SPL
        set(value) {
            field = value
            invalidate()
        }

    /** 是否显示 SPL（校准后的值），否则显示 dBFS */
    var showSPL: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    // ========== 显示范围 ==========
    private val minDisplayDb = 30f   // 最小显示分贝
    private val maxDisplayDb = 120f  // 最大显示分贝
    private val dbRange = maxDisplayDb - minDisplayDb

    // ========== 当前值 ==========
    private var currentDbFS: Float = -60f  // 当前 dBFS（平滑后）
    private var rawDbFS: Float = -60f      // 原始 dBFS
    private var peakHoldDb: Float = -60f   // 峰值保持
    private var peakHoldTime: Long = 0L    // 峰值保持时间戳
    private val peakHoldDurationMs = 2000L // 峰值保持 2 秒

    // ========== 统计值 ==========
    private var maxDb: Float = -Float.MAX_VALUE
    private var minDb: Float = Float.MAX_VALUE
    private var sumDb: Double = 0.0
    private var sumDbSquared: Double = 0.0  // 用于 Leq 计算
    private var sampleCount: Long = 0
    private var measurementStartTime: Long = 0L

    // ========== 历史记录 ==========
    private val historySize = 300  // 约 10 秒 @ 30fps
    private val historyData = FloatArray(historySize) { -60f }
    private var historyIndex = 0

    // ========== 平滑参数（指数平滑，符合 IEC 61672 标准）==========
    private var responseTimeConstantMs = 125f  // Fast: 125ms, Slow: 1000ms
    private var lastUpdateTimeMs = 0L  // 上次更新时间
    // Slow 模式下左侧大字每秒更新一次
    private var displayValueRefreshIntervalMs = 0L  // 0=不限制, 1000=每秒一次
    private var lastDisplayValueRefreshTimeMs = 0L
    private var displayDbCached = -60f  // 缓存的显示值（用于左侧大字）
    private var displayPeakDbCached = -60f  // 缓存的峰值显示

    /**
     * 计算基于时间的平滑系数
     * α = 1 - exp(-Δt/τ)，其中 τ 是时间常数
     * 符合标准声级计的指数时间加权
     */
    private fun calculateSmoothingAlpha(): Float {
        val now = System.currentTimeMillis()
        val deltaMs = if (lastUpdateTimeMs > 0) (now - lastUpdateTimeMs).toFloat() else 33f
        lastUpdateTimeMs = now
        // 限制 deltaMs 在合理范围内，避免极端值
        val clampedDelta = deltaMs.coerceIn(10f, 200f)
        return (1f - kotlin.math.exp(-clampedDelta / responseTimeConstantMs)).coerceIn(0.01f, 1f)
    }

    /**
     * 更新缓存的显示值（Slow 模式下左侧大字每秒更新一次）
     */
    private fun updateCachedDisplayValue() {
        val displayDb = if (showSPL) (currentDbFS + calibrationOffset) else currentDbFS
        val displayPeakDb = if (showSPL) peakHoldDb else (peakHoldDb - calibrationOffset)
        
        if (displayValueRefreshIntervalMs <= 0L) {
            // Fast 模式：实时更新
            displayDbCached = displayDb
            displayPeakDbCached = displayPeakDb
        } else {
            // Slow 模式：每秒更新一次
            val now = System.currentTimeMillis()
            if (now - lastDisplayValueRefreshTimeMs >= displayValueRefreshIntervalMs) {
                lastDisplayValueRefreshTimeMs = now
                displayDbCached = displayDb
                displayPeakDbCached = displayPeakDb
            }
        }
        // 界面始终实时刷新（波形图等需要实时更新）
        invalidate()
    }

    // ========== A 加权滤波器系数（简化版，基于 IEC 61672） ==========
    // 预计算的 A 加权曲线（相对于 1kHz 的增益，单位 dB）
    private val aWeightingFreqs = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
        200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
        2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
    )
    private val aWeightingGains = floatArrayOf(
        -50.5f, -44.7f, -39.4f, -34.6f, -30.2f, -26.2f, -22.5f, -19.1f, -16.1f, -13.4f,
        -10.9f, -8.6f, -6.6f, -4.8f, -3.2f, -1.9f, -0.8f, 0f, 0.6f, 1.0f,
        1.2f, 1.3f, 1.2f, 1.0f, 0.5f, -0.1f, -1.1f, -2.5f, -4.3f, -6.6f, -9.3f
    )

    // C 加权曲线
    private val cWeightingGains = floatArrayOf(
        -6.2f, -4.4f, -3.0f, -2.0f, -1.3f, -0.8f, -0.5f, -0.3f, -0.2f, -0.1f,
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -0.1f,
        -0.2f, -0.3f, -0.5f, -0.8f, -1.3f, -2.0f, -3.0f, -4.4f, -6.2f, -8.5f, -11.2f
    )

    // ========== 颜色 ==========
    private val colorGreen = Color.rgb(76, 175, 80)
    private val colorYellow = Color.rgb(255, 193, 7)
    private val colorOrange = Color.rgb(255, 152, 0)
    private val colorRed = Color.rgb(244, 67, 54)
    private val colorBarBg = Color.argb(60, 255, 255, 255)
    private val colorGrid = Color.argb(80, 255, 255, 255)
    private val colorText = Color.WHITE
    private val colorTextSecondary = Color.argb(180, 255, 255, 255)
    private val colorPeakHold = Color.rgb(255, 235, 59)

    init {
        mainValuePaint.color = colorText
        mainValuePaint.textAlign = Paint.Align.CENTER
        mainValuePaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        unitPaint.color = colorTextSecondary
        unitPaint.textAlign = Paint.Align.CENTER

        labelPaint.color = colorTextSecondary
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = 24f

        statPaint.color = colorText
        statPaint.textAlign = Paint.Align.LEFT
        statPaint.textSize = 28f

        barBgPaint.color = colorBarBg
        barBgPaint.style = Paint.Style.FILL

        gridPaint.color = colorGrid
        gridPaint.strokeWidth = 1f
        gridPaint.style = Paint.Style.STROKE

        historyPaint.color = colorGreen
        historyPaint.strokeWidth = 2f
        historyPaint.style = Paint.Style.STROKE

        peakHoldPaint.color = colorPeakHold
        peakHoldPaint.strokeWidth = 3f
        peakHoldPaint.style = Paint.Style.FILL
    }

    /**
     * 更新音频数据
     * @param samples 音频样本数组
     * @param sampleRate 采样率
     */
    fun updateAudioData(samples: ShortArray, sampleRate: Int) {
        if (samples.isEmpty()) return

        // 计算 RMS
        var sumSquares = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / samples.size)

        // 计算 dBFS
        rawDbFS = if (rms > 0) (20 * log10(rms)).toFloat() else -100f
        rawDbFS = rawDbFS.coerceIn(-100f, 0f)

        // 应用加权（简化版：直接在时域应用整体增益调整）
        // 完整实现需要对频谱进行加权，这里用经验公式近似
        val weightedDbFS = when (weightingType) {
            WeightingType.A -> rawDbFS + getApproximateAWeighting(samples, sampleRate)
            WeightingType.C -> rawDbFS + getApproximateCWeighting(samples, sampleRate)
            WeightingType.Z, WeightingType.FLAT -> rawDbFS
        }

        // 平滑处理（基于时间常数的指数平滑）
        val alpha = calculateSmoothingAlpha()
        currentDbFS = currentDbFS + alpha * (weightedDbFS - currentDbFS)

        // 转换为 SPL
        val currentSPL = currentDbFS + calibrationOffset

        // 更新峰值保持
        val now = System.currentTimeMillis()
        if (currentSPL > peakHoldDb || now - peakHoldTime > peakHoldDurationMs) {
            peakHoldDb = currentSPL
            peakHoldTime = now
        }

        // 更新统计
        if (measurementStartTime == 0L) {
            measurementStartTime = now
            resetStatistics()
        }
        
        val displayDb = if (showSPL) currentSPL else currentDbFS
        if (displayDb > maxDb) maxDb = displayDb
        if (displayDb < minDb && displayDb > -90f) minDb = displayDb
        sumDb += displayDb
        // Leq 计算：10 * log10(平均(10^(Li/10)))
        sumDbSquared += 10.0.pow(displayDb / 10.0)
        sampleCount++

        // 更新历史
        historyData[historyIndex] = displayDb
        historyIndex = (historyIndex + 1) % historySize

        // 更新缓存的显示值并刷新界面
        updateCachedDisplayValue()
    }

    /**
     * 使用频谱数据更新（更精确的加权计算）
     */
    fun updateWithSpectrum(magnitudes: FloatArray, sampleRate: Int) {
        if (magnitudes.isEmpty()) return

        val freqResolution = sampleRate.toFloat() / (magnitudes.size * 2)
        var weightedPower = 0.0

        for (i in magnitudes.indices) {
            val freq = i * freqResolution
            val magnitude = magnitudes[i].coerceIn(0f, 1f)
            val power = magnitude * magnitude

            // 应用频率加权
            val weightGain = when (weightingType) {
                WeightingType.A -> getAWeightingGain(freq)
                WeightingType.C -> getCWeightingGain(freq)
                WeightingType.Z, WeightingType.FLAT -> 0f
            }
            val linearGain = 10f.pow(weightGain / 20f)
            weightedPower += power * linearGain * linearGain
        }

        // 计算加权后的 dBFS
        val weightedRms = sqrt(weightedPower / magnitudes.size)
        rawDbFS = if (weightedRms > 0) (20 * log10(weightedRms)).toFloat() else -100f
        rawDbFS = rawDbFS.coerceIn(-100f, 0f)

        // 平滑处理（基于时间常数的指数平滑）
        val alpha = calculateSmoothingAlpha()
        currentDbFS = currentDbFS + alpha * (rawDbFS - currentDbFS)

        // 转换为 SPL
        val currentSPL = currentDbFS + calibrationOffset

        // 更新峰值保持
        val now = System.currentTimeMillis()
        if (currentSPL > peakHoldDb || now - peakHoldTime > peakHoldDurationMs) {
            peakHoldDb = currentSPL
            peakHoldTime = now
        }

        // 更新统计
        if (measurementStartTime == 0L) {
            measurementStartTime = now
            resetStatistics()
        }

        val displayDb = if (showSPL) currentSPL else currentDbFS
        if (displayDb > maxDb) maxDb = displayDb
        if (displayDb < minDb && displayDb > -90f) minDb = displayDb
        sumDb += displayDb
        sumDbSquared += 10.0.pow(displayDb / 10.0)
        sampleCount++

        // 更新历史
        historyData[historyIndex] = displayDb
        historyIndex = (historyIndex + 1) % historySize

        // 更新缓存的显示值并刷新界面
        updateCachedDisplayValue()
    }

    /** 获取 A 加权增益（dB） */
    private fun getAWeightingGain(freq: Float): Float {
        if (freq < aWeightingFreqs.first()) return aWeightingGains.first()
        if (freq > aWeightingFreqs.last()) return aWeightingGains.last()
        
        for (i in 0 until aWeightingFreqs.size - 1) {
            if (freq >= aWeightingFreqs[i] && freq < aWeightingFreqs[i + 1]) {
                val t = (freq - aWeightingFreqs[i]) / (aWeightingFreqs[i + 1] - aWeightingFreqs[i])
                return aWeightingGains[i] + t * (aWeightingGains[i + 1] - aWeightingGains[i])
            }
        }
        return 0f
    }

    /** 获取 C 加权增益（dB） */
    private fun getCWeightingGain(freq: Float): Float {
        if (freq < aWeightingFreqs.first()) return cWeightingGains.first()
        if (freq > aWeightingFreqs.last()) return cWeightingGains.last()

        for (i in 0 until aWeightingFreqs.size - 1) {
            if (freq >= aWeightingFreqs[i] && freq < aWeightingFreqs[i + 1]) {
                val t = (freq - aWeightingFreqs[i]) / (aWeightingFreqs[i + 1] - aWeightingFreqs[i])
                return cWeightingGains[i] + t * (cWeightingGains[i + 1] - cWeightingGains[i])
            }
        }
        return 0f
    }

    /** 近似 A 加权（时域简化版） */
    private fun getApproximateAWeighting(samples: ShortArray, sampleRate: Int): Float {
        // 简化：根据信号的主频率估算加权值
        // 实际应用中应使用 FFT 进行精确加权
        return -1f  // 典型语音信号的 A 加权约 -1 到 -3 dB
    }

    /** 近似 C 加权（时域简化版） */
    private fun getApproximateCWeighting(samples: ShortArray, sampleRate: Int): Float {
        return 0f  // C 加权在语音频段近似平坦
    }

    /** 重置统计 */
    fun resetStatistics() {
        maxDb = -Float.MAX_VALUE
        minDb = Float.MAX_VALUE
        sumDb = 0.0
        sumDbSquared = 0.0
        sampleCount = 0
        measurementStartTime = System.currentTimeMillis()
        peakHoldDb = -60f
        for (i in historyData.indices) historyData[i] = -60f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        
        // 绘制背景
        canvas.drawColor(Color.BLACK)

        // 横屏布局分区
        val leftPanelWidth = w * 0.25f      // 左侧：大数字显示
        val centerPanelWidth = w * 0.50f    // 中间：柱状图和波形
        val rightPanelWidth = w * 0.25f     // 右侧：统计信息

        // 实时值（用于条形图和波形）
        val displayDb = if (showSPL) (currentDbFS + calibrationOffset) else currentDbFS
        val displayPeakDb = if (showSPL) peakHoldDb else (peakHoldDb - calibrationOffset)

        // ===== 左侧面板：大数字显示（Slow 模式下使用缓存值，每秒更新一次）=====
        drawMainValue(canvas, 0f, 0f, leftPanelWidth, h, displayDbCached, displayPeakDbCached)

        // ===== 中间面板：柱状指示器 + 历史波形（始终使用实时值）=====
        drawCenterPanel(canvas, leftPanelWidth, 0f, centerPanelWidth, h, displayDb, displayPeakDb)

        // ===== 右侧面板：统计信息 =====
        drawStatistics(canvas, leftPanelWidth + centerPanelWidth, 0f, rightPanelWidth, h)
    }

    private fun drawMainValue(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, db: Float, peakDb: Float) {
        val centerX = x + w / 2
        val centerY = y + h / 2

        // 主数值（大字体）
        mainValuePaint.textSize = h * 0.25f
        val dbText = if (db > -90f) "%.1f".format(db.coerceIn(minDisplayDb, maxDisplayDb)) else "---"
        canvas.drawText(dbText, centerX, centerY - h * 0.05f, mainValuePaint)

        // 单位
        unitPaint.textSize = h * 0.08f
        val unitText = when {
            showSPL -> "dB" + when (weightingType) {
                WeightingType.A -> "(A)"
                WeightingType.C -> "(C)"
                WeightingType.Z -> "(Z)"
                WeightingType.FLAT -> ""
            }
            else -> "dBFS"
        }
        canvas.drawText(unitText, centerX, centerY + h * 0.08f, unitPaint)

        // 峰值保持
        labelPaint.textSize = h * 0.05f
        val peakText = "峰值: %.1f".format(peakDb.coerceIn(minDisplayDb, maxDisplayDb))
        canvas.drawText(peakText, centerX, centerY + h * 0.25f, labelPaint)

        // 响应时间指示
        val responseText = if (responseTime == ResponseTime.FAST) "FAST" else "SLOW"
        labelPaint.textSize = h * 0.04f
        canvas.drawText(responseText, centerX, y + h * 0.08f, labelPaint)
    }

    private fun drawCenterPanel(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, db: Float, peakDb: Float) {
        val padding = 20f
        val barHeight = h * 0.15f
        val barY = y + h * 0.15f
        val historyY = y + h * 0.45f
        val historyH = h * 0.45f

        // ===== 柱状指示器 =====
        val barLeft = x + padding
        val barRight = x + w - padding
        val barWidth = barRight - barLeft

        // 背景
        canvas.drawRoundRect(barLeft, barY, barRight, barY + barHeight, 8f, 8f, barBgPaint)

        // 计算填充比例
        val fillRatio = ((db - minDisplayDb) / dbRange).coerceIn(0f, 1f)
        val fillWidth = barWidth * fillRatio

        // 渐变填充
        if (fillWidth > 0) {
            val gradient = LinearGradient(
                barLeft, barY, barLeft + fillWidth, barY,
                intArrayOf(colorGreen, colorYellow, colorOrange, colorRed),
                floatArrayOf(0f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradient
            canvas.drawRoundRect(barLeft, barY, barLeft + fillWidth, barY + barHeight, 8f, 8f, barPaint)
            barPaint.shader = null
        }

        // 峰值保持线
        val peakRatio = ((peakDb - minDisplayDb) / dbRange).coerceIn(0f, 1f)
        val peakX = barLeft + barWidth * peakRatio
        canvas.drawRect(peakX - 2f, barY, peakX + 2f, barY + barHeight, peakHoldPaint)

        // 刻度标签
        labelPaint.textSize = 20f
        labelPaint.textAlign = Paint.Align.CENTER
        val scaleValues = listOf(30, 50, 70, 90, 110)
        for (value in scaleValues) {
            val ratio = (value - minDisplayDb) / dbRange
            val scaleX = barLeft + barWidth * ratio
            canvas.drawLine(scaleX, barY + barHeight, scaleX, barY + barHeight + 8f, gridPaint)
            canvas.drawText(value.toString(), scaleX, barY + barHeight + 28f, labelPaint)
        }

        // ===== 历史波形图 =====
        val historyLeft = x + padding
        val historyRight = x + w - padding
        val historyWidth = historyRight - historyLeft
        val historyBottom = historyY + historyH - padding

        // 网格
        gridPaint.strokeWidth = 1f
        // 横线
        for (db in listOf(40, 60, 80, 100)) {
            val ratio = (db - minDisplayDb) / dbRange
            val lineY = historyBottom - historyH * 0.8f * ratio
            canvas.drawLine(historyLeft, lineY, historyRight, lineY, gridPaint)
            
            labelPaint.textSize = 16f
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("$db", historyLeft - 5f, lineY + 5f, labelPaint)
        }

        // 波形
        historyPaint.strokeWidth = 2f
        val path = Path()
        var first = true
        for (i in 0 until historySize) {
            val dataIndex = (historyIndex + i) % historySize
            val dbValue = historyData[dataIndex]
            val ratio = ((dbValue - minDisplayDb) / dbRange).coerceIn(0f, 1f)
            val px = historyLeft + historyWidth * i / historySize
            val py = historyBottom - historyH * 0.8f * ratio
            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
        }

        // 根据当前分贝值设置颜色
        historyPaint.color = when {
            db >= 100 -> colorRed
            db >= 85 -> colorOrange
            db >= 70 -> colorYellow
            else -> colorGreen
        }
        canvas.drawPath(path, historyPaint)
    }

    private fun drawStatistics(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val padding = 30f
        val startX = x + padding
        var currentY = y + h * 0.15f
        val lineHeight = h * 0.12f

        statPaint.textSize = h * 0.055f
        statPaint.color = colorText

        // 最大值
        val maxText = if (maxDb > -100f) "%.1f dB".format(maxDb) else "---"
        statPaint.color = colorRed
        canvas.drawText("最大: $maxText", startX, currentY, statPaint)
        currentY += lineHeight

        // 最小值
        val minText = if (minDb < 200f) "%.1f dB".format(minDb) else "---"
        statPaint.color = colorGreen
        canvas.drawText("最小: $minText", startX, currentY, statPaint)
        currentY += lineHeight

        // 平均值 (Lavg)
        val avgDb = if (sampleCount > 0) (sumDb / sampleCount).toFloat() else -60f
        statPaint.color = colorYellow
        canvas.drawText("平均: %.1f dB".format(avgDb), startX, currentY, statPaint)
        currentY += lineHeight

        // 等效连续声级 (Leq)
        val leq = if (sampleCount > 0) (10 * log10(sumDbSquared / sampleCount)).toFloat() else -60f
        statPaint.color = Color.rgb(100, 181, 246)  // 浅蓝色
        canvas.drawText("Leq: %.1f dB".format(leq), startX, currentY, statPaint)
        currentY += lineHeight

        // 测量时长
        val durationMs = System.currentTimeMillis() - measurementStartTime
        val durationSec = durationMs / 1000
        val minutes = durationSec / 60
        val seconds = durationSec % 60
        statPaint.color = colorTextSecondary
        canvas.drawText("时长: %02d:%02d".format(minutes, seconds), startX, currentY, statPaint)
        currentY += lineHeight * 1.5f

        // 校准值
        statPaint.textSize = h * 0.04f
        statPaint.color = colorTextSecondary
        if (showSPL) {
            canvas.drawText("校准: +%.0f dB".format(calibrationOffset), startX, currentY, statPaint)
        } else {
            canvas.drawText("显示: dBFS", startX, currentY, statPaint)
        }

        // 提示：长按重置
        statPaint.textSize = h * 0.035f
        canvas.drawText("长按重置统计", startX, y + h - padding, statPaint)
    }

    override fun performLongClick(): Boolean {
        resetStatistics()
        return super.performLongClick()
    }

    init {
        isLongClickable = true
        setOnLongClickListener {
            resetStatistics()
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            true
        }
    }
}

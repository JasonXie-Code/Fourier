package com.fourier.audioanalyzer.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.fourier.audioanalyzer.R
import com.fourier.audioanalyzer.util.DebugLog
import com.fourier.audioanalyzer.util.DebugLog.Tag
import kotlin.math.*

/**
 * 频谱瀑布图视图（环形缓冲区优化版）
 * 显示时间-频率-幅度的三维频谱
 * 双指捏合缩放、单指拖动平移、双击归位
 * 
 * 性能优化（环形缓冲区架构）：
 * - ringBitmap：固定大小的环形缓冲区，每行 1 像素高，存储全部历史
 * - 新数据到达时只画 1 行，无需滚动或重绘
 * - 滚动查看历史时仅改变绘制偏移，0 重绘开销
 * - 缩放时使用硬件 Bitmap 缩放，极快
 * - 预计算颜色映射表（256级）和频率到X坐标映射
 */
class WaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // 网格线：关闭抗锯齿以减轻每帧绘制负担，配合缓存保证 120fps
    private val gridPaint = Paint().apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = false
    }
    // 黑色填充 Paint（用于填充无数据区域）
    private val blackFillPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    // ========== 环形缓冲区 Bitmap ==========
    /** 环形缓冲区 Bitmap：每行 1 像素高，存储全部历史数据 */
    private var ringBitmap: Bitmap? = null
    private var ringBitmapWidth = 0
    /** 环形缓冲区最大行数（= maxHistorySize） */
    private var ringBitmapMaxRows = 0
    
    /** 写入指针：指向下一个要写入的行（0 ~ ringBitmapMaxRows-1 循环） */
    private var writeRow = 0
    
    /** 已填充的行数（0 ~ ringBitmapMaxRows），用于判断历史数据是否足够 */
    private var filledRows = 0
    
    /** 单行像素缓冲区（避免每次分配） */
    private var rowPixelBuffer: IntArray? = null
    
    // ========== 旧的数据结构（保留用于兼容和峰值计算）==========
    /** 最近一帧的频谱数据（用于峰值计算等） */
    private var latestSpectrum: FloatArray? = null
    
    /** 历史时长（秒）：支持查看 60 秒历史 */
    private val historyDurationSec = 60f
    
    /** 最大历史行数：60 行/秒 × 60 秒 */
    private val maxHistorySize: Int
        get() = (historyDurationSec * 60f).toInt().coerceIn(100, 6000)
    
    /** 目标显示时间（秒）：控制屏幕上显示多长时间的数据 */
    var displayTimeSec: Float = 1.0f
        set(value) {
            val newVal = value.coerceIn(0.1f, 10f)
            if (field != newVal) {
                field = newVal
                invalidate()
            }
        }
    
    /** 可见行数：displayTimeSec 秒 × 60 行/秒 */
    private val visibleRowCount: Int
        get() = (displayTimeSec * 60f).toInt().coerceIn(2, 6000)
    
    /** 历史偏移（行数）：用于查看更早的历史数据，0 表示显示最新数据 */
    var historyOffsetRows = 0
        private set
    
    /** 累积滚动量（浮点）：用于平滑滑动响应，避免小幅滑动被截断 */
    private var accumulatedScrollRows = 0f

    var sampleRate: Int = 44100
        set(value) {
            if (field != value) {
                val oldVal = field
                field = value
                freqToXCache = null  // 采样率改变，频率映射失效
                DebugLog.d(Tag.WAVEFORM) {
                    "采样率改变: ${oldVal}Hz -> ${value}Hz, 清除历史数据"
                }
                clearHistoryDataOnly()
            }
        }
    
    /** 瀑布图专用 FFT 大小（可独立于频谱设置） */
    var waterfallFftSize: Int = 2048
        set(value) {
            val newVal = value.coerceIn(256, 16384)
            if (field != newVal) {
                val oldVal = field
                field = newVal
                freqToXCache = null
                DebugLog.d(Tag.WAVEFORM) {
                    "FFT大小改变: $oldVal -> $newVal, 清除历史数据"
                }
                clearHistoryDataOnly()
            }
        }
    
    // 兼容旧接口
    var fftSize: Int
        get() = waterfallFftSize
        set(value) { waterfallFftSize = value }

    /** 重叠率 0~0.95（保留用于其他用途，不影响显示速率） */
    var overlapRatio: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 0.95f)
        }
    
    /** 固定每行 = 1/60 秒，确保任意 FFT 下滚动速度一致且与时间轴对齐 */
    private val rowIntervalSec: Float = 1f / 60f


    /** 横轴频率缩放：线性 / 对数（与主视图选项同步） */
    var scaleMode: AudioVisualizerView.ScaleMode = AudioVisualizerView.ScaleMode.LINEAR
        set(value) {
            if (field != value) {
                field = value
                freqToXCache = null
                // scaleMode 改变需要重绘整个 ringBitmap
                rebuildRingBitmap()
                invalidate()
            }
        }
    
    /** 
     * 灵敏度（控制显示动态范围）：0.5~50，默认1.0
     */
    var sensitivity: Float = 1.0f
        set(value) {
            val newVal = value.coerceIn(0.5f, 50f)
            if (field != newVal) {
                field = newVal
                // 灵敏度改变需要重绘整个 ringBitmap
                rebuildRingBitmap()
                invalidate()
            }
        }
    
    private fun getDbFloor(): Float {
        return -60f * (1f + kotlin.math.log10(sensitivity))
    }
    
    /** 峰值频率（由外部传入，用于在瀑布图上标记） */
    var peakFrequency: Float = 0f
        set(value) {
            field = value
            peakFrequencyLastUpdateNs = System.nanoTime()
            invalidate()
        }
    
    /** 峰值线衰减：无新数据时逐渐淡化，衰减时间常数约 0.5 秒 */
    private var peakFrequencyLastUpdateNs = 0L
    private val peakLineDecayNs = 500_000_000L  // 0.5 秒
    
    /** 是否显示峰值频率线 */
    var showPeakLine: Boolean = true
    
    /** 频谱斜率（dB/十倍频程）：正值提升高频，负值衰减高频 */
    var spectrumSlope: Float = 0f
        set(value) {
            val newVal = value.coerceIn(-12f, 12f)
            if (field != newVal) {
                field = newVal
                slopeFactorCache = null
                rebuildRingBitmap()
                invalidate()
            }
        }
    
    /** 是否显示历史峰值线 */
    var showPeakHoldLine: Boolean = false
    
    private var peakHoldFrequency: Float = 0f
    private var peakHoldValue: Float = 0f
    private val peakHoldDecayFactor = 0.95f

    private val logScaleMinFreq = 20f
    private val twelveTetRefFreq = 27.5f

    // ========== 预计算的颜色查找表（256级）==========
    private var colorLUT = IntArray(256)
    
    // ========== 预计算的频率到X坐标映射 ==========
    private var freqToXCache: FloatArray? = null
    private var freqToXCacheSpectrumSize = 0
    private var freqToXCacheDrawWidth = 0f

    /** 0=彩虹, 1=灰度, 2=黑红, 3=蓝绿 */
    var colorPalette: Int = 0
        set(value) {
            val newVal = value.coerceIn(0, 3)
            if (field != newVal) {
                field = newVal
                colorMap = createColorMap(newVal)
                updateColorLUT()
                rebuildRingBitmap()
                invalidate()
            }
        }
    
    private var colorMap = createColorMap(0)

    private val padding = 40f
    private val paddingRightLabels = 56f

    val currentScaleX: Float get() = scaleX
    val currentScaleY: Float get() = scaleY
    private var scaleX = 1f
    private var scaleY = 1f
    var onScaleChanged: ((scaleX: Float, scaleY: Float) -> Unit)? = null
    private var offsetX = 0f
    private var offsetY = 0f

    private val minScale = 1f / 60f  // 最大时间窗口：60 秒
    private val maxScale = 10f  // 最小时间窗口：0.1 秒（当 displayTimeSec = 1.0 时）

    /** 上一帧的双指水平/垂直间距，用于逐帧计算独立 X/Y 缩放比 */
    private var prevSpanX = 0f
    private var prevSpanY = 0f
    private var isScaleGestureActive = false

    // ========== 调试统计 ==========
    private var lastDrawTime = 0L
    private var frameCount = 0
    private var lastFpsTime = 0L

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            prevSpanX = detector.currentSpanX
            prevSpanY = detector.currentSpanY
            isScaleGestureActive = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focusX = detector.focusX
            val focusY = detector.focusY
            val curSpanX = detector.currentSpanX
            val curSpanY = detector.currentSpanY

            // 逐帧增量比：当前间距 / 上一帧间距（避免除零，最小 10px）
            val rawFactorX = if (prevSpanX > 10f) curSpanX / prevSpanX else 1f
            val rawFactorY = if (prevSpanY > 10f) curSpanY / prevSpanY else 1f
            prevSpanX = curSpanX
            prevSpanY = curSpanY

            // 平方放大灵敏度（每帧增量接近 1，平方保持合理响应速度）
            val factorX = rawFactorX * rawFactorX
            val factorY = rawFactorY * rawFactorY

            val viewW = width.toFloat()
            val viewH = height.toFloat()
            if (viewW <= 0f || viewH <= 0f) return true

            val drawWidth  = (viewW - padding * 2).coerceAtLeast(1f)
            val drawHeight = (viewH - padding * 2).coerceAtLeast(1f)
            val contentCenterX = padding + drawWidth / 2f

            // ── 横向（频率轴）缩放，以双指中点 focusX 为锚点 ────────────────
            val oldScaleX = scaleX
            val newScaleX = (scaleX * factorX).coerceIn(1f, maxScale)
            if (newScaleX != oldScaleX) {
                // 缩放前：screenX = contentCenterX + offsetX + (contentX - contentCenterX) * scaleX
                // 保持 focusX 对应的内容坐标不变，反推新 offsetX
                val contentXAtFocus = contentCenterX + (focusX - contentCenterX - offsetX) / oldScaleX
                val newOffsetX = focusX - contentCenterX - (contentXAtFocus - contentCenterX) * newScaleX
                scaleX = newScaleX
                // 对称约束：offsetX ∈ [-half, +half]，保证内容始终填满显示区域不出现空白
                // half = drawWidth/2 × (scaleX−1)：scaleX=1时为0（无偏移），scaleX=2时为drawWidth/2
                val halfRange = drawWidth / 2f * (scaleX - 1f)
                offsetX = newOffsetX.coerceIn(-halfRange, halfRange)
                DebugLog.d(Tag.GESTURE, 500L) {
                    "瀑布图X缩放: focusX=${"%.1f".format(focusX)}, scaleX=${"%.3f".format(scaleX)}, offsetX=${"%.1f".format(offsetX)}"
                }
            }

            // ── 纵向（时间轴）缩放，以双指中点 focusY 对应的数据行为锚点 ────
            val oldEffRows = calcEffectiveVisibleRowCount()
            val oldRowHeight = drawHeight / oldEffRows
            val oldScaleY = scaleY
            val newScaleY = (scaleY * factorY).coerceIn(minScale, maxScale)
            if (newScaleY != oldScaleY) {
                // 计算 focusY 处对应的数据行偏移（从最新帧起）
                val focusYInView = (focusY - padding).coerceIn(0f, drawHeight)
                val dataRowAtFocus = historyOffsetRows + focusYInView / oldRowHeight

                scaleY = newScaleY
                val newEffRows = calcEffectiveVisibleRowCount()
                val newRowHeight = drawHeight / newEffRows

                // 保持 dataRowAtFocus 仍然显示在 focusY 处
                val desiredOffset = (dataRowAtFocus - focusYInView / newRowHeight).roundToInt()
                val maxOffset = maxOf(0, filledRows - newEffRows)
                historyOffsetRows = desiredOffset.coerceIn(0, maxOffset)
                accumulatedScrollRows = 0f
                DebugLog.d(Tag.GESTURE, 500L) {
                    "瀑布图Y缩放: focusY=${"%.1f".format(focusY)}, scaleY=${"%.3f".format(scaleY)}, historyOffset=$historyOffsetRows"
                }
            }

            onScaleChanged?.invoke(scaleX, scaleY)
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaleGestureActive = false
        }
    })
    
    private fun calcEffectiveVisibleRowCount(): Int {
        val maxRows = maxHistorySize
        return (visibleRowCount / scaleY).toInt().coerceIn(2, maxRows)
    }
    
    private fun clampOffsetY() {
        offsetY = 0f
        val effRows = calcEffectiveVisibleRowCount()
        val maxOffset = maxOf(0, filledRows - effRows)
        historyOffsetRows = historyOffsetRows.coerceIn(0, maxOffset)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            scaleX = 1f
            scaleY = 1f
            offsetX = 0f
            offsetY = 0f
            historyOffsetRows = 0
            accumulatedScrollRows = 0f
            onScaleChanged?.invoke(scaleX, scaleY)
            invalidate()
            return true
        }
        
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val drawW = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
            val drawH = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
            
            // 捏合缩放期间禁止所有滚动：onScale 已通过双指中点锚点更新 offsetX，
            // 若同时允许 scroll 修改 offsetX/historyOffsetRows 会相互干扰，导致锚点计算失效
            if (!isScaleGestureActive) {
                offsetX -= distanceX
                val halfRange = drawW / 2f * (scaleX - 1f)
                offsetX = offsetX.coerceIn(-halfRange, halfRange)
                
                val effRows = calcEffectiveVisibleRowCount()
                val rowsPerPixel = effRows.toFloat() / drawH
                
                accumulatedScrollRows += distanceY * rowsPerPixel
                val deltaRows = accumulatedScrollRows.toInt()
                
                if (deltaRows != 0) {
                    accumulatedScrollRows -= deltaRows
                    val maxOffset = maxOf(0, filledRows - effRows)
                    historyOffsetRows = (historyOffsetRows + deltaRows).coerceIn(0, maxOffset)
                }
                
                clampOffsetY()
            }
            
            onScaleChanged?.invoke(scaleX, scaleY)
            invalidate()
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        textPaint.textSize = 20f
        textPaint.textAlign = Paint.Align.LEFT
        
        updateColorLUT()
    }
    
    private fun updateColorLUT() {
        for (i in 0 until 256) {
            val amplitude = i / 255f
            val logAmplitude = if (amplitude > 0f) {
                (log10(amplitude + 0.001f) + 3f) / 3f
            } else {
                0f
            }.coerceIn(0f, 1f)
            val colorIndex = (logAmplitude * (colorMap.size - 1)).toInt().coerceIn(0, colorMap.size - 1)
            colorLUT[i] = colorMap[colorIndex]  // 修复：将 colorMap[colorIndex] 存入 colorLUT[i]
        }
    }
    
    private fun getColorFast(amplitude: Float): Int {
        val amp = amplitude.coerceIn(1e-7f, 1f)
        val db = 20f * kotlin.math.log10(amp)
        val dbFloor = getDbFloor()
        val normalized = ((db - dbFloor) / -dbFloor).coerceIn(0f, 1f)
        val index = (normalized * 255f).toInt().coerceIn(0, 255)
        return colorLUT[index]
    }
    
    private fun ensureFreqToXCache(spectrumSize: Int, drawWidth: Float) {
        if (freqToXCache != null && 
            freqToXCacheSpectrumSize == spectrumSize && 
            abs(freqToXCacheDrawWidth - drawWidth) < 1f) {
            return
        }
        
        val maxFreq = sampleRate / 2f
        val cache = FloatArray(spectrumSize + 1)
        
        for (i in 0..spectrumSize) {
            val freq = i * maxFreq / spectrumSize
            cache[i] = freqToXInternal(freq, maxFreq, drawWidth)
        }
        
        freqToXCache = cache
        freqToXCacheSpectrumSize = spectrumSize
        freqToXCacheDrawWidth = drawWidth
    }
    
    private fun freqToXInternal(freq: Float, maxFreq: Float, drawWidth: Float): Float {
        return when (scaleMode) {
            AudioVisualizerView.ScaleMode.LINEAR ->
                padding + (freq / maxFreq).coerceIn(0f, 1f) * drawWidth
            AudioVisualizerView.ScaleMode.LOGARITHMIC -> {
                val minF = maxOf(logScaleMinFreq, maxFreq / 2048f)
                val f = freq.coerceIn(minF, maxFreq)
                val logMin = log10(minF)
                val logMax = log10(maxFreq)
                val t = (log10(f) - logMin) / (logMax - logMin)
                padding + t.coerceIn(0f, 1f) * drawWidth
            }
            AudioVisualizerView.ScaleMode.TWELVE_TET -> {
                val minF = maxOf(logScaleMinFreq, maxFreq / 2048f)
                val f = freq.coerceIn(minF, maxFreq)
                val n = 12f * (ln(f / twelveTetRefFreq) / ln(2f))
                val nMin = 12f * (ln(minF / twelveTetRefFreq) / ln(2f))
                val nMax = 12f * (ln(maxFreq / twelveTetRefFreq) / ln(2f))
                val t = (n - nMin) / (nMax - nMin).coerceAtLeast(0.001f)
                padding + t.coerceIn(0f, 1f) * drawWidth
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.pointerCount) {
            2 -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        gestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)
        return true
    }
    
    /**
     * 确保环形缓冲区 Bitmap 可用
     */
    private fun ensureRingBitmap(width: Int) {
        val targetMaxRows = maxHistorySize
        
        if (ringBitmap == null || ringBitmapWidth != width || ringBitmapMaxRows != targetMaxRows) {
            // 释放旧的
            ringBitmap?.recycle()
            
            // 创建新的环形缓冲区：宽度 = 绘图区域宽度，高度 = 最大历史行数
            ringBitmap = Bitmap.createBitmap(width, targetMaxRows, Bitmap.Config.ARGB_8888)
            ringBitmapWidth = width
            ringBitmapMaxRows = targetMaxRows
            
            // 填充黑色背景
            ringBitmap?.eraseColor(Color.BLACK)
            
            // 重置指针
            writeRow = 0
            filledRows = 0
            
            DebugLog.d(Tag.WAVEFORM) {
                "创建环形缓冲区: ${width}x${targetMaxRows}, 内存≈${width * targetMaxRows * 4 / 1024}KB"
            }
        }
        
        // 确保单行缓冲区
        if (rowPixelBuffer == null || rowPixelBuffer!!.size != width) {
            rowPixelBuffer = IntArray(width)
        }
    }
    
    /** 可复用的频谱缓冲区，避免每次 copyOf 分配 */
    private var spectrumBuffer: FloatArray? = null
    
    /**
     * 添加频谱数据到环形缓冲区（只画 1 行，极快）
     * @param spectrum 频谱数据
     * @param generation 保留参数，兼容旧调用
     */
    fun addSpectrumData(spectrum: FloatArray, @Suppress("UNUSED_PARAMETER") generation: Long = -1L) {
        latestSpectrum = if (spectrumBuffer != null && spectrumBuffer!!.size == spectrum.size) {
            System.arraycopy(spectrum, 0, spectrumBuffer!!, 0, spectrum.size)
            spectrumBuffer!!
        } else {
            spectrumBuffer = spectrum.copyOf()
            spectrumBuffer!!
        }
        
        val bitmap = ringBitmap ?: return
        val bmpWidth = ringBitmapWidth
        if (bmpWidth <= 0) return
        
        val spectrumSize = spectrum.size
        if (spectrumSize == 0) return
        
        // 确保频率映射缓存
        // 注意：这里使用 ringBitmapWidth 而不是 drawWidth，因为 ringBitmap 的宽度是固定的
        ensureFreqToXCache(spectrumSize, bmpWidth.toFloat())
        val xCache = freqToXCache ?: return
        
        // 确保斜率缓存
        val applySlope = spectrumSlope != 0f
        if (applySlope) {
            ensureSlopeFactorCache(spectrumSize)
        }
        val slopeCache = slopeFactorCache
        
        // 获取单行像素缓冲区
        val pixels = rowPixelBuffer ?: return
        
        // 填充黑色
        pixels.fill(Color.BLACK)
        
        // 绘制频谱到像素数组
        for (freqIndex in 0 until spectrumSize) {
            var amplitude = spectrum[freqIndex].coerceIn(0f, 1f)
            
            if (applySlope && slopeCache != null && freqIndex < slopeCache.size) {
                amplitude = (amplitude * slopeCache[freqIndex]).coerceIn(0f, 1f)
            }
            
            val color = getColorFast(amplitude)
            // xCache 中存储的是相对于 padding 的坐标，这里 ringBitmap 从 0 开始
            // 所以需要减去 padding
            val xLo = (xCache[freqIndex] - padding).toInt().coerceIn(0, bmpWidth - 1)
            val xHi = (xCache[freqIndex + 1] - padding).toInt().coerceIn(xLo, bmpWidth)
            
            for (x in xLo until xHi) {
                pixels[x] = color
            }
        }
        
        // 写入到环形缓冲区的当前行
        bitmap.setPixels(pixels, 0, bmpWidth, 0, writeRow, bmpWidth, 1)
        
        // 更新写入指针（循环）
        writeRow = (writeRow + 1) % ringBitmapMaxRows
        filledRows = minOf(filledRows + 1, ringBitmapMaxRows)
        
        // 更新峰值保持
        updatePeakHold(spectrum)
    }
    
    /**
     * 准备后台预计算所需的参数（主线程调用，确保缓存已就绪）
     * @return 若 View 未就绪则 null
     */
    fun preparePrecomputeParams(spectrum: FloatArray): PrecomputeParams? {
        val bitmap = ringBitmap ?: return null
        val bmpWidth = ringBitmapWidth
        if (bmpWidth <= 0 || spectrum.isEmpty()) return null
        ensureFreqToXCache(spectrum.size, bmpWidth.toFloat())
        val xCache = freqToXCache ?: return null
        val applySlope = spectrumSlope != 0f
        if (applySlope) ensureSlopeFactorCache(spectrum.size)
        return PrecomputeParams(
            spectrum = spectrum.copyOf(),
            xCache = xCache,
            slopeCache = slopeFactorCache,
            applySlope = applySlope,
            bmpWidth = bmpWidth
        )
    }
    
    data class PrecomputeParams(
        val spectrum: FloatArray,
        val xCache: FloatArray,
        val slopeCache: FloatArray?,
        val applySlope: Boolean,
        val bmpWidth: Int
    )
    
    /**
     * 在后台线程计算行像素（仅做计算，不触碰 Bitmap）
     * 主线程需先调用 preparePrecomputeParams，再传入参数
     * @return 新分配的像素数组，主线程用 addSpectrumRowFromPixels 写入
     */
    /** 后台线程调用：根据预准备参数计算行像素 */
    fun computeRowPixelsForBackground(params: PrecomputeParams): IntArray =
        computeRowPixelsForBackground(
            params.spectrum,
            params.xCache,
            params.slopeCache,
            params.applySlope,
            params.bmpWidth
        )
    
    /**
     * 在后台线程计算行像素（仅做计算，不触碰 Bitmap）
     */
    private fun computeRowPixelsForBackground(
        spectrum: FloatArray,
        xCache: FloatArray,
        slopeCache: FloatArray?,
        applySlope: Boolean,
        bmpWidth: Int
    ): IntArray {
        val pixels = IntArray(bmpWidth)
        pixels.fill(Color.BLACK)
        val spectrumSize = spectrum.size
        for (freqIndex in 0 until spectrumSize) {
            var amplitude = spectrum[freqIndex].coerceIn(0f, 1f)
            if (applySlope && slopeCache != null && freqIndex < slopeCache.size) {
                amplitude = (amplitude * slopeCache[freqIndex]).coerceIn(0f, 1f)
            }
            val color = getColorFast(amplitude)
            val xLo = (xCache[freqIndex] - padding).toInt().coerceIn(0, bmpWidth - 1)
            val xHi = (xCache[freqIndex + 1] - padding).toInt().coerceIn(xLo, bmpWidth)
            for (x in xLo until xHi) {
                pixels[x] = color
            }
        }
        return pixels
    }
    
    /**
     * 从预计算的像素添加一行（主线程调用，仅 setPixels + 更新指针，极快）
     * @return 是否成功
     */
    fun addSpectrumRowFromPixels(pixels: IntArray, spectrum: FloatArray): Boolean {
        val bitmap = ringBitmap ?: return false
        val bmpWidth = ringBitmapWidth
        if (bmpWidth <= 0 || pixels.size < bmpWidth) return false
        latestSpectrum = if (spectrumBuffer != null && spectrumBuffer!!.size == spectrum.size) {
            System.arraycopy(spectrum, 0, spectrumBuffer!!, 0, spectrum.size)
            spectrumBuffer!!
        } else {
            spectrumBuffer = spectrum.copyOf()
            spectrumBuffer!!
        }
        bitmap.setPixels(pixels, 0, bmpWidth, 0, writeRow, bmpWidth, 1)
        writeRow = (writeRow + 1) % ringBitmapMaxRows
        filledRows = minOf(filledRows + 1, ringBitmapMaxRows)
        updatePeakHold(spectrum)
        return true
    }
    
    private fun updatePeakHold(spectrum: FloatArray) {
        if (!showPeakHoldLine || spectrum.isEmpty()) return
        
        val maxFreq = sampleRate / 2f
        var maxVal = 0f
        var maxIdx = 0
        for (i in spectrum.indices) {
            val amplitude = spectrum[i]
            if (amplitude > maxVal) {
                maxVal = amplitude
                maxIdx = i
            }
        }
        
        peakHoldValue *= peakHoldDecayFactor
        if (maxVal > peakHoldValue) {
            peakHoldValue = maxVal
            peakHoldFrequency = (maxIdx.toFloat() + 0.5f) / spectrum.size * maxFreq
        }
    }
    
    /**
     * 重建整个环形缓冲区（参数变化时调用）
     * 注意：这会丢失历史数据的视觉效果，但由于 latestSpectrum 只保留最新一帧，
     * 实际上无法重建历史。这是一个 trade-off。
     */
    private fun rebuildRingBitmap() {
        ringBitmap?.eraseColor(Color.BLACK)
        writeRow = 0
        filledRows = 0
        DebugLog.d(Tag.WAVEFORM) { "重建环形缓冲区（参数变化）" }
    }
    
    /**
     * 更新频谱数据（添加新的时间点并刷新显示）
     */
    fun updateSpectrum(spectrum: FloatArray) {
        addSpectrumData(spectrum, -1L)
        invalidate()
    }
    
    fun clearHistory() {
        ringBitmap?.eraseColor(Color.BLACK)
        writeRow = 0
        filledRows = 0
        historyOffsetRows = 0
        accumulatedScrollRows = 0f
        latestSpectrum = null
        peakHoldFrequency = 0f
        peakHoldValue = 0f
        scaleX = 1f
        scaleY = 1f
        offsetX = 0f
        offsetY = 0f
        onScaleChanged?.invoke(scaleX, scaleY)
        invalidate()
    }

    fun clearHistoryDataOnly() {
        ringBitmap?.eraseColor(Color.BLACK)
        writeRow = 0
        filledRows = 0
        historyOffsetRows = 0
        accumulatedScrollRows = 0f
        latestSpectrum = null
        invalidate()
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        freqToXCache = null
        gridCacheDrawWidth = -1f  // 使网格缓存失效
        val drawWidth = (w - padding * 2).toInt().coerceAtLeast(1)
        ensureRingBitmap(drawWidth)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ringBitmap?.recycle()
        ringBitmap = null
    }
    
    // 用于绘制的临时 Rect
    private val srcRect = Rect()
    private val dstRect = RectF()
    // 用于翻转的 Matrix（复用避免每帧创建）
    private val flipMatrix = Matrix()
    
    // ========== 网格/标签缓存：仅参数变化时重算，保证 onDraw 轻量以达 120fps ==========
    private var gridVerticalScreenX: FloatArray = FloatArray(0)
    private var gridHorizontalScreenY: FloatArray = FloatArray(0)
    private var gridVerticalCount = 0
    private var gridHorizontalCount = 0
    private var gridCacheDrawWidth = 0f
    private var gridCacheDrawHeight = 0f
    private var gridCacheScaleX = 0f
    private var gridCacheOffsetX = 0f
    private var gridCacheScaleModeOrdinal = -1
    private var gridCacheSampleRate = 0
    private var gridCacheEffRows = 0
    private var gridCacheDisplayRowCount = 0
    private var gridCacheHistoryOffsetRows = -1
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawWidth = viewWidth - padding * 2
        val drawHeight = viewHeight - padding * 2
        val contentCenterX = padding + drawWidth / 2f

        canvas.drawColor(ContextCompat.getColor(context, R.color.background))

        if (filledRows == 0) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "频谱瀑布图\n等待数据...",
                viewWidth / 2,
                viewHeight / 2,
                textPaint
            )
            textPaint.textAlign = Paint.Align.LEFT
            return
        }

        // 确保环形缓冲区可用
        val bmpWidth = drawWidth.toInt().coerceAtLeast(1)
        ensureRingBitmap(bmpWidth)
        
        val bitmap = ringBitmap ?: return
        
        // 计算显示参数
        val effRows = calcEffectiveVisibleRowCount()
        
        // 计算要显示的数据范围（考虑历史偏移）
        // 注意：displayRowCount 是实际可用的数据行数，可能小于 effRows
        val availableDataRows = (filledRows - historyOffsetRows).coerceAtLeast(0)
        val displayRowCount = minOf(effRows, availableDataRows)

        // ===== 核心：从环形缓冲区绘制到屏幕 =====
        // 环形缓冲区中：
        // - writeRow 指向下一个要写入的行（也就是最旧的数据，如果 filledRows == ringBitmapMaxRows）
        // - (writeRow - 1 + maxRows) % maxRows 是最新的数据
        // - 要显示的起始行（最新端）是 (writeRow - 1 - historyOffsetRows + maxRows) % maxRows
        
        val maxRows = ringBitmapMaxRows
        
        // 应用 X 方向缩放和平移
        canvas.save()
        canvas.translate(contentCenterX + offsetX, 0f)
        canvas.scale(scaleX, 1f)
        canvas.translate(-contentCenterX, 0f)
        
        if (displayRowCount > 0) {
            // 最新数据的物理行号
            val newestPhysicalRow = (writeRow - 1 + maxRows) % maxRows
            // 显示起始行的物理行号（考虑历史偏移）
            val startPhysicalRow = (newestPhysicalRow - historyOffsetRows + maxRows) % maxRows
            
            // 绘制环形缓冲区到屏幕（只绘制有数据的部分）
            // 由于是环形的，可能需要分两段绘制
            drawRingBufferToCanvas(canvas, bitmap, startPhysicalRow, displayRowCount, 
                maxRows, bmpWidth, drawHeight, effRows)
        }
        
        // 如果有数据但不足以填满整个屏幕，剩余区域显示黑色
        // 计算数据区域的高度（基于实际数据行数，不变形）
        val rowHeightOnScreen = drawHeight / effRows
        val dataHeight = displayRowCount * rowHeightOnScreen
        val emptyHeight = drawHeight - dataHeight
        
        if (emptyHeight > 0.5f && displayRowCount > 0) {
            // 绘制黑色填充区域（在数据下方）
            val emptyTop = padding + dataHeight
            val emptyBottom = padding + drawHeight
            canvas.drawRect(
                padding,
                emptyTop,
                padding + bmpWidth,
                emptyBottom,
                blackFillPaint
            )
        } else if (displayRowCount == 0 && filledRows == 0) {
            // 完全没有数据时，整个区域都是黑色（已在 onDraw 开头处理）
        }
        
        canvas.restore()
        
        // 绘制网格
        drawGrid(canvas, drawWidth, drawHeight)
        
        // 绘制标签
        drawLabels(canvas, drawWidth, drawHeight)
        
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 2000) {
            frameCount = 0
            lastFpsTime = now
        }
    }
    
    /**
     * 从环形缓冲区绘制到 Canvas
     * 处理环形边界：如果数据跨越 ringBitmap 的顶部和底部，需要分两段绘制
     * 使用 Matrix 翻转并确保像素对齐，避免闪烁
     */
    private fun drawRingBufferToCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        startPhysicalRow: Int,
        displayRowCount: Int,
        maxRows: Int,
        bmpWidth: Int,
        drawHeight: Float,
        effRows: Int
    ) {
        // 计算每行在屏幕上的高度（基于 effRows，确保不变形）
        val rowHeightOnScreen = drawHeight / effRows
        // 数据区域的实际高度（基于实际数据行数，不变形）
        val dataHeight = displayRowCount * rowHeightOnScreen
        
        // 结束物理行号（不包含）
        val endPhysicalRow = (startPhysicalRow - displayRowCount + 1 + maxRows) % maxRows
        
        // 检查是否跨越环形边界
        if (startPhysicalRow >= endPhysicalRow && displayRowCount < maxRows) {
            // 不跨越边界：从 endPhysicalRow 到 startPhysicalRow（从旧到新）
            // 在屏幕上：最新的（startPhysicalRow）在顶部，最旧的在底部
            
            // 源矩形：从 ringBitmap 中取 [endPhysicalRow, startPhysicalRow+1) 这些行
            srcRect.set(0, endPhysicalRow, bmpWidth, startPhysicalRow + 1)
            
            // 目标矩形：只绘制实际数据区域（不变形）
            val dstTop = padding.roundToInt().toFloat()
            val dstLeft = padding.roundToInt().toFloat()
            val dstRight = (padding + bmpWidth).roundToInt().toFloat()
            // 使用 dataHeight 而不是 drawHeight，确保数据不变形
            val dstBottom = (padding + dataHeight).roundToInt().toFloat()
            dstRect.set(dstLeft, dstTop, dstRight, dstBottom)
            
            // 使用 Matrix 翻转：先平移到中心，翻转，再平移回来
            flipMatrix.reset()
            val centerX = (dstLeft + dstRight) / 2f
            val centerY = (dstTop + dstBottom) / 2f
            flipMatrix.postTranslate(-centerX, -centerY)
            flipMatrix.postScale(1f, -1f)  // Y 轴翻转
            flipMatrix.postTranslate(centerX, centerY)
            
            canvas.save()
            canvas.concat(flipMatrix)
            canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
            canvas.restore()
            
        } else {
            // 跨越边界：需要分两段绘制
            // 段1：从 startPhysicalRow 到 0（环形缓冲区的开头）
            // 段2：从 maxRows-1 到 endPhysicalRow
            
            // 段1：startPhysicalRow 到 0（最新的数据）
            val rowsInSegment1 = startPhysicalRow + 1
            if (rowsInSegment1 > 0) {
                srcRect.set(0, 0, bmpWidth, rowsInSegment1)
                // 计算段1在总数据中的比例（基于 displayRowCount，不变形）
                val segment1Ratio = rowsInSegment1.toFloat() / displayRowCount
                val dstHeight1 = (dataHeight * segment1Ratio).roundToInt().toFloat()
                val dstTop1 = padding.roundToInt().toFloat()
                val dstLeft1 = padding.roundToInt().toFloat()
                val dstRight1 = (padding + bmpWidth).roundToInt().toFloat()
                val dstBottom1 = (dstTop1 + dstHeight1).roundToInt().toFloat()
                dstRect.set(dstLeft1, dstTop1, dstRight1, dstBottom1)
                
                flipMatrix.reset()
                val centerX1 = (dstLeft1 + dstRight1) / 2f
                val centerY1 = (dstTop1 + dstBottom1) / 2f
                flipMatrix.postTranslate(-centerX1, -centerY1)
                flipMatrix.postScale(1f, -1f)
                flipMatrix.postTranslate(centerX1, centerY1)
                
                canvas.save()
                canvas.concat(flipMatrix)
                canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                canvas.restore()
            }
            
            // 段2：maxRows-1 到 endPhysicalRow（较旧的数据）
            val rowsInSegment2 = displayRowCount - rowsInSegment1
            if (rowsInSegment2 > 0) {
                val segment2Start = maxRows - rowsInSegment2
                srcRect.set(0, segment2Start, bmpWidth, maxRows)
                // 段2从段1的底部开始，使用 dataHeight 而不是 drawHeight（不变形）
                val dstTop2 = (padding + dataHeight * rowsInSegment1 / displayRowCount).roundToInt().toFloat()
                val dstHeight2 = (dataHeight - (dstTop2 - padding)).roundToInt().toFloat()
                val dstLeft2 = padding.roundToInt().toFloat()
                val dstRight2 = (padding + bmpWidth).roundToInt().toFloat()
                val dstBottom2 = (padding + dataHeight).roundToInt().toFloat()
                dstRect.set(dstLeft2, dstTop2, dstRight2, dstBottom2)
                
                flipMatrix.reset()
                val centerX2 = (dstLeft2 + dstRight2) / 2f
                val centerY2 = (dstTop2 + dstBottom2) / 2f
                flipMatrix.postTranslate(-centerX2, -centerY2)
                flipMatrix.postScale(1f, -1f)
                flipMatrix.postTranslate(centerX2, centerY2)
                
                canvas.save()
                canvas.concat(flipMatrix)
                canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                canvas.restore()
            }
        }
    }
    
    /**
     * 计算合适的时间标签步长，确保标签数量在 5-7 个左右
     * @param visibleTimeSec 可见时间范围（秒）
     * @return 标签步长（秒）
     */
    private fun calculateOptimalTimeStep(visibleTimeSec: Float): Float {
        // 目标标签数量：6 个（不包括"现在"标签）
        val targetLabelCount = 6f
        val idealStep = visibleTimeSec / targetLabelCount
        
        // 候选步长：0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 30, 60
        val candidates = listOf(0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f, 20f, 30f, 60f)
        
        // 选择能产生 5-7 个标签的候选值
        var bestStep = candidates[0]
        var bestLabelCount = visibleTimeSec / bestStep
        var bestScore = abs(bestLabelCount - targetLabelCount)
        
        for (candidate in candidates) {
            val labelCount = visibleTimeSec / candidate
            // 如果候选值产生的标签数在合理范围内（4-8 个），优先选择
            if (labelCount >= 4f && labelCount <= 8f) {
                val score = abs(labelCount - targetLabelCount)
                if (score < bestScore) {
                    bestScore = score
                    bestStep = candidate
                    bestLabelCount = labelCount
                }
            }
        }
        
        // 如果没有找到合适的候选值，选择最接近的
        if (bestLabelCount < 4f || bestLabelCount > 8f) {
            bestStep = candidates[0]
            var minDiff = abs(idealStep - bestStep)
            for (candidate in candidates) {
                if (candidate > idealStep * 2f) break
                val diff = abs(idealStep - candidate)
                if (diff < minDiff) {
                    minDiff = diff
                    bestStep = candidate
                }
            }
        }
        
        // 确保步长不会太小（至少 0.1 秒）
        return bestStep.coerceAtLeast(0.1f)
    }
    
    /** 仅在参数变化时重算网格线屏幕坐标，避免每帧分配与复杂计算，保证 120fps */
    private fun ensureGridCache(drawWidth: Float, drawHeight: Float, contentCenterX: Float) {
        if (filledRows == 0) return
        val effRows = calcEffectiveVisibleRowCount()
        val availableDataRows = (filledRows - historyOffsetRows).coerceAtLeast(0)
        val displayRowCount = minOf(effRows, availableDataRows)
        val visibleTimeSec = displayRowCount * rowIntervalSec
        val valid = gridCacheDrawWidth == drawWidth && gridCacheDrawHeight == drawHeight &&
            gridCacheScaleX == scaleX && gridCacheOffsetX == offsetX &&
            gridCacheScaleModeOrdinal == scaleMode.ordinal && gridCacheSampleRate == sampleRate &&
            gridCacheEffRows == effRows && gridCacheDisplayRowCount == displayRowCount &&
            gridCacheHistoryOffsetRows == historyOffsetRows
        if (valid) return
        
        gridCacheDrawWidth = drawWidth
        gridCacheDrawHeight = drawHeight
        gridCacheScaleX = scaleX
        gridCacheOffsetX = offsetX
        gridCacheScaleModeOrdinal = scaleMode.ordinal
        gridCacheSampleRate = sampleRate
        gridCacheEffRows = effRows
        gridCacheDisplayRowCount = displayRowCount
        gridCacheHistoryOffsetRows = historyOffsetRows
        
        val maxFreq = sampleRate / 2f
        val frequencies = when (scaleMode) {
            AudioVisualizerView.ScaleMode.LINEAR ->
                listOf(0f, maxFreq / 4, maxFreq / 2, maxFreq * 3 / 4, maxFreq)
            AudioVisualizerView.ScaleMode.LOGARITHMIC -> {
                val minF = maxOf(logScaleMinFreq, maxFreq / 2048f)
                val fixed = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
                (listOf(minF) + fixed.filter { it >= minF && it <= maxFreq }).distinct().sorted()
            }
            AudioVisualizerView.ScaleMode.TWELVE_TET -> {
                val minF = maxOf(logScaleMinFreq, maxFreq / 2048f)
                val nMin = kotlin.math.ceil(12 * (ln(minF / twelveTetRefFreq) / ln(2f))).toInt().coerceAtLeast(0)
                val nMax = kotlin.math.floor(12 * (ln(maxFreq / twelveTetRefFreq) / ln(2f))).toInt()
                (nMin..nMax).map { n -> twelveTetRefFreq * Math.pow(2.0, (n / 12f).toDouble()).toFloat() }.filter { f -> f <= maxFreq }
            }
        }
        val vertList = mutableListOf<Float>()
        for (freq in frequencies) {
            val contentX = freqToX(freq, maxFreq, drawWidth)
            val screenX = contentCenterX + offsetX + (contentX - contentCenterX) * scaleX
            if (screenX >= padding && screenX <= width - padding) {
                vertList.add(screenX)
            }
        }
        if (vertList.size > gridVerticalScreenX.size) {
            gridVerticalScreenX = FloatArray(vertList.size)
        }
        gridVerticalCount = vertList.size
        for (i in 0 until gridVerticalCount) gridVerticalScreenX[i] = vertList[i]
        
        val rowHeightOnScreen = drawHeight / effRows
        val labelStepSec = calculateOptimalTimeStep(visibleTimeSec)
        val offsetTimeSec = historyOffsetRows * rowIntervalSec
        var t = if (historyOffsetRows == 0) labelStepSec else {
            val firstT = kotlin.math.ceil(offsetTimeSec / labelStepSec).toFloat() * labelStepSec - offsetTimeSec
            if (firstT <= 0.001f) labelStepSec else firstT
        }
        val horizList = mutableListOf<Float>()
        horizList.add(padding)
        while (t <= visibleTimeSec && (offsetTimeSec + t) <= 60f) {
            val rowIndex = t / rowIntervalSec
            val screenY = padding + rowIndex * rowHeightOnScreen
            if (screenY >= padding && screenY <= padding + drawHeight) {
                horizList.add(screenY)
            }
            t += labelStepSec
        }
        if (horizList.size > gridHorizontalScreenY.size) {
            gridHorizontalScreenY = FloatArray(horizList.size)
        }
        gridHorizontalCount = horizList.size
        for (i in 0 until gridHorizontalCount) gridHorizontalScreenY[i] = horizList[i]
    }
    
    private fun drawGrid(canvas: Canvas, drawWidth: Float, drawHeight: Float) {
        if (filledRows == 0) return
        val contentCenterX = padding + drawWidth / 2f
        ensureGridCache(drawWidth, drawHeight, contentCenterX)
        for (i in 0 until gridVerticalCount) {
            val screenX = gridVerticalScreenX[i]
            canvas.drawLine(screenX, padding, screenX, padding + drawHeight, gridPaint)
        }
        canvas.drawLine(padding, padding, padding + drawWidth, padding, gridPaint)
        for (i in 1 until gridHorizontalCount) {
            val screenY = gridHorizontalScreenY[i]
            canvas.drawLine(padding, screenY, padding + drawWidth, screenY, gridPaint)
        }
    }
    
    // 预计算的斜率因子缓存
    private var slopeFactorCache: FloatArray? = null
    private var slopeFactorCacheSize = 0
    private var slopeFactorCacheSlope = Float.NaN
    
    private fun ensureSlopeFactorCache(spectrumSize: Int) {
        if (slopeFactorCache != null && 
            slopeFactorCacheSize == spectrumSize && 
            slopeFactorCacheSlope == spectrumSlope) {
            return
        }
        
        val maxFreq = sampleRate / 2f
        val cache = FloatArray(spectrumSize)
        
        for (i in 0 until spectrumSize) {
            val freq = (i.toFloat() + 0.5f) / spectrumSize * maxFreq
            cache[i] = Math.pow(10.0, (spectrumSlope * log10((freq / 1000f).coerceAtLeast(0.001f)) / 20f).toDouble()).toFloat()
        }
        
        slopeFactorCache = cache
        slopeFactorCacheSize = spectrumSize
        slopeFactorCacheSlope = spectrumSlope
    }
    
    // 缓存的峰值线 Paint
    private val peakLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 0)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    
    private val holdLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5f, 8f), 0f)
    }
    
    private fun freqToX(freq: Float, maxFreq: Float, drawWidth: Float): Float {
        return freqToXInternal(freq, maxFreq, drawWidth)
    }
    
    private fun createColorMap(preset: Int = 0): IntArray {
        val colors = mutableListOf<Int>()
        when (preset) {
            1 -> { // 灰度
                for (i in 0..95) {
                    val v = (i * 255 / 95).coerceIn(0, 255)
                    colors.add(Color.rgb(v, v, v))
                }
            }
            2 -> { // 黑红
                for (i in 0..95) {
                    val v = (i * 255 / 95).coerceIn(0, 255)
                    colors.add(Color.rgb(v, 0, 0))
                }
            }
            3 -> { // 蓝绿
                for (i in 0..47) {
                    val v = (i * 255 / 47).coerceIn(0, 255)
                    colors.add(Color.rgb(0, 0, v))
                }
                for (i in 0..47) {
                    val v = (i * 255 / 47).coerceIn(0, 255)
                    colors.add(Color.rgb(0, v, 255 - v))
                }
            }
            else -> { // 0: 彩虹
                for (i in 0..15) {
                    val ratio = i / 15f
                    colors.add(Color.rgb(0, 0, (ratio * 255).toInt()))
                }
                for (i in 0..15) {
                    val ratio = i / 15f
                    colors.add(Color.rgb(0, (ratio * 255).toInt(), 255))
                }
                for (i in 0..15) {
                    val ratio = i / 15f
                    colors.add(Color.rgb(0, 255, ((1 - ratio) * 255).toInt()))
                }
                for (i in 0..15) {
                    val ratio = i / 15f
                    colors.add(Color.rgb((ratio * 255).toInt(), 255, 0))
                }
                for (i in 0..15) {
                    val ratio = i / 15f
                    colors.add(Color.rgb(255, ((1 - ratio) * 255).toInt(), 0))
                }
                for (i in 0..15) {
                    val ratio = i / 15f
                    colors.add(Color.rgb(255, (ratio * 255).toInt(), (ratio * 255).toInt()))
                }
            }
        }
        return colors.toIntArray()
    }
    
    private fun drawLabels(canvas: Canvas, drawWidth: Float, drawHeight: Float) {
        if (filledRows == 0) return
        
        val contentCenterX = padding + drawWidth / 2f
        val maxFreq = sampleRate / 2f
        val minF = if (scaleMode == AudioVisualizerView.ScaleMode.LOGARITHMIC || scaleMode == AudioVisualizerView.ScaleMode.TWELVE_TET)
            maxOf(logScaleMinFreq, maxFreq / 2048f) else 0f

        // 频率标签
        val frequencies = when (scaleMode) {
            AudioVisualizerView.ScaleMode.LINEAR ->
                listOf(0f, maxFreq / 4, maxFreq / 2, maxFreq * 3 / 4, maxFreq)
            AudioVisualizerView.ScaleMode.LOGARITHMIC -> {
                val fixed = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
                (listOf(minF) + fixed.filter { it >= minF && it <= maxFreq }).distinct().sorted()
            }
            AudioVisualizerView.ScaleMode.TWELVE_TET -> {
                val nMin = kotlin.math.ceil(12 * (ln(minF / twelveTetRefFreq) / ln(2f))).toInt().coerceAtLeast(0)
                val nMax = kotlin.math.floor(12 * (ln(maxFreq / twelveTetRefFreq) / ln(2f))).toInt()
                (nMin..nMax).map { n -> twelveTetRefFreq * Math.pow(2.0, (n / 12f).toDouble()).toFloat() }.filter { f -> f <= maxFreq }
            }
        }
        
        for (freq in frequencies) {
            val contentX = freqToX(freq, maxFreq, drawWidth)
            val screenX = contentCenterX + offsetX + (contentX - contentCenterX) * scaleX
            if (screenX >= paddingLeft && screenX <= width - paddingRight) {
                val label = when {
                    freq >= 1000 -> "${freq / 1000}kHz"
                    freq >= 1 -> "${freq.toInt()}Hz"
                    else -> "${freq}Hz"
                }
                canvas.drawText(label, screenX, height - 10f, textPaint)
            }
        }
        
        // 时间标签
        if (filledRows > 0) {
            val effectiveVisibleRowCount = calcEffectiveVisibleRowCount()
            // 使用与图像绘制相同的行高计算方式，确保时间标签与图像位置对齐
            val rowHeightOnScreen = drawHeight / effectiveVisibleRowCount
            // 计算实际可用的数据行数（与图像绘制逻辑一致）
            val availableDataRows = (filledRows - historyOffsetRows).coerceAtLeast(0)
            val displayRowCount = minOf(effectiveVisibleRowCount, availableDataRows)
            // 计算实际数据区域的高度（与图像绘制逻辑一致）
            val dataHeight = displayRowCount * rowHeightOnScreen
            
            val offsetTimeSec = historyOffsetRows * rowIntervalSec
            // 使用实际数据行数计算可见时间，而不是有效可见行数
            val visibleTimeSec = displayRowCount * rowIntervalSec
            
            val timeLabels = mutableListOf<Pair<Float, String>>()
            
            // 使用动态计算的步长，确保 5-7 个标签
            val labelStepSec = calculateOptimalTimeStep(visibleTimeSec)
            
            fun formatTimeLabel(timeSec: Float): String {
                return when {
                    timeSec < 0.05f -> "现在"
                    labelStepSec >= 1f -> "${timeSec.toInt()}秒前"
                    timeSec < 10f -> String.format("%.1f秒前", timeSec)
                    else -> "${timeSec.toInt()}秒前"
                }
            }
            
            if (historyOffsetRows == 0) {
                timeLabels.add(0f to "现在")
            } else {
                timeLabels.add(0f to formatTimeLabel(offsetTimeSec))
            }
            
            var t = if (historyOffsetRows == 0) {
                labelStepSec
            } else {
                val firstT = kotlin.math.ceil(offsetTimeSec / labelStepSec).toFloat() * labelStepSec - offsetTimeSec
                if (firstT <= 0.001f) labelStepSec else firstT
            }
            while (t <= visibleTimeSec && (offsetTimeSec + t) <= 60f) {
                val absoluteTime = offsetTimeSec + t
                timeLabels.add(t to formatTimeLabel(absoluteTime))
                t += labelStepSec
                if (timeLabels.size >= 16) break
            }
            
            textPaint.textAlign = Paint.Align.RIGHT
            for ((relativeTime, label) in timeLabels) {
                val rowIndex = relativeTime / rowIntervalSec
                // 使用与图像绘制相同的行高计算方式，确保时间标签与图像位置对齐
                val screenY = padding + rowIndex * rowHeightOnScreen
                // 限制时间标签只在数据区域内显示
                if (screenY >= padding && screenY <= padding + dataHeight)
                    canvas.drawText(label, width - paddingRightLabels, screenY, textPaint)
            }
            textPaint.textAlign = Paint.Align.LEFT
            
            // 历史进度指示
            val totalHistoryTime = filledRows * rowIntervalSec
            if (totalHistoryTime > visibleTimeSec && historyOffsetRows > 0) {
                val progressText = "历史: ${offsetTimeSec.toInt()}~${(offsetTimeSec + visibleTimeSec).toInt()}秒 / ${totalHistoryTime.toInt()}秒"
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(progressText, width - paddingRightLabels, padding + 20f, textPaint)
                textPaint.textAlign = Paint.Align.LEFT
            }
        }
        
        // 峰值线（带衰减：无新数据时逐渐淡化）
        if (showPeakLine && peakFrequency > 0f) {
            val elapsedNs = System.nanoTime() - peakFrequencyLastUpdateNs
            val alpha = if (elapsedNs >= peakLineDecayNs) 0f else 1f - elapsedNs.toFloat() / peakLineDecayNs
            if (alpha > 0.05f) {
                val maxFreq = sampleRate / 2f
                if (peakFrequency <= maxFreq) {
                    val contentX = freqToX(peakFrequency, maxFreq, drawWidth)
                    val screenX = contentCenterX + offsetX + (contentX - contentCenterX) * scaleX
                    if (screenX >= paddingLeft && screenX <= width - paddingRight) {
                        peakLinePaint.alpha = (alpha * 200).toInt().coerceIn(20, 255)
                        canvas.drawLine(screenX, padding, screenX, padding + drawHeight, peakLinePaint)
                        peakLinePaint.alpha = 255
                        
                        val label = when {
                            peakFrequency >= 1000 -> "${String.format("%.1f", peakFrequency / 1000)}kHz"
                            else -> "${peakFrequency.toInt()}Hz"
                        }
                        textPaint.color = Color.argb((alpha * 255).toInt().coerceIn(20, 255), 255, 255, 0)
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(label, screenX, padding - 5f, textPaint)
                        textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
                        textPaint.textAlign = Paint.Align.LEFT
                    }
                }
            }
        }
        
        // 峰值保持线
        if (showPeakHoldLine && peakHoldFrequency > 0f && peakHoldValue > 0.01f) {
            val maxFreq = sampleRate / 2f
            if (peakHoldFrequency <= maxFreq) {
                val contentX = freqToX(peakHoldFrequency, maxFreq, drawWidth)
                val screenX = contentCenterX + offsetX + (contentX - contentCenterX) * scaleX
                if (screenX >= paddingLeft && screenX <= width - paddingRight) {
                    holdLinePaint.color = Color.argb((180 * peakHoldValue).toInt().coerceIn(50, 180), 255, 165, 0)
                    canvas.drawLine(screenX, padding, screenX, padding + drawHeight, holdLinePaint)
                }
            }
        }
    }
}

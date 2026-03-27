package com.fourier.audioanalyzer.audio

import kotlin.math.*

/**
 * 音频滤波器实现
 * 支持低通、高通、带通、陷波滤波器
 * 使用双二阶（Biquad）滤波器级联实现
 */
class AudioFilter {
    
    companion object {
        const val TYPE_LOWPASS = 0
        const val TYPE_HIGHPASS = 1
        const val TYPE_BANDPASS = 2
        const val TYPE_NOTCH = 3
    }
    
    // 滤波器参数
    var enabled: Boolean = false
    var filterType: Int = TYPE_LOWPASS
    var cutoffFreq: Float = 1000f      // 截止频率（低通/高通）
    var centerFreq: Float = 1000f      // 中心频率（带通/陷波）
    var bandwidth: Float = 500f         // 带宽（带通/陷波）
    var order: Int = 2                  // 滤波器阶数
    var sampleRate: Float = 44100f
    
    // Biquad 滤波器系数
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    
    // 级联滤波器的状态（每阶两个状态变量）
    private val maxOrder = 8
    private val x1 = DoubleArray(maxOrder)
    private val x2 = DoubleArray(maxOrder)
    private val y1 = DoubleArray(maxOrder)
    private val y2 = DoubleArray(maxOrder)
    
    // 上次计算时的参数，用于检测参数变化
    private var lastType = -1
    private var lastCutoff = 0f
    private var lastCenter = 0f
    private var lastBandwidth = 0f
    private var lastOrder = 0
    private var lastSampleRate = 0f
    
    /**
     * 更新滤波器系数
     * 当参数发生变化时调用
     */
    fun updateCoefficients() {
        // 检查参数是否变化
        if (filterType == lastType && 
            cutoffFreq == lastCutoff && 
            centerFreq == lastCenter &&
            bandwidth == lastBandwidth &&
            order == lastOrder &&
            sampleRate == lastSampleRate) {
            return
        }
        
        lastType = filterType
        lastCutoff = cutoffFreq
        lastCenter = centerFreq
        lastBandwidth = bandwidth
        lastOrder = order
        lastSampleRate = sampleRate
        
        when (filterType) {
            TYPE_LOWPASS -> calculateLowpassCoefficients()
            TYPE_HIGHPASS -> calculateHighpassCoefficients()
            TYPE_BANDPASS -> calculateBandpassCoefficients()
            TYPE_NOTCH -> calculateNotchCoefficients()
        }
        
        // 重置滤波器状态
        resetState()
    }
    
    /**
     * 重置滤波器状态
     */
    fun resetState() {
        for (i in 0 until maxOrder) {
            x1[i] = 0.0
            x2[i] = 0.0
            y1[i] = 0.0
            y2[i] = 0.0
        }
    }
    
    /**
     * 计算低通滤波器系数（Butterworth）
     */
    private fun calculateLowpassCoefficients() {
        val omega = 2.0 * PI * cutoffFreq / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * 0.7071)  // Q = 0.7071 for Butterworth
        
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cosOmega) / 2.0) / a0
        b1 = (1.0 - cosOmega) / a0
        b2 = ((1.0 - cosOmega) / 2.0) / a0
        a1 = (-2.0 * cosOmega) / a0
        a2 = (1.0 - alpha) / a0
    }
    
    /**
     * 计算高通滤波器系数（Butterworth）
     */
    private fun calculateHighpassCoefficients() {
        val omega = 2.0 * PI * cutoffFreq / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * 0.7071)
        
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosOmega) / 2.0) / a0
        b1 = (-(1.0 + cosOmega)) / a0
        b2 = ((1.0 + cosOmega) / 2.0) / a0
        a1 = (-2.0 * cosOmega) / a0
        a2 = (1.0 - alpha) / a0
    }
    
    /**
     * 计算带通滤波器系数
     */
    private fun calculateBandpassCoefficients() {
        val omega = 2.0 * PI * centerFreq / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        // 计算 Q 值：Q = centerFreq / bandwidth
        val q = centerFreq / bandwidth.coerceAtLeast(1f)
        val alpha = sinOmega / (2.0 * q)
        
        val a0 = 1.0 + alpha
        b0 = (sinOmega / 2.0) / a0
        b1 = 0.0
        b2 = (-sinOmega / 2.0) / a0
        a1 = (-2.0 * cosOmega) / a0
        a2 = (1.0 - alpha) / a0
    }
    
    /**
     * 计算陷波滤波器系数
     */
    private fun calculateNotchCoefficients() {
        val omega = 2.0 * PI * centerFreq / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        // 计算 Q 值：Q = centerFreq / bandwidth
        val q = centerFreq / bandwidth.coerceAtLeast(1f)
        val alpha = sinOmega / (2.0 * q)
        
        val a0 = 1.0 + alpha
        b0 = 1.0 / a0
        b1 = (-2.0 * cosOmega) / a0
        b2 = 1.0 / a0
        a1 = (-2.0 * cosOmega) / a0
        a2 = (1.0 - alpha) / a0
    }
    
    /**
     * 处理单个样本（单阶 Biquad）
     */
    private fun processSampleBiquad(input: Double, stage: Int): Double {
        val output = b0 * input + b1 * x1[stage] + b2 * x2[stage] - a1 * y1[stage] - a2 * y2[stage]
        
        x2[stage] = x1[stage]
        x1[stage] = input
        y2[stage] = y1[stage]
        y1[stage] = output
        
        return output
    }
    
    /**
     * 处理单个样本（级联多阶）
     */
    fun processSample(input: Short): Short {
        if (!enabled) return input
        
        var sample = input.toDouble() / 32768.0
        
        // 级联处理（每两阶对应一个 Biquad）
        val numStages = (order + 1) / 2
        for (stage in 0 until numStages.coerceAtMost(maxOrder)) {
            sample = processSampleBiquad(sample, stage)
        }
        
        // 限幅并转换回 Short
        sample = sample.coerceIn(-1.0, 1.0)
        return (sample * 32767.0).toInt().toShort()
    }
    
    /**
     * 处理样本数组
     */
    fun processBuffer(buffer: ShortArray): ShortArray {
        if (!enabled) return buffer
        
        updateCoefficients()
        
        val output = ShortArray(buffer.size)
        for (i in buffer.indices) {
            output[i] = processSample(buffer[i])
        }
        return output
    }
    
    /**
     * 原地处理样本数组
     */
    fun processBufferInPlace(buffer: ShortArray) {
        if (!enabled) return
        
        updateCoefficients()
        
        for (i in buffer.indices) {
            buffer[i] = processSample(buffer[i])
        }
    }
    
    /**
     * 处理 Float 样本数组（归一化 -1.0 到 1.0）
     */
    fun processFloatBuffer(buffer: FloatArray): FloatArray {
        if (!enabled) return buffer
        
        updateCoefficients()
        
        val output = FloatArray(buffer.size)
        val numStages = (order + 1) / 2
        
        for (i in buffer.indices) {
            var sample = buffer[i].toDouble()
            
            for (stage in 0 until numStages.coerceAtMost(maxOrder)) {
                sample = processSampleBiquad(sample, stage)
            }
            
            output[i] = sample.coerceIn(-1.0, 1.0).toFloat()
        }
        return output
    }
    
    /**
     * 设置滤波器参数（便捷方法）
     */
    fun configure(
        enabled: Boolean = this.enabled,
        filterType: Int = this.filterType,
        cutoffFreq: Float = this.cutoffFreq,
        centerFreq: Float = this.centerFreq,
        bandwidth: Float = this.bandwidth,
        order: Int = this.order,
        sampleRate: Float = this.sampleRate
    ) {
        this.enabled = enabled
        this.filterType = filterType
        this.cutoffFreq = cutoffFreq
        this.centerFreq = centerFreq
        this.bandwidth = bandwidth
        this.order = order.coerceIn(1, 8)
        this.sampleRate = sampleRate
        
        if (enabled) {
            updateCoefficients()
        }
    }
    
    /**
     * 获取滤波器频率响应（用于可视化）
     * @param numPoints 响应点数
     * @return 幅度响应数组（dB）
     */
    fun getFrequencyResponse(numPoints: Int = 512): FloatArray {
        updateCoefficients()
        
        val response = FloatArray(numPoints)
        val numStages = (order + 1) / 2
        
        for (i in 0 until numPoints) {
            // 对数频率刻度：20Hz 到 sampleRate/2
            val freq = 20.0 * 10.0.pow(i.toDouble() / numPoints * log10(sampleRate / 40.0))
            val omega = 2.0 * PI * freq / sampleRate
            
            // 计算复数频率响应 H(e^jω)
            val cosW = cos(omega)
            val sinW = sin(omega)
            val cos2W = cos(2.0 * omega)
            val sin2W = sin(2.0 * omega)
            
            // 分子：B(z) = b0 + b1*z^-1 + b2*z^-2
            val numReal = b0 + b1 * cosW + b2 * cos2W
            val numImag = -b1 * sinW - b2 * sin2W
            
            // 分母：A(z) = 1 + a1*z^-1 + a2*z^-2
            val denReal = 1.0 + a1 * cosW + a2 * cos2W
            val denImag = -a1 * sinW - a2 * sin2W
            
            // |H|^2 = |B|^2 / |A|^2
            val numMag2 = numReal * numReal + numImag * numImag
            val denMag2 = denReal * denReal + denImag * denImag
            
            // 级联多阶
            var totalMag2 = 1.0
            for (stage in 0 until numStages) {
                totalMag2 *= numMag2 / denMag2.coerceAtLeast(1e-10)
            }
            
            // 转换为 dB
            response[i] = (10.0 * log10(totalMag2.coerceAtLeast(1e-10))).toFloat()
        }
        
        return response
    }
}

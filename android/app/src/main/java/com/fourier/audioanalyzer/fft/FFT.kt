package com.fourier.audioanalyzer.fft

import kotlin.math.*

/**
 * 快速傅里叶变换 (FFT) 实现
 * 使用Cooley-Tukey算法进行FFT计算
 */
class FFT(private val n: Int) {
    private val cos = DoubleArray(n / 2)
    private val sin = DoubleArray(n / 2)
    
    init {
        // 验证FFT大小必须是2的幂次方
        require(n > 0) { "FFT大小必须大于0" }
        require((n and (n - 1)) == 0) { "FFT大小必须是2的幂次方，当前值: $n" }
        require(n <= 65536) { "FFT大小不能超过65536（性能限制）" }
        
        // 预计算三角函数值以提高性能
        for (i in 0 until n / 2) {
            cos[i] = cos(-2.0 * PI * i / n)
            sin[i] = sin(-2.0 * PI * i / n)
        }
    }
    
    /**
     * 执行FFT变换
     * @param x 输入实数数组
     * @return 复数数组，包含频率域数据
     */
    fun fft(x: DoubleArray): Array<Complex> {
        require(x.size == n) { "输入数组大小必须为 $n" }
        
        // 转换为复数数组
        val complex = Array(n) { i -> Complex(x[i], 0.0) }
        
        // 执行FFT
        fft(complex)
        
        return complex
    }
    
    /**
     * 迭代FFT实现（优化版本，减少内存分配和递归开销）
     */
    private fun fft(x: Array<Complex>) {
        val n = x.size
        if (n <= 1) return
        
        // 位反转置换（bit-reversal permutation）
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                // 交换 x[i] 和 x[j]
                val temp = x[i]
                x[i] = x[j]
                x[j] = temp
            }
        }
        
        // 迭代FFT计算（使用预计算的三角函数值）
        var len = 2
        while (len <= n) {
            // 使用预计算的三角函数值，避免重复计算
            val step = this.n / len
            
            for (i in 0 until n step len) {
                for (j in 0 until len / 2) {
                    val k = j * step
                    val u = x[i + j]
                    val t = Complex(
                        cos[k] * x[i + j + len / 2].real - sin[k] * x[i + j + len / 2].imag,
                        sin[k] * x[i + j + len / 2].real + cos[k] * x[i + j + len / 2].imag
                    )
                    
                    x[i + j] = Complex(u.real + t.real, u.imag + t.imag)
                    x[i + j + len / 2] = Complex(u.real - t.real, u.imag - t.imag)
                }
            }
            
            len = len shl 1
        }
    }
    
    /**
     * 计算功率谱密度
     * @param fftResult FFT结果
     * @return 功率谱数组（仅包含正频率部分）
     */
    fun computePowerSpectrum(fftResult: Array<Complex>): DoubleArray {
        val spectrum = DoubleArray(n / 2)
        for (i in spectrum.indices) {
            val magnitude = sqrt(fftResult[i].real * fftResult[i].real + 
                                fftResult[i].imag * fftResult[i].imag)
            spectrum[i] = magnitude / n // 归一化
        }
        return spectrum
    }
    
    /**
     * 计算幅度谱（优化版本，减少重复计算）
     * @param fftResult FFT结果
     * @return 幅度谱数组
     */
    fun computeMagnitudeSpectrum(fftResult: Array<Complex>): DoubleArray {
        val spectrum = DoubleArray(n / 2)
        val scale = 1.0 / n  // 预计算归一化因子
        for (i in spectrum.indices) {
            val real = fftResult[i].real
            val imag = fftResult[i].imag
            spectrum[i] = sqrt(real * real + imag * imag) * scale
        }
        return spectrum
    }
}

/**
 * 复数类
 */
data class Complex(
    var real: Double,
    var imag: Double
) {
    operator fun plus(other: Complex): Complex {
        return Complex(real + other.real, imag + other.imag)
    }
    
    operator fun minus(other: Complex): Complex {
        return Complex(real - other.real, imag - other.imag)
    }
    
    operator fun times(other: Complex): Complex {
        return Complex(
            real * other.real - imag * other.imag,
            real * other.imag + imag * other.real
        )
    }
}

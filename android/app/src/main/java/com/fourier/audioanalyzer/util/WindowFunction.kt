package com.fourier.audioanalyzer.util

import kotlin.math.*

/**
 * 窗函数工具类
 * 提供多种窗函数用于FFT预处理
 */
object WindowFunction {
    
    enum class Type {
        RECTANGULAR,  // 矩形窗（无窗）
        HANNING,      // 汉宁窗
        HAMMING,      // 汉明窗
        BLACKMAN,     // 布莱克曼窗
        KAISER        // 凯泽窗
    }
    
    /**
     * 应用窗函数
     */
    fun applyWindow(data: DoubleArray, type: Type = Type.HANNING): DoubleArray {
        val windowed = DoubleArray(data.size)
        val n = data.size
        
        for (i in data.indices) {
            val window = when (type) {
                Type.RECTANGULAR -> 1.0
                Type.HANNING -> hanning(i, n)
                Type.HAMMING -> hamming(i, n)
                Type.BLACKMAN -> blackman(i, n)
                Type.KAISER -> kaiser(i, n, 5.0)
            }
            windowed[i] = data[i] * window
        }
        
        return windowed
    }
    
    /**
     * 汉宁窗
     */
    private fun hanning(i: Int, n: Int): Double {
        return 0.5 * (1 - cos(2 * PI * i / (n - 1)))
    }
    
    /**
     * 汉明窗
     */
    private fun hamming(i: Int, n: Int): Double {
        return 0.54 - 0.46 * cos(2 * PI * i / (n - 1))
    }
    
    /**
     * 布莱克曼窗
     */
    private fun blackman(i: Int, n: Int): Double {
        val a0 = 0.42
        val a1 = 0.5
        val a2 = 0.08
        val x = 2 * PI * i / (n - 1)
        return a0 - a1 * cos(x) + a2 * cos(2 * x)
    }
    
    /**
     * 凯泽窗
     */
    private fun kaiser(i: Int, n: Int, beta: Double): Double {
        val alpha = (n - 1) / 2.0
        val arg = beta * sqrt(1 - ((i - alpha) / alpha).pow(2))
        return besselI0(arg) / besselI0(beta)
    }
    
    /**
     * 修正贝塞尔函数 I0
     */
    private fun besselI0(x: Double): Double {
        var result = 1.0
        var term = 1.0
        val x2 = x * x / 4.0
        
        for (k in 1..20) {
            term *= x2 / (k * k)
            result += term
        }
        
        return result
    }
}

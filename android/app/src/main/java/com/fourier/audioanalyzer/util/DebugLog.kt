package com.fourier.audioanalyzer.util

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 调试日志工具：支持防刷屏、分类、异步输出
 * 
 * 功能：
 * - 按标签分类日志
 * - 相同消息节流（同一消息在指定时间内只输出一次）
 * - 计数器（统计重复消息出现次数）
 * - 异步输出避免阻塞主线程
 * - 错误和警告会立即输出
 */
object DebugLog {
    private const val TAG_PREFIX = "Fourier"
    private val executor = Executors.newSingleThreadExecutor()
    
    // 节流控制：存储每个消息的最后输出时间
    private val lastLogTime = ConcurrentHashMap<String, Long>()
    // 消息计数器
    private val messageCount = ConcurrentHashMap<String, AtomicLong>()
    // 默认节流间隔（毫秒）
    private const val DEFAULT_THROTTLE_MS = 1000L
    // 错误节流间隔（毫秒）
    private const val ERROR_THROTTLE_MS = 5000L
    
    // 日志开关
    var enabled = true
    var verboseEnabled = false  // 详细日志（高频调用）
    
    /**
     * 日志标签枚举
     */
    enum class Tag(val label: String) {
        AUDIO("Audio"),           // 音频录制/处理
        FFT("FFT"),               // FFT 计算
        WAVEFORM("Waveform"),     // 波形显示
        GESTURE("Gesture"),       // 手势操作
        TRIGGER("Trigger"),       // 触发功能
        SETTINGS("Settings"),     // 设置
        UI("UI"),                 // UI 更新
        FILE("File"),             // 文件操作
        SYSTEM("System"),         // 系统级别
        PERF("Perf")              // 性能监控
    }
    
    /**
     * 调试日志（节流）
     * @param tag 日志标签
     * @param message 消息生成函数
     * @param throttleMs 节流间隔，默认 1 秒
     */
    fun d(tag: Tag, throttleMs: Long = DEFAULT_THROTTLE_MS, message: () -> String) {
        if (!enabled) return
        
        val key = "${tag.label}:${message.hashCode()}"
        val now = System.currentTimeMillis()
        val lastTime = lastLogTime[key] ?: 0L
        
        if (now - lastTime >= throttleMs) {
            lastLogTime[key] = now
            val count = messageCount.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
            
            executor.execute {
                val msg = message()
                val fullTag = "$TAG_PREFIX.${tag.label}"
                if (count > 1) {
                    Log.d(fullTag, "$msg (×$count)")
                } else {
                    Log.d(fullTag, msg)
                }
            }
        } else {
            // 静默计数
            messageCount.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        }
    }
    
    /**
     * 详细日志（仅在 verboseEnabled 时输出，更长节流）
     */
    fun v(tag: Tag, throttleMs: Long = 2000L, message: () -> String) {
        if (!enabled || !verboseEnabled) return
        d(tag, throttleMs, message)
    }
    
    /**
     * 警告日志（较长节流，重要信息）
     */
    fun w(tag: Tag, throttleMs: Long = ERROR_THROTTLE_MS, message: () -> String) {
        if (!enabled) return
        
        val key = "W:${tag.label}:${message.hashCode()}"
        val now = System.currentTimeMillis()
        val lastTime = lastLogTime[key] ?: 0L
        
        if (now - lastTime >= throttleMs) {
            lastLogTime[key] = now
            val count = messageCount.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
            
            executor.execute {
                val msg = message()
                val fullTag = "$TAG_PREFIX.${tag.label}"
                if (count > 1) {
                    Log.w(fullTag, "⚠️ $msg (×$count)")
                } else {
                    Log.w(fullTag, "⚠️ $msg")
                }
            }
        } else {
            messageCount.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        }
    }
    
    /**
     * 错误日志（总是输出，但有节流防止刷屏）
     */
    fun e(tag: Tag, message: () -> String, throwable: Throwable? = null) {
        if (!enabled) return
        
        val key = "E:${tag.label}:${message.hashCode()}"
        val now = System.currentTimeMillis()
        val lastTime = lastLogTime[key] ?: 0L
        
        if (now - lastTime >= ERROR_THROTTLE_MS) {
            lastLogTime[key] = now
            val count = messageCount.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
            
            executor.execute {
                val msg = message()
                val fullTag = "$TAG_PREFIX.${tag.label}"
                val displayMsg = if (count > 1) "❌ $msg (×$count)" else "❌ $msg"
                
                if (throwable != null) {
                    Log.e(fullTag, displayMsg, throwable)
                } else {
                    Log.e(fullTag, displayMsg)
                }
            }
        } else {
            messageCount.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        }
    }
    
    /**
     * 一次性日志（不节流，用于重要事件）
     */
    fun once(tag: Tag, eventId: String, message: () -> String) {
        if (!enabled) return
        
        val key = "ONCE:${tag.label}:$eventId"
        if (lastLogTime.putIfAbsent(key, System.currentTimeMillis()) == null) {
            executor.execute {
                Log.i("$TAG_PREFIX.${tag.label}", "📌 ${message()}")
            }
        }
    }
    
    /**
     * 条件检查日志：当条件不满足时输出警告
     */
    fun check(tag: Tag, condition: Boolean, message: () -> String) {
        if (!enabled) return
        if (!condition) {
            w(tag) { "条件检查失败: ${message()}" }
        }
    }
    
    /**
     * 范围检查：当值超出范围时输出警告
     */
    fun <T : Comparable<T>> checkRange(
        tag: Tag, 
        value: T, 
        min: T, 
        max: T, 
        name: String
    ) {
        if (!enabled) return
        if (value < min || value > max) {
            w(tag) { "$name 超出范围: $value (应在 $min ~ $max)" }
        }
    }
    
    /**
     * 性能监控：记录操作耗时
     */
    inline fun <T> timed(tag: Tag, operationName: String, warnThresholdMs: Long = 16L, block: () -> T): T {
        if (!enabled) return block()
        
        val startNs = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f
        
        if (elapsedMs > warnThresholdMs) {
            w(tag, 3000L) { "$operationName 耗时过长: ${String.format("%.1f", elapsedMs)}ms (阈值 ${warnThresholdMs}ms)" }
        } else if (verboseEnabled) {
            v(tag) { "$operationName 耗时: ${String.format("%.1f", elapsedMs)}ms" }
        }
        
        return result
    }
    
    /**
     * 清除节流缓存（用于测试或重置）
     */
    fun clearCache() {
        lastLogTime.clear()
        messageCount.clear()
    }
    
    /**
     * 获取某个消息的累计计数
     */
    fun getCount(tag: Tag, messageHash: Int): Long {
        val key = "${tag.label}:$messageHash"
        return messageCount[key]?.get() ?: 0L
    }
}

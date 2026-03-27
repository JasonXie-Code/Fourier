package com.fourier.audioanalyzer.util

import android.util.Log
import java.util.concurrent.Executors

/**
 * 异步日志：在后台线程执行 Log I/O，避免主线程阻塞导致示波器卡顿。
 * 使用 lazy 的 message 构建，确保字符串拼接也在后台执行。
 */
object AsyncLog {
    private const val TAG = "Oscilloscope"
    private val executor = Executors.newSingleThreadExecutor()

    /** 在后台执行 Log.d，主线程仅提交任务，不阻塞 */
    fun d(message: () -> String) {
        executor.execute { Log.d(TAG, message()) }
    }

    /** 在后台执行 Log.w，主线程仅提交任务，不阻塞 */
    fun w(message: () -> String) {
        executor.execute { Log.w(TAG, message()) }
    }
}

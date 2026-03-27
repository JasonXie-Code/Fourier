package com.fourier.audioanalyzer.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.fourier.audioanalyzer.MainActivity
import com.fourier.audioanalyzer.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext

/**
 * 系统声音捕获前台服务
 * 使用 MediaProjection API 和 AudioPlaybackCapture 捕获设备播放的音频
 * 需要 Android 10 (API 29) 及以上版本
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaProjectionService : Service() {

    companion object {
        private const val TAG = "MediaProjectionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "system_audio_capture_channel"
        
        // 用于从 Activity 启动服务时传递 MediaProjection 数据
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        /** 每次读取的样本数，与 AudioRecorder 保持一致（约 120Hz @ 44.1kHz） */
        private const val READ_CHUNK_SAMPLES = 368
        
        /** 单例引用，供 Activity 获取音频数据 */
        @Volatile
        private var instance: MediaProjectionService? = null
        
        fun getInstance(): MediaProjectionService? = instance
    }
    
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }
    
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var isCapturing = false
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    /** SharedFlow 缓冲多块，与 AudioRecorder 保持一致 */
    private val _audioData = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioData: SharedFlow<ShortArray> = _audioData
    
    private val _isCapturingState = MutableStateFlow(false)
    val isCapturingState: StateFlow<Boolean> = _isCapturingState.asStateFlow()
    
    private val _sampleRateFlow = MutableStateFlow(sampleRate)
    val sampleRateFlow: StateFlow<Int> = _sampleRateFlow.asStateFlow()
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.d(TAG, "服务已创建")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        
        // 立即启动前台服务通知
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 获取 MediaProjection 数据
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        
        if (resultCode != -1 && resultData != null) {
            startCapture(resultCode, resultData)
        } else {
            Log.e(TAG, "无效的 MediaProjection 数据")
            stopSelf()
        }
        
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        instance = null
        scope.cancel()
        Log.d(TAG, "服务已销毁")
    }
    
    /**
     * 设置采样率（需要在启动捕获前调用）
     */
    fun setSampleRate(newSampleRate: Int): Boolean {
        if (isCapturing) {
            Log.w(TAG, "无法在捕获时更改采样率")
            return false
        }
        sampleRate = newSampleRate
        _sampleRateFlow.value = newSampleRate
        return true
    }
    
    /**
     * 开始捕获系统音频
     */
    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (isCapturing) {
            Log.w(TAG, "已在捕获中")
            return
        }
        
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            
            if (mediaProjection == null) {
                Log.e(TAG, "无法获取 MediaProjection")
                stopSelf()
                return
            }
            
            // 注册回调以处理停止事件
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection 已停止")
                    stopCapture()
                }
            }, null)
            
            // 创建 AudioPlaybackCaptureConfiguration
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            
            // 计算缓冲区大小
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                audioFormat
            ) * 2
            
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "无法获取音频缓冲区大小")
                stopSelf()
                return
            }
            
            // 创建 AudioRecord（使用 AudioPlaybackCapture）
            val audioFormatObj = AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()
            
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormatObj)
                .setBufferSizeInBytes(bufferSize)
                .build()
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                audioRecord?.release()
                audioRecord = null
                stopSelf()
                return
            }
            
            // 开始录制
            audioRecord?.startRecording()
            isCapturing = true
            _isCapturingState.value = true
            
            // 启动捕获循环
            captureJob = scope.launch {
                captureAudioLoop(bufferSize)
            }
            
            Log.d(TAG, "系统音频捕获已开始，采样率: $sampleRate")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "权限不足: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "启动捕获失败: ${e.message}", e)
            stopSelf()
        }
    }
    
    /**
     * 音频捕获循环
     */
    private suspend fun captureAudioLoop(bufferSize: Int) {
        val readChunk = ShortArray(minOf(READ_CHUNK_SAMPLES, bufferSize / 2))
        
        while (isCapturing && coroutineContext.isActive) {
            try {
                val readSize = audioRecord?.read(readChunk, 0, readChunk.size) ?: 0
                
                if (readSize > 0) {
                    val data = readChunk.copyOf(readSize)
                    _audioData.tryEmit(data)
                } else if (readSize < 0) {
                    Log.w(TAG, "读取音频数据失败: $readSize")
                }
            } catch (e: Exception) {
                if (isCapturing) Log.w(TAG, "捕获读取异常", e)
            }
            
            delay(0) // 让出协程，保证高刷新率
        }
    }
    
    /**
     * 停止捕获
     */
    fun stopCapture() {
        if (!isCapturing) return
        
        isCapturing = false
        _isCapturingState.value = false
        captureJob?.cancel()
        captureJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "停止录制失败", e)
        }
        
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "停止 MediaProjection 失败", e)
        }
        
        Log.d(TAG, "系统音频捕获已停止")
        
        // 停止前台服务
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "系统声音捕获",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于捕获系统播放的音频"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("傅里叶音频分析器")
            .setContentText("正在捕获系统声音...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

package com.fourier.audioanalyzer.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频源类型
 */
enum class AudioSourceType {
    /** 原始麦克风（无系统降噪/增强处理） */
    MIC_RAW,
    /** 麦克风（系统默认处理） */
    MIC,
    /** 系统声音（需要 Android 10+ 和 MediaProjection 权限） */
    SYSTEM
}

/**
 * 系统音频捕获状态回调
 */
interface SystemAudioCaptureCallback {
    /** 需要请求 MediaProjection 权限 */
    fun onRequestMediaProjection()
    /** 系统音频捕获已启动 */
    fun onSystemAudioCaptureStarted()
    /** 系统音频捕获已停止 */
    fun onSystemAudioCaptureStopped()
}

/**
 * 音频录制器
 * 负责从麦克风或系统音频捕获音频数据并进行处理
 */
class AudioRecorder(
    private var sampleRate: Int = 44100,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private var audioSourceType: AudioSourceType = AudioSourceType.MIC_RAW
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /** 系统音频捕获回调 */
    var systemAudioCaptureCallback: SystemAudioCaptureCallback? = null
    
    /** 是否正在使用系统音频（通过 MediaProjectionService） */
    private var isUsingSystemAudio = false
    
    /** 系统音频数据收集 Job */
    private var systemAudioCollectJob: Job? = null
    
    /** SharedFlow 缓冲多块，避免主线程卡顿时 StateFlow 合并丢弃中间块导致波形跳变 */
    private val _audioData = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioData: SharedFlow<ShortArray> = _audioData
    
    private val _sampleRateFlow = MutableStateFlow(sampleRate)
    val sampleRateFlow: StateFlow<Int> = _sampleRateFlow.asStateFlow()
    
    /**
     * 更改采样率（需要先停止录制）
     */
    fun setSampleRate(newSampleRate: Int): Boolean {
        if (isRecording) {
            Log.w(TAG, "无法在录制时更改采样率")
            return false
        }
        
        // 检查采样率是否支持
        val bufferSize = AudioRecord.getMinBufferSize(
            newSampleRate,
            channelConfig,
            audioFormat
        )
        
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "不支持的采样率: $newSampleRate")
            return false
        }
        
        sampleRate = newSampleRate
        _sampleRateFlow.value = newSampleRate
        Log.d(TAG, "采样率已更改为: $newSampleRate")
        return true
    }
    
    /**
     * 更改音频源类型（需要先停止录制）
     * @return true 表示已切换成功或已请求权限，false 表示不支持
     */
    fun setAudioSourceType(sourceType: AudioSourceType): Boolean {
        if (isRecording) {
            Log.w(TAG, "无法在录制时更改音频源")
            return false
        }
        
        // 系统声音需要 Android 10+
        if (sourceType == AudioSourceType.SYSTEM) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.w(TAG, "系统声音捕获需要 Android 10+")
                return false
            }
            // 请求 MediaProjection 权限
            Log.d(TAG, "请求 MediaProjection 权限...")
            systemAudioCaptureCallback?.onRequestMediaProjection()
        }
        
        audioSourceType = sourceType
        Log.d(TAG, "音频源已更改为: $sourceType")
        return true
    }
    
    /**
     * 通知系统音频已开始捕获（由 MainActivity 调用）
     */
    fun notifySystemAudioStarted() {
        if (audioSourceType == AudioSourceType.SYSTEM) {
            isUsingSystemAudio = true
            systemAudioCaptureCallback?.onSystemAudioCaptureStarted()
            Log.d(TAG, "系统音频捕获已确认启动")
        }
    }
    
    /**
     * 通知系统音频已停止捕获（由 MainActivity 调用）
     */
    fun notifySystemAudioStopped() {
        isUsingSystemAudio = false
        systemAudioCaptureCallback?.onSystemAudioCaptureStopped()
        Log.d(TAG, "系统音频捕获已停止")
    }
    
    /**
     * 获取当前音频源类型
     */
    fun getAudioSourceType(): AudioSourceType = audioSourceType
    
    private var recordingBuffer: MutableList<Short> = mutableListOf()
    private var isSavingRecording = false
    
    companion object {
        private const val TAG = "AudioRecorder"
        private const val BUFFER_SIZE_MULTIPLIER = 2
        /** 每次读取的样本数，用于约 120Hz 回调（44.1kHz 下 44100/368≈119.8Hz）
         *  16 的整数倍，兼容波形网格步长；内部缓冲仍用 getMinBufferSize 防欠载 */
        private const val READ_CHUNK_SAMPLES = 368
    }
    
    /** 根据当前音频源类型返回对应的 AudioSource 常量 */
    private fun getAudioSourceForType(): Int {
        return when (audioSourceType) {
            AudioSourceType.MIC_RAW -> {
                // 原始麦克风：优先使用 UNPROCESSED（API 24+），否则用 MIC
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    MediaRecorder.AudioSource.UNPROCESSED
                } else {
                    MediaRecorder.AudioSource.MIC
                }
            }
            AudioSourceType.MIC -> {
                // 系统默认处理的麦克风
                MediaRecorder.AudioSource.MIC
            }
            AudioSourceType.SYSTEM -> {
                // 系统声音：通过 MediaProjectionService 使用 AudioPlaybackCapture API
                // 这里不会被调用，因为 startRecording() 会直接走 startSystemAudioRecording()
                // 但为了安全，回退到 MIC
                Log.w(TAG, "getAudioSourceForType: SYSTEM 应通过 MediaProjectionService 处理")
                MediaRecorder.AudioSource.MIC
            }
        }
    }
    
    /** 优先返回未处理音源（原始麦克风，无系统降噪/人声增强）；API 24 以下用 MIC */
    @Deprecated("使用 getAudioSourceForType() 替代", ReplaceWith("getAudioSourceForType()"))
    private fun preferUnprocessedAudioSource(): Int {
        return getAudioSourceForType()
    }
    
    /**
     * 开始录制
     */
    fun startRecording() {
        if (isRecording) return
        
        // 系统音频模式：从 MediaProjectionService 获取数据
        if (audioSourceType == AudioSourceType.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startSystemAudioRecording()
            return
        }
        
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            audioFormat
        ) * BUFFER_SIZE_MULTIPLIER
        
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "无法获取音频缓冲区大小")
            return
        }
        
        try {
            // 根据当前音频源类型选择对应的 AudioSource
            val audioSource = getAudioSourceForType()
            Log.d(TAG, "尝试使用音频源: $audioSourceType (source=$audioSource)")
            
            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                // 如果选择的音频源不可用，回退到 MIC
                if (audioSource != MediaRecorder.AudioSource.MIC) {
                    Log.w(TAG, "音频源 $audioSourceType 不可用，回退到 MIC")
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                }
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord初始化失败")
                    audioRecord?.release()
                    audioRecord = null
                    return
                }
            }
            
            audioRecord?.startRecording()
            isRecording = true
            
            recordingJob = scope.launch {
                recordAudio(bufferSize)
            }
            
            Log.d(TAG, "开始录制音频，音频源: $audioSourceType")
        } catch (e: Exception) {
            Log.e(TAG, "录制启动失败", e)
        }
    }
    
    /**
     * 开始系统音频录制（从 MediaProjectionService 获取数据）
     */
    private fun startSystemAudioRecording() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "系统音频捕获需要 Android 10+")
            return
        }
        
        val service = MediaProjectionService.getInstance()
        if (service == null) {
            Log.w(TAG, "MediaProjectionService 未启动，请求权限...")
            systemAudioCaptureCallback?.onRequestMediaProjection()
            return
        }
        
        isRecording = true
        isUsingSystemAudio = true
        
        // 从服务收集音频数据并转发
        systemAudioCollectJob = scope.launch {
            service.audioData.collect { data ->
                _audioData.tryEmit(data)
                
                if (isSavingRecording) {
                    recordingBuffer.addAll(data.toList())
                }
            }
        }
        
        Log.d(TAG, "开始从 MediaProjectionService 接收系统音频")
    }
    
    /**
     * 停止录制
     */
    fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        systemAudioCollectJob?.cancel()
        systemAudioCollectJob = null
        
        // 如果是系统音频模式，不需要停止 AudioRecord（由 MediaProjectionService 管理）
        if (!isUsingSystemAudio) {
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Log.e(TAG, "停止录制失败", e)
            }
        }
        
        isUsingSystemAudio = false
        Log.d(TAG, "停止录制音频")
    }
    
    /**
     * 录制音频循环
     * 使用小块读取（READ_CHUNK_SAMPLES）以支持约 120Hz 数据回调，配合高刷屏丝滑显示；
     * AudioRecord 内部仍用 getMinBufferSize*2 缓冲，防止欠载。
     */
    private suspend fun recordAudio(bufferSize: Int) {
        val readChunk = ShortArray(minOf(READ_CHUNK_SAMPLES, bufferSize / 2))

        while (isRecording && coroutineContext.isActive) {
            try {
                val readSize = audioRecord?.read(readChunk, 0, readChunk.size) ?: 0

                if (readSize > 0) {
                    val data = readChunk.copyOf(readSize)
                    _audioData.tryEmit(data)

                    if (isSavingRecording) {
                        recordingBuffer.addAll(data.toList())
                    }
                } else if (readSize != 0) {
                    Log.w(TAG, "读取音频数据失败: $readSize")
                }
            } catch (e: Exception) {
                if (isRecording) Log.w(TAG, "录制读取异常", e)
            }

            delay(0) // 仅让出协程，不节流，保证高刷新率
        }
    }
    
    /**
     * 开始保存录制
     */
    fun startSavingRecording() {
        isSavingRecording = true
        recordingBuffer.clear()
    }
    
    /**
     * 停止并保存录制
     */
    suspend fun stopAndSaveRecording(file: File): Boolean = withContext(Dispatchers.IO) {
        isSavingRecording = false
        
        if (recordingBuffer.isEmpty()) {
            return@withContext false
        }
        
        try {
            val outputStream = FileOutputStream(file)
            val byteBuffer = ByteBuffer.allocate(recordingBuffer.size * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            
            for (sample in recordingBuffer) {
                byteBuffer.putShort(sample)
            }
            
            outputStream.write(byteBuffer.array())
            outputStream.close()
            
            recordingBuffer.clear()
            Log.d(TAG, "录制已保存到: ${file.absolutePath}")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "保存录制失败", e)
            return@withContext false
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopRecording()
        scope.cancel()
    }
    
    /**
     * 获取当前采样率
     */
    fun getSampleRate(): Int = sampleRate
}

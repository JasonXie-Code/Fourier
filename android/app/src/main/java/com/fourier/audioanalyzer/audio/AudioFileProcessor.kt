package com.fourier.audioanalyzer.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * 音频文件处理器
 * 负责从音频文件中提取音频数据进行分析
 */
class AudioFileProcessor(private val context: Context) {
    
    private val _audioData = MutableStateFlow<ShortArray?>(null)
    val audioData: StateFlow<ShortArray?> = _audioData.asStateFlow()
    
    private val _sampleRateFlow = MutableStateFlow<Int>(44100)
    val sampleRateFlow: StateFlow<Int> = _sampleRateFlow.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _fileInfo = MutableStateFlow<FileInfo?>(null)
    val fileInfo: StateFlow<FileInfo?> = _fileInfo.asStateFlow()
    
    private var extractor: MediaExtractor? = null
    private var audioTrackIndex = -1
    private var sampleRate = 44100
    private var channelCount = 1
    private var durationUs = 0L
    private var currentPositionUs = 0L

    @Volatile
    private var pcmFile: File? = null
    @Volatile
    private var pcmFileOffset = 0L
    
    data class FileInfo(
        val fileName: String,
        val duration: Long, // 微秒
        val sampleRate: Int,
        val channelCount: Int,
        val fileSize: Long
    )
    
    companion object {
        private const val TAG = "AudioFileProcessor"
    }
    
    /**
     * 加载音频文件
     */
    fun loadAudioFile(uri: Uri): Boolean {
        return try {
            extractor = MediaExtractor()
            extractor?.setDataSource(context, uri, null)
            
            // 查找音频轨道
            audioTrackIndex = findAudioTrack()
            if (audioTrackIndex < 0) {
                Log.e(TAG, "未找到音频轨道")
                return false
            }
            
            extractor?.selectTrack(audioTrackIndex)
            val format = extractor?.getTrackFormat(audioTrackIndex)
            
            sampleRate = format?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
            channelCount = format?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            durationUs = format?.getLong(MediaFormat.KEY_DURATION) ?: 0L
            
            _sampleRateFlow.value = sampleRate
            
            // 获取文件信息
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val fileName = getFileName(uri)
            val fileSize = getFileSize(uri)
            
            this.durationUs = durationUs
            _fileInfo.value = FileInfo(
                fileName = fileName,
                duration = durationUs,
                sampleRate = sampleRate,
                channelCount = channelCount,
                fileSize = fileSize
            )
            
            Log.d(TAG, "音频文件加载成功: $fileName, 采样率: $sampleRate, 时长: ${durationUs / 1000000}秒")
            true
        } catch (e: Exception) {
            Log.e(TAG, "加载音频文件失败", e)
            false
        }
    }
    
    /**
     * 加载PCM文件（项目录制的文件）
     */
    fun loadPCMFile(file: File, sampleRate: Int = 44100): Boolean {
        return try {
            this.sampleRate = sampleRate
            _sampleRateFlow.value = sampleRate
            this.pcmFile = file
            this.pcmFileOffset = 0L
            
            val fileSize = file.length()
            this.durationUs = (fileSize / 2 / sampleRate * 1000000L).toLong() // 16-bit = 2 bytes per sample
            
            _fileInfo.value = FileInfo(
                fileName = file.name,
                duration = this.durationUs,
                sampleRate = sampleRate,
                channelCount = 1,
                fileSize = fileSize
            )
            
            Log.d(TAG, "PCM文件加载成功: ${file.name}, 采样率: $sampleRate")
            true
        } catch (e: Exception) {
            Log.e(TAG, "加载PCM文件失败", e)
            false
        }
    }
    
    /**
     * 重置PCM文件位置
     */
    fun resetPCMPosition() {
        pcmFileOffset = 0L
    }
    
    /**
     * 开始播放/分析
     */
    fun startProcessing() {
        _isPlaying.value = true
        currentPositionUs = 0L
    }
    
    /**
     * 停止播放/分析
     */
    fun stopProcessing() {
        _isPlaying.value = false
    }
    
    /**
     * 读取下一帧音频数据
     */
    fun readNextFrame(bufferSize: Int = 4096): ShortArray? {
        if (!_isPlaying.value) return null
        
        return try {
            if (extractor != null) {
                // 从MediaExtractor读取
                readFromExtractor(bufferSize)
            } else if (pcmFile != null) {
                // 从PCM文件读取（使用当前偏移的副本，读后更新）
                val offset = pcmFileOffset
                readPCMData(pcmFile!!, offset, bufferSize)?.also {
                    pcmFileOffset = offset + it.size
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取音频数据失败", e)
            null
        }
    }
    
    /**
     * 从MediaExtractor读取音频数据
     */
    private fun readFromExtractor(bufferSize: Int): ShortArray? {
        val extractor = this.extractor ?: return null
        
        val buffer = ByteArray(bufferSize)
        val sampleBuffer = ShortArray(bufferSize / 2)
        
        val sampleSize = extractor.readSampleData(ByteBuffer.wrap(buffer), 0)
        
        if (sampleSize < 0) {
            // 文件结束
            stopProcessing()
            return null
        }
        
        // 转换为ShortArray
        val byteBuffer = ByteBuffer.wrap(buffer, 0, sampleSize)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = byteBuffer.asShortBuffer()
        shortBuffer.get(sampleBuffer, 0, minOf(sampleSize / 2, sampleBuffer.size))
        
        currentPositionUs = extractor.sampleTime
        extractor.advance()
        
        // 转换为单声道（如果是立体声）
        val monoData = if (channelCount > 1) {
            convertToMono(sampleBuffer, channelCount)
        } else {
            sampleBuffer.sliceArray(0 until (sampleSize / 2))
        }
        
        _audioData.value = monoData
        return monoData
    }
    
    /**
     * 从PCM文件读取数据
     */
    fun readPCMData(file: File, offset: Long, size: Int): ShortArray? {
        return try {
            val inputStream = FileInputStream(file)
            inputStream.skip(offset * 2) // 16-bit = 2 bytes per sample
            
            val buffer = ByteArray(size * 2)
            val bytesRead = inputStream.read(buffer)
            inputStream.close()
            
            if (bytesRead <= 0) return null
            
            val shortArray = ShortArray(bytesRead / 2)
            val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val shortBuffer = byteBuffer.asShortBuffer()
            shortBuffer.get(shortArray)
            
            _audioData.value = shortArray
            shortArray
        } catch (e: Exception) {
            Log.e(TAG, "读取PCM数据失败", e)
            null
        }
    }
    
    /**
     * 转换为单声道
     */
    private fun convertToMono(data: ShortArray, channels: Int): ShortArray {
        val monoSize = data.size / channels
        val mono = ShortArray(monoSize)
        
        for (i in mono.indices) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += data[i * channels + ch].toInt()
            }
            mono[i] = (sum / channels).toShort()
        }
        
        return mono
    }
    
    /**
     * 查找音频轨道
     */
    private fun findAudioTrack(): Int {
        val extractor = this.extractor ?: return -1
        
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }
    
    /**
     * 获取文件名
     */
    private fun getFileName(uri: Uri): String {
        return when {
            uri.scheme == "file" -> File(uri.path ?: "").name
            uri.scheme == "content" -> {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            return@use it.getString(nameIndex) ?: "audio_file"
                        } else {
                            return@use "audio_file"
                        }
                    } else {
                        return@use "audio_file"
                    }
                } ?: "audio_file"
            }
            else -> "audio_file"
        }
    }
    
    /**
     * 获取文件大小
     */
    private fun getFileSize(uri: Uri): Long {
        return when {
            uri.scheme == "file" -> File(uri.path ?: "").length()
            uri.scheme == "content" -> {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex >= 0) {
                            return@use it.getLong(sizeIndex)
                        } else {
                            return@use 0L
                        }
                    } else {
                        return@use 0L
                    }
                } ?: 0L
            }
            else -> 0L
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        extractor?.release()
        extractor = null
        pcmFile = null
        pcmFileOffset = 0L
        _isPlaying.value = false
    }
    
    /**
     * 获取当前播放位置（微秒）
     */
    fun getCurrentPosition(): Long {
        return if (extractor != null) {
            currentPositionUs
        } else if (pcmFile != null) {
            // PCM: 字节偏移转时间 (2 bytes per sample)
            pcmFileOffset * 1_000_000L / (sampleRate * 2)
        } else {
            0L
        }
    }

    /**
     * 获取总时长（微秒）
     */
    fun getDurationUs(): Long = durationUs

    /**
     * 设置播放位置（微秒）
     */
    fun seekTo(positionUs: Long) {
        val pos = positionUs.coerceIn(0L, durationUs)
        if (extractor != null) {
            extractor?.seekTo(pos, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            currentPositionUs = pos
        } else if (pcmFile != null) {
            // PCM: 时间转字节偏移 (2 bytes per sample)
            pcmFileOffset = (pos * sampleRate * 2 / 1_000_000L).coerceIn(0L, pcmFile!!.length())
        }
    }
}

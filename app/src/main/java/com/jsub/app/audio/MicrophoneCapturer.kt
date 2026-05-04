package com.jsub.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * 麦克风音频捕获器（无需MediaProjection）
 *
 * 作为SystemAudioCapturer的回退方案：
 * - 不需要"共享屏幕"授权弹窗
 * - 不需要MediaProjection
 * - 直接录制环境音（包括扬声器播放的声音）
 * - 兼容所有Android版本（API 16+）
 */
class MicrophoneCapturer : AudioCapturer {

    companion object {
        private const val TAG = "MicrophoneCapturer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_DURATION_MS = 500L
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _audioBufferFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val audioBufferFlow: SharedFlow<ByteArray> = _audioBufferFlow.asSharedFlow()

    override fun startCapture() {
        stopCapture()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBufferSize.coerceAtLeast(
            (SAMPLE_RATE * 2 * BUFFER_DURATION_MS / 1000).toInt()
        )

        val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.CAMCORDER
        } else {
            MediaRecorder.AudioSource.MIC
        }

        audioRecord = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        val record = audioRecord ?: run {
            Log.e(TAG, "Failed to create AudioRecord")
            throw IllegalStateException("无法创建麦克风录制器")
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized. Check RECORD_AUDIO permission.")
            throw IllegalStateException("麦克风录制器初始化失败，请检查录音权限是否已授予")
        }

        record.startRecording()

        captureJob = scope.launch {
            val readBuffer = ByteArray(bufferSize)
            var accumulatedBuffer = ByteArray(0)
            val targetBytes = (SAMPLE_RATE * 2 * BUFFER_DURATION_MS / 1000).toInt()

            while (isActive) {
                try {
                    val read = record.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        accumulatedBuffer = accumulatedBuffer.plus(readBuffer.copyOfRange(0, read))
                        while (accumulatedBuffer.size >= targetBytes) {
                            val chunk = accumulatedBuffer.copyOfRange(0, targetBytes)
                            accumulatedBuffer = accumulatedBuffer.copyOfRange(
                                targetBytes,
                                accumulatedBuffer.size
                            )
                            _audioBufferFlow.emit(chunk)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Microphone capture error", e)
                    delay(100)
                }
            }
        }

        Log.i(TAG, "Microphone capture started (source=$audioSource, sampleRate=$SAMPLE_RATE)")
    }

    override fun stopCapture() {
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }

        audioRecord = null
        Log.i(TAG, "Microphone capture stopped")
    }

    override fun release() {
        stopCapture()
        scope.cancel()
    }
}

package com.jsub.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * 系统音频捕获实现
 *
 * 使用 MediaProjection + AudioRecord 捕获系统音频。
 * 输出格式：16kHz, 16bit, 单声道 PCM
 */
class SystemAudioCapturer : AudioCapturer {

    companion object {
        private const val TAG = "SystemAudioCapturer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_DURATION_MS = 500L // 每500ms推送一次
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val _audioBufferFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val audioBufferFlow: Flow<ByteArray> = _audioBufferFlow.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startCapture(mediaProjection: MediaProjection) {
        stopCapture()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBufferSize.coerceAtLeast(
            (SAMPLE_RATE * 2 * BUFFER_DURATION_MS / 1000).toInt()
        )

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AUDIO_FORMAT)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        val record = audioRecord ?: run {
            Log.e(TAG, "Failed to create AudioRecord")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            return
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
                    Log.e(TAG, "Audio capture error", e)
                    delay(100)
                }
            }
        }

        Log.i(TAG, "Audio capture started")
    }

    override fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        Log.i(TAG, "Audio capture stopped")
    }

    override fun release() {
        stopCapture()
        scope.cancel()
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
        Log.i(TAG, "Audio capturer released")
    }
}

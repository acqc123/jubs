package com.jsub.app.audio

import android.media.projection.MediaProjection
import kotlinx.coroutines.flow.Flow

/**
 * 音频捕获器接口
 *
 * 负责捕获设备音频（系统音频或麦克风），输出PCM格式的音频数据流。
 * 输出格式：16kHz采样率, 16bit, 单声道
 */
interface AudioCapturer {

    /**
     * 开始音频捕获（系统音频模式，需要MediaProjection）
     */
    fun startCapture(mediaProjection: MediaProjection) {
        throw UnsupportedOperationException("This capturer does not support system audio capture")
    }

    /**
     * 开始音频捕获（麦克风模式，无需MediaProjection）
     */
    fun startCapture() {
        throw UnsupportedOperationException("This capturer does not support microphone capture")
    }

    /**
     * 停止音频捕获
     */
    fun stopCapture()

    /**
     * 释放所有资源
     */
    fun release()

    /**
     * PCM音频数据流
     */
    val audioBufferFlow: Flow<ByteArray>
}

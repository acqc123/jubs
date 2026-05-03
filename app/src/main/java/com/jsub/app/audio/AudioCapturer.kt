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
     * 开始音频捕获
     *
     * @param mediaProjection MediaProjection实例，用于捕获系统音频
     */
    fun startCapture(mediaProjection: MediaProjection)

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
     *
     * 每次emit一个音频数据块，约0.5秒的音频数据
     */
    val audioBufferFlow: Flow<ByteArray>
}

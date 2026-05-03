package com.jsub.app.speech.engine

import kotlinx.coroutines.flow.Flow

/**
 * 语音识别引擎接口
 *
 * 统一接口，支持多种语音识别引擎（Whisper在线API / SenseVoice本地ONNX / AnimeWhisper在线API）。
 * 所有引擎实现此接口即可被StreamingSpeechProcessor使用。
 */
interface SpeechRecognitionEngine {

    /**
     * 引擎名称（用于日志和显示）
     */
    val name: String

    /**
     * 是否需要网络连接
     */
    val requiresNetwork: Boolean

    /**
     * 是否需要下载模型文件（本地引擎返回true）
     */
    val requiresModelDownload: Boolean

    /**
     * 模型是否已准备就绪
     */
    val isModelReady: Boolean

    /**
     * 初始化引擎（如下载模型等）
     *
     * @return 初始化是否成功
     */
    suspend fun initialize(): Boolean

    /**
     * 单段音频识别
     *
     * @param audioData PCM音频数据（16kHz, 16bit, 单声道）
     * @return 识别出的日文文本，空字符串表示无内容，"[xxx]"格式表示错误
     */
    suspend fun recognize(audioData: ByteArray): String

    /**
     * 流式音频识别
     *
     * 持续接收音频流，实时输出识别结果。
     *
     * @param audioFlow 音频数据流
     * @return 识别文本流
     */
    fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String>

    /**
     * 释放引擎资源
     *
     * 清理模型、内存和网络资源。
     */
    fun release()
}

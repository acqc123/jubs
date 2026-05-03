package com.jsub.app.api

import kotlinx.coroutines.flow.Flow

/**
 * 语音识别结果
 *
 * @param text 识别出的文本
 * @param isFinal 是否是最终结果（true=确定结果, false=中间结果）
 * @param confidence 置信度 0.0-1.0
 */
data class RecognitionResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float
)

/**
 * 语音识别API接口
 *
 * 将音频数据识别为日文文本。
 */
interface SpeechRecognitionApi {

    /**
     * 单段音频识别
     *
     * @param audioData PCM音频数据（16kHz, 16bit, 单声道）
     * @return 识别结果
     */
    suspend fun recognize(audioData: ByteArray): RecognitionResult

    /**
     * 流式音频识别
     *
     * 持续接收音频流，实时输出识别结果。
     *
     * @param audioFlow 音频数据流
     * @return 识别结果流
     */
    fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<RecognitionResult>
}

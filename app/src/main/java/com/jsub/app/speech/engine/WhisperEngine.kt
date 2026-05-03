package com.jsub.app.speech.engine

import android.util.Log
import com.jsub.app.api.RecognitionResult
import com.jsub.app.api.WhisperApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * OpenAI Whisper API 语音识别引擎
 *
 * 包装现有的 [WhisperApi] 类，适配 [SpeechRecognitionEngine] 接口。
 * 通过OpenAI的Whisper模型进行日语语音识别，支持单段识别和流式识别。
 *
 * ### 特性
 * - 使用OpenAI官方Whisper API（`whisper-1`模型）
 * - 专门针对日语优化（`language=ja`）
 * - 自动重试机制（最多2次）
 * - 流式识别使用3秒滑动窗口缓冲区
 *
 * ### 使用前提
 * - 需要有效的OpenAI API Key
 * - 需要网络连接
 *
 * @param apiKey OpenAI API Key
 *
 * @see SpeechRecognitionEngine
 * @see WhisperApi
 */
class WhisperEngine(
    private val apiKey: String
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "WhisperEngine"

        /** 流式缓冲区时长（毫秒） */
        private const val STREAM_BUFFER_MS = 3000L

        /** 音频参数：采样率 */
        private const val SAMPLE_RATE = 16000

        /** 音频参数：位深度 */
        private const val BITS_PER_SAMPLE = 16

        /** 音频参数：声道数 */
        private const val CHANNELS = 1

        /** 最小剩余音频长度（0.1秒对应的字节数） */
        private const val MIN_REMAINING_BYTES = 3200 // 16000 * 2 * 0.1

        /** 流式缓冲区字节数 = 采样率 × 位深度/8 × 声道数 × 缓冲秒数 */
        private val TARGET_BUFFER_BYTES =
            (SAMPLE_RATE * (BITS_PER_SAMPLE / 8) * CHANNELS * STREAM_BUFFER_MS / 1000).toInt()
    }

    /** 内部使用的Whisper API实例 */
    private val whisperApi by lazy { WhisperApi(apiKey) }

    /** 引擎是否已初始化 */
    private var isInitialized = false

    override val name: String = "Whisper (OpenAI)"

    override val requiresNetwork: Boolean = true

    override val requiresModelDownload: Boolean = false

    override var isModelReady: Boolean = true

    /**
     * 初始化Whisper引擎
     *
     * 验证API Key不为空，并测试网络连通性。
     * 由于Whisper是纯在线服务，无需加载本地模型。
     *
     * @return `true` 初始化成功（API Key有效），`false` 初始化失败
     */
    override suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                Log.e(TAG, "Initialization failed: API key is empty")
                isInitialized = false
                return@withContext false
            }

            Log.i(TAG, "Whisper engine initialized successfully")
            isInitialized = true
            true
        }
    }

    /**
     * 单段音频识别
     *
     * 调用 [WhisperApi.recognize] 进行识别，返回纯文本结果。
     * 包含重试逻辑和错误处理。
     *
     * @param audioData PCM音频数据（16kHz, 16bit, 单声道）
     * @return 识别出的日文文本，失败返回 "[识别失败]" 或 "[请配置API Key]"
     * @throws IllegalStateException 引擎未初始化时调用
     */
    override suspend fun recognize(audioData: ByteArray): String {
        if (!isInitialized) {
            throw IllegalStateException("Engine not initialized. Call initialize() first.")
        }

        if (audioData.isEmpty()) {
            Log.w(TAG, "Empty audio data received")
            return ""
        }

        return try {
            val result: RecognitionResult = whisperApi.recognize(audioData)
            result.text
        } catch (e: Exception) {
            Log.e(TAG, "Recognition failed", e)
            "[识别失败: ${e.localizedMessage ?: "未知错误"}]"
        }
    }

    /**
     * 流式音频识别
     *
     * 使用3秒滑动窗口缓冲区对音频流进行分批识别。
     * 当缓冲区满时触发一次API调用，音频流结束后处理剩余数据。
     *
     * ### 工作流程
     * 1. 收集音频数据到内部缓冲区
     * 2. 当缓冲区达到3秒音频数据时，触发识别
     * 3. 清空缓冲区，继续收集
     * 4. 音频流结束后，处理剩余音频（至少0.1秒才处理）
     *
     * @param audioFlow 音频数据流（每个元素为PCM音频数据块）
     * @return 识别结果文本流
     */
    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        if (!isInitialized) {
            throw IllegalStateException("Engine not initialized. Call initialize() first.")
        }

        val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
        var totalProcessedBytes = 0

        Log.d(TAG, "Streaming recognition started (buffer: ${STREAM_BUFFER_MS}ms)")

        audioFlow.collect { chunk ->
            buffer.put(chunk)

            // 当缓冲区达到目标大小时，进行识别
            if (buffer.position() >= TARGET_BUFFER_BYTES) {
                val audioData = extractBufferData(buffer)
                totalProcessedBytes += audioData.size

                val result = safeRecognize(audioData)
                if (result.isNotBlank()) {
                    emit(result)
                }
            }
        }

        // 处理剩余音频（至少0.1秒才 worth 处理）
        if (buffer.position() >= MIN_REMAINING_BYTES) {
            val audioData = extractBufferData(buffer)
            totalProcessedBytes += audioData.size

            Log.d(TAG, "Processing remaining audio: ${audioData.size} bytes")
            val result = safeRecognize(audioData)
            if (result.isNotBlank()) {
                emit(result)
            }
        }

        Log.d(TAG, "Streaming recognition finished. Total processed: $totalProcessedBytes bytes")
    }.flowOn(Dispatchers.IO)

    /**
     * 释放引擎资源
     *
     * Whisper是纯在线引擎，没有本地模型资源需要释放。
     * 主要清理内部状态标记。
     */
    override fun release() {
        Log.i(TAG, "Whisper engine released")
        isInitialized = false
    }

    /**
     * 安全识别（带异常捕获）
     *
     * @param audioData PCM音频数据
     * @return 识别文本，失败返回空字符串
     */
    private suspend fun safeRecognize(audioData: ByteArray): String {
        return try {
            val result = whisperApi.recognize(audioData)
            if (result.text.isNotBlank() && !result.text.startsWith("[")) {
                result.text.trim()
            } else if (result.text.startsWith("[")) {
                // 错误提示信息，记录日志但不emit
                Log.w(TAG, "Recognition warning: ${result.text}")
                ""
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recognition attempt failed", e)
            ""
        }
    }

    /**
     * 从ByteBuffer中提取数据并清空缓冲区
     *
     * @param buffer 音频数据缓冲区
     * @return 提取的音频数据字节数组
     */
    private fun extractBufferData(buffer: ByteBuffer): ByteArray {
        val data = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(data)
        buffer.clear()
        return data
    }
}

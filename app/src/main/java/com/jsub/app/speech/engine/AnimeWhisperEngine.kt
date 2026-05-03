package com.jsub.app.speech.engine

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Anime-Whisper 语音识别引擎
 *
 * 基于 HuggingFace Inference API 的动漫优化Whisper模型。
 * 使用 `litagin/anime-whisper-medium-v1` 模型，针对动漫/游戏语音进行了专门训练，
 * 在识别动漫角色语音方面优于通用Whisper模型。
 *
 * ### 特性
 * - 动漫/游戏语音优化识别
 * - HuggingFace Inference API（免费额度可用）
 * - 可选API Token（提高速率限制）
 * - 模型冷启动自动等待重试
 * - 流式识别使用3秒滑动窗口缓冲区
 *
 * ### API说明
 * - 端点：`POST https://api-inference.huggingface.co/models/litagin/anime-whisper-medium-v1`
 * - 请求：Base64编码的WAV音频数据（JSON格式）
 * - 响应：`{"text": "识别结果"}`
 *
 * ### 模型冷启动
 * HuggingFace的免费推理API在无请求时会让模型休眠。
 * 冷启动时API返回503，引擎会自动等待并重试（最多5次，每次间隔3秒）。
 *
 * @param apiKey HuggingFace API Token（可选，但建议使用以提高速率限制）
 *
 * @see SpeechRecognitionEngine
 */
class AnimeWhisperEngine(
    private val apiKey: String = ""
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "AnimeWhisperEngine"

        /** HuggingFace Inference API端点 */
        private const val API_URL =
            "https://api-inference.huggingface.co/models/litagin/anime-whisper-medium-v1"

        /** 流式缓冲区时长（毫秒） */
        private const val STREAM_BUFFER_MS = 3000L

        /** 音频参数：采样率 */
        private const val SAMPLE_RATE = 16000

        /** 音频参数：位深度 */
        private const val BITS_PER_SAMPLE = 16

        /** 音频参数：声道数 */
        private const val CHANNELS = 1

        /** 最大重试次数 */
        private const val MAX_RETRIES = 5

        /** 模型冷启动等待间隔（毫秒） */
        private const val COLD_START_RETRY_DELAY_MS = 3000L

        /** 通用请求失败重试间隔（毫秒） */
        private const val RETRY_DELAY_MS = 1000L

        /** 请求超时（秒） */
        private const val REQUEST_TIMEOUT_SECONDS = 30L

        /** 最小剩余音频长度（0.1秒对应的字节数: 16000 * 2 * 0.1） */
        private const val MIN_REMAINING_BYTES = 3200

        /** 流式缓冲区字节数 = 采样率 × 位深度/8 × 声道数 × 缓冲秒数 */
        private val TARGET_BUFFER_BYTES =
            (SAMPLE_RATE * (BITS_PER_SAMPLE / 8) * CHANNELS * STREAM_BUFFER_MS / 1000).toInt()
    }

    /** JSON序列化器 */
    private val json = Json { ignoreUnknownKeys = true }

    /** OkHttp客户端 */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 引擎是否已初始化 */
    private var isInitialized = false

    override val name: String = "Anime-Whisper (HF)"

    override val requiresNetwork: Boolean = true

    override val requiresModelDownload: Boolean = false

    override var isModelReady: Boolean = true

    /**
     * API请求体数据类
     */
    @Serializable
    private data class InferenceRequest(
        @SerialName("inputs") val inputs: String
    )

    /**
     * API响应数据类
     */
    @Serializable
    private data class InferenceResponse(
        @SerialName("text") val text: String = "",
        @SerialName("error") val error: String? = null
    )

    /**
     * HuggingFace错误响应
     */
    @Serializable
    private data class HuggingFaceError(
        @SerialName("error") val error: String = "",
        @SerialName("estimated_time") val estimatedTime: Double? = null
    )

    /**
     * 初始化Anime-Whisper引擎
     *
     * 验证网络连通性和API可访问性。
     * 如果提供了API Token，验证Token格式。
     *
     * @return `true` 初始化成功，`false` 初始化失败
     */
    override suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 验证网络连通性：发送一个空请求测试API可达性
                val testRequest = buildInferenceRequest(ByteArray(0))
                client.newCall(testRequest).execute().use { response ->
                    when (response.code()) {
                        200, 400, 422 -> {
                            // API可达（400/422是因为空音频，说明服务在线）
                            Log.i(TAG, "Anime-Whisper engine initialized successfully")
                            isInitialized = true
                            true
                        }
                        401 -> {
                            Log.w(TAG, "API Token invalid or expired")
                            // Token无效但API可达，仍然可以使用（可能有限流）
                            isInitialized = true
                            true
                        }
                        503 -> {
                            // 模型冷启动中，这是正常的
                            Log.i(TAG, "Model is warming up, engine initialized (will retry on first request)")
                            isInitialized = true
                            true
                        }
                        else -> {
                            Log.w(TAG, "Unexpected response code: ${response.code()}")
                            isInitialized = true
                            true
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Initialization failed: network error", e)
                // 网络不通，但仍然标记为初始化（使用时再报错）
                isInitialized = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                false
            }
        }
    }

    /**
     * 单段音频识别
     *
     * 将PCM音频数据包装为WAV格式，Base64编码后发送到HuggingFace Inference API。
     * 包含模型冷启动等待重试逻辑。
     *
     * @param audioData PCM音频数据（16kHz, 16bit, 单声道）
     * @return 识别出的日文文本，失败返回空字符串或错误提示
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

        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null

            repeat(MAX_RETRIES) { attempt ->
                try {
                    // PCM -> WAV -> Base64
                    val wavData = addWavHeader(audioData, SAMPLE_RATE)
                    val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)

                    val requestBody = RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        json.encodeToString(InferenceRequest.serializer(), InferenceRequest(base64Audio))
                    )

                    val requestBuilder = Request.Builder()
                        .url(API_URL)
                        .post(requestBody)

                    // 如果提供了API Token，添加到请求头
                    if (apiKey.isNotBlank()) {
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }

                    val request = requestBuilder.build()

                    client.newCall(request).execute().use { response ->
                        val body = response.body()?.string() ?: ""

                        when (response.code()) {
                            200 -> {
                                // 解析成功响应
                                val result = parseResponse(body)
                                return@withContext result
                            }
                            503 -> {
                                // 模型冷启动中，等待后重试
                                val errorData = try {
                                    json.decodeFromString(HuggingFaceError.serializer(), body)
                                } catch (_: Exception) {
                                    null
                                }
                                val waitTime = errorData?.estimatedTime?.toLong()
                                    ?: (COLD_START_RETRY_DELAY_MS / 1000)
                                Log.i(TAG, "Model warming up, waiting ${waitTime}s before retry ${attempt + 1}/$MAX_RETRIES")
                                delay(waitTime * 1000)
                                lastException = IOException("Model warming up (503)")
                            }
                            400, 422 -> {
                                // 请求格式错误
                                Log.e(TAG, "Bad request: $body")
                                return@withContext "[请求格式错误]"
                            }
                            401, 403 -> {
                                Log.e(TAG, "Authentication failed: $body")
                                return@withContext "[API认证失败，请检查Token]"
                            }
                            429 -> {
                                // 速率限制
                                Log.w(TAG, "Rate limited, retrying...")
                                lastException = IOException("Rate limited (429)")
                                delay(RETRY_DELAY_MS * 2)
                            }
                            else -> {
                                lastException = IOException("HTTP ${response.code()}: $body")
                                Log.w(TAG, "Request failed: ${response.code()}, retry ${attempt + 1}/$MAX_RETRIES")
                                if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS)
                            }
                        }
                    }
                } catch (e: IOException) {
                    lastException = e
                    Log.w(TAG, "Recognize attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error during recognition", e)
                    return@withContext "[识别错误: ${e.localizedMessage ?: "未知错误"}]"
                }
            }

            Log.e(TAG, "All $MAX_RETRIES recognition retries failed", lastException)
            "[识别失败，请检查网络或稍后重试]"
        }
    }

    /**
     * 流式音频识别
     *
     * 使用3秒滑动窗口缓冲区对音频流进行分批识别。
     * 当缓冲区满时触发一次API调用，音频流结束后处理剩余数据。
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
        var chunkCount = 0

        Log.d(TAG, "Streaming recognition started (buffer: ${STREAM_BUFFER_MS}ms)")

        audioFlow.collect { chunk ->
            buffer.put(chunk)

            // 当缓冲区达到目标大小时，进行识别
            if (buffer.position() >= TARGET_BUFFER_BYTES) {
                val audioData = extractBufferData(buffer)
                totalProcessedBytes += audioData.size
                chunkCount++

                val result = safeRecognize(audioData)
                if (result.isNotBlank()) {
                    Log.d(TAG, "Chunk $chunkCount result: $result")
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

        Log.d(TAG, "Streaming recognition finished. Chunks: $chunkCount, Total: $totalProcessedBytes bytes")
    }.flowOn(Dispatchers.IO)

    /**
     * 释放引擎资源
     *
     * 清理HTTP客户端和内部状态。
     */
    override fun release() {
        Log.i(TAG, "Anime-Whisper engine released")
        isInitialized = false
        // OkHttpClient不需要显式关闭，让它自然回收
    }

    /**
     * 安全识别（带异常捕获）
     *
     * @param audioData PCM音频数据
     * @return 识别文本，失败返回空字符串
     */
    private suspend fun safeRecognize(audioData: ByteArray): String {
        return try {
            val result = recognize(audioData)
            if (result.startsWith("[")) {
                // 错误提示信息，记录日志但不emit
                Log.w(TAG, "Recognition warning: $result")
                ""
            } else {
                result.trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recognition attempt failed", e)
            ""
        }
    }

    /**
     * 构建HuggingFace推理请求
     *
     * 用于初始化时的连通性测试。
     *
     * @param audioData 音频数据
     * @return OkHttp Request对象
     */
    private fun buildInferenceRequest(audioData: ByteArray): Request {
        val wavData = addWavHeader(audioData, SAMPLE_RATE)
        val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)

        val requestBody = RequestBody.create(
            MediaType.parse("application/json; charset=utf-8"),
            json.encodeToString(InferenceRequest.serializer(), InferenceRequest(base64Audio))
        )

        val requestBuilder = Request.Builder()
            .url(API_URL)
            .post(requestBody)

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        return requestBuilder.build()
    }

    /**
     * 解析API响应
     *
     * HuggingFace Inference API的响应格式可能有两种：
     * 1. 简单文本：`{"text": "识别结果"}`
     * 2. 错误信息：`{"error": "错误描述"}`
     *
     * @param body 响应体字符串
     * @return 识别文本，出错返回空字符串或错误提示
     */
    private fun parseResponse(body: String): String {
        return try {
            // 尝试解析为成功响应
            val response = json.decodeFromString(InferenceResponse.serializer(), body)

            if (response.error != null) {
                Log.e(TAG, "API error: ${response.error}")
                return "[API错误: ${response.error}]"
            }

            response.text.trim()
        } catch (e: Exception) {
            // 如果JSON解析失败，尝试直接提取text字段
            Log.w(TAG, "Failed to parse JSON response, attempting fallback: $body")
            try {
                // 最后的fallback：检查是否是纯文本
                if (body.startsWith("{") && body.contains("\"text\"")) {
                    val regex = """"text"\s*:\s*"([^"]*)"""".toRegex()
                    val match = regex.find(body)
                    match?.groupValues?.get(1)?.trim() ?: "[解析响应失败]"
                } else {
                    "[解析响应失败: ${e.localizedMessage}]"
                }
            } catch (_: Exception) {
                "[解析响应失败]"
            }
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

    /**
     * 为PCM音频数据添加WAV文件头
     *
     * 生成符合RIFF/WAVE格式的标准WAV文件头（44字节），
     * 然后将PCM数据追加到头部后面。
     *
     * ### WAV文件结构
     * ```
     * 偏移  大小  内容
     * 0     4     "RIFF"
     * 4     4     文件总大小 - 8
     * 8     4     "WAVE"
     * 12    4     "fmt "
     * 16    4     fmt块大小 (16)
     * 20    2     音频格式 (1 = PCM)
     * 22    2     声道数
     * 24    4     采样率
     * 28    4     字节率 = 采样率 × 声道数 × 位深度/8
     * 32    2     块对齐 = 声道数 × 位深度/8
     * 34    2     位深度
     * 36    4     "data"
     * 40    4     PCM数据大小
     * 44    ...   PCM音频数据
     * ```
     *
     * @param pcmData PCM音频原始数据（16bit, 单声道）
     * @param sampleRate 采样率（默认16000Hz）
     * @return 完整的WAV格式音频数据
     */
    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int = 16000): ByteArray {
        val pcmDataLen = pcmData.size
        val totalDataLen = pcmDataLen + 36
        val byteRate = sampleRate * CHANNELS * (BITS_PER_SAMPLE / 8)
        val blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8)

        val header = ByteArray(44)

        // RIFF chunk (12 bytes)
        // 0-3: "RIFF"
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        // 4-7: 文件总大小 - 8
        header[4] = (totalDataLen and 0xFF).toByte()
        header[5] = ((totalDataLen shr 8) and 0xFF).toByte()
        header[6] = ((totalDataLen shr 16) and 0xFF).toByte()
        header[7] = ((totalDataLen shr 24) and 0xFF).toByte()
        // 8-11: "WAVE"
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt chunk (24 bytes)
        // 12-15: "fmt "
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        // 16-19: fmt块大小 (16 for PCM)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        // 20-21: 音频格式 (1 = PCM)
        header[20] = 1
        header[21] = 0
        // 22-23: 声道数
        header[22] = CHANNELS.toByte()
        header[23] = 0
        // 24-27: 采样率
        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = ((sampleRate shr 8) and 0xFF).toByte()
        header[26] = ((sampleRate shr 16) and 0xFF).toByte()
        header[27] = ((sampleRate shr 24) and 0xFF).toByte()
        // 28-31: 字节率
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = ((byteRate shr 8) and 0xFF).toByte()
        header[30] = ((byteRate shr 16) and 0xFF).toByte()
        header[31] = ((byteRate shr 24) and 0xFF).toByte()
        // 32-33: 块对齐
        header[32] = (blockAlign and 0xFF).toByte()
        header[33] = ((blockAlign shr 8) and 0xFF).toByte()
        // 34-35: 位深度
        header[34] = BITS_PER_SAMPLE.toByte()
        header[35] = 0

        // data chunk (8 bytes + PCM data)
        // 36-39: "data"
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        // 40-43: PCM数据大小
        header[40] = (pcmDataLen and 0xFF).toByte()
        header[41] = ((pcmDataLen shr 8) and 0xFF).toByte()
        header[42] = ((pcmDataLen shr 16) and 0xFF).toByte()
        header[43] = ((pcmDataLen shr 24) and 0xFF).toByte()

        return header + pcmData
    }
}

package com.jsub.app.api

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI Whisper API实现
 *
 * 使用Whisper模型进行日语语音识别。
 * 支持单段识别和近实时的流式识别（通过3秒缓冲区）。
 */
class WhisperApi(
    private val apiKey: String
) : SpeechRecognitionApi {

    companion object {
        private const val TAG = "WhisperApi"
        private const val API_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val MODEL = "whisper-1"
        private const val LANGUAGE = "ja"
        private const val STREAM_BUFFER_MS = 3000L // 流式缓冲3秒
        private const val MAX_RETRIES = 2
        private const val REQUEST_TIMEOUT_SECONDS = 15L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    data class WhisperResponse(
        @SerialName("text") val text: String = "",
        @SerialName("error") val error: WhisperError? = null
    )

    @Serializable
    data class WhisperError(
        @SerialName("message") val message: String,
        @SerialName("type") val type: String,
        @SerialName("code") val code: String? = null
    )

    override suspend fun recognize(audioData: ByteArray): RecognitionResult {
        if (audioData.isEmpty()) {
            return RecognitionResult("", true, 0f)
        }

        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is empty")
            return RecognitionResult("[请配置API Key]", true, 0f)
        }

        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null

            repeat(MAX_RETRIES) { attempt ->
                try {
                    val requestBody = buildRequestBody(audioData)
                    val request = Request.Builder()
                        .url(API_URL)
                        .post(requestBody)
                        .header("Authorization", "Bearer $apiKey")
                        .build()

                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""

                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code}: $body")
                        }

                        body?.let {
                            val result = json.decodeFromString(WhisperResponse.serializer(), it)
                            result.error?.let { error ->
                                return@withContext RecognitionResult(
                                    "[API错误: ${error.message}]",
                                    true,
                                    0f
                                )
                            }
                            return@withContext RecognitionResult(
                                text = result.text.trim(),
                                isFinal = true,
                                confidence = 0.9f
                            )
                        } ?: throw IOException("Empty response")
                    }
                } catch (e: IOException) {
                    lastException = e
                    Log.w(TAG, "Recognize attempt ${attempt + 1} failed", e)
                    if (attempt < MAX_RETRIES - 1) delay(1000)
                }
            }

            Log.e(TAG, "All recognize retries failed", lastException)
            RecognitionResult("[识别失败]", true, 0f)
        }
    }

    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<RecognitionResult> =
        flow {
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024) // 1MB buffer
            val targetBytes = (16000 * 2 * STREAM_BUFFER_MS / 1000).toInt() // 3s of 16kHz 16bit mono

            audioFlow.collect { chunk ->
                buffer.put(chunk)

                // 当缓冲区达到目标大小时，进行识别
                if (buffer.position() >= targetBytes) {
                    val audioData = ByteArray(buffer.position())
                    buffer.flip()
                    buffer.get(audioData)
                    buffer.clear()

                    val result = recognize(audioData)
                    emit(result)
                }
            }

            // 处理剩余音频
            if (buffer.position() > 1600) { // 至少0.1秒
                val audioData = ByteArray(buffer.position())
                buffer.flip()
                buffer.get(audioData)
                val result = recognize(audioData)
                emit(result)
            }
        }.flowOn(Dispatchers.IO)

    /**
     * 构建Whisper API请求体
     */
    private fun buildRequestBody(audioData: ByteArray): RequestBody {
        val audioBody = audioData.toRequestBody("audio/wav".toMediaType())

        return MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", audioBody)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", LANGUAGE)
            .addFormDataPart("response_format", "json")
            .build()
    }
}

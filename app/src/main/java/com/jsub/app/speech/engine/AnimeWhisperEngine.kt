package com.jsub.app.speech.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Anime-Whisper 在线语音识别引擎（HuggingFace）
 *
 * 使用 HuggingFace Inference API 直接上传二进制音频数据。
 * 模型：litagin/anime-whisper-medium-v1（动漫/ASMR优化）
 */
class AnimeWhisperEngine(
    private val apiKey: String = ""
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "AnimeWhisperEngine"
        private const val API_URL = "https://api-inference.huggingface.co/models/litagin/anime-whisper-medium-v1"
        private const val SAMPLE_RATE = 16000
        private const val STREAM_BUFFER_MS = 3000L
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 3000L
    }

    private var isInitialized = false

    override val name: String = "Anime-Whisper (HF)"
    override val requiresNetwork: Boolean = true
    override val requiresModelDownload: Boolean = false
    override var isModelReady: Boolean = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun initialize(): Boolean {
        isInitialized = true
        Log.i(TAG, "Initialized (online engine, no model download needed)")
        return true
    }

    override suspend fun recognize(audioData: ByteArray): String {
        if (audioData.isEmpty()) return ""

        return withContext(Dispatchers.IO) {
            // Convert PCM to WAV
            val wavData = pcmToWav(audioData, SAMPLE_RATE)

            var lastError: String? = null
            repeat(MAX_RETRIES) { attempt ->
                try {
                    val requestBuilder = Request.Builder()
                        .url(API_URL)
                        .post(wavData.toRequestBody("audio/wav".toMediaType()))

                    // Add HF Token if provided
                    if (apiKey.isNotBlank()) {
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }

                    val response = client.newCall(requestBuilder.build()).execute()
                    val body = response.body?.string() ?: ""

                    when (response.code) {
                        200 -> {
                            // Parse response - HF returns [{"text": "..."}] or {"text": "..."}
                            val result = parseHFResponse(body)
                            if (result.isNotBlank()) {
                                return@withContext result
                            }
                        }
                        503 -> {
                            // Model warming up
                            lastError = "模型正在冷启动中..."
                            Log.i(TAG, "Model warming up, retry ${attempt + 1}/$MAX_RETRIES")
                            delay(RETRY_DELAY_MS)
                        }
                        401, 403 -> {
                            lastError = "API Token无效"
                            Log.e(TAG, "Auth failed: $body")
                            return@withContext "[API认证失败，请检查HuggingFace Token]"
                        }
                        429 -> {
                            lastError = "请求太频繁"
                            Log.w(TAG, "Rate limited, waiting...")
                            delay(RETRY_DELAY_MS * 2)
                        }
                        else -> {
                            lastError = "HTTP ${response.code}"
                            Log.w(TAG, "HTTP ${response.code}: $body")
                            if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS)
                        }
                    }
                } catch (e: IOException) {
                    lastError = e.message
                    Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS)
                }
            }

            Log.e(TAG, "All retries failed, last error: $lastError")
            "[识别失败: ${lastError ?: "网络错误"}]"
        }
    }

    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        val buffer = ByteArrayOutputStream()
        val targetBytes = (SAMPLE_RATE * 2 * STREAM_BUFFER_MS / 1000).toInt()

        audioFlow.collect { chunk ->
            buffer.write(chunk)
            while (buffer.size() >= targetBytes) {
                val data = buffer.toByteArray().copyOfRange(0, targetBytes)
                // Remove processed data
                val remaining = buffer.toByteArray().copyOfRange(targetBytes, buffer.size())
                buffer.reset()
                buffer.write(remaining)

                val result = recognize(data)
                if (result.isNotBlank() && !result.startsWith("[")) {
                    emit(result)
                }
            }
        }

        // Process remaining audio
        if (buffer.size() > 1600) { // At least 0.05s
            val result = recognize(buffer.toByteArray())
            if (result.isNotBlank() && !result.startsWith("[")) {
                emit(result)
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun release() {
        // Nothing to release for online engine
    }

    /**
     * Parse HuggingFace Inference API response
     * Response can be: [{"text": "..."}] or {"text": "..."} or {"error": "..."}
     */
    private fun parseHFResponse(body: String): String {
        return try {
            when {
                body.startsWith("[") -> {
                    // Array format: [{"text": "..."}]
                    val jsonArray = org.json.JSONArray(body)
                    if (jsonArray.length() > 0) {
                        jsonArray.getJSONObject(0).optString("text", "")
                    } else ""
                }
                body.startsWith("{") -> {
                    // Object format: {"text": "..."}
                    val json = JSONObject(body)
                    if (json.has("text")) {
                        json.getString("text")
                    } else if (json.has("error")) {
                        Log.w(TAG, "HF Error: ${json.getString("error")}")
                        ""
                    } else ""
                }
                else -> body.trim() // Plain text response
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}, raw: $body")
            ""
        }
    }

    /**
     * Convert PCM raw audio to WAV format
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2 // 16bit mono
        val totalDataLen = pcmData.size + 36

        return ByteArrayOutputStream().apply {
            // RIFF header
            write("RIFF".toByteArray())
            writeInt(totalDataLen)
            write("WAVE".toByteArray())
            // fmt chunk
            write("fmt ".toByteArray())
            writeInt(16) // Subchunk1Size
            writeShort(1) // AudioFormat (PCM)
            writeShort(1) // NumChannels (mono)
            writeInt(sampleRate)
            writeInt(byteRate)
            writeShort(2) // BlockAlign
            writeShort(16) // BitsPerSample
            // data chunk
            write("data".toByteArray())
            writeInt(pcmData.size)
            write(pcmData)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
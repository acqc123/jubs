package com.jsub.app.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Kimi (Moonshot AI) 翻译API实现
 *
 * 使用Kimi的LLM Chat API进行日文到中文的翻译。
 * Kimi对中文理解和生成能力优秀，适合日译中场景。
 *
 * API文档: https://platform.moonshot.cn/docs
 *
 * @param apiKey Kimi API Key（从 https://platform.moonshot.cn 获取）
 */
class KimiTranslationApi(
    private val apiKey: String
) : TranslationApi {

    companion object {
        private const val TAG = "KimiTranslationApi"
        private const val BASE_URL = "https://api.moonshot.cn/v1"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY = 1000L

        /** 系统提示词：指导Kimi进行精准翻译 */
        private val SYSTEM_PROMPT = """
            你是一位专业的日语翻译助手。请将用户提供的日语文本翻译成流畅自然的简体中文。
            要求：
            1. 保持原意准确，不要遗漏信息
            2. 翻译要自然流畅，符合中文表达习惯
            3. 对于口语化的内容（如ASMR、动漫对话），保留语气和情感
            4. 专有名词（人名、地名、作品名）保留原文或常见译法
            5. 只输出翻译结果，不要添加解释、注释或额外内容
            6. 如果输入不是日文，直接翻译为中文即可
        """.trimIndent()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    data class ChatRequest(
        @SerialName("model") val model: String = "moonshot-v1-8k",
        @SerialName("messages") val messages: List<Message>,
        @SerialName("temperature") val temperature: Float = 0.3f,
        @SerialName("max_tokens") val maxTokens: Int = 1024,
        @SerialName("stream") val stream: Boolean = false
    )

    @Serializable
    data class Message(
        @SerialName("role") val role: String,
        @SerialName("content") val content: String
    )

    @Serializable
    data class ChatResponse(
        @SerialName("choices") val choices: List<Choice>? = null,
        @SerialName("error") val error: KimiError? = null,
        @SerialName("usage") val usage: Usage? = null
    )

    @Serializable
    data class Choice(
        @SerialName("message") val message: MessageContent? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class MessageContent(
        @SerialName("role") val role: String? = null,
        @SerialName("content") val content: String? = null
    )

    @Serializable
    data class KimiError(
        @SerialName("message") val message: String,
        @SerialName("type") val type: String? = null,
        @SerialName("code") val code: String? = null
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0
    )

    override suspend fun translate(japaneseText: String): String = withContext(Dispatchers.IO) {
        if (japaneseText.isBlank()) return@withContext ""

        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is empty")
            return@withContext "[请在设置中配置Kimi API Key]"
        }

        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY

        repeat(MAX_RETRIES) { attempt ->
            try {
                val requestBody = buildRequestBody(japaneseText)

                val request = Request.Builder()
                    .url("$BASE_URL/chat/completions")
                    .post(requestBody)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    // 处理限流
                    if (response.code == 429) {
                        Log.w(TAG, "Rate limited, retrying in ${retryDelay}ms")
                        delay(retryDelay)
                        retryDelay *= 2
                        return@repeat
                    }

                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: $body")
                    }

                    body.let { responseBody ->
                        val result = json.decodeFromString(ChatResponse.serializer(), responseBody)

                        result.error?.let { error ->
                            Log.e(TAG, "API error: ${error.message}")
                            return@withContext "[API错误: ${error.message}]"
                        }

                        val translatedText = result.choices?.firstOrNull()
                            ?.message?.content?.trim()

                        if (translatedText.isNullOrBlank()) {
                            Log.w(TAG, "Empty translation result")
                            return@withContext "[翻译结果为空]"
                        }

                        // 记录token使用量
                        result.usage?.let { usage ->
                            Log.d(TAG, "Translation tokens - prompt: ${usage.promptTokens}, completion: ${usage.completionTokens}, total: ${usage.totalTokens}")
                        }

                        return@withContext translatedText
                    } ?: throw IOException("Empty response body")
                }
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Translation attempt ${attempt + 1} failed", e)
                if (attempt < MAX_RETRIES - 1) {
                    delay(retryDelay)
                    retryDelay *= 2
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during translation", e)
                return@withContext "[翻译错误: ${e.localizedMessage}]"
            }
        }

        Log.e(TAG, "All translation retries failed", lastException)
        return@withContext "[翻译失败，请检查网络或API Key]"
    }

    /**
     * 构建 Kimi Chat API 请求体
     */
    private fun buildRequestBody(japaneseText: String): RequestBody {
        // 根据文本长度选择模型
        val model = when {
            japaneseText.length > 3000 -> "moonshot-v1-128k"
            japaneseText.length > 1000 -> "moonshot-v1-32k"
            else -> "moonshot-v1-8k"
        }

        val messages = listOf(
            Message(role = "system", content = SYSTEM_PROMPT),
            Message(role = "user", content = japaneseText)
        )

        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = 0.3f,
            maxTokens = (japaneseText.length * 2 + 100).coerceAtMost(2048)
        )

        val jsonBody = json.encodeToString(ChatRequest.serializer(), request)

        return jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
    }
}

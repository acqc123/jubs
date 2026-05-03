package com.jsub.app.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google翻译API实现
 *
 * 支持Google Cloud Translation API和免费fallback方案。
 */
class GoogleTranslateApi(
    private val apiKey: String = ""
) : TranslationApi {

    companion object {
        private const val TAG = "GoogleTranslateApi"
        private const val BASE_URL = "https://translation.googleapis.com/language/translate/v2"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY = 1000L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    data class TranslateRequest(
        @SerialName("q") val text: String,
        @SerialName("source") val source: String = "ja",
        @SerialName("target") val target: String = "zh-CN",
        @SerialName("format") val format: String = "text"
    )

    @Serializable
    data class TranslateResponse(
        @SerialName("data") val data: TranslationData? = null,
        @SerialName("error") val error: ApiError? = null
    )

    @Serializable
    data class TranslationData(
        @SerialName("translations") val translations: List<Translation>
    )

    @Serializable
    data class Translation(
        @SerialName("translatedText") val translatedText: String,
        @SerialName("detectedSourceLanguage") val detectedSourceLanguage: String? = null
    )

    @Serializable
    data class ApiError(
        @SerialName("message") val message: String,
        @SerialName("code") val code: Int
    )

    override suspend fun translate(japaneseText: String): String = withContext(Dispatchers.IO) {
        if (japaneseText.isBlank()) return@withContext ""

        // 如果没有API Key，使用LibreTranslate作为fallback
        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key provided, falling back to LibreTranslate")
            return@withContext LibreTranslateApi().translate(japaneseText)
        }

        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY

        repeat(MAX_RETRIES) { attempt ->
            try {
                val urlBuilder = BASE_URL.toHttpUrlOrNull()?.newBuilder()
                    ?: throw IllegalStateException("Invalid base URL")
                urlBuilder.addQueryParameter("key", apiKey)

                val requestBody = json.encodeToString(
                    TranslateRequest.serializer(),
                    TranslateRequest(text = japaneseText)
                ).toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body.string()

                    if (!response.isSuccessful) {
                        if (response.code == 429) {
                            Log.w(TAG, "Rate limited, retrying in ${retryDelay}ms")
                            delay(retryDelay)
                            retryDelay *= 2
                            return@repeat
                        }
                        throw IOException("HTTP ${response.code}: $body")
                    }

                    body?.let {
                        val result = json.decodeFromString(TranslateResponse.serializer(), it)
                        result.error?.let { error ->
                            throw IOException("API Error ${error.code}: ${error.message}")
                        }
                        return@withContext result.data?.translations?.firstOrNull()?.translatedText
                            ?: japaneseText
                    } ?: throw IOException("Empty response body")
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(retryDelay)
                    retryDelay *= 2
                }
            }
        }

        // 所有重试失败，尝试LibreTranslate
        Log.e(TAG, "All retries failed, trying LibreTranslate fallback", lastException)
        return@withContext try {
            LibreTranslateApi().translate(japaneseText)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback also failed", e)
            "[翻译失败] $japaneseText"
        }
    }
}

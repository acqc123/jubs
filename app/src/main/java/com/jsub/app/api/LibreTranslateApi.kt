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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LibreTranslate免费翻译API实现
 *
 * 使用LibreTranslate的免费公开端点，支持ja→zh翻译。
 * 注意：免费端点可能有速率限制。
 */
class LibreTranslateApi : TranslationApi {

    companion object {
        private const val TAG = "LibreTranslateApi"
        private const val API_URL = "https://libretranslate.de/translate"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY = 2000L
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
    data class LibreRequest(
        @SerialName("q") val text: String,
        @SerialName("source") val source: String = "ja",
        @SerialName("target") val target: String = "zh",
        @SerialName("format") val format: String = "text"
    )

    @Serializable
    data class LibreResponse(
        @SerialName("translatedText") val translatedText: String? = null,
        @SerialName("error") val error: String? = null
    )

    override suspend fun translate(japaneseText: String): String = withContext(Dispatchers.IO) {
        if (japaneseText.isBlank()) return@withContext ""

        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY

        repeat(MAX_RETRIES) { attempt ->
            try {
                val requestBody = json.encodeToString(
                    LibreRequest.serializer(),
                    LibreRequest(text = japaneseText)
                ).toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(API_URL)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    if (response.code == 429) {
                        Log.w(TAG, "Rate limited (429), retrying in ${retryDelay}ms (attempt ${attempt + 1})")
                        delay(retryDelay)
                        retryDelay *= 2
                        return@repeat
                    }

                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: $body")
                    }

                    body.let {
                        val result = json.decodeFromString(LibreResponse.serializer(), it)
                        result.error?.let { error ->
                            throw IOException("API Error: $error")
                        }
                        return@withContext result.translatedText ?: japaneseText
                    }
                }
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Translation attempt ${attempt + 1} failed", e)
                if (attempt < MAX_RETRIES - 1) {
                    delay(retryDelay)
                    retryDelay *= 2
                }
            }
        }

        Log.e(TAG, "All translation retries failed", lastException)
        return@withContext "[翻译失败] $japaneseText"
    }
}

package com.jsub.app.speech.engine

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 百度语音识别引擎（短语音识别标准版）
 *
 * 基于百度 AI 开放平台 REST API，支持日语识别。
 * 免费额度 50000 次/日。
 *
 * ### 申请流程
 * 1. 注册 https://ai.baidu.com/
 * 2. 创建应用，获取 API Key / Secret Key
 * 3. 开通"语音识别"服务
 * 4. 在应用内开通日语识别权限
 *
 * @param apiKey 百度 API Key
 * @param secretKey 百度 Secret Key
 */
class BaiduEngine(
    private val apiKey: String = "",
    private val secretKey: String = ""
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "BaiduEngine"

        /** 百度语音识别 API */
        private const val ASR_URL = "https://vop.baidu.com/server_api"
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"

        /** 音频参数 */
        private const val SAMPLE_RATE = 16000
        private const val STREAM_BUFFER_MS = 3000L

        /** 请求超时 */
        private const val REQUEST_TIMEOUT_SECONDS = 30L

        /** 最大重试次数 */
        private const val MAX_RETRIES = 3
    }

    override val name: String = "百度语音识别"
    override val requiresNetwork: Boolean = true
    override val requiresModelDownload: Boolean = false
    override var isModelReady: Boolean = false

    private var accessToken: String = ""
    private var tokenExpiry: Long = 0

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 初始化百度引擎
     * 获取 access_token
     */
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            Log.w(TAG, "百度 API 未配置")
            isModelReady = false
            return@withContext false
        }

        try {
            refreshToken()
            isModelReady = true
            Log.i(TAG, "百度引擎初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "百度引擎初始化失败", e)
            isModelReady = false
            false
        }
    }

    /**
     * 单段音频识别
     */
    override suspend fun recognize(audioData: ByteArray): String {
        if (!isModelReady) return "[百度未配置：请在设置中填写 API Key/Secret Key]"

        return withContext(Dispatchers.IO) {
            var retryCount = 0
            var lastError: Exception? = null

            while (retryCount < MAX_RETRIES) {
                try {
                    // 确保 token 有效
                    if (System.currentTimeMillis() > tokenExpiry) {
                        refreshToken()
                    }

                    return@withContext doRecognize(audioData)
                } catch (e: IOException) {
                    lastError = e
                    retryCount++
                    Log.w(TAG, "识别失败，重试 $retryCount/$MAX_RETRIES")
                    if (retryCount < MAX_RETRIES) delay(1000L * retryCount)
                } catch (e: Exception) {
                    Log.e(TAG, "识别异常", e)
                    return@withContext "[识别错误: ${e.localizedMessage}]"
                }
            }

            "[识别失败: ${lastError?.localizedMessage ?: "网络错误"}]"
        }
    }

    /**
     * 流式音频识别
     */
    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        if (!isModelReady) {
            emit("[百度未配置：请在设置中填写 API Key/Secret Key]")
            return@flow
        }

        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        val targetBytes = (SAMPLE_RATE * 2 * STREAM_BUFFER_MS / 1000).toInt()

        audioFlow.collect { chunk ->
            buffer.put(chunk)
            if (buffer.position() >= targetBytes) {
                val data = extractBufferData(buffer)
                val result = try {
                    recognize(data)
                } catch (e: Exception) {
                    Log.e(TAG, "流式识别失败", e)
                    ""
                }
                if (result.isNotBlank() && !result.startsWith("[")) {
                    emit(result)
                }
            }
        }

        // 处理剩余
        if (buffer.position() > 3200) {
            val result = try {
                recognize(extractBufferData(buffer))
            } catch (_: Exception) { "" }
            if (result.isNotBlank() && !result.startsWith("[")) {
                emit(result)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 释放引擎资源
     */
    override fun release() {
        accessToken = ""
        tokenExpiry = 0
        isModelReady = false
    }

    // ==================== 内部方法 ====================

    /**
     * 执行识别请求
     */
    private fun doRecognize(audioData: ByteArray): String {
        // PCM -> WAV（百度需要 WAV 格式）
        val wavData = pcmToWav(audioData, SAMPLE_RATE)
        val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)

        val requestBody = json.encodeToString(
            BaiduAsrRequest.serializer(),
            BaiduAsrRequest(
                token = accessToken,
                cuid = "jsub_android_app",
                format = "wav",
                rate = SAMPLE_RATE,
                channel = 1,
                speech = base64Audio,
                len = wavData.size,
                devPid = 318 // 318 = 日语（短语音识别）
            )
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(ASR_URL)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return "[请求失败: HTTP ${response.code}]"
            }

            val result = json.decodeFromString(BaiduAsrResponse.serializer(), body)
            when {
                result.errNo == 0 -> {
                    result.result?.firstOrNull()?.trim() ?: ""
                }
                result.errNo == 3301 -> "[识别错误: 音频质量不佳或无声]"
                result.errNo == 3302 -> "[识别错误: token 无效，请检查 API Key]"
                result.errNo == 3308 -> "[识别错误: 请求过于频繁]"
                result.errNo == 3314 -> "[识别错误: 音频太长]"
                else -> "[识别错误(${result.errNo}): ${result.errMsg ?: "未知"}]"
            }
        }
    }

    /**
     * 刷新 access_token
     */
    private fun refreshToken() {
        val url = "$TOKEN_URL?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Token 请求失败: ${response.code}")
            }

            val body = response.body?.string() ?: ""
            val tokenResponse = json.decodeFromString(BaiduTokenResponse.serializer(), body)

            if (tokenResponse.accessToken.isBlank()) {
                throw IOException("获取 token 失败: ${tokenResponse.errorDescription ?: "未知错误"}")
            }

            accessToken = tokenResponse.accessToken
            // 提前 1 小时过期
            tokenExpiry = System.currentTimeMillis() + (tokenResponse.expiresIn - 3600) * 1000
            Log.i(TAG, "Token 刷新成功，有效期 ${tokenResponse.expiresIn}s")
        }
    }

    /**
     * PCM 转 WAV
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val pcmLen = pcmData.size
        val totalLen = pcmLen + 36
        val byteRate = sampleRate * 2 // 16bit mono

        val header = ByteArray(44)
        // RIFF
        "RIFF".toByteArray().copyInto(header, 0)
        writeIntLE(header, 4, totalLen)
        "WAVE".toByteArray().copyInto(header, 8)
        // fmt
        "fmt ".toByteArray().copyInto(header, 12)
        writeIntLE(header, 16, 16) // fmt chunk size
        writeShortLE(header, 20, 1) // PCM
        writeShortLE(header, 22, 1) // mono
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, 2) // block align
        writeShortLE(header, 34, 16) // bits per sample
        // data
        "data".toByteArray().copyInto(header, 36)
        writeIntLE(header, 40, pcmLen)

        return header + pcmData
    }

    private fun writeIntLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun extractBufferData(buffer: java.nio.ByteBuffer): ByteArray {
        val data = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(data)
        buffer.clear()
        return data
    }

    // ==================== 数据类 ====================

    @Serializable
    private data class BaiduAsrRequest(
        @SerialName("token") val token: String,
        @SerialName("cuid") val cuid: String,
        @SerialName("format") val format: String,
        @SerialName("rate") val rate: Int,
        @SerialName("channel") val channel: Int,
        @SerialName("speech") val speech: String,
        @SerialName("len") val len: Int,
        @SerialName("dev_pid") val devPid: Int
    )

    @Serializable
    private data class BaiduAsrResponse(
        @SerialName("err_no") val errNo: Int = -1,
        @SerialName("err_msg") val errMsg: String? = null,
        @SerialName("result") val result: List<String>? = null
    )

    @Serializable
    private data class BaiduTokenResponse(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("expires_in") val expiresIn: Long = 0,
        @SerialName("error") val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null
    )
}

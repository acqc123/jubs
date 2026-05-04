package com.jsub.app.speech.engine

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 百度语音识别引擎（短语音识别标准版）
 * 申请地址：https://ai.baidu.com/tech/speech
 */
class BaiduEngine(
    private val apiKey: String = "",
    private val secretKey: String = ""
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "BaiduEngine"
        private const val ASR_URL = "https://vop.baidu.com/server_api"
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val SAMPLE_RATE = 16000
        private const val STREAM_BUFFER_MS = 3000L
    }

    override val name: String = "百度语音识别"
    override val requiresNetwork: Boolean = true
    override val requiresModelDownload: Boolean = false
    override var isModelReady: Boolean = false

    private var accessToken: String = ""
    private var tokenExpiry: Long = 0

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            isModelReady = false
            return@withContext false
        }
        try {
            refreshToken()
            isModelReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败", e)
            isModelReady = false
            false
        }
    }

    override suspend fun recognize(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        if (!isModelReady) return@withContext "[百度未配置]"
        if (System.currentTimeMillis() > tokenExpiry) refreshToken()

        val wavData = pcmToWav(audioData, SAMPLE_RATE)
        val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)

        val jsonBody = JSONObject().apply {
            put("token", accessToken)
            put("cuid", "jsub_android")
            put("format", "wav")
            put("rate", SAMPLE_RATE)
            put("channel", 1)
            put("speech", base64Audio)
            put("len", wavData.size)
            put("dev_pid", 318) // 日语
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder().url(ASR_URL).post(jsonBody).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val result = JSONObject(body)
            when (result.optInt("err_no", -1)) {
                0 -> result.optJSONArray("result")?.optString(0, "") ?: ""
                3301 -> "[识别错误: 音频质量不佳]"
                3302 -> "[Token 无效]"
                else -> "[错误${result.optInt("err_no")}: ${result.optString("err_msg")}]"
            }
        }
    }

    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        val targetBytes = (SAMPLE_RATE * 2 * STREAM_BUFFER_MS / 1000).toInt()

        audioFlow.collect { chunk ->
            buffer.put(chunk)
            if (buffer.position() >= targetBytes) {
                val result = recognize(extractBufferData(buffer))
                if (result.isNotBlank() && !result.startsWith("[")) emit(result)
            }
        }
        if (buffer.position() > 3200) {
            val result = recognize(extractBufferData(buffer))
            if (result.isNotBlank() && !result.startsWith("[")) emit(result)
        }
    }.flowOn(Dispatchers.IO)

    override fun release() {
        accessToken = ""
        tokenExpiry = 0
        isModelReady = false
    }

    private fun refreshToken() {
        val url = "$TOKEN_URL?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            accessToken = json.getString("access_token")
            tokenExpiry = System.currentTimeMillis() + (json.getLong("expires_in") - 3600) * 1000
        }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val pcmLen = pcmData.size
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        writeIntLE(header, 4, pcmLen + 36)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, 1)
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, 2)
        writeShortLE(header, 34, 16)
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
}

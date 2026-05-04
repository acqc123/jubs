package com.jsub.app.speech.engine

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞语音识别引擎（流式听写 WebSocket API）
 * 申请地址：https://www.xfyun.cn/
 */
class XunfeiEngine(
    private val appId: String = "",
    private val apiKey: String = "",
    private val apiSecret: String = ""
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "XunfeiEngine"
        private const val HOST = "iat-api.xfyun.cn"
        private const val PATH = "/v2/iat"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280
    }

    override val name: String = "讯飞流式听写"
    override val requiresNetwork: Boolean = true
    override val requiresModelDownload: Boolean = false
    override var isModelReady: Boolean = false

    private var webSocket: WebSocket? = null
    private val resultBuffer = StringBuilder()
    private var recognitionComplete = false
    private var isInitialized = false

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            isModelReady = false
            isInitialized = false
            return@withContext false
        }
        isModelReady = true
        isInitialized = true
        true
    }

    override suspend fun recognize(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext "[讯飞未配置]"

        resultBuffer.clear()
        recognitionComplete = false

        val wsUrl = buildAuthUrl()
        connectAndRecognize(wsUrl, audioData)

        var waited = 0
        while (!recognitionComplete && waited < 300) {
            delay(100)
            waited++
        }
        closeWebSocket()
        resultBuffer.toString().trim()
    }

    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        if (!isInitialized) {
            emit("[讯飞未配置]")
            return@flow
        }
        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        val targetBytes = SAMPLE_RATE * 2 * 3

        audioFlow.collect { chunk ->
            buffer.put(chunk)
            if (buffer.position() >= targetBytes) {
                val result = recognize(extractBuffer(buffer))
                if (result.isNotBlank() && !result.startsWith("[")) emit(result)
            }
        }
        if (buffer.position() > 3200) {
            val result = recognize(extractBuffer(buffer))
            if (result.isNotBlank() && !result.startsWith("[")) emit(result)
        }
    }.flowOn(Dispatchers.IO)

    override fun release() {
        closeWebSocket()
        isInitialized = false
        isModelReady = false
    }

    private fun connectAndRecognize(wsUrl: String, audioData: ByteArray) {
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                sendAudioFrames(ws, audioData)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleResponse(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                recognitionComplete = true
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                resultBuffer.append("[连接失败]")
                recognitionComplete = true
            }
        })
    }

    private fun sendAudioFrames(ws: WebSocket, audioData: ByteArray) {
        val business = JSONObject().apply {
            put("language", "ja")
            put("domain", "iat")
            put("accent", "mandarin")
            put("dwa", "wpgs")
        }
        val common = JSONObject().apply { put("app_id", appId) }

        var offset = 0
        val timer = Timer()
        timer.scheduleAtFixedRate(0, 40) {
            if (offset >= audioData.size || recognitionComplete) {
                timer.cancel()
                ws.send(JSONObject().apply {
                    put("data", JSONObject().apply {
                        put("status", 2)
                        put("format", "audio/L16;rate=16000")
                        put("audio", "")
                        put("encoding", "raw")
                    })
                }.toString())
                return@scheduleAtFixedRate
            }

            val end = minOf(offset + FRAME_SIZE, audioData.size)
            val chunk = audioData.copyOfRange(offset, end)
            val isEnd = if (end >= audioData.size) 2 else 1

            val base64Audio = Base64.encodeToString(chunk, Base64.NO_WRAP)

            val frame = if (offset == 0) {
                JSONObject().apply {
                    put("common", common)
                    put("business", business)
                    put("data", JSONObject().apply {
                        put("status", 0)
                        put("format", "audio/L16;rate=16000")
                        put("encoding", "raw")
                        put("audio", base64Audio)
                    })
                }
            } else {
                JSONObject().apply {
                    put("data", JSONObject().apply {
                        put("status", isEnd)
                        put("format", "audio/L16;rate=16000")
                        put("audio", base64Audio)
                        put("encoding", "raw")
                    })
                }
            }

            ws.send(frame.toString())
            offset += FRAME_SIZE
        }
    }

    private fun handleResponse(text: String) {
        try {
            val json = JSONObject(text)
            val code = json.optInt("code", -1)
            if (code != 0) {
                resultBuffer.append("[错误: ${json.optString("message")}]")
                recognitionComplete = true
                return
            }

            val data = json.optJSONObject("data") ?: return
            val result = data.optJSONObject("result") ?: return
            val ws = result.optJSONArray("ws") ?: return

            val sb = StringBuilder()
            for (i in 0 until ws.length()) {
                val item = ws.getJSONObject(i)
                val cw = item.optJSONArray("cw") ?: continue
                for (j in 0 until cw.length()) {
                    sb.append(cw.getJSONObject(j).optString("w", ""))
                }
            }

            val partial = sb.toString()
            if (partial.isNotBlank()) {
                resultBuffer.clear()
                resultBuffer.append(partial)
            }

            if (data.optInt("status", 0) == 2) {
                recognitionComplete = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析错误", e)
        }
    }

    private fun closeWebSocket() {
        try { webSocket?.close(1000, "done") } catch (_: Exception) {}
        webSocket = null
    }

    private fun buildAuthUrl(): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())
        val sigOrigin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val signature = hmacSha256(sigOrigin, apiSecret)
        val authOrigin = "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val auth = Base64.encodeToString(authOrigin.toByteArray(), Base64.NO_WRAP)
        return "wss://$HOST$PATH?authorization=${URLEncoder.encode(auth, "UTF-8")}&date=${URLEncoder.encode(date, "UTF-8")}&host=${URLEncoder.encode(HOST, "UTF-8")}"
    }

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun extractBuffer(buffer: java.nio.ByteBuffer): ByteArray {
        val data = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(data)
        buffer.clear()
        return data
    }
}

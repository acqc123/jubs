package com.jsub.app.speech.engine

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.schedule

/**
 * 讯飞语音识别引擎（流式听写 WebSocket API）
 *
 * 基于讯飞开放平台实时语音转写 API，支持日语识别。
 * 使用 WebSocket 连接进行实时流式识别。
 *
 * ### 申请流程
 * 1. 注册 https://www.xfyun.cn/
 * 2. 创建应用，获取 AppID / APIKey / APISecret
 * 3. 开通"语音听写（流式版）"服务
 * 4. 如需要日语，邮件申请小语种权限
 *
 * @param appId 讯飞 AppID
 * @param apiKey 讯飞 APIKey
 * @param apiSecret 讯飞 APISecret
 */
class XunfeiEngine(
    private val appId: String = "",
    private val apiKey: String = "",
    private val apiSecret: String = ""
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "XunfeiEngine"

        /** 讯飞听写 WebSocket 地址 */
        private const val HOST = "iat-api.xfyun.cn"
        private const val PATH = "/v2/iat"

        /** 音频参数 */
        private const val SAMPLE_RATE = 16000
        private const val STREAM_BUFFER_MS = 3000L

        /** 每帧音频大小：1280 bytes = 40ms @ 16kHz 16bit mono */
        private const val FRAME_SIZE = 1280

        /** 连接超时 */
        private const val CONNECT_TIMEOUT_SECONDS = 10L

        /** 帧发送间隔（ms），模拟实时流 */
        private const val FRAME_INTERVAL_MS = 40L

        /** 最大重试次数 */
        private const val MAX_RETRIES = 3
    }

    override val name: String = "讯飞流式听写"
    override val requiresNetwork: Boolean = true
    override val requiresModelDownload: Boolean = false
    override var isModelReady: Boolean = false

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private val resultChannel = Channel<String>(Channel.BUFFERED)

    /** 完整识别结果累积 */
    private val resultBuffer = StringBuilder()

    /** 引擎是否已初始化 */
    private var isInitialized = false

    /** 识别是否完成 */
    private var recognitionComplete = false

    /**
     * 初始化讯飞引擎
     * 验证 AppID/APIKey/APISecret 是否已配置
     */
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            Log.w(TAG, "讯飞 API 未配置")
            isModelReady = false
            isInitialized = false
            return@withContext false
        }
        isModelReady = true
        isInitialized = true
        Log.i(TAG, "讯飞引擎初始化成功")
        true
    }

    /**
     * 单段音频识别
     * 将音频分段通过 WebSocket 发送给讯飞服务器
     */
    override suspend fun recognize(audioData: ByteArray): String {
        if (!isInitialized) return "[讯飞未配置：请在设置中填写 AppID/APIKey/APISecret]"

        return withContext(Dispatchers.IO) {
            var retryCount = 0
            var lastError: Exception? = null

            while (retryCount < MAX_RETRIES) {
                try {
                    return@withContext recognizeInternal(audioData)
                } catch (e: Exception) {
                    lastError = e
                    retryCount++
                    Log.w(TAG, "识别失败，重试 $retryCount/$MAX_RETRIES: ${e.message}")
                    if (retryCount < MAX_RETRIES) delay(1000L * retryCount)
                }
            }

            Log.e(TAG, "识别最终失败", lastError)
            "[讯飞识别失败: ${lastError?.localizedMessage ?: "网络错误"}]"
        }
    }

    /**
     * 流式音频识别
     * 使用 WebSocket 实时流式识别
     */
    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        if (!isInitialized) {
            emit("[讯飞未配置：请在设置中填写 AppID/APIKey/APISecret]")
            return@flow
        }

        val buffer = ByteArrayOutputStream()

        // 收集音频数据到缓冲区
        audioFlow.collect { chunk ->
            buffer.write(chunk)

            // 每 3 秒触发一次识别
            if (buffer.size() >= SAMPLE_RATE * 2 * STREAM_BUFFER_MS / 1000) {
                val audioData = buffer.toByteArray()
                buffer.reset()

                val result = try {
                    recognizeInternal(audioData)
                } catch (e: Exception) {
                    Log.e(TAG, "流式识别失败", e)
                    ""
                }

                if (result.isNotBlank() && !result.startsWith("[")) {
                    emit(result)
                }
            }
        }

        // 处理剩余音频
        if (buffer.size() > 3200) {
            val result = try {
                recognizeInternal(buffer.toByteArray())
            } catch (e: Exception) {
                ""
            }
            if (result.isNotBlank() && !result.startsWith("[")) {
                emit(result)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 释放引擎资源
     */
    override fun release() {
        closeWebSocket()
        client?.dispatcher?.executorService?.shutdown()
        client = null
        isInitialized = false
        isModelReady = false
    }

    // ==================== 内部方法 ====================

    /**
     * 内部识别方法
     */
    private suspend fun recognizeInternal(audioData: ByteArray): String {
        resultBuffer.clear()
        recognitionComplete = false

        val wsUrl = buildAuthUrl()
        connectAndRecognize(wsUrl, audioData)

        // 等待识别完成，最多 30 秒
        var waited = 0
        while (!recognitionComplete && waited < 300) {
            delay(100)
            waited++
        }

        closeWebSocket()

        return resultBuffer.toString().trim()
    }

    /**
     * 连接 WebSocket 并发送音频数据
     */
    private fun connectAndRecognize(wsUrl: String, audioData: ByteArray) {
        client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()

        webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 已连接")
                // 发送第一帧（含业务参数）
                sendFirstFrame(ws, audioData)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleResponse(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                Log.d(TAG, "收到二进制消息")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 关闭: $code $reason")
                recognitionComplete = true
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 错误", t)
                resultBuffer.append("[连接失败: ${t.localizedMessage}]")
                recognitionComplete = true
            }
        })
    }

    /**
     * 发送第一帧（含业务参数）
     */
    private fun sendFirstFrame(ws: WebSocket, audioData: ByteArray) {
        val business = JSONObject().apply {
            put("language", "ja")        // 日语
            put("domain", "iat")         // 听写
            put("accent", "mandarin")    // 口音（日语也用此参数）
            put("dwa", "wpgs")           // 动态修正（开）
            put("pd", "game")            // 游戏领域优化
        }

        val common = JSONObject().apply {
            put("app_id", appId)
        }

        val data = JSONObject().apply {
            put("status", 0)  // 第一帧
            put("format", "audio/L16;rate=16000")
            put("encoding", "raw")
            put("audio", Base64.encodeToString(audioData.copyOf(FRAME_SIZE.coerceAtMost(audioData.size)), Base64.NO_WRAP))
        }

        val frame = JSONObject().apply {
            put("common", common)
            put("business", business)
            put("data", data)
        }

        ws.send(frame.toString())

        // 发送中间帧
        var offset = FRAME_SIZE
        val timer = Timer()
        timer.scheduleAtFixedRate(0, FRAME_INTERVAL_MS) {
            if (offset >= audioData.size || recognitionComplete) {
                timer.cancel()
                // 发送最后一帧
                sendLastFrame(ws)
                return@scheduleAtFixedRate
            }

            val end = (offset + FRAME_SIZE).coerceAtMost(audioData.size)
            val chunk = audioData.copyOfRange(offset, end)
            val isEnd = if (end >= audioData.size) 2 else 1

            val midFrame = JSONObject().apply {
                put("data", JSONObject().apply {
                    put("status", isEnd)
                    put("format", "audio/L16;rate=16000")
                    put("audio", Base64.encodeToString(chunk, Base64.NO_WRAP))
                    put("encoding", "raw")
                })
            }

            ws.send(midFrame.toString())
            offset += FRAME_SIZE
        }
    }

    /**
     * 发送最后一帧
     */
    private fun sendLastFrame(ws: WebSocket) {
        val lastFrame = JSONObject().apply {
            put("data", JSONObject().apply {
                put("status", 2)
                put("format", "audio/L16;rate=16000")
                put("audio", "")
                put("encoding", "raw")
            })
        }
        ws.send(lastFrame.toString())
    }

    /**
     * 处理服务器响应
     */
    private fun handleResponse(text: String) {
        try {
            val json = JSONObject(text)
            val code = json.optInt("code", -1)

            if (code != 0) {
                val message = json.optString("message", "未知错误")
                Log.e(TAG, "讯飞错误: code=$code, message=$message")
                resultBuffer.append("[讯飞错误: $message]")
                recognitionComplete = true
                return
            }

            val data = json.optJSONObject("data") ?: return
            val result = data.optJSONObject("result") ?: return
            val ws = result.optJSONArray("ws") ?: return

            // 解析识别结果
            val sb = StringBuilder()
            for (i in 0 until ws.length()) {
                val item = ws.getJSONObject(i)
                val cw = item.optJSONArray("cw") ?: continue
                for (j in 0 until cw.length()) {
                    val word = cw.getJSONObject(j)
                    sb.append(word.optString("w", ""))
                }
            }

            val partialResult = sb.toString()
            if (partialResult.isNotBlank()) {
                // 替换掉上次的结果（wpgs 动态修正模式）
                resultBuffer.clear()
                resultBuffer.append(partialResult)
                Log.d(TAG, "识别结果: $partialResult")
            }

            // 检查是否是最终结果
            val status = data.optInt("status", 0)
            if (status == 2) {
                recognitionComplete = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败: $text", e)
        }
    }

    /**
     * 关闭 WebSocket 连接
     */
    private fun closeWebSocket() {
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (_: Exception) {}
        webSocket = null
    }

    /**
     * 构建带鉴权参数的 WebSocket URL
     * 使用 HMAC-SHA256 签名
     */
    private fun buildAuthUrl(): String {
        val date = getRfc1123Date()
        val signatureOrigin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"

        val signature = hmacSha256(signatureOrigin, apiSecret)
        val authorizationOrigin = "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(authorizationOrigin.toByteArray(), Base64.NO_WRAP)

        return "wss://$HOST$PATH?" +
                "authorization=${URLEncoder.encode(authorization, "UTF-8")}" +
                "&date=${URLEncoder.encode(date, "UTF-8")}" +
                "&host=${URLEncoder.encode(HOST, "UTF-8")}"
    }

    /**
     * RFC1123 日期格式
     */
    private fun getRfc1123Date(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date())
    }

    /**
     * HMAC-SHA256 签名
     */
    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}

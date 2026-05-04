package com.jsub.app.speech.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 讯飞语音识别引擎（流式听写）
 *
 * 预留接口，需要用户配置 AppID + APIKey + APISecret 后激活。
 * 日语小语种需邮件申请开通。
 *
 * 申请地址：https://www.xfyun.cn/
 * 开通服务：语音听写（流式版）→ 小语种（日语）
 */
class XunfeiEngine(
    private val appId: String = "",
    private val apiKey: String = "",
    private val apiSecret: String = ""
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "XunfeiEngine"
    }

    override val name: String = "讯飞流式听写"
    override val requiresNetwork: Boolean = true
    override val requiresModelDownload: Boolean = false
    override var isModelReady: Boolean = false

    override suspend fun initialize(): Boolean {
        if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            Log.w(TAG, "讯飞 API 未配置，请在设置中填写 AppID/APIKey/APISecret")
            isModelReady = false
            return false
        }
        Log.i(TAG, "讯飞引擎初始化（预留接口，待实现 WebSocket 连接）")
        isModelReady = true
        return true
    }

    override suspend fun recognize(audioData: ByteArray): String {
        if (!isModelReady) return "[讯飞未配置：请申请 AppID/APIKey/APISecret]"
        return "[讯飞识别预留接口：请先完成 WebSocket 实现]"
    }

    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        emit("[讯飞流式识别预留接口]")
    }.flowOn(Dispatchers.IO)

    override fun release() {
        // WebSocket 连接释放
    }
}
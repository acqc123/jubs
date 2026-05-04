package com.jsub.app.speech.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 讯飞语音识别引擎（流式听写）
 * 申请地址：https://www.xfyun.cn/
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
            Log.w(TAG, "讯飞 API 未配置")
            isModelReady = false
            return false
        }
        isModelReady = true
        Log.i(TAG, "讯飞引擎初始化成功")
        return true
    }

    override suspend fun recognize(audioData: ByteArray): String {
        if (!isModelReady) return "[讯飞未配置：请在设置中填写 AppID|APIKey|APISecret]"
        return "[讯飞识别结果]"
    }

    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        emit("[讯飞流式识别预留接口]")
    }.flowOn(Dispatchers.IO)

    override fun release() {
        isModelReady = false
    }
}

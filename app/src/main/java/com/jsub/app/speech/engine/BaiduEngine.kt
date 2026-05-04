package com.jsub.app.speech.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 百度语音识别引擎（短语音识别）
 * 申请地址：https://ai.baidu.com/tech/speech
 */
class BaiduEngine(
    private val apiKey: String = "",
    private val secretKey: String = ""
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "BaiduEngine"
    }

    override val name: String = "百度语音识别"
    override val requiresNetwork: Boolean = true
    override val requiresModelDownload: Boolean = false
    override var isModelReady: Boolean = false

    override suspend fun initialize(): Boolean {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            Log.w(TAG, "百度 API 未配置")
            isModelReady = false
            return false
        }
        isModelReady = true
        Log.i(TAG, "百度引擎初始化成功")
        return true
    }

    override suspend fun recognize(audioData: ByteArray): String {
        if (!isModelReady) return "[百度未配置：请在设置中填写 APIKey|SecretKey]"
        return "[百度识别结果]"
    }

    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        emit("[百度流式识别预留接口]")
    }.flowOn(Dispatchers.IO)

    override fun release() {
        isModelReady = false
    }
}

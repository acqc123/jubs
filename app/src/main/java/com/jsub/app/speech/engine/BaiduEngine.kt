package com.jsub.app.speech.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 百度语音识别引擎（短语音识别）
 *
 * 预留接口，需要用户配置 API Key + Secret Key 后激活。
 * 免费额度 5万次/日，日语精度一般。
 *
 * 申请地址：https://ai.baidu.com/tech/speech
 * 开通服务：短语音识别 → 日语
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
            Log.w(TAG, "百度 API 未配置，请在设置中填写 API Key/Secret Key")
            isModelReady = false
            return false
        }
        Log.i(TAG, "百度引擎初始化（预留接口，待实现 REST API 调用）")
        isModelReady = true
        return true
    }

    override suspend fun recognize(audioData: ByteArray): String {
        if (!isModelReady) return "[百度未配置：请申请 API Key/Secret Key]"
        return "[百度识别预留接口：请先完成 REST API 实现]"
    }

    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        emit("[百度流式识别预留接口]")
    }.flowOn(Dispatchers.IO)

    override fun release() {
        // HTTP 连接释放
    }
}
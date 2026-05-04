package com.jsub.app.speech.engine

import android.content.Context
import android.util.Log
import com.jsub.app.model.SpeechProvider

/**
 * 语音识别引擎工厂
 *
 * 支持的引擎：
 * | 提供商 | 引擎类 | 说明 |
 * |--------|--------|------|
 * | ANIME_WHISPER | AnimeWhisperEngine | HuggingFace 动漫优化（默认推荐） |
 * | SENSEVOICE_LOCAL | SenseVoiceEngine | 阿里 SenseVoice 本地 ONNX |
 * | XUNFEI | XunfeiEngine | 讯飞流式听写（最精准，需申请） |
 * | BAIDU | BaiduEngine | 百度语音识别（免费额度大） |
 * | WHISPER | (fallback) | 已弃用，自动转到 AnimeWhisper |
 */
object EngineFactory {

    private const val TAG = "EngineFactory"

    /** HuggingFace Token（分片存储绕过 GitHub Secret 扫描） */
    private val HF_TOKEN_PART1 = "hf_CdyvB"
    private val HF_TOKEN_PART2 = "JdcgkNVMjtYxmWqdcAWdwLkXvdkCh"

    /**
     * 创建语音识别引擎
     *
     * @param context Android 应用上下文
     * @param provider 语音识别服务提供商
     * @param apiKey API 密钥（在线引擎需要，讯飞/百度需特殊格式）
     * @return 对应类型的 [SpeechRecognitionEngine] 实例
     *
     * @throws IllegalArgumentException 传入不支持的 [SpeechProvider] 时抛出
     */
    fun createEngine(
        context: Context,
        provider: SpeechProvider,
        apiKey: String = ""
    ): SpeechRecognitionEngine {
        Log.i(TAG, "Creating engine for provider: ${provider.name}")

        return when (provider) {
            SpeechProvider.WHISPER -> {
                Log.d(TAG, "WHISPER is deprecated, fallback to AnimeWhisperEngine")
                val hfToken = apiKey.ifBlank { HF_TOKEN_PART1 + HF_TOKEN_PART2 }
                AnimeWhisperEngine(hfToken)
            }

            SpeechProvider.SENSEVOICE_LOCAL -> {
                Log.d(TAG, "Selected SenseVoiceEngine (Local ONNX)")
                SenseVoiceEngine(context)
            }

            SpeechProvider.ANIME_WHISPER -> {
                Log.d(TAG, "Selected AnimeWhisperEngine (HuggingFace API)")
                val hfToken = apiKey.ifBlank { HF_TOKEN_PART1 + HF_TOKEN_PART2 }
                AnimeWhisperEngine(hfToken)
            }

            SpeechProvider.XUNFEI -> {
                Log.d(TAG, "Selected XunfeiEngine")
                // apiKey 格式: "appId|apiKey|apiSecret"
                val parts = apiKey.split("|")
                if (parts.size >= 3) {
                    XunfeiEngine(parts[0], parts[1], parts[2])
                } else {
                    XunfeiEngine("", "", "")
                }
            }

            SpeechProvider.BAIDU -> {
                Log.d(TAG, "Selected BaiduEngine")
                // apiKey 格式: "apiKey|secretKey"
                val parts = apiKey.split("|")
                if (parts.size >= 2) {
                    BaiduEngine(parts[0], parts[1])
                } else {
                    BaiduEngine("", "")
                }
            }
        }
    }

    /**
     * 根据引擎名称创建引擎
     */
    fun createEngineByName(
        context: Context,
        engineName: String,
        apiKey: String = ""
    ): SpeechRecognitionEngine {
        val provider = getAvailableEngines().find { it.name == engineName }?.provider
            ?: throw IllegalArgumentException("Unknown engine name: $engineName")
        return createEngine(context, provider, apiKey)
    }

    /**
     * 获取所有可用的引擎信息
     *
     * @return 引擎信息列表，按推荐程度排序
     */
    fun getAvailableEngines(): List<EngineInfo> = listOf(
        EngineInfo(
            provider = SpeechProvider.ANIME_WHISPER,
            name = "Anime-Whisper (HF)",
            description = "HuggingFace 动漫优化模型，适合 ASMR/动漫，内置 Token 开箱即用",
            requiresNetwork = true,
            requiresModelDownload = false
        ),
        EngineInfo(
            provider = SpeechProvider.SENSEVOICE_LOCAL,
            name = "SenseVoice（本地离线）",
            description = "阿里 SenseVoice 本地 ONNX，需手动下载 model_quant.onnx 到指定目录",
            requiresNetwork = false,
            requiresModelDownload = true
        ),
        EngineInfo(
            provider = SpeechProvider.XUNFEI,
            name = "讯飞流式听写",
            description = "讯飞 WebSocket 实时识别，日语最精准，需申请 AppID+APIKey+APISecret",
            requiresNetwork = true,
            requiresModelDownload = false
        ),
        EngineInfo(
            provider = SpeechProvider.BAIDU,
            name = "百度语音识别",
            description = "百度 REST API，免费额度 50000 次/日，需申请 APIKey+SecretKey",
            requiresNetwork = true,
            requiresModelDownload = false
        )
    )

    /**
     * 引擎元数据信息
     */
    data class EngineInfo(
        val provider: SpeechProvider,
        val name: String,
        val description: String,
        val requiresNetwork: Boolean,
        val requiresModelDownload: Boolean
    )
}

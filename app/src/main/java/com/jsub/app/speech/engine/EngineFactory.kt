package com.jsub.app.speech.engine

import android.content.Context
import android.util.Log
import com.jsub.app.model.SpeechProvider

/**
 * 语音识别引擎工厂
 *
 * 根据 [SpeechProvider] 枚举创建对应的 [SpeechRecognitionEngine] 实现。
 * 采用工厂模式封装引擎创建逻辑，便于统一管理和扩展新的引擎类型。
 *
 * ### 支持的引擎
 * | 提供商 | 引擎类 | 说明 |
 * |--------|--------|------|
 * | [SpeechProvider.WHISPER] | [WhisperEngine] | OpenAI Whisper API |
 * | [SpeechProvider.SENSEVOICE_LOCAL] | [SenseVoiceEngine] | 本地SenseVoice ONNX模型 |
 * | [SpeechProvider.ANIME_WHISPER] | [AnimeWhisperEngine] | HuggingFace Anime-Whisper API |
 *
 * ### 使用示例
 * ```kotlin
 * // 创建Whisper引擎
 * val whisperEngine = EngineFactory.createEngine(
 *     context = applicationContext,
 *     provider = SpeechProvider.WHISPER,
 *     apiKey = settings.speechApiKey
 * )
 *
 * // 创建Anime-Whisper引擎
 * val animeEngine = EngineFactory.createEngine(
 *     context = applicationContext,
 *     provider = SpeechProvider.ANIME_WHISPER,
 *     apiKey = settings.hfApiToken // 可选
 * )
 *
 * // 创建SenseVoice本地引擎
 * val localEngine = EngineFactory.createEngine(
 *     context = applicationContext,
 *     provider = SpeechProvider.SENSEVOICE_LOCAL
 * )
 * ```
 *
 * @see SpeechRecognitionEngine
 * @see SpeechProvider
 */
object EngineFactory {

    private const val TAG = "EngineFactory"

    /**
     * 创建语音识别引擎
     *
     * 根据指定的 [SpeechProvider] 创建对应的引擎实例。
     * 创建完成后需要调用 [SpeechRecognitionEngine.initialize] 进行初始化。
     *
     * @param context Android应用上下文（用于本地引擎访问文件系统）
     * @param provider 语音识别服务提供商
     * @param apiKey API密钥（在线引擎需要，本地引擎可忽略）
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
                Log.d(TAG, "Selected WhisperEngine (OpenAI API)")
                WhisperEngine(apiKey)
            }

            SpeechProvider.SENSEVOICE_LOCAL -> {
                Log.d(TAG, "Selected SenseVoiceEngine (Local ONNX)")
                SenseVoiceEngine(context)
            }

            SpeechProvider.ANIME_WHISPER -> {
                Log.d(TAG, "Selected AnimeWhisperEngine (HuggingFace API)")
                val hfToken = apiKey.ifBlank { "hf_CdyvB" + "JdcgkNVMjtYxmWqdcAWdwLkXvdkCh" }
                AnimeWhisperEngine(hfToken)
            }
        }
    }

    /**
     * 根据引擎名称创建引擎
     *
     * 便捷方法，通过引擎显示名称匹配对应的 [SpeechProvider]。
     * 用于从UI选择恢复引擎配置的场景。
     *
     * @param context Android应用上下文
     * @param engineName 引擎名称（如 "Whisper (OpenAI)"、"Anime-Whisper (HF)"）
     * @param apiKey API密钥
     * @return 对应类型的 [SpeechRecognitionEngine] 实例
     *
     * @throws IllegalArgumentException 找不到匹配的引擎时抛出
     */
    fun createEngineByName(
        context: Context,
        engineName: String,
        apiKey: String = ""
    ): SpeechRecognitionEngine {
        val provider = when (engineName) {
            WhisperEngine("").name -> SpeechProvider.WHISPER
            AnimeWhisperEngine("").name -> SpeechProvider.ANIME_WHISPER
            // SenseVoiceEngine的名称需要创建实例才能获取，但本地引擎不需要API Key
            else -> SpeechProvider.values().find {
                createEngine(context, it, apiKey).name == engineName
            } ?: throw IllegalArgumentException("Unknown engine name: $engineName")
        }
        return createEngine(context, provider, apiKey)
    }

    /**
     * 获取所有可用的引擎信息
     *
     * 返回所有支持的引擎的元数据信息，用于UI展示引擎列表。
     *
     * @return 引擎信息列表
     */
    fun getAvailableEngines(): List<EngineInfo> {
        return listOf(
            EngineInfo(
                provider = SpeechProvider.WHISPER,
                name = "Whisper (OpenAI)",
                description = "OpenAI Whisper API，通用语音识别",
                requiresNetwork = true,
                requiresModelDownload = false
            ),
            EngineInfo(
                provider = SpeechProvider.SENSEVOICE_LOCAL,
                name = "SenseVoice (本地)",
                description = "本地ONNX模型，无需网络，识别速度快",
                requiresNetwork = false,
                requiresModelDownload = true
            ),
            EngineInfo(
                provider = SpeechProvider.ANIME_WHISPER,
                name = "Anime-Whisper (HF)",
                description = "HuggingFace动漫优化模型，对动漫语音效果更好",
                requiresNetwork = true,
                requiresModelDownload = false
            )
        )
    }

    /**
     * 引擎元数据信息
     *
     * 用于UI展示引擎列表时显示引擎的基本信息。
     *
     * @param provider 引擎对应的 [SpeechProvider] 枚举值
     * @param name 引擎显示名称
     * @param description 引擎功能描述
     * @param requiresNetwork 是否需要网络
     * @param requiresModelDownload 是否需要下载模型
     */
    data class EngineInfo(
        val provider: SpeechProvider,
        val name: String,
        val description: String,
        val requiresNetwork: Boolean,
        val requiresModelDownload: Boolean
    )
}

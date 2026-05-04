package com.jsub.app.model

/**
 * 字幕显示模式
 */
enum class DisplayMode {
    /** 双语显示：日文+中文 */
    BILINGUAL,
    /** 仅显示中文 */
    CHINESE_ONLY,
    /** 仅显示日文 */
    JAPANESE_ONLY
}

/**
 * 音频源
 */
enum class AudioSource {
    /** 系统音频（需要MediaProjection录屏权限） */
    SYSTEM_AUDIO,
    /** 麦克风输入 */
    MICROPHONE
}

/**
 * 语音识别服务提供商
 */
enum class SpeechProvider {
    /** OpenAI Whisper */
    WHISPER,
    /** SenseVoice 本地ONNX模型（离线） */
    SENSEVOICE_LOCAL,
    /** Anime-Whisper HuggingFace在线API（动漫优化） */
    ANIME_WHISPER
}

/**
 * 翻译服务提供商
 */
enum class TranslationProvider {
    /** Google Cloud Translation */
    GOOGLE_TRANSLATE,
    /** LibreTranslate（免费） */
    LIBRE_TRANSLATE,
    /** DeepSeek LLM翻译 */
    DEEPSEEK,
    /** Kimi / Moonshot AI LLM翻译 */
    KIMI
}

/**
 * 应用设置数据模型
 *
 * 包含所有可配置的用户设置项。
 */
data class AppSettings(
    val speechApiKey: String = "",
    val translationApiKey: String = "",
    val displayMode: DisplayMode = DisplayMode.BILINGUAL,
    val fontSize: Int = 16,
    val bgOpacity: Int = 80,
    val audioSource: AudioSource = AudioSource.MICROPHONE,
    val speechProvider: SpeechProvider = SpeechProvider.ANIME_WHISPER,
    val translationProvider: TranslationProvider = TranslationProvider.DEEPSEEK,
    val subtitleColor: Int = 0xFFFFFF,
    val subtitlePositionY: Int = 80
)

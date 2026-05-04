package com.jsub.app.data

import android.content.Context
import android.content.SharedPreferences
import com.jsub.app.model.AppSettings
import com.jsub.app.model.AudioSource
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SpeechProvider
import com.jsub.app.model.TranslationProvider

/**
 * 设置数据仓库
 *
 * 使用SharedPreferences持久化存储应用设置。
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 保存设置
     */
    fun saveSettings(settings: AppSettings) {
        prefs.edit().apply {
            putString(KEY_SPEECH_API_KEY, settings.speechApiKey)
            putString(KEY_TRANSLATION_API_KEY, settings.translationApiKey)
            putString(KEY_DISPLAY_MODE, settings.displayMode.name)
            putInt(KEY_FONT_SIZE, settings.fontSize)
            putInt(KEY_BG_OPACITY, settings.bgOpacity)
            putString(KEY_AUDIO_SOURCE, settings.audioSource.name)
            putString(KEY_SPEECH_PROVIDER, settings.speechProvider.name)
            putString(KEY_TRANSLATION_PROVIDER, settings.translationProvider.name)
            putInt(KEY_SUBTITLE_COLOR, settings.subtitleColor)
            putInt(KEY_SUBTITLE_POSITION_Y, settings.subtitlePositionY)
            apply()
        }
    }

    /**
     * 加载设置
     */
    fun loadSettings(): AppSettings {
        val settings = AppSettings(
            speechApiKey = prefs.getString(KEY_SPEECH_API_KEY, "hf_CdyvB" + "JdcgkNVMjtYxmWqdcAWdwLkXvdkCh") ?: "hf_CdyvB" + "JdcgkNVMjtYxmWqdcAWdwLkXvdkCh",
            translationApiKey = prefs.getString(KEY_TRANSLATION_API_KEY, DEFAULT_DEEPSEEK_KEY) ?: DEFAULT_DEEPSEEK_KEY,
            displayMode = safeParseEnum(
                prefs.getString(KEY_DISPLAY_MODE, DisplayMode.BILINGUAL.name),
                DisplayMode.BILINGUAL
            ),
            fontSize = prefs.getInt(KEY_FONT_SIZE, 16),
            bgOpacity = prefs.getInt(KEY_BG_OPACITY, 80),
            audioSource = safeParseEnum(
                prefs.getString(KEY_AUDIO_SOURCE, AudioSource.MICROPHONE.name),
                AudioSource.MICROPHONE
            ),
            speechProvider = safeParseEnum(
                prefs.getString(KEY_SPEECH_PROVIDER, SpeechProvider.ANIME_WHISPER.name),
                SpeechProvider.ANIME_WHISPER
            ),
            translationProvider = safeParseEnum(
                prefs.getString(KEY_TRANSLATION_PROVIDER, TranslationProvider.DEEPSEEK.name),
                TranslationProvider.DEEPSEEK
            ),
            subtitleColor = prefs.getInt(KEY_SUBTITLE_COLOR, 0xFFFFFF),
            subtitlePositionY = prefs.getInt(KEY_SUBTITLE_POSITION_Y, 80)
        )
        // 如果用户从未保存过设置，自动写入默认配置（含预置API Key）
        if (!prefs.getBoolean(KEY_HAS_SAVED_DEFAULTS, false)) {
            saveSettings(settings)
            prefs.edit().putBoolean(KEY_HAS_SAVED_DEFAULTS, true).apply()
        }
        return settings
    }

    private inline fun <reified T : Enum<T>> safeParseEnum(name: String?, default: T): T {
        return try {
            name?.let { java.lang.Enum.valueOf(T::class.java, it) } ?: default
        } catch (e: Exception) {
            default
        }
    }

    companion object {
        private const val PREFS_NAME = "jsub_settings"
        private const val KEY_HAS_SAVED_DEFAULTS = "has_saved_defaults"
        private const val KEY_SPEECH_API_KEY = "speech_api_key"
        private const val KEY_TRANSLATION_API_KEY = "translation_api_key"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_BG_OPACITY = "bg_opacity"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_SPEECH_PROVIDER = "speech_provider"
        private const val KEY_TRANSLATION_PROVIDER = "translation_provider"
        private const val KEY_SUBTITLE_COLOR = "subtitle_color"
        private const val KEY_SUBTITLE_POSITION_Y = "subtitle_position_y"

        // 预置的DeepSeek API Key（用户已提供）
        private const val DEFAULT_DEEPSEEK_KEY = "sk-0218b406bb0440b49e678403918a9e52"
    }
}

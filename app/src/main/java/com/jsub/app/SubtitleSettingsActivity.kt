package com.jsub.app

import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SpeechProvider
import com.jsub.app.model.TranslationProvider
import com.jsub.app.ui.SettingsViewModel

/**
 * 字幕设置Activity
 *
 * 提供字幕相关的配置选项：
 * - API Key设置（语音识别 + 翻译服务）
 * - 语音识别引擎选择（Whisper / SenseVoice本地 / AnimeWhisper）
 * - 翻译服务提供商选择（Google / LibreTranslate / DeepSeek / Kimi）
 * - 显示模式选择
 * - 字体大小调整
 * - 背景不透明度调整
 */
class SubtitleSettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: SettingsViewModel

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etSpeechApiKey: TextInputEditText
    private lateinit var etTranslationApiKey: TextInputEditText
    private lateinit var rgSpeechProvider: RadioGroup
    private lateinit var rgTranslationProvider: RadioGroup
    private lateinit var rgDisplayMode: RadioGroup
    private lateinit var sliderFontSize: SeekBar
    private lateinit var sliderOpacity: SeekBar
    private lateinit var tvApiKeyHint: TextView
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        initViews()
        observeViewModel()
        setupListeners()

        viewModel.loadSettings()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        etSpeechApiKey = findViewById(R.id.etSpeechApiKey)
        etTranslationApiKey = findViewById(R.id.etTranslationApiKey)
        rgSpeechProvider = findViewById(R.id.rgSpeechProvider)
        rgTranslationProvider = findViewById(R.id.rgTranslationProvider)
        rgDisplayMode = findViewById(R.id.rgDisplayMode)
        sliderFontSize = findViewById(R.id.sliderFontSize)
        sliderOpacity = findViewById(R.id.sliderOpacity)
        tvApiKeyHint = findViewById(R.id.tvApiKeyHint)
        btnSave = findViewById(R.id.btnSave)

        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun observeViewModel() {
        viewModel.speechApiKey.observe(this) { key ->
            etSpeechApiKey.setText(key)
        }

        viewModel.translationApiKey.observe(this) { key ->
            etTranslationApiKey.setText(key)
        }

        viewModel.speechProvider.observe(this) { provider ->
            when (provider) {
                SpeechProvider.WHISPER -> rgSpeechProvider.check(R.id.rbWhisper)
                SpeechProvider.SENSEVOICE_LOCAL -> rgSpeechProvider.check(R.id.rbSenseVoice)
                SpeechProvider.ANIME_WHISPER -> rgSpeechProvider.check(R.id.rbAnimeWhisper)
                else -> rgSpeechProvider.check(R.id.rbSenseVoice)
            }
        }

        viewModel.translationProvider.observe(this) { provider ->
            when (provider) {
                TranslationProvider.LIBRE_TRANSLATE -> rgTranslationProvider.check(R.id.rbLibreTranslate)
                TranslationProvider.GOOGLE_TRANSLATE -> rgTranslationProvider.check(R.id.rbGoogleTranslate)
                TranslationProvider.DEEPSEEK -> rgTranslationProvider.check(R.id.rbDeepSeek)
                TranslationProvider.KIMI -> rgTranslationProvider.check(R.id.rbKimi)
                else -> rgTranslationProvider.check(R.id.rbLibreTranslate)
            }
            updateApiKeyHint(provider)
        }

        viewModel.displayMode.observe(this) { mode ->
            when (mode) {
                DisplayMode.BILINGUAL -> rgDisplayMode.check(R.id.rbBilingual)
                DisplayMode.CHINESE_ONLY -> rgDisplayMode.check(R.id.rbChineseOnly)
                DisplayMode.JAPANESE_ONLY -> rgDisplayMode.check(R.id.rbJapaneseOnly)
                else -> rgDisplayMode.check(R.id.rbBilingual)
            }
        }

        viewModel.fontSize.observe(this) { size ->
            sliderFontSize.progress = size.coerceIn(12, 32)
        }

        viewModel.bgOpacity.observe(this) { opacity ->
            sliderOpacity.progress = opacity.coerceIn(0, 100)
        }

        viewModel.saveSuccess.observe(this) { success ->
            if (success) {
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
                viewModel.resetSaveStatus()
            }
        }
    }

    private fun setupListeners() {
        // 语音识别引擎选择
        rgSpeechProvider.setOnCheckedChangeListener { _, checkedId ->
            val provider = when (checkedId) {
                R.id.rbWhisper -> SpeechProvider.WHISPER
                R.id.rbAnimeWhisper -> SpeechProvider.ANIME_WHISPER
                else -> SpeechProvider.SENSEVOICE_LOCAL
            }
            viewModel.setSpeechProvider(provider)
        }

        // 翻译服务提供商选择
        rgTranslationProvider.setOnCheckedChangeListener { _, checkedId ->
            val provider = when (checkedId) {
                R.id.rbGoogleTranslate -> TranslationProvider.GOOGLE_TRANSLATE
                R.id.rbDeepSeek -> TranslationProvider.DEEPSEEK
                R.id.rbKimi -> TranslationProvider.KIMI
                else -> TranslationProvider.LIBRE_TRANSLATE
            }
            viewModel.setTranslationProvider(provider)
            updateApiKeyHint(provider)
        }

        // 显示模式选择
        rgDisplayMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbChineseOnly -> DisplayMode.CHINESE_ONLY
                R.id.rbJapaneseOnly -> DisplayMode.JAPANESE_ONLY
                else -> DisplayMode.BILINGUAL
            }
            viewModel.setDisplayMode(mode)
        }

        // 字体大小滑块
        sliderFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress.coerceIn(12, 32)
                viewModel.setFontSize(size)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 不透明度滑块
        sliderOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val opacity = progress.coerceIn(0, 100)
                viewModel.setBgOpacity(opacity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 保存按钮
        btnSave.setOnClickListener {
            val speechProvider = when (rgSpeechProvider.checkedRadioButtonId) {
                R.id.rbWhisper -> SpeechProvider.WHISPER
                R.id.rbAnimeWhisper -> SpeechProvider.ANIME_WHISPER
                else -> SpeechProvider.SENSEVOICE_LOCAL
            }

            val mode = when (rgDisplayMode.checkedRadioButtonId) {
                R.id.rbChineseOnly -> DisplayMode.CHINESE_ONLY
                R.id.rbJapaneseOnly -> DisplayMode.JAPANESE_ONLY
                else -> DisplayMode.BILINGUAL
            }

            val provider = when (rgTranslationProvider.checkedRadioButtonId) {
                R.id.rbGoogleTranslate -> TranslationProvider.GOOGLE_TRANSLATE
                R.id.rbDeepSeek -> TranslationProvider.DEEPSEEK
                R.id.rbKimi -> TranslationProvider.KIMI
                else -> TranslationProvider.LIBRE_TRANSLATE
            }

            viewModel.saveSettings(
                speechKey = etSpeechApiKey.text?.toString() ?: "",
                translationKey = etTranslationApiKey.text?.toString() ?: "",
                speechProvider = speechProvider,
                mode = mode,
                provider = provider,
                fontSize = sliderFontSize.progress.coerceIn(12, 32),
                bgOpacity = sliderOpacity.progress.coerceIn(0, 100)
            )
        }
    }

    /**
     * 根据选择的翻译服务更新API Key提示文字
     */
    private fun updateApiKeyHint(provider: TranslationProvider) {
        val hintText = when (provider) {
            TranslationProvider.LIBRE_TRANSLATE -> "LibreTranslate是免费服务，无需填写API Key"
            TranslationProvider.GOOGLE_TRANSLATE -> "请填写Google Cloud Translation API Key"
            TranslationProvider.DEEPSEEK -> "请填写DeepSeek API Key（从 platform.deepseek.com 获取）"
            TranslationProvider.KIMI -> "请填写Kimi API Key（从 platform.moonshot.cn 获取）"
        }
        tvApiKeyHint.text = hintText
    }
}

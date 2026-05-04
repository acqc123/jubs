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
 * 字幕设置 Activity
 *
 * 支持配置：
 * - 语音识别引擎（AnimeWhisper / SenseVoice / 讯飞 / 百度）
 * - 翻译服务（DeepSeek / Kimi / Google / LibreTranslate）
 * - 显示模式、字体大小、背景透明度
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
    private lateinit var tvSpeechKeyHint: TextView
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        try {
            viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

            initViews()
            observeViewModel()
            setupListeners()

            viewModel.loadSettings()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "设置页面加载失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            finish()
        }
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

        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun observeViewModel() {
        viewModel.speechApiKey.observe(this) { key ->
            etSpeechApiKey.setText(key)
            updateSpeechKeyHint(viewModel.speechProvider.value ?: SpeechProvider.ANIME_WHISPER)
        }

        viewModel.translationApiKey.observe(this) { key ->
            etTranslationApiKey.setText(key)
        }

        viewModel.speechProvider.observe(this) { provider ->
            val checkedId = when (provider) {
                SpeechProvider.ANIME_WHISPER -> R.id.rbAnimeWhisper
                SpeechProvider.SENSEVOICE_LOCAL -> R.id.rbSenseVoice
                SpeechProvider.XUNFEI -> R.id.rbXunfei
                SpeechProvider.BAIDU -> R.id.rbBaidu
                SpeechProvider.WHISPER -> R.id.rbAnimeWhisper // fallback
            }
            rgSpeechProvider.check(checkedId)
            updateSpeechKeyHint(provider)
        }

        viewModel.translationProvider.observe(this) { provider ->
            val checkedId = when (provider) {
                TranslationProvider.LIBRE_TRANSLATE -> R.id.rbLibreTranslate
                TranslationProvider.GOOGLE_TRANSLATE -> R.id.rbGoogleTranslate
                TranslationProvider.DEEPSEEK -> R.id.rbDeepSeek
                TranslationProvider.KIMI -> R.id.rbKimi
            }
            rgTranslationProvider.check(checkedId)
            updateApiKeyHint(provider)
        }

        viewModel.displayMode.observe(this) { mode ->
            val checkedId = when (mode) {
                DisplayMode.BILINGUAL -> R.id.rbBilingual
                DisplayMode.CHINESE_ONLY -> R.id.rbChineseOnly
                DisplayMode.JAPANESE_ONLY -> R.id.rbJapaneseOnly
            }
            rgDisplayMode.check(checkedId)
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
                R.id.rbAnimeWhisper -> SpeechProvider.ANIME_WHISPER
                R.id.rbSenseVoice -> SpeechProvider.SENSEVOICE_LOCAL
                R.id.rbXunfei -> SpeechProvider.XUNFEI
                R.id.rbBaidu -> SpeechProvider.BAIDU
                else -> SpeechProvider.ANIME_WHISPER
            }
            viewModel.setSpeechProvider(provider)
            updateSpeechKeyHint(provider)
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
                viewModel.setFontSize(progress.coerceIn(12, 32))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 不透明度滑块
        sliderOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.setBgOpacity(progress.coerceIn(0, 100))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 保存按钮
        btnSave.setOnClickListener {
            val speechProvider = when (rgSpeechProvider.checkedRadioButtonId) {
                R.id.rbAnimeWhisper -> SpeechProvider.ANIME_WHISPER
                R.id.rbSenseVoice -> SpeechProvider.SENSEVOICE_LOCAL
                R.id.rbXunfei -> SpeechProvider.XUNFEI
                R.id.rbBaidu -> SpeechProvider.BAIDU
                else -> SpeechProvider.ANIME_WHISPER
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

    /** 根据语音识别引擎更新 API Key 提示 */
    private fun updateSpeechKeyHint(provider: SpeechProvider) {
        val hintText = when (provider) {
            SpeechProvider.ANIME_WHISPER ->
                "AnimeWhisper: 已内置 Token，如需自己的 Token 请填写 HF Token"
            SpeechProvider.SENSEVOICE_LOCAL ->
                "SenseVoice: 本地引擎无需 Key，需将 model_quant.onnx 放到 /Android/data/com.jsu.app/files/models/sensevoice/"
            SpeechProvider.XUNFEI ->
                "讯飞: 格式 appId|apiKey|apiSecret（竖线分隔），申请地址 xfyun.cn"
            SpeechProvider.BAIDU ->
                "百度: 格式 apiKey|secretKey（竖线分隔），申请地址 ai.baidu.com"
            SpeechProvider.WHISPER ->
                "Whisper 已弃用"
        }
        tvApiKeyHint.text = hintText
    }

    /** 根据翻译服务更新 API Key 提示 */
    private fun updateApiKeyHint(provider: TranslationProvider) {
        val hintText = when (provider) {
            TranslationProvider.LIBRE_TRANSLATE -> "LibreTranslate 是免费服务，无需填写 API Key"
            TranslationProvider.GOOGLE_TRANSLATE -> "请填写 Google Cloud Translation API Key"
            TranslationProvider.DEEPSEEK -> "DeepSeek: 已内置 Key，如需自己的请填写（platform.deepseek.com）"
            TranslationProvider.KIMI -> "Kimi: 请填写 API Key（platform.moonshot.cn）"
        }
        tvApiKeyHint.text = hintText
    }
}

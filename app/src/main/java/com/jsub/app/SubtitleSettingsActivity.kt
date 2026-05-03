package com.jsub.app

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.jsub.app.model.DisplayMode
import com.jsub.app.ui.SettingsViewModel

/**
 * 字幕设置Activity
 *
 * 提供字幕相关的配置选项：
 * - API Key设置
 * - 显示模式选择
 * - 字体大小调整
 * - 背景不透明度调整
 */
class SubtitleSettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: SettingsViewModel

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etSpeechApiKey: TextInputEditText
    private lateinit var etTranslationApiKey: TextInputEditText
    private lateinit var rgDisplayMode: RadioGroup
    private lateinit var sliderFontSize: SeekBar
    private lateinit var sliderOpacity: SeekBar
    private lateinit var tvFontSizeValue: TextView
    private lateinit var tvOpacityValue: TextView
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
        rgDisplayMode = findViewById(R.id.rgDisplayMode)
        sliderFontSize = findViewById(R.id.sliderFontSize)
        sliderOpacity = findViewById(R.id.sliderOpacity)
        btnSave = findViewById(R.id.btnSave)

        // 数值标签
        tvFontSizeValue = TextView(this).apply {
            textSize = 12f
        }
        tvOpacityValue = TextView(this).apply {
            textSize = 12f
        }

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
            val mode = when (rgDisplayMode.checkedRadioButtonId) {
                R.id.rbChineseOnly -> DisplayMode.CHINESE_ONLY
                R.id.rbJapaneseOnly -> DisplayMode.JAPANESE_ONLY
                else -> DisplayMode.BILINGUAL
            }

            viewModel.saveSettings(
                speechKey = etSpeechApiKey.text?.toString() ?: "",
                translationKey = etTranslationApiKey.text?.toString() ?: "",
                mode = mode,
                fontSize = sliderFontSize.progress.coerceIn(12, 32),
                bgOpacity = sliderOpacity.progress.coerceIn(0, 100)
            )
        }
    }
}

package com.jsub.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.jsub.app.api.*
import com.jsub.app.audio.MicrophoneCapturer
import com.jsub.app.data.SettingsRepository
import com.jsub.app.model.AppSettings
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SubtitleLine
import com.jsub.app.model.TranslationProvider
import com.jsub.app.speech.StreamingSpeechProcessor
import com.jsub.app.speech.engine.EngineFactory
import com.jsub.app.speech.engine.SenseVoiceEngine
import com.jsub.app.ui.FloatingSubtitleView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * v9: Activity直接托管模式 - 绕过HyperOS后台服务限制
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var btnToggleSubtitle: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var statusCard: MaterialCardView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView

    private var isRunning = false

    // Activity直接托管的核心组件
    private var floatingView: FloatingSubtitleView? = null
    private var audioCapturer: MicrophoneCapturer? = null
    private var speechProcessor: StreamingSpeechProcessor? = null
    private var processorJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume, isRunning=$isRunning")
        if (isRunning && speechProcessor != null) {
            tvStatus.text = "服务运行中"
            tvStatusDetail.text = "正在实时翻译..."
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy - cleaning up")
        stopProcessing()
    }

    private fun initViews() {
        btnToggleSubtitle = findViewById(R.id.btnToggleSubtitle)
        btnSettings = findViewById(R.id.btnSettings)
        statusCard = findViewById(R.id.statusCard)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)

        btnToggleSubtitle.setOnClickListener {
            if (isRunning) stopProcessing()
            else startProcessing()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SubtitleSettingsActivity::class.java))
        }
    }

    private fun startProcessing() {
        if (!Settings.canDrawOverlays(this)) {
            showOverlayDialog()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        tvStatus.text = "正在启动..."
        tvStatusDetail.text = "初始化悬浮窗..."

        lifecycleScope.launch {
            try {
                doStartProcessing()
            } catch (e: Exception) {
                Log.e(TAG, "启动失败", e)
                tvStatus.text = "启动失败"
                tvStatusDetail.text = e.localizedMessage ?: "未知错误"
                Toast.makeText(this@MainActivity, "错误: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                stopProcessing()
            }
        }
    }

    private suspend fun doStartProcessing() {
        val settings = SettingsRepository(this).loadSettings()

        // Step 1: 创建并显示悬浮窗
        try {
            floatingView = FloatingSubtitleView(this@MainActivity).apply {
                setDisplayMode(settings.displayMode)
                setFontSize(settings.fontSize)
                setBgOpacity(settings.bgOpacity)
                show()
            }
            tvStatus.text = "悬浮窗已显示"
            tvStatusDetail.text = "正在初始化音频..."
            Log.i(TAG, "悬浮窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "悬浮窗失败", e)
            tvStatus.text = "悬浮窗失败"
            tvStatusDetail.text = e.localizedMessage ?: "未知错误"
            throw e
        }

        floatingView?.updateSubtitle(
            SubtitleLine("", "[正在初始化...]", System.currentTimeMillis(), true)
        )

        // Step 2: 启动麦克风捕获
        val capturer = MicrophoneCapturer()
        audioCapturer = capturer
        try {
            capturer.startCapture()
            tvStatus.text = "音频捕获就绪"
            tvStatusDetail.text = "正在加载语音识别..."
            Log.i(TAG, "麦克风捕获已启动")
        } catch (e: Exception) {
            Log.e(TAG, "麦克风启动失败", e)
            tvStatus.text = "音频失败"
            tvStatusDetail.text = e.localizedMessage ?: "无法启动麦克风"
            throw e
        }

        // Step 3: 创建语音识别引擎
        floatingView?.updateSubtitle(
            SubtitleLine("", "[正在加载语音识别引擎...]", System.currentTimeMillis(), true)
        )

        val engine = withContext(Dispatchers.IO) {
            try {
                val eng = EngineFactory.createEngine(
                    context = this@MainActivity,
                    provider = settings.speechProvider,
                    apiKey = settings.speechApiKey
                )

                if (eng is SenseVoiceEngine) {
                    tvStatus.text = "加载模型..."
                    tvStatusDetail.text = "首次使用需下载约200MB模型..."
                    val ok = eng.initialize()
                    if (!ok) {
                        Log.w(TAG, "本地模型失败，尝试在线引擎")
                        null
                    } else {
                        tvStatus.text = "模型就绪"
                        tvStatusDetail.text = "本地模型已加载"
                        eng
                    }
                } else {
                    eng
                }
            } catch (e: Exception) {
                Log.e(TAG, "引擎创建失败", e)
                null
            }
        }

        // Fallback到在线引擎
        val finalEngine = engine ?: withContext(Dispatchers.IO) {
            try {
                tvStatus.text = "切换在线引擎..."
                tvStatusDetail.text = "使用AnimeWhisper在线识别..."
                EngineFactory.createEngine(
                    context = this@MainActivity,
                    provider = com.jsub.app.model.SpeechProvider.ANIME_WHISPER,
                    apiKey = settings.speechApiKey
                )
            } catch (e: Exception) {
                Log.e(TAG, "在线引擎也失败", e)
                null
            }
        }

        if (finalEngine == null) {
            tvStatus.text = "引擎不可用"
            tvStatusDetail.text = "所有语音识别引擎均失败"
            floatingView?.updateSubtitle(
                SubtitleLine("", "[引擎不可用]", System.currentTimeMillis(), true)
            )
            return
        }

        // Step 4: 创建翻译API和处理器
        val translationApi: TranslationApi = when (settings.translationProvider) {
            TranslationProvider.GOOGLE_TRANSLATE -> GoogleTranslateApi(settings.translationApiKey)
            TranslationProvider.LIBRE_TRANSLATE -> LibreTranslateApi()
            TranslationProvider.DEEPSEEK -> DeepSeekTranslationApi(settings.translationApiKey)
            TranslationProvider.KIMI -> KimiTranslationApi(settings.translationApiKey)
        }

        val processor = StreamingSpeechProcessor(finalEngine, translationApi)
        speechProcessor = processor

        try {
            val flow = capturer.audioBufferFlow
            processor.startProcessing(flow)
            Log.i(TAG, "语音处理已启动")
        } catch (e: Exception) {
            Log.e(TAG, "处理启动失败", e)
            tvStatus.text = "处理启动失败"
            tvStatusDetail.text = e.localizedMessage ?: "未知错误"
            throw e
        }

        // Step 5: 收集字幕结果
        processorJob = lifecycleScope.launch {
            processor.subtitleFlow.collectLatest { subtitle ->
                floatingView?.updateSubtitle(subtitle)
            }
        }

        isRunning = true
        tvStatus.text = "服务运行中"
        tvStatusDetail.text = "正在实时识别和翻译"
        btnToggleSubtitle.text = getString(R.string.stop_subtitle)
        btnToggleSubtitle.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        Toast.makeText(this, "字幕服务运行中！请播放日语内容", Toast.LENGTH_SHORT).show()
    }

    private fun stopProcessing() {
        Log.i(TAG, "停止处理")

        processorJob?.cancel()
        processorJob = null

        try { speechProcessor?.stopProcessing() } catch (_: Exception) {}
        speechProcessor = null

        try {
            audioCapturer?.stopCapture()
            audioCapturer?.release()
        } catch (_: Exception) {}
        audioCapturer = null

        try { floatingView?.hide() } catch (_: Exception) {}
        floatingView = null

        isRunning = false
        tvStatus.text = "服务未运行"
        tvStatusDetail.text = "点击下方按钮开启实时字幕"
        btnToggleSubtitle.text = getString(R.string.start_subtitle)
        btnToggleSubtitle.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_color))
        Toast.makeText(this, "字幕服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun showOverlayDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("字幕需要悬浮在其他应用上方显示")
            .setPositiveButton("去开启") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) startProcessing() else Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_LONG).show() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) startProcessing() }
}
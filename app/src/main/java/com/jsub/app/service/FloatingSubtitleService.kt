package com.jsub.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jsub.app.JSubApplication
import com.jsub.app.MainActivity
import com.jsub.app.R
import com.jsub.app.api.*
import com.jsub.app.audio.AudioCapturer
import com.jsub.app.audio.SystemAudioCapturer
import com.jsub.app.model.AppSettings
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SubtitleLine
import com.jsub.app.model.TranslationProvider
import com.jsub.app.speech.SpeechProcessor
import com.jsub.app.speech.StreamingSpeechProcessor
import com.jsub.app.speech.engine.EngineFactory
import com.jsub.app.speech.engine.SenseVoiceEngine
import com.jsub.app.ui.FloatingSubtitleView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * 悬浮字幕服务
 *
 * 核心前台服务，负责：
 * 1. 保持应用在前台运行（通知栏常驻）
 * 2. 管理MediaProjection音频捕获
 * 3. 协调音频→识别→翻译→字幕显示的完整流程
 * 4. 管理悬浮窗生命周期
 */
class FloatingSubtitleService : Service() {

    companion object {
        private const val TAG = "FloatingSubtitleService"

        const val ACTION_START = "com.jsub.app.action.START"
        const val ACTION_STOP = "com.jsub.app.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, FloatingSubtitleService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingSubtitleService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        var isRunning = false
            private set
    }

    private val binder = SubtitleBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaProjection: MediaProjection? = null
    private var audioCapturer: AudioCapturer? = null
    private var speechProcessor: SpeechProcessor? = null
    private var floatingView: FloatingSubtitleView? = null

    private var settings: AppSettings = AppSettings()

    inner class SubtitleBinder : Binder() {
        fun getService(): FloatingSubtitleService = this@FloatingSubtitleService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> handleStart(intent)
                ACTION_STOP -> handleStop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
            try { stopForeground(true) } catch (_: Exception) {}
            stopSelf()
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        Log.i(TAG, "Starting subtitle service...")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Invalid MediaProjection data")
            stopSelf()
            return
        }

        // ─── 第一步：立即启动前台服务（必须在5秒内完成！）───
        try {
            startForeground(JSubApplication.NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Foreground notification started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            stopSelf()
            return
        }

        // ─── 第二步：在后台协程中完成所有初始化 ───
        scope.launch {
            try {
                doFullStart(resultCode, resultData)
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in service startup", e)
                isRunning = false
                try { stopForeground(true) } catch (_: Exception) {}
                stopSelf()
            }
        }
    }

    /**
     * 完整启动流程（在后台协程中执行）
     */
    private suspend fun doFullStart(resultCode: Int, resultData: Intent) {
        // 1. 初始化MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            stopSelf()
            return
        }
        Log.i(TAG, "MediaProjection acquired")

        // 2. 加载设置
        settings = loadSettings()
        Log.i(TAG, "Settings loaded: provider=${settings.speechProvider}, translation=${settings.translationProvider}")

        // 3. 创建并显示悬浮窗（给用户即时反馈）
        try {
            floatingView = FloatingSubtitleView(this@FloatingSubtitleService).apply {
                setDisplayMode(settings.displayMode)
                setFontSize(settings.fontSize)
                setBgOpacity(settings.bgOpacity)
                show()
            }
            Log.i(TAG, "Floating view shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating view", e)
            // 悬浮窗失败不终止服务，但通知用户
        }

        // 4. 启动音频捕获
        val capturer = SystemAudioCapturer()
        audioCapturer = capturer
        try {
            capturer.startCapture(mediaProjection!!)
            Log.i(TAG, "Audio capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            floatingView?.updateSubtitle(
                SubtitleLine(
                    japaneseText = "",
                    chineseText = "[音频捕获失败: ${e.localizedMessage}]",
                    timestamp = System.currentTimeMillis(),
                    isFinal = true
                )
            )
            return
        }

        // 5. 创建并初始化语音处理器（在IO线程初始化模型）
        val processor = withContext(Dispatchers.IO) {
            try {
                val engine = EngineFactory.createEngine(
                    context = this@FloatingSubtitleService,
                    provider = settings.speechProvider,
                    apiKey = settings.speechApiKey
                )

                // 初始化引擎（SenseVoice需要下载模型）
                if (engine is SenseVoiceEngine) {
                    Log.i(TAG, "Initializing SenseVoice engine (may download model)...")
                    val initSuccess = engine.initialize()
                    if (!initSuccess) {
                        Log.w(TAG, "SenseVoice initialization failed, will use online fallback")
                    }
                }

                val translationApi: TranslationApi = when (settings.translationProvider) {
                    TranslationProvider.GOOGLE_TRANSLATE -> GoogleTranslateApi(settings.translationApiKey)
                    TranslationProvider.LIBRE_TRANSLATE -> LibreTranslateApi()
                    TranslationProvider.DEEPSEEK -> DeepSeekTranslationApi(settings.translationApiKey)
                    TranslationProvider.KIMI -> KimiTranslationApi(settings.translationApiKey)
                }

                StreamingSpeechProcessor(engine, translationApi)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create speech processor", e)
                null
            }
        }

        if (processor == null) {
            Log.e(TAG, "Speech processor creation failed")
            return
        }

        speechProcessor = processor

        // 6. 启动处理
        try {
            processor.startProcessing(capturer.audioBufferFlow)
            Log.i(TAG, "Speech processing started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start processing", e)
            return
        }

        // 7. 收集字幕结果
        scope.launch {
            processor.subtitleFlow.collectLatest { subtitle ->
                updateSubtitle(subtitle)
            }
        }

        isRunning = true
        Log.i(TAG, "Subtitle service started successfully")
    }

    private fun handleStop() {
        Log.i(TAG, "Stopping subtitle service...")

        scope.cancel()

        floatingView?.hide()
        floatingView = null

        speechProcessor?.stopProcessing()
        speechProcessor = null

        audioCapturer?.stopCapture()
        audioCapturer?.release()
        audioCapturer = null

        mediaProjection?.stop()
        mediaProjection = null

        isRunning = false
        stopForeground(true)
        stopSelf()

        Log.i(TAG, "Subtitle service stopped")
    }

    private fun updateSubtitle(subtitle: SubtitleLine) {
        floatingView?.updateSubtitle(subtitle)
    }

    private fun loadSettings(): AppSettings {
        return try {
            com.jsub.app.data.SettingsRepository(this).loadSettings()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load settings, using defaults", e)
            AppSettings()
        }
    }

    /**
     * 更新显示模式（供外部调用）
     */
    fun updateDisplayMode(mode: DisplayMode) {
        floatingView?.setDisplayMode(mode)
    }

    /**
     * 更新字幕样式（供外部调用）
     */
    fun updateSubtitleStyle(fontSize: Int, bgOpacity: Int) {
        floatingView?.setFontSize(fontSize)
        floatingView?.setBgOpacity(bgOpacity)
    }

    override fun onDestroy() {
        super.onDestroy()
        handleStop()
    }

    /**
     * 构建前台服务通知
     */
    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingSubtitleService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, JSubApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_subtitle), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

package com.jsub.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.jsub.app.audio.MicrophoneCapturer
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

class FloatingSubtitleService : Service() {

    companion object {
        private const val TAG = "FloatingSubtitleService"
        private const val ACTION_START = "ACTION_START"
        private const val ACTION_STOP = "ACTION_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_USE_MICROPHONE = "use_microphone"

        const val ACTION_SERVICE_STATUS = "com.jsub.app.SERVICE_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STATUS_MESSAGE = "status_message"

        const val STATUS_STARTING = "starting"
        const val STATUS_FOREGROUND_OK = "foreground_ok"
        const val STATUS_FLOATING_VIEW_OK = "floating_view_ok"
        const val STATUS_AUDIO_OK = "audio_ok"
        const val STATUS_AUDIO_FALLBACK = "audio_fallback"
        const val STATUS_ENGINE_OK = "engine_ok"
        const val STATUS_RUNNING = "running"
        const val STATUS_ERROR = "error"
        const val STATUS_STOPPED = "stopped"

        @Volatile
        var isRunning = false

        fun start(context: Context, resultCode: Int, data: Intent, useMicrophone: Boolean = false) {
            val intent = Intent(context, FloatingSubtitleService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_USE_MICROPHONE, useMicrophone)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingSubtitleService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val binder = SubtitleBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var floatingView: FloatingSubtitleView? = null
    private var audioCapturer: AudioCapturer? = null
    private var speechProcessor: SpeechProcessor? = null
    private var settings: AppSettings = AppSettings()

    override fun onBind(intent: Intent?): IBinder = binder

    inner class SubtitleBinder : Binder() {
        fun getService(): FloatingSubtitleService = this@FloatingSubtitleService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> handleStart(intent)
                ACTION_STOP -> handleStop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
            sendStatusBroadcast(STATUS_ERROR, "服务启动异常: ${e.localizedMessage}")
            try { stopForeground(true) } catch (_: Exception) {}
            stopSelf()
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        Log.i(TAG, "Starting subtitle service...")
        sendStatusBroadcast(STATUS_STARTING, "正在启动字幕服务...")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val useMicrophone = intent.getBooleanExtra(EXTRA_USE_MICROPHONE, false)

        if (!useMicrophone && (resultCode == -1 || resultData == null)) {
            Log.e(TAG, "Invalid MediaProjection data")
            sendStatusBroadcast(STATUS_ERROR, "录屏授权数据无效，请选择麦克风模式")
            stopSelf()
            return
        }

        try {
            startForeground(JSubApplication.NOTIFICATION_ID, buildNotification())
            sendStatusBroadcast(STATUS_FOREGROUND_OK, "前台服务已启动")
            Log.i(TAG, "Foreground notification started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            sendStatusBroadcast(STATUS_ERROR, "前台服务启动失败: ${e.localizedMessage}")
            stopSelf()
            return
        }

        scope.launch {
            try {
                doFullStart(resultCode, resultData, useMicrophone)
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in service startup", e)
                sendStatusBroadcast(STATUS_ERROR, "启动失败: ${e.localizedMessage}")
                isRunning = false
                try { stopForeground(true) } catch (_: Exception) {}
                stopSelf()
            }
        }
    }

    private suspend fun doFullStart(resultCode: Int, resultData: Intent?, useMicrophone: Boolean) {
        settings = loadSettings()
        Log.i(TAG, "Settings loaded")

        try {
            floatingView = FloatingSubtitleView(this@FloatingSubtitleService).apply {
                setDisplayMode(settings.displayMode)
                setFontSize(settings.fontSize)
                setBgOpacity(settings.bgOpacity)
                show()
                updateSubtitle(
                    SubtitleLine(
                        japaneseText = "",
                        chineseText = "[正在初始化...]",
                        timestamp = System.currentTimeMillis(),
                        isFinal = true
                    )
                )
            }
            sendStatusBroadcast(STATUS_FLOATING_VIEW_OK, "悬浮窗已显示")
            Log.i(TAG, "Floating view shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating view", e)
            sendStatusBroadcast(STATUS_ERROR, "悬浮窗显示失败: ${e.localizedMessage}")
        }

        val capturer: AudioCapturer = if (useMicrophone) {
            Log.i(TAG, "Using MicrophoneCapturer")
            MicrophoneCapturer()
        } else {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)

            if (mediaProjection == null) {
                Log.w(TAG, "MediaProjection is null, falling back to microphone")
                sendStatusBroadcast(STATUS_AUDIO_FALLBACK, "录屏授权失败，自动切换到麦克风")
                MicrophoneCapturer()
            } else {
                SystemAudioCapturer()
            }
        }

        audioCapturer = capturer

        try {
            if (capturer is SystemAudioCapturer && mediaProjection != null) {
                capturer.startCapture(mediaProjection!!)
            } else {
                capturer.startCapture()
            }
            sendStatusBroadcast(STATUS_AUDIO_OK, "音频捕获已启动")
            Log.i(TAG, "Audio capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture failed, trying microphone fallback", e)
            sendStatusBroadcast(STATUS_AUDIO_FALLBACK, "系统音频失败，尝试麦克风: ${e.localizedMessage}")

            try {
                audioCapturer?.stopCapture()
                audioCapturer?.release()
            } catch (_: Exception) {}

            val micCapturer = MicrophoneCapturer()
            audioCapturer = micCapturer
            micCapturer.startCapture()
            sendStatusBroadcast(STATUS_AUDIO_OK, "已切换到麦克风模式")
            Log.i(TAG, "Switched to MicrophoneCapturer")
        }

        floatingView?.updateSubtitle(
            SubtitleLine(
                japaneseText = "",
                chineseText = "[正在加载语音识别...]",
                timestamp = System.currentTimeMillis(),
                isFinal = true
            )
        )

        val processor = withContext(Dispatchers.IO) {
            try {
                val engine = EngineFactory.createEngine(
                    context = this@FloatingSubtitleService,
                    provider = settings.speechProvider,
                    apiKey = settings.speechApiKey
                )

                if (engine is SenseVoiceEngine) {
                    Log.i(TAG, "Initializing SenseVoice...")
                    engine.initialize()
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
            sendStatusBroadcast(STATUS_ERROR, "语音识别引擎创建失败")
            return
        }

        speechProcessor = processor

        try {
            val flow = audioCapturer?.audioBufferFlow
            if (flow != null) {
                processor.startProcessing(flow)
                Log.i(TAG, "Speech processing started")
            } else {
                throw IllegalStateException("Audio capturer flow is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start processing", e)
            sendStatusBroadcast(STATUS_ERROR, "语音识别启动失败: ${e.localizedMessage}")
            return
        }

        scope.launch {
            processor.subtitleFlow.collectLatest { subtitle ->
                updateSubtitle(subtitle)
            }
        }

        isRunning = true
        sendStatusBroadcast(STATUS_RUNNING, "字幕服务运行中")
        Log.i(TAG, "Service started successfully")
    }

    private fun handleStop() {
        Log.i(TAG, "Stopping subtitle service...")
        sendStatusBroadcast(STATUS_STOPPED, "字幕服务已停止")

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

    fun updateDisplayMode(mode: DisplayMode) {
        floatingView?.setDisplayMode(mode)
    }

    fun updateSubtitleStyle(fontSize: Int, bgOpacity: Int) {
        floatingView?.setFontSize(fontSize)
        floatingView?.setBgOpacity(bgOpacity)
    }

    override fun onDestroy() {
        super.onDestroy()
        handleStop()
    }

    private fun sendStatusBroadcast(status: String, message: String) {
        try {
            sendBroadcast(Intent(ACTION_SERVICE_STATUS).apply {
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_STATUS_MESSAGE, message)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send status broadcast", e)
        }
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, FloatingSubtitleService::class.java).apply { action = ACTION_STOP },
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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(JSubApplication.CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    JSubApplication.CHANNEL_ID,
                    getString(R.string.service_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.service_notification_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}

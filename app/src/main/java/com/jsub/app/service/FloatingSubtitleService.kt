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
import com.jsub.app.speech.engine.SpeechRecognitionEngine
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
        const val STATUS_FLOATING_VIEW_FAIL = "floating_view_fail"
        const val STATUS_AUDIO_OK = "audio_ok"
        const val STATUS_AUDIO_FALLBACK = "audio_fallback"
        const val STATUS_MODEL_DOWNLOADING = "model_downloading"
        const val STATUS_MODEL_READY = "model_ready"
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
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingSubtitleService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private val binder = SubtitleBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var floatingView: FloatingSubtitleView? = null
    private var audioCapturer: AudioCapturer? = null
    private var speechProcessor: SpeechProcessor? = null
    private var settings: AppSettings = AppSettings()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class SubtitleBinder : Binder() {
        fun getService(): FloatingSubtitleService = this@FloatingSubtitleService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        try {
            startForeground(JSubApplication.NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "startForeground() succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground() FAILED", e)
            sendStatusBroadcast(STATUS_ERROR, "前台服务启动失败: ${e.localizedMessage}")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                sendStatusBroadcast(STATUS_STARTING, "服务启动中...")

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val useMicrophone = intent.getBooleanExtra(EXTRA_USE_MICROPHONE, false)

                scope.launch {
                    try {
                        doFullStart(resultCode, resultData, useMicrophone)
                    } catch (e: Exception) {
                        Log.e(TAG, "Fatal startup error", e)
                        sendStatusBroadcast(STATUS_ERROR, "启动失败: ${e.localizedMessage}")
                        cleanupAndStop()
                    }
                }
            }
            ACTION_STOP -> {
                sendStatusBroadcast(STATUS_STOPPED, "服务停止")
                cleanupAndStop()
            }
        }
        return START_STICKY
    }

    private suspend fun doFullStart(resultCode: Int, resultData: Intent?, useMicrophone: Boolean) {
        settings = loadSettings()
        Log.i(TAG, "Settings loaded")

        // Step 1: Show floating window immediately
        try {
            // Hide any existing floating view first (prevent duplicates)
            try {
                floatingView?.hide()
            } catch (_: Exception) {}

            floatingView = FloatingSubtitleView(this@FloatingSubtitleService).apply {
                setDisplayMode(settings.displayMode)
                setFontSize(settings.fontSize)
                setBgOpacity(settings.bgOpacity)
                show()
            }
            sendStatusBroadcast(STATUS_FLOATING_VIEW_OK, "悬浮窗已显示")
            Log.i(TAG, "Floating view ADDED")
        } catch (e: Exception) {
            Log.e(TAG, "Floating view FAILED", e)
            sendStatusBroadcast(STATUS_FLOATING_VIEW_FAIL, "悬浮窗显示失败: ${e.localizedMessage}")
        }

        // Show initial status
        floatingView?.updateSubtitle(
            SubtitleLine("", "[正在初始化音频...]", System.currentTimeMillis(), true)
        )

        // Step 2: Start audio capture
        val capturer: AudioCapturer = if (useMicrophone) {
            Log.i(TAG, "Using MicrophoneCapturer")
            MicrophoneCapturer()
        } else {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)

            if (mediaProjection == null) {
                Log.w(TAG, "MediaProjection null, fallback to microphone")
                sendStatusBroadcast(STATUS_AUDIO_FALLBACK, "录屏授权无效，切换麦克风")
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
            Log.e(TAG, "Audio capture failed, fallback", e)
            sendStatusBroadcast(STATUS_AUDIO_FALLBACK, "系统音频失败，尝试麦克风: ${e.localizedMessage}")
            try { audioCapturer?.stopCapture(); audioCapturer?.release() } catch (_: Exception) {}
            val mic = MicrophoneCapturer()
            audioCapturer = mic
            mic.startCapture()
            sendStatusBroadcast(STATUS_AUDIO_OK, "已切换到麦克风")
        }

        // Step 3: Create speech engine with model download progress
        floatingView?.updateSubtitle(
            SubtitleLine("", "[正在加载语音识别引擎...]", System.currentTimeMillis(), true)
        )

        val engine = withContext(Dispatchers.IO) {
            try {
                val eng = EngineFactory.createEngine(
                    context = this@FloatingSubtitleService,
                    provider = settings.speechProvider,
                    apiKey = settings.speechApiKey
                )

                // If using SenseVoice, initialize (may download 200MB model)
                if (eng is SenseVoiceEngine) {
                    sendStatusBroadcast(STATUS_MODEL_DOWNLOADING, "正在下载/加载SenseVoice模型 (~200MB)...")
                    val initSuccess = eng.initialize()
                    if (!initSuccess) {
                        Log.w(TAG, "SenseVoice init failed, falling back")
                        sendStatusBroadcast(STATUS_ERROR, "本地模型加载失败，尝试在线识别...")
                        null
                    } else {
                        sendStatusBroadcast(STATUS_MODEL_READY, "本地模型已就绪")
                        eng
                    }
                } else {
                    eng
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine creation failed", e)
                null
            }
        }

        // Fallback to online engine if local failed
        val finalEngine = engine ?: withContext(Dispatchers.IO) {
            try {
                sendStatusBroadcast(STATUS_MODEL_DOWNLOADING, "切换到在线识别引擎...")
                // Try AnimeWhisper online
                val fallback = EngineFactory.createEngine(
                    context = this@FloatingSubtitleService,
                    provider = com.jsub.app.model.SpeechProvider.ANIME_WHISPER,
                    apiKey = settings.speechApiKey
                )
                fallback
            } catch (e: Exception) {
                Log.e(TAG, "Fallback engine also failed", e)
                null
            }
        }

        if (finalEngine == null) {
            sendStatusBroadcast(STATUS_ERROR, "所有语音识别引擎均不可用")
            floatingView?.updateSubtitle(
                SubtitleLine("", "[引擎初始化失败]", System.currentTimeMillis(), true)
            )
            return
        }

        // Step 4: Create translation API and processor
        val translationApi: TranslationApi = when (settings.translationProvider) {
            TranslationProvider.GOOGLE_TRANSLATE -> GoogleTranslateApi(settings.translationApiKey)
            TranslationProvider.LIBRE_TRANSLATE -> LibreTranslateApi()
            TranslationProvider.DEEPSEEK -> DeepSeekTranslationApi(settings.translationApiKey)
            TranslationProvider.KIMI -> KimiTranslationApi(settings.translationApiKey)
        }

        val processor = StreamingSpeechProcessor(finalEngine, translationApi)
        speechProcessor = processor

        try {
            val flow = audioCapturer?.audioBufferFlow
            if (flow != null) {
                processor.startProcessing(flow)
                Log.i(TAG, "Speech processing started")
            } else {
                throw IllegalStateException("Audio flow is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Processing start failed", e)
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
        Log.i(TAG, "Service FULLY started")
    }

    private fun updateSubtitle(subtitle: SubtitleLine) {
        floatingView?.updateSubtitle(subtitle)
    }

    private fun cleanupAndStop() {
        scope.cancel()
        try { floatingView?.hide() } catch (_: Exception) {}
        floatingView = null
        try { speechProcessor?.stopProcessing() } catch (_: Exception) {}
        speechProcessor = null
        try { audioCapturer?.stopCapture(); audioCapturer?.release() } catch (_: Exception) {}
        audioCapturer = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        isRunning = false
        try { stopForeground(true) } catch (_: Exception) {}
        stopSelf()
    }

    private fun loadSettings(): AppSettings {
        return try {
            com.jsub.app.data.SettingsRepository(this).loadSettings()
        } catch (e: Exception) {
            Log.w(TAG, "Settings load failed", e)
            AppSettings()
        }
    }

    private fun sendStatusBroadcast(status: String, message: String) {
        try {
            sendBroadcast(Intent(ACTION_SERVICE_STATUS).apply {
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_STATUS_MESSAGE, message)
            })
            Log.d(TAG, "Broadcast: $status - $message")
        } catch (e: Exception) {
            Log.w(TAG, "Broadcast failed", e)
        }
    }

    private fun buildNotification(): Notification {
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
                    "字幕服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持字幕服务在前台运行"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
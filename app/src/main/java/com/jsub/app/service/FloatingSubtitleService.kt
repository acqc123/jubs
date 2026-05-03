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
import com.jsub.app.audio.AudioCapturer
import com.jsub.app.audio.SystemAudioCapturer
import com.jsub.app.model.AppSettings
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SubtitleLine
import com.jsub.app.model.TranslationProvider
import com.jsub.app.speech.SpeechProcessor
import com.jsub.app.speech.StreamingSpeechProcessor
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
        fun isRunning(): Boolean = isRunning
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        Log.i(TAG, "Starting subtitle service...")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Invalid MediaProjection data")
            stopSelf()
            return
        }

        // 启动前台服务
        startForeground(JSubApplication.NOTIFICATION_ID, buildNotification())

        // 初始化MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            stopSelf()
            return
        }

        // 加载设置
        settings = loadSettings()

        // 创建悬浮窗
        floatingView = FloatingSubtitleView(this).apply {
            setDisplayMode(settings.displayMode)
            setFontSize(settings.fontSize)
            setBgOpacity(settings.bgOpacity)
            show()
        }

        // 启动音频捕获
        val capturer = SystemAudioCapturer()
        audioCapturer = capturer
        capturer.startCapture(mediaProjection!!)

        // 启动语音处理
        val processor = StreamingSpeechProcessor.create(
            context = this,
            speechProvider = settings.speechProvider,
            speechApiKey = settings.speechApiKey,
            translationApiKey = settings.translationApiKey,
            translationProvider = settings.translationProvider
        )
        speechProcessor = processor
        processor.startProcessing(capturer.audioBufferFlow)

        // 收集字幕结果
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

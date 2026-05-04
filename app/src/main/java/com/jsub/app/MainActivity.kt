package com.jsub.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.jsub.app.service.FloatingSubtitleService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var btnToggleSubtitle: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var statusCard: MaterialCardView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var rgAudioSource: RadioGroup

    private var isServiceRunning = false
    private var useMicrophone = false
    private val handler = Handler(Looper.getMainLooper())

    // Prevent double-start from multiple onResume() calls
    private var hasDeferredStart = false
    private var deferredCode: Int? = null
    private var deferredData: Intent? = null
    private var deferredMic = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(FloatingSubtitleService.EXTRA_STATUS) ?: return
            val msg = intent.getStringExtra(FloatingSubtitleService.EXTRA_STATUS_MESSAGE) ?: ""
            Log.i(TAG, "Status: $status | $msg")

            runOnUiThread {
                tvStatusDetail.text = msg
                when (status) {
                    FloatingSubtitleService.STATUS_STARTING -> {
                        tvStatus.text = "正在启动..."
                        tvStatusDetail.text = "服务初始化中..."
                    }
                    FloatingSubtitleService.STATUS_FOREGROUND_OK -> {
                        tvStatus.text = "前台服务就绪"
                    }
                    FloatingSubtitleService.STATUS_FLOATING_VIEW_OK -> {
                        tvStatus.text = "悬浮窗已显示"
                        tvStatusDetail.text = msg
                    }
                    FloatingSubtitleService.STATUS_FLOATING_VIEW_FAIL -> {
                        tvStatus.text = "悬浮窗失败"
                        tvStatusDetail.text = msg
                        Toast.makeText(this@MainActivity, "⚠️ 悬浮窗失败: $msg", Toast.LENGTH_LONG).show()
                    }
                    FloatingSubtitleService.STATUS_AUDIO_OK -> {
                        tvStatus.text = "音频捕获正常"
                        tvStatusDetail.text = msg
                    }
                    FloatingSubtitleService.STATUS_AUDIO_FALLBACK -> {
                        tvStatus.text = "已切换麦克风"
                        tvStatusDetail.text = msg
                        Toast.makeText(this@MainActivity, "🔊 $msg", Toast.LENGTH_LONG).show()
                    }
                    FloatingSubtitleService.STATUS_RUNNING -> {
                        isServiceRunning = true
                        updateUIState(true)
                        Toast.makeText(this@MainActivity, "✅ 字幕服务运行中！", Toast.LENGTH_SHORT).show()
                    }
                    FloatingSubtitleService.STATUS_ERROR -> {
                        isServiceRunning = false
                        updateUIState(false)
                        tvStatus.text = "启动失败"
                        tvStatusDetail.text = msg
                        Toast.makeText(this@MainActivity, "❌ 错误: $msg", Toast.LENGTH_LONG).show()
                    }
                    FloatingSubtitleService.STATUS_STOPPED -> {
                        isServiceRunning = false
                        updateUIState(false)
                    }
                }
            }
        }
    }

    // MediaProjection launcher - only stores result, actual start in onResume()
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.i(TAG, "MediaProjection granted, queuing deferred start")
            hasDeferredStart = true
            deferredCode = result.resultCode
            deferredData = result.data
            deferredMic = false
            tvStatus.text = "等待启动..."
            tvStatusDetail.text = "请点击屏幕回到本APP"
        } else {
            Toast.makeText(this, "需要录屏权限才能捕获内部音频", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")

        // Always register receiver first
        registerStatusReceiver()

        // Update current state
        updateUIState(FloatingSubtitleService.isRunning)

        // Execute deferred start if queued
        if (hasDeferredStart) {
            hasDeferredStart = false
            val code = deferredCode!!
            val data = deferredData!!
            val mic = deferredMic
            deferredCode = null
            deferredData = null

            Log.i(TAG, "Executing deferred start (mic=$mic)")
            tvStatus.text = "正在启动服务..."
            tvStatusDetail.text = "请稍候..."

            handler.postDelayed({
                try {
                    FloatingSubtitleService.start(this, code, data, mic)
                } catch (e: Exception) {
                    Log.e(TAG, "Deferred start failed", e)
                    Toast.makeText(this, "启动失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    updateUIState(false)
                }
            }, 300) // 300ms delay to ensure Activity is fully foreground
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
        unregisterStatusReceiver()
    }

    private fun registerStatusReceiver() {
        try {
            unregisterStatusReceiver() // avoid duplicate registration
        } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    statusReceiver,
                    IntentFilter(FloatingSubtitleService.ACTION_SERVICE_STATUS),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(statusReceiver, IntentFilter(FloatingSubtitleService.ACTION_SERVICE_STATUS))
            }
            Log.d(TAG, "StatusReceiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver", e)
        }
    }

    private fun unregisterStatusReceiver() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    private fun initViews() {
        btnToggleSubtitle = findViewById(R.id.btnToggleSubtitle)
        btnSettings = findViewById(R.id.btnSettings)
        statusCard = findViewById(R.id.statusCard)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        rgAudioSource = findViewById(R.id.rgAudioSource)

        btnToggleSubtitle.setOnClickListener {
            if (isServiceRunning) stopSubtitleService()
            else startSubtitleService()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SubtitleSettingsActivity::class.java))
        }

        rgAudioSource.setOnCheckedChangeListener { _, checkedId ->
            useMicrophone = (checkedId == R.id.rbMicrophone)
        }
    }

    private fun startSubtitleService() {
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
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

        if (useMicrophone) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                tvStatus.text = "正在启动..."
                tvStatusDetail.text = "麦克风模式..."
                handler.post {
                    try {
                        FloatingSubtitleService.start(this, -1, Intent(), true)
                    } catch (e: Exception) {
                        Toast.makeText(this, "启动失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        updateUIState(false)
                    }
                }
            } else {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            try {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } catch (e: Exception) {
                Toast.makeText(this, "录屏启动失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopSubtitleService() {
        FloatingSubtitleService.stop(this)
        updateUIState(false)
        Toast.makeText(this, "字幕服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateUIState(running: Boolean) {
        isServiceRunning = running
        if (running) {
            btnToggleSubtitle.text = getString(R.string.stop_subtitle)
            btnToggleSubtitle.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            tvStatus.text = "服务运行中"
            tvStatusDetail.text = "正在实时识别和翻译"
            statusCard.strokeWidth = 2
            statusCard.strokeColor = ContextCompat.getColor(this, R.color.secondary_color)
        } else {
            btnToggleSubtitle.text = getString(R.string.start_subtitle)
            btnToggleSubtitle.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_color))
            tvStatus.text = "服务未运行"
            tvStatusDetail.text = "点击下方按钮开启实时字幕"
            statusCard.strokeWidth = 0
        }
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.overlay_permission_desc)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                overlayPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (Settings.canDrawOverlays(this)) startSubtitleService() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) startSubtitleService() }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) startSubtitleService() }
}
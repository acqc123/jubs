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

    // Store MediaProjection result for deferred service start
    private var pendingMediaProjectionCode: Int? = null
    private var pendingMediaProjectionData: Intent? = null
    private var pendingUseMicrophone = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(FloatingSubtitleService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(FloatingSubtitleService.EXTRA_STATUS_MESSAGE) ?: ""
            runOnUiThread {
                tvStatusDetail.text = message
                when (status) {
                    FloatingSubtitleService.STATUS_STARTING -> tvStatus.text = "正在启动..."
                    FloatingSubtitleService.STATUS_FOREGROUND_OK -> tvStatus.text = "服务初始化中..."
                    FloatingSubtitleService.STATUS_FLOATING_VIEW_OK -> tvStatus.text = "悬浮窗已就绪"
                    FloatingSubtitleService.STATUS_AUDIO_OK -> tvStatus.text = "音频捕获正常"
                    FloatingSubtitleService.STATUS_AUDIO_FALLBACK -> {
                        tvStatus.text = "已切换麦克风"
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                    FloatingSubtitleService.STATUS_RUNNING -> {
                        isServiceRunning = true
                        updateUIState(true)
                        Toast.makeText(this@MainActivity, "字幕服务运行中！", Toast.LENGTH_SHORT).show()
                    }
                    FloatingSubtitleService.STATUS_ERROR -> {
                        isServiceRunning = false
                        updateUIState(false)
                        tvStatus.text = "启动失败"
                        Toast.makeText(this@MainActivity, "错误: $message", Toast.LENGTH_LONG).show()
                    }
                    FloatingSubtitleService.STATUS_STOPPED -> {
                        isServiceRunning = false
                        updateUIState(false)
                    }
                }
            }
        }
    }

    // MediaProjection launcher - stores result, actual start happens in onResume
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            pendingMediaProjectionCode = result.resultCode
            pendingMediaProjectionData = result.data
            pendingUseMicrophone = false
            tvStatus.text = "等待启动..."
            tvStatusDetail.text = "请在设置中选择要共享的内容"
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
        updateUIState(FloatingSubtitleService.isRunning)

        // Register status receiver
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, IntentFilter(FloatingSubtitleService.ACTION_SERVICE_STATUS), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(statusReceiver, IntentFilter(FloatingSubtitleService.ACTION_SERVICE_STATUS))
            }
        } catch (_: Exception) {}

        // DEFERRED SERVICE START - critical fix for Android 12+ background start restriction
        // After MediaProjection dialog, Activity may briefly be in background.
        // We wait until onResume() to start the service, ensuring we're in foreground.
        if (pendingMediaProjectionCode != null && pendingMediaProjectionData != null) {
            val code = pendingMediaProjectionCode!!
            val data = pendingMediaProjectionData!!
            pendingMediaProjectionCode = null
            pendingMediaProjectionData = null

            tvStatus.text = "正在启动服务..."
            tvStatusDetail.text = "请稍候..."

            // Use Handler to ensure Activity is fully resumed before starting service
            Handler(Looper.getMainLooper()).post {
                try {
                    FloatingSubtitleService.start(this, code, data, useMicrophone = false)
                } catch (e: Exception) {
                    Log.e(TAG, "启动失败", e)
                    Toast.makeText(this, "启动失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    updateUIState(false)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
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
            if (isServiceRunning) {
                stopSubtitleService()
            } else {
                startSubtitleService()
            }
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
                Handler(Looper.getMainLooper()).post {
                    try {
                        FloatingSubtitleService.start(this, -1, Intent(), useMicrophone = true)
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
                Toast.makeText(this, "启动录屏失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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
            tvStatusDetail.text = "正在实时识别和翻译日语内容"
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
    ) {
        if (Settings.canDrawOverlays(this)) startSubtitleService()
        else Toast.makeText(this, "悬浮窗权限是显示字幕的必要权限", Toast.LENGTH_LONG).show()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) startSubtitleService() }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) startSubtitleService() }
}

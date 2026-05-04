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

    // Deferred start state
    private var hasDeferredStart = false
    private var deferredCode: Int = -1
    private var deferredData: Intent? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(FloatingSubtitleService.EXTRA_STATUS) ?: return
            val msg = intent.getStringExtra(FloatingSubtitleService.EXTRA_STATUS_MESSAGE) ?: ""
            runOnUiThread {
                tvStatusDetail.text = msg
                when (status) {
                    FloatingSubtitleService.STATUS_STARTING -> tvStatus.text = "正在启动..."
                    FloatingSubtitleService.STATUS_FLOATING_OK -> tvStatus.text = "悬浮窗已显示"
                    FloatingSubtitleService.STATUS_FLOATING_FAIL -> {
                        tvStatus.text = "悬浮窗失败"
                        Toast.makeText(this@MainActivity, "⚠️ $msg", Toast.LENGTH_LONG).show()
                    }
                    FloatingSubtitleService.STATUS_AUDIO_OK -> tvStatus.text = "音频就绪"
                    FloatingSubtitleService.STATUS_AUDIO_FAIL -> {
                        tvStatus.text = "音频有问题"
                        Toast.makeText(this@MainActivity, "🔊 $msg", Toast.LENGTH_LONG).show()
                    }
                    FloatingSubtitleService.STATUS_MODEL_DOWNLOADING -> tvStatus.text = "加载模型..."
                    FloatingSubtitleService.STATUS_MODEL_READY -> tvStatus.text = "模型就绪"
                    FloatingSubtitleService.STATUS_MODEL_FAIL -> tvStatus.text = "模型加载失败"
                    FloatingSubtitleService.STATUS_RUNNING -> {
                        isServiceRunning = true
                        updateUIState(true)
                        Toast.makeText(this@MainActivity, "✅ 字幕服务运行中", Toast.LENGTH_SHORT).show()
                    }
                    FloatingSubtitleService.STATUS_ERROR -> {
                        isServiceRunning = false
                        updateUIState(false)
                        tvStatus.text = "启动失败"
                        Toast.makeText(this@MainActivity, "❌ $msg", Toast.LENGTH_LONG).show()
                    }
                    FloatingSubtitleService.STATUS_STOPPED -> {
                        isServiceRunning = false
                        updateUIState(false)
                    }
                }
            }
        }
    }

    // MediaProjection step 1: authorization dialog
    private val projectionAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.i(TAG, "Step 1: Projection auth granted, launching step 2")
            // Android 12+ requires a second dialog to select capture target
            // We pass the result through to step 2
            launchProjectionStep2(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "录屏授权被拒绝，已自动切换麦克风模式", Toast.LENGTH_LONG).show()
            // Auto-fallback to microphone
            useMicrophone = true
            rgAudioSource.check(R.id.rbMicrophone)
            handler.postDelayed({ startSubtitleService() }, 500)
        }
    }

    // MediaProjection step 2: select capture target (Android 12+)
    // Note: This is handled by the system automatically after step 1 on some devices
    // The result from step 1 is sufficient on most devices
    private fun launchProjectionStep2(resultCode: Int, data: Intent) {
        // Store for deferred start in onResume
        hasDeferredStart = true
        deferredCode = resultCode
        deferredData = data
        tvStatus.text = "等待启动..."
        tvStatusDetail.text = "请点击确定/开始共享回到APP"
        Toast.makeText(this, "请点击确定回到本APP完成启动", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: hasDeferred=$hasDeferredStart, isRunning=${FloatingSubtitleService.isRunning}")

        registerReceiver()
        updateUIState(FloatingSubtitleService.isRunning)

        // Execute deferred service start
        if (hasDeferredStart && !FloatingSubtitleService.isRunning) {
            hasDeferredStart = false
            val code = deferredCode
            val data = deferredData
            deferredData = null

            Log.i(TAG, "Executing deferred start")
            tvStatus.text = "正在启动服务..."
            tvStatusDetail.text = "请稍候..."

            handler.postDelayed({
                try {
                    FloatingSubtitleService.start(this, code, data ?: Intent(), useMicrophone = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Deferred start failed", e)
                    Toast.makeText(this, "启动失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    updateUIState(false)
                }
            }, 800)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver()
    }

    private fun registerReceiver() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, IntentFilter(FloatingSubtitleService.ACTION_SERVICE_STATUS), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(statusReceiver, IntentFilter(FloatingSubtitleService.ACTION_SERVICE_STATUS))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Register receiver failed", e)
        }
    }

    private fun unregisterReceiver() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
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

        if (useMicrophone) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                tvStatus.text = "正在启动..."
                tvStatusDetail.text = "麦克风模式启动中..."
                handler.post {
                    try {
                        FloatingSubtitleService.start(this, -1, Intent(), true)
                    } catch (e: Exception) {
                        Toast.makeText(this, "启动失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        updateUIState(false)
                    }
                }
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            try {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionAuthLauncher.launch(projectionManager.createScreenCaptureIntent())
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

    private fun showOverlayDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.overlay_permission_desc)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startSubtitleService()
        else Toast.makeText(this, "悬浮窗权限是必要权限", Toast.LENGTH_LONG).show()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) startSubtitleService() }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) startSubtitleService() }
}
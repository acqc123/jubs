package com.jsub.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
import com.google.android.material.card.MaterialCardView
import com.jsub.app.service.FloatingSubtitleService

/**
 * 主Activity
 *
 * 应用入口界面，提供：
 * - 权限检查和申请
 * - 字幕服务启动/停止控制
 * - 跳转到设置页面
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private lateinit var btnToggleSubtitle: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var statusCard: MaterialCardView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView

    private var isServiceRunning = false

    /** MediaProjection权限申请回调 */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            FloatingSubtitleService.start(this, result.resultCode, result.data!!)
            updateUIState(true)
            Toast.makeText(this, "字幕服务已启动", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要录屏权限才能捕获音频", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissionsOnLaunch()
    }

    override fun onResume() {
        super.onResume()
        updateUIState(FloatingSubtitleService.isRunning)
    }

    private fun initViews() {
        btnToggleSubtitle = findViewById(R.id.btnToggleSubtitle)
        btnSettings = findViewById(R.id.btnSettings)
        statusCard = findViewById(R.id.statusCard)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)

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
    }

    /**
     * 启动字幕服务
     */
    private fun startSubtitleService() {
        // 1. 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
            return
        }

        // 2. 检查通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // 3. 请求MediaProjection（录屏权限）
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch media projection", e)
            Toast.makeText(this, "启动录屏失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 停止字幕服务
     */
    private fun stopSubtitleService() {
        FloatingSubtitleService.stop(this)
        updateUIState(false)
        Toast.makeText(this, "字幕服务已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 更新UI状态
     */
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

    /**
     * 显示悬浮窗权限引导对话框
     */
    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.overlay_permission_desc)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 悬浮窗权限申请回调 */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startSubtitleService()
        } else {
            Toast.makeText(this, "悬浮窗权限是显示字幕的必要权限", Toast.LENGTH_LONG).show()
        }
    }

    /** 通知权限申请回调 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSubtitleService()
        } else {
            Toast.makeText(this, "通知权限用于保持服务运行", Toast.LENGTH_LONG).show()
            // 继续尝试启动
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    /**
     * 启动时检查权限状态
     */
    private fun checkPermissionsOnLaunch() {
        val overlayGranted = Settings.canDrawOverlays(this)
        Log.d(TAG, "Overlay permission: $overlayGranted")
    }
}

package com.jsub.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import com.jsub.app.R
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SubtitleLine

/**
 * 悬浮字幕视图 - 简化版，确保可靠显示
 */
class FloatingSubtitleView(context: Context) {

    companion object {
        private const val TAG = "FloatingSubtitleView"
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rootView = LayoutInflater.from(context).inflate(R.layout.floating_subtitle_view, null)
    private val tvChinese: TextView = rootView.findViewById(R.id.tvChinese)
    private val tvJapanese: TextView = rootView.findViewById(R.id.tvJapanese)
    private val progressBar: ProgressBar = rootView.findViewById(R.id.progressIndicator)
    private val btnClose: TextView = rootView.findViewById(R.id.btnClose)
    private var isShowing = false

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = 0
        y = 150
    }

    init {
        btnClose.setOnClickListener { hide() }
    }

    fun show() {
        if (isShowing) return
        try {
            wm.addView(rootView, layoutParams)
            isShowing = true
            Log.i(TAG, "SHOW: floating view added to window manager")
        } catch (e: Exception) {
            Log.e(TAG, "SHOW FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            wm.removeView(rootView)
            isShowing = false
            Log.i(TAG, "HIDE: floating view removed")
        } catch (e: Exception) {
            Log.w(TAG, "HIDE error: ${e.message}")
        }
    }

    fun isVisible(): Boolean = isShowing

    fun updateSubtitle(subtitle: SubtitleLine) {
        tvChinese.text = subtitle.chineseText
        tvJapanese.text = subtitle.japaneseText
        tvJapanese.visibility = if (subtitle.japaneseText.isEmpty()) View.GONE else View.VISIBLE
        progressBar.visibility = if (subtitle.isFinal) View.GONE else View.VISIBLE
        // Show close button once we have real content
        if (subtitle.chineseText.isNotBlank() && !subtitle.chineseText.startsWith("[")) {
            btnClose.visibility = View.VISIBLE
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        tvJapanese.visibility = when (mode) {
            DisplayMode.BILINGUAL, DisplayMode.JAPANESE_ONLY -> View.VISIBLE
            DisplayMode.CHINESE_ONLY -> View.GONE
        }
    }

    fun setFontSize(sizeSp: Int) {
        tvChinese.textSize = sizeSp.toFloat()
        tvJapanese.textSize = (sizeSp - 2).toFloat()
    }

    fun setBgOpacity(percent: Int) {
        val alpha = (percent * 255 / 100).coerceIn(0, 255)
        rootView.background?.alpha = alpha
    }
}
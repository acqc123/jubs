package com.jsub.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.jsub.app.R
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SubtitleLine

/**
 * 悬浮字幕视图
 *
 * 系统级悬浮窗，显示实时翻译字幕。
 * 支持拖动、调整样式、显示模式切换。
 */
class FloatingSubtitleView(context: Context) {

    companion object {
        private const val TAG = "FloatingSubtitleView"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutInflater = LayoutInflater.from(context)

    private var rootView: View? = null
    private var tvJapanese: TextView? = null
    private var tvChinese: TextView? = null
    private var progressBar: ProgressBar? = null
    private var controlPanel: View? = null

    private var displayMode = DisplayMode.BILINGUAL
    private var isShowing = false

    // 拖动相关
    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = 50
        y = 1200 // 默认底部偏上位置
    }

    init {
        createView()
    }

    private fun createView() {
        rootView = layoutInflater.inflate(R.layout.floating_subtitle_view, null).apply {
            tvJapanese = findViewById(R.id.tvJapanese)
            tvChinese = findViewById(R.id.tvChinese)
            progressBar = findViewById(R.id.progressIndicator)
            controlPanel = findViewById(R.id.controlPanel)

            // 拖动支持
            setOnTouchListener { _, event ->
                handleTouch(event)
                true
            }

            // 控制面板按钮
            findViewById<View>(R.id.btnClose)?.setOnClickListener {
                hide()
            }

            findViewById<View>(R.id.btnMinimize)?.setOnClickListener {
                // 最小化：只显示一小条，点击展开
                toggleMinimize()
            }
        }
    }

    /**
     * 显示悬浮窗
     */
    fun show() {
        if (isShowing) return
        try {
            rootView?.let {
                windowManager.addView(it, layoutParams)
                isShowing = true
                Log.d(TAG, "Floating subtitle view shown")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating view", e)
        }
    }

    /**
     * 隐藏悬浮窗
     */
    fun hide() {
        if (!isShowing) return
        try {
            rootView?.let {
                windowManager.removeView(it)
                isShowing = false
                Log.d(TAG, "Floating subtitle view hidden")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide floating view", e)
        }
    }

    /**
     * 更新字幕内容
     */
    fun updateSubtitle(subtitle: SubtitleLine) {
        tvJapanese?.text = subtitle.japaneseText
        tvChinese?.text = subtitle.chineseText

        progressBar?.visibility = if (subtitle.isFinal) View.GONE else View.VISIBLE

        updateVisibilityByMode()
    }

    /**
     * 设置显示模式
     */
    fun setDisplayMode(mode: DisplayMode) {
        this.displayMode = mode
        updateVisibilityByMode()
    }

    /**
     * 设置字体大小
     */
    fun setFontSize(sizeSp: Int) {
        tvJapanese?.textSize = sizeSp - 1f
        tvChinese?.textSize = sizeSp.toFloat()
    }

    /**
     * 设置背景不透明度
     */
    fun setBgOpacity(percent: Int) {
        val alpha = (255 * percent / 100).coerceIn(0, 255)
        rootView?.findViewById<FrameLayout>(R.id.subtitleContainer)?.let { container ->
            (container.background as? GradientDrawable)?.alpha = alpha
        }
    }

    /**
     * 设置位置
     */
    fun setPosition(x: Int, y: Int) {
        layoutParams.x = x
        layoutParams.y = y
        if (isShowing) {
            windowManager.updateViewLayout(rootView, layoutParams)
        }
    }

    /**
     * 是否正在显示
     */
    fun isShowing(): Boolean = isShowing

    private fun updateVisibilityByMode() {
        when (displayMode) {
            DisplayMode.BILINGUAL -> {
                tvJapanese?.visibility = View.VISIBLE
                tvChinese?.visibility = View.VISIBLE
            }
            DisplayMode.CHINESE_ONLY -> {
                tvJapanese?.visibility = View.GONE
                tvChinese?.visibility = View.VISIBLE
            }
            DisplayMode.JAPANESE_ONLY -> {
                tvJapanese?.visibility = View.VISIBLE
                tvChinese?.visibility = View.GONE
            }
        }
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchStartX).toInt()
                val dy = (event.rawY - touchStartY).toInt()

                if (kotlin.math.abs(dx) > 15 || kotlin.math.abs(dy) > 15) {
                    isDragging = true
                }

                if (isDragging) {
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    rootView?.let {
                        windowManager.updateViewLayout(it, layoutParams)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // 点击显示/隐藏控制面板
                    toggleControlPanel()
                }
            }
        }
    }

    private fun toggleControlPanel() {
        controlPanel?.visibility = if (controlPanel?.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun toggleMinimize() {
        // 简化实现：隐藏日文行
        tvJapanese?.visibility = if (tvJapanese?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
}

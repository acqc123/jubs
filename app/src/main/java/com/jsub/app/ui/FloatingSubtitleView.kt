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
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import com.jsub.app.R
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SubtitleLine

class FloatingSubtitleView(context: Context) {

    companion object {
        private const val TAG = "FloatingSubtitleView"
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)

    private var rootView: View? = null
    private var tvChinese: TextView? = null
    private var tvJapanese: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnClose: ImageButton? = null
    private var isShowing = false

    // Drag state
    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var dragStartTime = 0L

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = 0
        y = 180
    }

    init {
        createView()
    }

    private fun createView() {
        rootView = inflater.inflate(R.layout.floating_subtitle_view, null).apply {
            tvChinese = findViewById(R.id.tvChinese)
            tvJapanese = findViewById(R.id.tvJapanese)
            progressBar = findViewById(R.id.progressIndicator)
            btnClose = findViewById(R.id.btnClose)

            btnClose?.setOnClickListener {
                hide()
            }

            setOnTouchListener { _, event ->
                handleTouch(event)
                true
            }
        }
    }

    @Throws(Exception::class)
    fun show() {
        if (isShowing) return
        val view = rootView ?: throw IllegalStateException("rootView is null")
        try {
            wm.addView(view, layoutParams)
            isShowing = true
            Log.i(TAG, "Floating view ADDED")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "View already added?", e)
            throw e
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "BadToken - overlay permission denied?", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add view", e)
            throw e
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            rootView?.let { wm.removeView(it) }
            isShowing = false
            Log.i(TAG, "Floating view REMOVED")
        } catch (e: Exception) {
            Log.w(TAG, "Error hiding view", e)
        }
    }

    fun isShowing(): Boolean = isShowing

    fun updateSubtitle(subtitle: SubtitleLine) {
        tvChinese?.text = subtitle.chineseText
        tvJapanese?.text = subtitle.japaneseText
        tvJapanese?.visibility = if (subtitle.japaneseText.isEmpty()) View.GONE else View.VISIBLE
        progressBar?.visibility = if (subtitle.isFinal) View.GONE else View.VISIBLE

        // Show close button after first real subtitle
        if (subtitle.chineseText.isNotBlank() && !subtitle.chineseText.startsWith("[")) {
            btnClose?.visibility = View.VISIBLE
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        when (mode) {
            DisplayMode.BILINGUAL -> {
                tvJapanese?.visibility = View.VISIBLE
            }
            DisplayMode.CHINESE_ONLY -> {
                tvJapanese?.visibility = View.GONE
            }
            DisplayMode.JAPANESE_ONLY -> {
                tvJapanese?.visibility = View.VISIBLE
            }
        }
    }

    fun setFontSize(sizeSp: Int) {
        tvChinese?.textSize = sizeSp.toFloat()
        tvJapanese?.textSize = (sizeSp - 2).toFloat()
    }

    fun setBgOpacity(percent: Int) {
        val alpha = (255 * percent / 100).coerceIn(0, 255)
        rootView?.findViewById<FrameLayout>(R.id.subtitleContainer)?.let { container ->
            (container.background as? GradientDrawable)?.alpha = alpha
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
                dragStartTime = System.currentTimeMillis()
                btnClose?.visibility = View.VISIBLE // Show close button on touch
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchStartX).toInt()
                val dy = (event.rawY - touchStartY).toInt()
                if (kotlin.math.abs(dx) > 15 || kotlin.math.abs(dy) > 15) isDragging = true
                if (isDragging) {
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY - dy
                    rootView?.let { wm.updateViewLayout(it, layoutParams) }
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - dragStartTime
                if (!isDragging && duration < 200) {
                    // Quick tap: toggle close button visibility
                    btnClose?.visibility = if (btnClose?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
        }
    }
}
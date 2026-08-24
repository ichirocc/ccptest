package com.ichirocc.intervalbubblecamera.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import com.ichirocc.intervalbubblecamera.MainActivity
import com.ichirocc.intervalbubblecamera.R
import kotlin.math.abs

class BubbleOverlay(
    private val context: Context,
    @get:DrawableRes private val iconRes: Int,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val bubbleSize = 72.dp
    private val layoutParams = WindowManager.LayoutParams(
        bubbleSize,
        bubbleSize,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = context.resources.displayMetrics.widthPixels - bubbleSize - 16.dp
        y = 180.dp
    }

    private val bubbleView = createBubbleView()
    private var showing = false

    fun show(): Boolean {
        if (showing) return true
        if (!Settings.canDrawOverlays(context)) return false

        return runCatching {
            windowManager.addView(bubbleView, layoutParams)
            showing = true
            true
        }.getOrDefault(false)
    }

    fun hide() {
        if (!showing) return
        runCatching { windowManager.removeView(bubbleView) }
        showing = false
    }

    fun updateIcon(@DrawableRes iconRes: Int) {
        bubbleView.setBackgroundResource(iconRes)
    }

    private fun createBubbleView(): View {
        val root = FrameLayout(context).apply {
            setBackgroundResource(iconRes)
            elevation = 10.dp.toFloat()
            contentDescription = context.getString(R.string.bubble_description)
            isClickable = true
            isFocusable = true
            setOnClickListener { openApp() }
        }

        attachDragGesture(root)
        return root
    }

    private fun attachDragGesture(view: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) moved = true

                    val width = context.resources.displayMetrics.widthPixels
                    val height = context.resources.displayMetrics.heightPixels
                    layoutParams.x = (initialX + deltaX).coerceIn(0, (width - bubbleSize).coerceAtLeast(0))
                    layoutParams.y = (initialY + deltaY).coerceIn(0, (height - bubbleSize).coerceAtLeast(0))
                    if (showing) runCatching { windowManager.updateViewLayout(view, layoutParams) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) touchedView.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun openApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()
}

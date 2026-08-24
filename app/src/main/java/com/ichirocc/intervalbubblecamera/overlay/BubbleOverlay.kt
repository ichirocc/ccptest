package com.ichirocc.intervalbubblecamera.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.ichirocc.intervalbubblecamera.MainActivity
import com.ichirocc.intervalbubblecamera.R
import kotlin.math.abs

class BubbleOverlay(private val context: Context) {
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

    private val countBadge = TextView(context)
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

    fun updateCount(count: Int) {
        countBadge.text = if (count > 99) "99+" else count.toString()
    }

    fun hide() {
        if (!showing) return
        runCatching { windowManager.removeView(bubbleView) }
        showing = false
    }

    private fun createBubbleView(): View {
        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.primary))
                setStroke(3.dp, Color.WHITE)
            }
            elevation = 10.dp.toFloat()
            contentDescription = context.getString(R.string.bubble_description)
            isClickable = true
            isFocusable = true
            setOnClickListener { openApp() }
        }

        val cameraIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_camera)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = null
        }
        root.addView(
            cameraIcon,
            FrameLayout.LayoutParams(42.dp, 42.dp, Gravity.CENTER),
        )

        countBadge.apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
            minWidth = 24.dp
            minHeight = 24.dp
            setPadding(5.dp, 0, 5.dp, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.danger))
                setStroke(2.dp, Color.WHITE)
            }
            contentDescription = context.getString(R.string.bubble_count_description)
        }
        root.addView(
            countBadge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                24.dp,
                Gravity.END or Gravity.BOTTOM,
            ),
        )

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

package com.jeremy.dashcam.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.jeremy.dashcam.R
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Round draggable button drawn over other apps while drive mode is on and Jeremy is in background.
 * Tap  → start event / stop & save event.   Long-press → turn drive mode fully off.
 * Owned by DashcamService, so it lives exactly as long as the drive and never vanishes on app switches.
 */
class FloatingButton(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: BubbleView? = null
    private var lastX: Int? = null
    private var lastY: Int? = null

    val isShowing get() = view != null

    fun canShow() = Settings.canDrawOverlays(context)

    fun show(eventActive: Boolean) {
        if (view != null) { setEventActive(eventActive); return }
        if (!canShow()) return
        val density = context.resources.displayMetrics.density
        val size = (68 * density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX ?: (context.resources.displayMetrics.widthPixels - size - (12 * density).toInt())
            y = lastY ?: (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        }
        val v = BubbleView(context).apply { this.eventActive = eventActive }
        attachTouch(v, lp)
        runCatching { wm.addView(v, lp) }.onSuccess { view = v }
    }

    fun setEventActive(active: Boolean) {
        view?.let { it.eventActive = active; it.invalidate() }
    }

    fun hide() {
        val v = view ?: return
        v.stopPulse()
        runCatching { wm.removeView(v) }
        view = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(v: BubbleView, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong() + 150
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longFired = false
        val longPress = Runnable {
            if (!dragging) {
                longFired = true
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPress()
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false; longFired = false
                    v.held = true; v.invalidate()
                    v.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; v.removeCallbacks(longPress) }
                    if (dragging) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        lastX = lp.x; lastY = lp.y
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    v.held = false; v.invalidate()
                    if (e.actionMasked == MotionEvent.ACTION_UP && !dragging && !longFired &&
                        abs(e.rawX - downX) < slop && abs(e.rawY - downY) < slop
                    ) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onTap()
                    }
                    if (dragging) snapToEdge(v, lp)
                }
            }
            true
        }
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams) {
        val w = context.resources.displayMetrics.widthPixels
        val margin = (8 * context.resources.displayMetrics.density).toInt()
        val target = if (lp.x + v.width / 2 < w / 2) margin else w - v.width - margin
        ValueAnimator.ofInt(lp.x, target).apply {
            duration = 180
            addUpdateListener {
                lp.x = it.animatedValue as Int; lastX = lp.x
                if (v.isAttachedToWindow) runCatching { wm.updateViewLayout(v, lp) }
            }
        }.start()
    }

    private class BubbleView(context: Context) : View(context) {
        var eventActive = false
            set(value) { field = value; if (value) startPulse() else stopPulse() }
        var held = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val camIcon: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_videocam)!!.mutate()
        private val stopIcon: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_stop)!!.mutate()
        private var pulse = 0f
        private var animator: ValueAnimator? = null

        fun startPulse() {
            if (animator != null) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stopPulse() { animator?.cancel(); animator = null; pulse = 0f }

        override fun onDetachedFromWindow() { stopPulse(); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = width / 2f - 4f
            val base = if (eventActive) Color.rgb(229, 57, 53) else Color.rgb(34, 197, 94)
            glow.color = base; glow.alpha = (60 + 70 * pulse).toInt()
            canvas.drawCircle(cx, cy, r, glow)
            fill.color = if (held) darken(base) else base
            canvas.drawCircle(cx, cy, r * 0.84f, fill)
            ring.color = Color.WHITE; ring.alpha = 220; ring.strokeWidth = r * 0.07f
            canvas.drawCircle(cx, cy, r * 0.84f, ring)
            val icon = if (eventActive) stopIcon else camIcon
            val s = (r * 0.95f).toInt()
            icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            icon.draw(canvas)
        }

        private fun darken(c: Int) = Color.rgb((Color.red(c) * 0.8f).toInt(), (Color.green(c) * 0.8f).toInt(), (Color.blue(c) * 0.8f).toInt())
    }
}

package com.shomer.bodycam.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shomer.bodycam.R
import com.shomer.bodycam.core.Haptics

/**
 * Man-down: after a detected fall, a full-screen countdown is shown over every app, the phone beeps and
 * vibrates every second, and recording is already running. "I'm OK" cancels; otherwise the emergency
 * contact is called automatically.
 */
class ManDownOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var remaining = 0
    private var tone: ToneGenerator? = null

    val isShowing get() = root != null

    fun show(seconds: Int, name: String, number: String, test: Boolean, onOk: () -> Unit = {}, onCall: () -> Unit) {
        if (root != null) return
        if (!Settings.canDrawOverlays(context)) { if (!test) onCall(); return }
        remaining = seconds
        val d = context.resources.displayMetrics.density
        fun btn(text: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(context).apply {
            this.text = text; textSize = 22f; setTextColor(fg); isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(bg); cornerRadius = 18 * d }
            setPadding(0, (20 * d).toInt(), 0, (20 * d).toInt())
            setOnClickListener { onClick() }
        }
        val title = TextView(context).apply {
            text = context.getString(if (test) R.string.mandown_test_title else R.string.mandown_title)
            textSize = 30f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        val count = TextView(context).apply { textSize = 104f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        val who = listOf(name, number).filter { it.isNotBlank() }.joinToString("  ")
        val sub = TextView(context).apply {
            text = (if (test) context.getString(R.string.mandown_test_note) + "\n" else "") +
                context.getString(R.string.mandown_body, who)
            textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(140, 18, 18), Color.rgb(60, 8, 8)))
            val pad = (28 * d).toInt(); setPadding(pad, pad, pad, pad)
            addView(title); addView(count); addView(sub)
            val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (18 * d).toInt() } }
            addView(btn(context.getString(R.string.mandown_ok), Color.rgb(255, 176, 32), Color.rgb(30, 18, 0)) { dismiss(); onOk() }, lp())
            addView(btn(context.getString(R.string.mandown_call_now), Color.argb(90, 255, 255, 255), Color.WHITE) { dismiss(); if (!test) onCall() }, lp())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(layout, params) }.onFailure { if (!test) onCall(); return }
        root = layout
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()
        val tick = object : Runnable {
            override fun run() {
                if (root == null) return
                count.text = remaining.toString()
                if (remaining <= 0) { dismiss(); if (!test) onCall(); return }
                runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400) }
                Haptics.play(context, Haptics.Pattern.STOPPED, force = true)
                remaining--
                main.postDelayed(this, 1000)
            }
        }
        tick.run()
    }

    fun dismiss() {
        main.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        tone?.release(); tone = null
    }

    companion object {
        /** Places the call (needs CALL_PHONE). Falls back to opening the dialer with the number filled in. */
        fun call(context: Context, number: String) {
            val uri = Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })
            val direct = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(direct) }.onFailure {
                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}

package com.jeremy.dashcam.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PRO: after a strong impact, a full-screen countdown is shown over every app.
 * "I'm OK" cancels; otherwise the emergency number is dialled automatically.
 */
class EmergencyOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var remaining = 0

    val isShowing get() = root != null

    fun show(seconds: Int, name: String, number: String, test: Boolean, onSpeak: (String) -> Unit, onCall: () -> Unit) {
        if (root != null || !Settings.canDrawOverlays(context)) { if (!test) onCall(); return }
        remaining = seconds
        val d = context.resources.displayMetrics.density
        fun btn(text: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(context).apply {
            this.text = text; textSize = 22f; setTextColor(fg); isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(bg); cornerRadius = 40 * d }
            setPadding(0, (18 * d).toInt(), 0, (18 * d).toInt())
            setOnClickListener { onClick() }
        }
        val title = TextView(context).apply {
            text = if (test) "בדיקת התראת חירום" else "זוהתה מכה חזקה"
            textSize = 30f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        val count = TextView(context).apply { textSize = 96f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        val sub = TextView(context).apply {
            text = (if (test) "בבדיקה לא יתבצע חיוג.\n" else "") + "אם לא תלחץ \"אני בסדר\" נחייג אל\n$name  $number"
            textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(240, 183, 28, 28))
            val pad = (28 * d).toInt(); setPadding(pad, pad, pad, pad)
            addView(title); addView(count); addView(sub)
            val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (18 * d).toInt() } }
            addView(btn("אני בסדר", Color.WHITE, Color.rgb(183, 28, 28)) { dismiss() }, lp())
            addView(btn("התקשר עכשיו", Color.rgb(120, 0, 0), Color.WHITE) { dismiss(); if (!test) onCall() }, lp())
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
        onSpeak(if (test) "בדיקת התראת חירום" else "זוהתה מכה חזקה. אם אתה בסדר, לחץ אני בסדר. אחרת נחייג לאיש הקשר לחירום.")
        val tick = object : Runnable {
            override fun run() {
                if (root == null) return
                count.text = remaining.toString()
                if (remaining <= 0) { dismiss(); if (!test) onCall(); return }
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

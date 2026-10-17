package com.jeremy.monitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * One screen: Start logging, drive with Jeremy + Waze, Stop, then Share the CSV.
 * A separate, throwaway tool — it only reads device stats, never touches Jeremy.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var stats: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var shareBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#071210"))
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        fun title(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#5BE3A6")); textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fun body(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#9FB0A8")); textSize = 14f
            setPadding(0, dp(8), 0, dp(14))
        }

        root.addView(title("Jeremy Monitor"))
        root.addView(body(
            "מדידת חום וסוללה לאורך זמן.\n\n" +
            "1. לחץ \"התחל מדידה\".\n" +
            "2. עבור ל-Jeremy, הפעל נסיעה, ופתח את Waze.\n" +
            "3. סע 20–30 דקות (עדיף מחובר למטען, כמו ברכב).\n" +
            "4. חזור לכאן, לחץ \"עצור\", ואז \"שתף CSV\" ושלח לי את הקובץ."
        ))

        stats = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.parseColor("#15221C"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(stats, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })

        startBtn = button("▶  התחל מדידה", "#27C07A") { startLogging() }
        stopBtn = button("■  עצור", "#E2574C") { stopLogging() }
        shareBtn = button("↗  שתף CSV", "#2B6EF2") { shareCsv() }
        root.addView(startBtn); root.addView(stopBtn); root.addView(shareBtn)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshButtons()
        tick.run()
    }

    private fun button(label: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 17f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(colorHex))
        setOnClickListener { onClick() }
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)).also {
            it.topMargin = dp(8); layoutParams = it
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = MonitorBus.latest
            stats.text = if (s == null) {
                if (MonitorBus.running) "מתחיל…" else "לא פעיל.\nלחץ \"התחל מדידה\"."
            } else {
                val mm = s.elapsedS / 60; val ss = s.elapsedS % 60
                buildString {
                    append("זמן:        %d:%02d\n".format(mm, ss))
                    append("סוללה:      ${s.batteryPct}%  ${if (s.charging) "(בטעינה ⚡)" else "(פורק ↓)"}\n")
                    append("טמפ' סוללה: ${"%.1f".format(Locale.US, s.battTempC)}°C  (שיא ${"%.1f".format(Locale.US, MonitorBus.peakBattTempC)}°)\n")
                    s.cpuTempC?.let { append("טמפ' מעבד:  ${"%.1f".format(Locale.US, it)}°C  (שיא ${"%.1f".format(Locale.US, MonitorBus.peakCpuTempC)}°)\n") }
                    append("זרם:        ${s.currentMa} mA\n")
                    s.drainPctPerHour?.let { append("קצב סוללה:  ${"%+.1f".format(Locale.US, it)} %/שעה\n") }
                    append("זיכרון פנוי: ${s.availMemMb}/${s.totalMemMb} MB${if (s.lowMemory) "  ⚠ נמוך" else ""}")
                }
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun startLogging() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val i = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        refreshButtons()
    }

    private fun stopLogging() {
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
        refreshButtons()
    }

    private fun shareCsv() {
        val path = MonitorBus.csvPath
        if (path == null) { toast("אין עדיין קובץ. התחל מדידה קודם."); return }
        val f = File(path)
        if (!f.exists()) { toast("הקובץ לא נמצא."); return }
        val uri = FileProvider.getUriForFile(this, "com.jeremy.monitor.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "שתף CSV"))
    }

    private fun refreshButtons() {
        val on = MonitorBus.running
        startBtn.isEnabled = !on
        stopBtn.isEnabled = on
        startBtn.alpha = if (on) 0.4f else 1f
        stopBtn.alpha = if (on) 1f else 0.4f
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()

    override fun onResume() { super.onResume(); refreshButtons() }
    override fun onDestroy() { ui.removeCallbacks(tick); super.onDestroy() }
}

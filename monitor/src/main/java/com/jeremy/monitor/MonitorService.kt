package com.jeremy.monitor

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Samples device load every few seconds and appends it to a CSV, running as a foreground service so it
 * keeps logging after the user switches to Jeremy + Waze. Nothing here touches the Jeremy app.
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_STOP = "com.jeremy.monitor.STOP"
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 42
        private const val PERIOD_MS = 2000L
        private const val TAG = "JeremyMonitor"
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var startElapsed = 0L
    private var startWall = 0L
    private var csv: File? = null
    private var startPct = -1
    private var startPctAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        if (MonitorBus.running) return START_STICKY

        createChannel()
        startForeground(NOTIF_ID, buildNotification("מתחיל…"))

        startElapsed = System.currentTimeMillis()
        startWall = startElapsed
        MonitorBus.running = true
        MonitorBus.peakBattTempC = 0f
        MonitorBus.peakCpuTempC = 0f

        val name = "jeremy_monitor_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        csv = File(getExternalFilesDir(null), name).also {
            runCatching {
                it.appendText("ts_iso,elapsed_s,battery_pct,batt_temp_c,charging,current_mA,avail_mem_mb,total_mem_mb,low_memory,cpu_temp_c\n")
            }
        }
        MonitorBus.csvPath = csv?.absolutePath

        thread = HandlerThread("monitor-sampler").apply { start() }
        handler = Handler(thread.looper)
        handler.post(sampler)
        return START_STICKY
    }

    private val sampler = object : Runnable {
        override fun run() {
            runCatching { sampleOnce() }.onFailure { Log.w(TAG, "sample failed", it) }
            if (MonitorBus.running) handler.postDelayed(this, PERIOD_MS)
        }
    }

    private fun sampleOnce() {
        val now = System.currentTimeMillis()
        val elapsedS = (now - startElapsed) / 1000

        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1).let { if (it > 0) it / 10f else -1f }
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        var pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct !in 0..100) {
            val lvl = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (lvl >= 0 && scale > 0) pct = lvl * 100 / scale
        }
        val currentUa = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0)
        val currentMa = currentUa / 1000

        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val availMb = (mi.availMem / (1024 * 1024)).toInt()
        val totalMb = (mi.totalMem / (1024 * 1024)).toInt()

        val cpuTemp = readCpuTemp()

        if (tempC > MonitorBus.peakBattTempC) MonitorBus.peakBattTempC = tempC
        if (cpuTemp != null && cpuTemp > MonitorBus.peakCpuTempC) MonitorBus.peakCpuTempC = cpuTemp

        // drain rate from the first reading to now
        if (startPct < 0 && pct in 0..100) { startPct = pct; startPctAt = now }
        val drain: Float? = if (startPct in 0..100 && now - startPctAt > 60_000) {
            (pct - startPct) * 3_600_000f / (now - startPctAt) // %/hour
        } else null

        MonitorBus.latest = MonitorBus.Sample(
            elapsedS = elapsedS, batteryPct = pct, battTempC = tempC, charging = charging,
            currentMa = currentMa, availMemMb = availMb, totalMemMb = totalMb,
            lowMemory = mi.lowMemory, cpuTempC = cpuTemp, drainPctPerHour = drain,
        )

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(now))
        runCatching {
            csv?.appendText("$iso,$elapsedS,$pct,${fmt(tempC)},$charging,$currentMa,$availMb,$totalMb,${mi.lowMemory},${cpuTemp?.let { fmt(it) } ?: ""}\n")
        }

        val note = "🌡 ${fmt(tempC)}°  🔋 $pct%  ${if (charging) "⚡" else "↓"}  ${currentMa}mA"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(note))
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.1f", v)

    /** Best-effort CPU/SoC temperature from the thermal zones (not every device exposes it). */
    private fun readCpuTemp(): Float? {
        var best: Float? = null
        runCatching {
            val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
            for (z in zones) {
                val raw = runCatching { File(z, "temp").readText().trim().toFloat() }.getOrNull() ?: continue
                var c = raw
                if (c > 1000) c /= 1000f   // some zones report milli-°C
                if (c in 1f..150f && (best == null || c > best!!)) best = c
            }
        }
        return best
    }

    private fun stopEverything() {
        MonitorBus.running = false
        runCatching { if (::handler.isInitialized) handler.removeCallbacksAndMessages(null) }
        runCatching { if (::thread.isInitialized) thread.quitSafely() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        MonitorBus.running = false
        runCatching { if (::thread.isInitialized) thread.quitSafely() }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Jeremy Monitor", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("מדידת ביצועים פעילה")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }
}

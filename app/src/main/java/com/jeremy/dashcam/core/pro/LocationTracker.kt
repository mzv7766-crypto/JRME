package com.jeremy.dashcam.core.pro

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/** PRO: GPS speed + position, kept as a time series so exports can burn the value of every moment. */
object LocationTracker {
    data class Fix(val wallMs: Long, val lat: Double, val lon: Double, val speedKmh: Float?, val accuracy: Float)

    val latest = MutableStateFlow<Fix?>(null)
    private val history = ArrayDeque<Fix>()
    private var lm: LocationManager? = null
    private const val KEEP_MS = 3 * 60 * 60 * 1000L

    private val listener = LocationListener { loc -> onLocation(loc) }

    @SuppressLint("MissingPermission")
    fun start(c: Context) {
        if (lm != null) return
        val m = c.getSystemService(LocationManager::class.java) ?: return
        lm = m
        runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }
        runCatching { m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, listener, Looper.getMainLooper()) }
    }

    fun stop() {
        lm?.let { runCatching { it.removeUpdates(listener) } }
        lm = null
        latest.value = null
    }

    private fun onLocation(l: Location) {
        // prefer GPS; ignore network fixes when a recent GPS fix exists
        val prev = latest.value
        if (l.provider != LocationManager.GPS_PROVIDER && prev != null && System.currentTimeMillis() - prev.wallMs < 5000 && prev.accuracy < 30) return
        val f = Fix(System.currentTimeMillis(), l.latitude, l.longitude, if (l.hasSpeed()) l.speed * 3.6f else null, if (l.hasAccuracy()) l.accuracy else 999f)
        latest.value = f
        synchronized(history) {
            history.addLast(f)
            while (history.isNotEmpty() && f.wallMs - history.first.wallMs > KEEP_MS) history.removeFirst()
        }
    }

    /** Nearest fix to [wallMs] (within 10 s), or null. */
    fun at(wallMs: Long): Fix? = synchronized(history) {
        var best: Fix? = null
        for (f in history) if (best == null || abs(f.wallMs - wallMs) < abs(best.wallMs - wallMs)) best = f
        best?.takeIf { abs(it.wallMs - wallMs) <= 10_000 }
    }

    /** Text burned into the video, e.g. "87 קמ״ש  •  31.77612, 35.21371". */
    fun label(f: Fix): String {
        val sp = f.speedKmh?.let { "${it.toInt()} קמ״ש  •  " } ?: ""
        return sp + String.format(Locale.US, "%.5f, %.5f", f.lat, f.lon)
    }
}

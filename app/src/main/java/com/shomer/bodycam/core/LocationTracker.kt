package com.shomer.bodycam.core

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

/** GPS position kept as a time series, so exports can burn where every moment was filmed. */
object LocationTracker {
    data class Fix(val wallMs: Long, val lat: Double, val lon: Double, val speedKmh: Float?, val accuracy: Float)

    val latest = MutableStateFlow<Fix?>(null)
    private val history = ArrayDeque<Fix>()
    private var lm: LocationManager? = null
    private const val KEEP_MS = 3 * 60 * 60 * 1000L

    private val listener = LocationListener { loc -> onLocation(loc) }
    private var recording = false
    private var eco = false
    private var appliedMode: String? = null

    /**
     * GPS is one of the biggest battery users. Every second only while an incident is being recorded
     * (each moment is stamped with where it was filmed); between incidents every 10 s, or 30 s when saving power.
     */
    @SuppressLint("MissingPermission")
    private fun apply() {
        val m = lm ?: return
        val (gpsMs, gpsM, netMs) = when {
            recording -> Triple(1_000L, 0f, 5_000L)
            eco -> Triple(30_000L, 15f, 60_000L)
            else -> Triple(10_000L, 5f, 30_000L)
        }
        val mode = "$gpsMs/$gpsM/$netMs"
        if (mode == appliedMode) return
        appliedMode = mode
        runCatching { m.removeUpdates(listener) }
        runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, gpsMs, gpsM, listener, Looper.getMainLooper()) }
        runCatching { m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, netMs, 0f, listener, Looper.getMainLooper()) }
    }

    fun start(c: Context) {
        if (lm != null) return
        lm = c.getSystemService(LocationManager::class.java) ?: return
        appliedMode = null
        apply()
    }

    fun setRecording(on: Boolean) { recording = on; apply() }
    fun setEco(on: Boolean) { eco = on; apply() }

    fun stop() {
        lm?.let { runCatching { it.removeUpdates(listener) } }
        lm = null; appliedMode = null; recording = false
        latest.value = null
    }

    private fun onLocation(l: Location) {
        // prefer GPS; ignore network fixes when a recent GPS fix exists
        val prev = latest.value
        if (l.provider != LocationManager.GPS_PROVIDER && prev != null && System.currentTimeMillis() - prev.wallMs < 15_000 && prev.accuracy < 30) return
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

    /** Text burned into the video, e.g. "31.77612, 35.21371  ±8m". */
    fun label(f: Fix): String =
        String.format(Locale.US, "%.5f, %.5f", f.lat, f.lon) + if (f.accuracy < 500) "  ±${f.accuracy.toInt()}m" else ""
}

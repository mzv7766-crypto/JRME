package com.shomer.bodycam.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches phone temperature and battery while on shift. When the phone runs hot or the battery is low
 * (or the user turned on power saving) the camera service switches to [eco]: 720p, at most 30 FPS,
 * full-shift clips without the burned-in stamp, slower GPS and a lighter live picture.
 * Incidents are never interrupted – the change applies between recordings.
 */
object PowerGuard {
    enum class Reason { NONE, SAVER, BATTERY, HEAT }

    private val _hot = MutableStateFlow(false)
    val hot: StateFlow<Boolean> = _hot.asStateFlow()
    private val _lowBattery = MutableStateFlow(false)
    val lowBattery: StateFlow<Boolean> = _lowBattery.asStateFlow()

    private const val LOW_PCT = 15
    private const val OK_PCT = 25
    private var app: Context? = null
    private var thermal: Any? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onBattery(i)
    }

    fun start(c: Context) {
        if (app != null) return
        val ctx = c.applicationContext
        app = ctx
        ContextCompat.registerReceiver(ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { onBattery(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val l = PowerManager.OnThermalStatusChangedListener { st -> onThermal(st) }
            runCatching { pm.addThermalStatusListener(ContextCompat.getMainExecutor(ctx), l); thermal = l; onThermal(pm.currentThermalStatus) }
        }
    }

    fun stop() {
        val ctx = app ?: return
        runCatching { ctx.unregisterReceiver(batteryReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (thermal as? PowerManager.OnThermalStatusChangedListener)?.let { l ->
                runCatching { ctx.getSystemService(PowerManager::class.java).removeThermalStatusListener(l) }
            }
        }
        thermal = null; app = null
        _hot.value = false; _lowBattery.value = false
    }

    /** Hysteresis: hot from SEVERE, cool again only at LIGHT or below. */
    private fun onThermal(st: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (st >= PowerManager.THERMAL_STATUS_SEVERE) _hot.value = true
        else if (st <= PowerManager.THERMAL_STATUS_LIGHT) _hot.value = false
    }

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100 / scale
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        _lowBattery.value = when {
            plugged -> false
            pct <= LOW_PCT -> true
            pct >= OK_PCT -> false
            else -> _lowBattery.value
        }
    }

    fun reason(saverSetting: Boolean): Reason = when {
        _hot.value -> Reason.HEAT
        _lowBattery.value -> Reason.BATTERY
        saverSetting -> Reason.SAVER
        else -> Reason.NONE
    }
}

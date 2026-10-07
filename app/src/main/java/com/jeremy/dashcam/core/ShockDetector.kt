package com.jeremy.dashcam.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.jeremy.dashcam.data.Sensitivity
import kotlin.math.sqrt

/** Detects impacts / violent shakes from the motion sensors, fully on-device. */
class ShockDetector(context: Context, private val onShock: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linear: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravity = FloatArray(3)
    private var thresholdMs2 = 2.5f * SensorManager.GRAVITY_EARTH
    private var lastTrigger = 0L
    private var running = false

    fun start(sensitivity: Sensitivity) {
        thresholdMs2 = SensorManager.GRAVITY_EARTH * when (sensitivity) {
            Sensitivity.HIGH -> 1.6f
            Sensitivity.MEDIUM -> 2.5f
            Sensitivity.LOW -> 3.5f
        }
        if (running) return
        val s = linear ?: accel ?: return
        running = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (running) sm.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x: Float; val y: Float; val z: Float
        if (e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            x = e.values[0]; y = e.values[1]; z = e.values[2]
        } else {
            val a = 0.9f
            for (i in 0..2) gravity[i] = a * gravity[i] + (1 - a) * e.values[i]
            x = e.values[0] - gravity[0]; y = e.values[1] - gravity[1]; z = e.values[2] - gravity[2]
        }
        val mag = sqrt(x * x + y * y + z * z)
        val now = SystemClock.elapsedRealtime()
        if (mag > thresholdMs2 && now - lastTrigger > COOLDOWN_MS) {
            lastTrigger = now
            onShock()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object { private const val COOLDOWN_MS = 10_000L }
}

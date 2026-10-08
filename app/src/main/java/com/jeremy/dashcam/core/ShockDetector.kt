package com.jeremy.dashcam.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.jeremy.dashcam.core.detect.ImpactAlgorithm
import com.jeremy.dashcam.data.Sensitivity
import kotlin.math.sqrt

/**
 * Android wrapper around [ImpactAlgorithm]: linear acceleration (≈200 Hz) + gravity + gyroscope,
 * processed on a dedicated thread so the main thread stays free.
 */
class ShockDetector(context: Context, private val onDetect: (ImpactAlgorithm.Detection) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linear: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val algorithm = ImpactAlgorithm()
    private var thread: HandlerThread? = null
    private val gravity = FloatArray(3)
    private var haveGravitySensor = false
    @Volatile private var gyroMag = 0f
    private var running = false

    fun start(sensitivity: Sensitivity) {
        algorithm.sensitivity = sensitivity.toLevel()
        if (running) return
        val main = linear ?: accel ?: return
        val t = HandlerThread("jeremy-sensors").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        algorithm.reset()
        haveGravitySensor = gravitySensor != null && linear != null
        val period = 5_000 // µs → 200 Hz (falls back to the fastest the device allows)
        running = sm.registerListener(this, main, period, h)
        gravitySensor?.let { if (haveGravitySensor) sm.registerListener(this, it, 20_000, h) }
        gyroSensor?.let { sm.registerListener(this, it, 10_000, h) }
    }

    fun stop() {
        if (running) sm.unregisterListener(this)
        running = false
        thread?.quitSafely(); thread = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { gravity[0] = e.values[0]; gravity[1] = e.values[1]; gravity[2] = e.values[2] }
            Sensor.TYPE_GYROSCOPE -> gyroMag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            Sensor.TYPE_LINEAR_ACCELERATION -> feed(e.values[0], e.values[1], e.values[2])
            Sensor.TYPE_ACCELEROMETER -> {
                // No linear-acceleration sensor: estimate gravity with a slow low-pass filter.
                val a = 0.92f
                for (i in 0..2) gravity[i] = a * gravity[i] + (1 - a) * e.values[i]
                feed(e.values[0] - gravity[0], e.values[1] - gravity[1], e.values[2] - gravity[2])
            }
        }
    }

    private fun feed(x: Float, y: Float, z: Float) {
        val d = algorithm.onSample(SystemClock.elapsedRealtime(), x, y, z, gravity[0], gravity[1], gravity[2], gyroMag)
        if (d != null) onDetect(d)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

fun Sensitivity.toLevel() = when (this) {
    Sensitivity.LOW -> ImpactAlgorithm.Level.LOW
    Sensitivity.MEDIUM -> ImpactAlgorithm.Level.MEDIUM
    Sensitivity.HIGH -> ImpactAlgorithm.Level.HIGH
}

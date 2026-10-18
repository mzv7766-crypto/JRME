package com.shomer.bodycam.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.shomer.bodycam.core.detect.FallAlgorithm
import com.shomer.bodycam.core.detect.ShakeAlgorithm
import com.shomer.bodycam.data.Sensitivity

/**
 * Accelerometer (gravity included, ~100 Hz) on its own thread, feeding the man-down and shake
 * detectors. Callbacks arrive on the sensor thread.
 */
class BodySensors(
    context: Context,
    private val onFall: (FallAlgorithm.Detection) -> Unit,
    private val onShake: () -> Unit,
) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val fall = FallAlgorithm()
    val shake = ShakeAlgorithm()
    @Volatile var fallOn = false
    @Volatile var shakeOn = false
    private var thread: HandlerThread? = null
    private var registered = false

    val available get() = accel != null

    fun update(fallEnabled: Boolean, sensitivity: Sensitivity, shakeEnabled: Boolean) {
        fall.sensitivity = when (sensitivity) {
            Sensitivity.LOW -> FallAlgorithm.Level.LOW
            Sensitivity.MEDIUM -> FallAlgorithm.Level.MEDIUM
            Sensitivity.HIGH -> FallAlgorithm.Level.HIGH
        }
        fallOn = fallEnabled; shakeOn = shakeEnabled
        if (fallEnabled || shakeEnabled) start() else stop()
    }

    private fun start() {
        if (registered) return
        val a = accel ?: return
        val t = HandlerThread("shomer-sensors").also { it.start() }
        thread = t
        fall.reset(); shake.reset()
        // ~100 Hz, delivered in batches (≤ 0.4 s late) so the sensor hub buffers samples instead of waking the CPU 100×/s
        registered = sm.registerListener(this, a, 10_000, 400_000, Handler(t.looper))
    }

    fun stop() {
        if (registered) sm.unregisterListener(this)
        registered = false
        thread?.quitSafely(); thread = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        // the sample's own time (batched samples arrive together, so the arrival time would be wrong)
        val t = if (e.timestamp > 0) e.timestamp / 1_000_000 else SystemClock.elapsedRealtime()
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        if (fallOn) fall.onSample(t, x, y, z)?.let(onFall)
        if (shakeOn && shake.onSample(t, x, y, z) != null) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

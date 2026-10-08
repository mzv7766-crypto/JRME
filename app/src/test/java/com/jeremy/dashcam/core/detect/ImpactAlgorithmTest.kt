package com.jeremy.dashcam.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/** Simulated 200 Hz drives. Phone mounted upright: gravity along +Y, road forward = −Z, left/right = X. */
class ImpactAlgorithmTest {
    private val G = 9.80665f
    private val rnd = Random(42)

    private class Sample(val ax: Float, val ay: Float, val az: Float, val gyro: Float = 0.2f, val freeFall: Boolean = false)

    private fun run(algo: ImpactAlgorithm, seconds: Double, gen: (tSec: Double) -> Sample): List<ImpactAlgorithm.Detection> {
        val out = mutableListOf<ImpactAlgorithm.Detection>()
        val n = (seconds * 200).toInt()
        for (i in 0 until n) {
            val t = i / 200.0
            val s = gen(t)
            // road vibration noise ±0.05 g on every axis
            val nx = (rnd.nextFloat() - 0.5f) * 0.1f * G
            val ny = (rnd.nextFloat() - 0.5f) * 0.1f * G
            val nz = (rnd.nextFloat() - 0.5f) * 0.1f * G
            val (gx, gy, gz) = if (s.freeFall) Triple(0f, G, 0f) else Triple(0f, G, 0f)
            // during free fall the linear acceleration cancels gravity (total ≈ 0)
            val ay = if (s.freeFall) -G else s.ay + ny
            val d = algo.onSample((t * 1000).toLong(), s.ax + nx, ay, s.az + nz, gx, gy, gz, s.gyro)
            if (d != null) out += d
        }
        return out
    }

    private fun quiet() = Sample(0f, 0f, 0f)

    @Test fun normalDrivingNeverTriggers() {
        val a = ImpactAlgorithm()
        // gentle accel/brake/turns up to 0.3 g
        val r = run(a, 120.0) { t -> Sample((0.25f * sin(t / 3) * G).toFloat(), 0f, (0.3f * sin(t / 5) * G).toFloat()) }
        assertEquals(0, r.size)
    }

    @Test fun potholeVerticalSpikeIsIgnored() {
        val a = ImpactAlgorithm()
        // 3.5 g vertical jolt for 60 ms – a nasty pothole
        val r = run(a, 10.0) { t -> if (t in 5.0..5.06) Sample(0f, 3.5f * G, 0f) else quiet() }
        assertEquals(0, r.size)
    }

    @Test fun singleSampleTapIsIgnored() {
        val a = ImpactAlgorithm()
        // 6 g for one sample (5 ms) – phone tapped in the mount
        val r = run(a, 10.0) { t -> if (t in 5.0..5.004) Sample(6f * G, 0f, 0f) else quiet() }
        assertEquals(0, r.size)
    }

    @Test fun frontalCrashTriggersImpact() {
        val a = ImpactAlgorithm()
        // 4 g horizontal deceleration for 120 ms
        val r = run(a, 10.0) { t -> if (t in 5.0..5.12) Sample(0f, 0f, 4f * G) else quiet() }
        assertEquals(1, r.size)
        assertEquals(ImpactAlgorithm.Type.IMPACT, r[0].type)
    }

    @Test fun sideImpactTriggersImpact() {
        val a = ImpactAlgorithm()
        val r = run(a, 10.0) { t -> if (t in 6.0..6.08) Sample(3f * G, 0.5f * G, 0f) else quiet() }
        assertEquals(1, r.size)
        assertEquals(ImpactAlgorithm.Type.IMPACT, r[0].type)
    }

    @Test fun emergencyBrakingTriggersHarsh() {
        val a = ImpactAlgorithm()
        // 0.8 g braking held for 1.5 s
        val r = run(a, 10.0) { t -> if (t in 5.0..6.5) Sample(0f, 0f, 0.8f * G) else quiet() }
        assertEquals(1, r.size)
        assertEquals(ImpactAlgorithm.Type.HARSH, r[0].type)
    }

    @Test fun firmButNormalBrakingIsIgnored() {
        val a = ImpactAlgorithm()
        // 0.4 g – a firm traffic-light stop
        val r = run(a, 10.0) { t -> if (t in 5.0..7.0) Sample(0f, 0f, 0.4f * G) else quiet() }
        assertEquals(0, r.size)
    }

    @Test fun droppedPhoneIsIgnored() {
        val a = ImpactAlgorithm()
        // 150 ms free fall then 5 g landing for 40 ms
        val r = run(a, 10.0) { t ->
            when {
                t in 5.0..5.15 -> Sample(0f, 0f, 0f, freeFall = true)
                t in 5.151..5.19 -> Sample(2f * G, 4f * G, 2f * G)
                else -> quiet()
            }
        }
        assertEquals(0, r.size)
    }

    @Test fun grabbingPhoneIsIgnored() {
        val a = ImpactAlgorithm()
        // 2.6 g for 80 ms while rotating fast (7 rad/s) – picking the phone off the mount
        val r = run(a, 10.0) { t -> if (t in 5.0..5.08) Sample(2.6f * G, 0f, 0f, gyro = 7f) else quiet() }
        assertEquals(0, r.size)
    }

    @Test fun warmupIgnoresMountingThePhone() {
        val a = ImpactAlgorithm()
        val r = run(a, 5.0) { t -> if (t in 1.0..1.1) Sample(0f, 0f, 4f * G) else quiet() }
        assertEquals(0, r.size)
    }

    @Test fun sensitivityChangesThreshold() {
        val low = ImpactAlgorithm(ImpactAlgorithm.Level.LOW)
        val high = ImpactAlgorithm(ImpactAlgorithm.Level.HIGH)
        val crash: (Double) -> Sample = { t -> if (t in 5.0..5.1) Sample(0f, 0f, 2.0f * G) else quiet() }
        assertEquals(0, run(low, 10.0, crash).size)
        assertNotNull(run(high, 10.0, crash).firstOrNull())
    }

    @Test fun cooldownPreventsDoubleEvents() {
        val a = ImpactAlgorithm()
        val r = run(a, 12.0) { t -> if (t in 5.0..5.1 || t in 6.0..6.1) Sample(0f, 0f, 4f * G) else quiet() }
        assertEquals(1, r.size)
    }

    @Test fun bumpIsReportedForFusion() {
        val a = ImpactAlgorithm()
        run(a, 10.0) { t -> if (t in 5.0..5.6) Sample(0.5f * G, 0f, 0f) else quiet() }
        assertTrue(a.hadBumpNear(5_600, 1500))
        assertFalse(a.hadBumpNear(20_000, 1500))
    }
}

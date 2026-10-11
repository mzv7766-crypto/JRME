package com.shomer.bodycam.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Simulated chest-worn phone at 100 Hz: gravity along +Y when standing, along +Z when lying on the back. */
class FallAlgorithmTest {
    private val G = 9.80665f
    private var t = 0L
    private val hits = mutableListOf<FallAlgorithm.Detection>()

    private fun feed(a: FallAlgorithm, ms: Long, f: (Long) -> FloatArray) {
        val end = t + ms
        while (t < end) {
            val v = f(t)
            a.onSample(t, v[0], v[1], v[2])?.let { hits += it }
            t += 10
        }
    }

    private fun standing(k: Float = 1f) = { _: Long -> floatArrayOf(0f, G * k, 0f) }
    private val lying = { _: Long -> floatArrayOf(0f, 0.3f, G) }

    private fun fall(a: FallAlgorithm, impactG: Float = 3.5f, freeFallMs: Long = 300) {
        feed(a, freeFallMs) { floatArrayOf(0f, 0.2f * G, 0.1f * G) }            // falling
        feed(a, 60) { floatArrayOf(0f, impactG * G * 0.4f, impactG * G * 0.92f) }  // hitting the floor
        feed(a, 200) { tt -> floatArrayOf(0f, 0.5f * G, G * (1f + 0.6f * sin(tt / 20.0).toFloat())) } // settling
    }

    @Test fun realFallThenLyingStillIsDetected() {
        val a = FallAlgorithm()
        feed(a, 4000, standing())
        fall(a)
        feed(a, 4000, lying)
        assertEquals(1, hits.size)
        assert(hits[0].angleDeg > 60f)
    }

    @Test fun fallButGettingUpRightAwayIsNotAnAlarm() {
        val a = FallAlgorithm()
        feed(a, 4000, standing())
        fall(a)
        // moving around, then standing again
        feed(a, 3000) { tt -> floatArrayOf(2f * sin(tt / 40.0).toFloat(), G + 4f * sin(tt / 25.0).toFloat(), 3f * cos(tt / 30.0).toFloat()) }
        feed(a, 3000, standing())
        assertEquals(0, hits.size)
    }

    @Test fun walkingAndRunningNeverTrigger() {
        val a = FallAlgorithm(FallAlgorithm.Level.HIGH)
        feed(a, 3000, standing())
        // walking: ~2 steps/s, ±0.4 g
        feed(a, 20_000) { tt -> floatArrayOf(0.5f * sin(2 * PI * 1.0 * tt / 1000).toFloat(), G + 0.4f * G * sin(2 * PI * 2.0 * tt / 1000).toFloat(), 0.8f) }
        // running: ~2.8 steps/s, hard foot strikes up to ~3 g with a short low-force flight phase
        feed(a, 20_000) { tt ->
            val ph = (tt % 357) / 357.0
            val y = when { ph < 0.12 -> 3.0f * G; ph < 0.45 -> 0.45f * G; else -> 1.1f * G }
            floatArrayOf(0.6f, y, 1.2f)
        }
        assertEquals(0, hits.size)
    }

    @Test fun sittingDownHardIsNotAFall() {
        val a = FallAlgorithm()
        feed(a, 4000, standing())
        feed(a, 80) { floatArrayOf(0f, 2.2f * G, 0.5f * G) }        // thump on a chair, no free fall
        // seated: leaning back only a little
        feed(a, 5000) { floatArrayOf(0f, G * 0.94f, G * 0.34f) }
        assertEquals(0, hits.size)
    }

    @Test fun knockedDownWithoutFreeFallIsDetected() {
        val a = FallAlgorithm()
        feed(a, 4000, standing())
        feed(a, 60) { floatArrayOf(2f * G, 1.5f * G, 3.4f * G) }    // very hard hit
        feed(a, 300) { tt -> floatArrayOf(0f, 0.4f * G, G * (1f + 0.5f * sin(tt / 15.0).toFloat())) }
        feed(a, 4000, lying)
        assertEquals(1, hits.size)
    }

    @Test fun cooldownPreventsRepeatedAlarms() {
        val a = FallAlgorithm()
        feed(a, 4000, standing())
        fall(a); feed(a, 4000, lying)
        feed(a, 3000, standing())
        fall(a); feed(a, 4000, lying)
        assertEquals(1, hits.size)
    }

    @Test fun shakingThePhoneOnPurposeIsNotAFall() {
        val a = FallAlgorithm()
        feed(a, 4000, standing())
        feed(a, 1500) { tt -> floatArrayOf(3.5f * G * sin(2 * PI * 5.0 * tt / 1000).toFloat(), G, 0f) }
        feed(a, 5000, standing())
        assertEquals(0, hits.size)
    }

    @Test fun putOnATableFaceUpIsNotAFall() {
        val a = FallAlgorithm()
        feed(a, 4000, standing())
        // taken off and laid down gently: orientation changes, but no free fall and no impact
        feed(a, 1000) { tt -> val k = (tt % 1000) / 1000f; floatArrayOf(0f, G * (1 - k), G * k) }
        feed(a, 5000, lying)
        assertNull(hits.firstOrNull())
        assertNotNull(a)
    }
}

package com.shomer.bodycam.core.detect

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ShakeAlgorithmTest {
    private val G = 9.80665f
    private var t = 0L
    private var hits = 0

    private fun feed(a: ShakeAlgorithm, ms: Long, f: (Long) -> FloatArray) {
        val end = t + ms
        while (t < end) {
            val v = f(t)
            if (a.onSample(t, v[0], v[1], v[2]) != null) hits++
            t += 10
        }
    }

    private val still = { _: Long -> floatArrayOf(0f, G, 0f) }

    @Test fun firmShakeStartsRecording() {
        val a = ShakeAlgorithm()
        feed(a, 2000, still)
        feed(a, 1200) { tt -> floatArrayOf(2.6f * G * sin(2 * PI * 4.5 * tt / 1000).toFloat(), G, 0f) }
        feed(a, 2000, still)
        assertEquals(1, hits)
    }

    @Test fun walkingAndRunningDoNotCount() {
        val a = ShakeAlgorithm()
        feed(a, 2000, still)
        feed(a, 20_000) { tt -> floatArrayOf(0.3f, G + 0.5f * G * sin(2 * PI * 2.0 * tt / 1000).toFloat(), 0.5f) }
        feed(a, 20_000) { tt ->
            val ph = (tt % 357) / 357.0
            floatArrayOf(0.5f, if (ph < 0.12) 3.2f * G else if (ph < 0.45) 0.3f * G else 1.1f * G, 1f)
        }
        assertEquals(0, hits)
    }

    @Test fun aSingleKnockDoesNotCount() {
        val a = ShakeAlgorithm()
        feed(a, 2000, still)
        feed(a, 50) { floatArrayOf(4f * G, G, 0f) }
        feed(a, 3000, still)
        assertEquals(0, hits)
    }

    @Test fun gentleWaveDoesNotCount() {
        val a = ShakeAlgorithm()
        feed(a, 2000, still)
        feed(a, 3000) { tt -> floatArrayOf(1.0f * G * sin(2 * PI * 4.0 * tt / 1000).toFloat(), G, 0f) }
        assertEquals(0, hits)
    }

    @Test fun twoShakesAreTwoRecordingsOnlyAfterCooldown() {
        val a = ShakeAlgorithm()
        feed(a, 2000, still)
        val shake = { tt: Long -> floatArrayOf(2.6f * G * sin(2 * PI * 4.5 * tt / 1000).toFloat(), G, 0f) }
        feed(a, 1200, shake); feed(a, 500, still); feed(a, 1200, shake)
        assertEquals(1, hits)
        feed(a, 4000, still); feed(a, 1200, shake)
        assertEquals(2, hits)
    }
}

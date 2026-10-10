package com.jeremy.dashcam.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class ApproachAlgorithmTest {
    private val step = 250L

    /** square-ish box centred at (cx, cy) with apparent size s */
    private fun box(label: String, cx: Float, cy: Float, s: Float) =
        ApproachAlgorithm.Box(label, 0.8f, cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)

    private fun run(a: ApproachAlgorithm, frames: Int, f: (Int) -> List<ApproachAlgorithm.Box>): ApproachAlgorithm.Danger? {
        var hit: ApproachAlgorithm.Danger? = null
        for (i in 0 until frames) a.process(f(i), 10_000L + i * step)?.let { if (hit == null) hit = it }
        return hit
    }

    @Test fun carBrakingHardAheadTriggers() {
        // size grows 25% every 250 ms → TTC ≈ 1 s
        val d = run(ApproachAlgorithm(), 10) { i -> listOf(box("car", 0.5f, 0.6f, (0.16f * Math.pow(1.25, i.toDouble())).toFloat().coerceAtMost(0.9f))) }
        assertNotNull(d)
        assertEquals("car", d!!.label)
    }

    @Test fun carAtSteadyDistanceDoesNot() {
        assertNull(run(ApproachAlgorithm(), 40) { listOf(box("car", 0.5f, 0.6f, 0.3f)) })
    }

    @Test fun detectorJitterDoesNot() {
        val r = Random(7)
        assertNull(run(ApproachAlgorithm(), 120) { listOf(box("car", 0.5f + (r.nextFloat() - .5f) * 0.02f, 0.6f, 0.3f * (1 + (r.nextFloat() - .5f) * 0.12f))) })
    }

    @Test fun parkedCarOnTheSideDoesNot() {
        // we drive past a parked car: it grows fast but stays at the edge of the image
        assertNull(run(ApproachAlgorithm(), 10) { i -> listOf(box("car", 0.12f, 0.65f, (0.12f * Math.pow(1.3, i.toDouble())).toFloat().coerceAtMost(0.2f))) })
    }

    @Test fun farAwayCarDoesNot() {
        assertNull(run(ApproachAlgorithm(), 10) { i -> listOf(box("car", 0.5f, 0.5f, 0.03f * (1 + i * 0.2f))) })
    }

    @Test fun pedestrianSteppingOutTriggers() {
        // a tall narrow person in the lane, growing ~30% per update
        val d = run(ApproachAlgorithm(), 10) { i ->
            val s = (0.08f * Math.pow(1.3, i.toDouble())).toFloat().coerceAtMost(0.6f)
            val w = s / sqrt(3f); val h = s * sqrt(3f)
            listOf(ApproachAlgorithm.Box("person", 0.7f, 0.5f - w / 2, 0.75f - h, 0.5f + w / 2, 0.75f))
        }
        assertNotNull(d)
        assertEquals("person", d!!.label)
    }

    @Test fun lowSensitivityNeedsCloserDanger() {
        // TTC ≈ 1.5 s: HIGH reacts, LOW does not
        val grow = { i: Int -> listOf(box("car", 0.5f, 0.6f, (0.16f * Math.pow(1.17, i.toDouble())).toFloat())) }
        assertNotNull(run(ApproachAlgorithm().apply { sensitivity = ApproachAlgorithm.Level.HIGH }, 10, grow))
        assertNull(run(ApproachAlgorithm().apply { sensitivity = ApproachAlgorithm.Level.LOW }, 10, grow))
    }

    @Test fun coolDownAfterTrigger() {
        val a = ApproachAlgorithm()
        var hits = 0
        for (round in 0 until 3) for (i in 0 until 10) {
            if (a.process(listOf(box("car", 0.5f, 0.6f, (0.16f * Math.pow(1.25, i.toDouble())).toFloat().coerceAtMost(0.9f))), 10_000L + (round * 10 + i) * step) != null) hits++
        }
        assertEquals(1, hits)
    }
}

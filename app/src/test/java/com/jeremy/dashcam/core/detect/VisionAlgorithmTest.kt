package com.jeremy.dashcam.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VisionAlgorithmTest {
    private val gw = 40; private val gh = 30
    private val rnd = Random(7)

    /** Textured road scene, optionally shifted (camera shake), with brightness offset and an optional object. */
    private fun scene(shiftX: Int = 0, shiftY: Int = 0, bright: Int = 0, obj: IntRange? = null, objRows: IntRange = 12..24): IntArray {
        val g = IntArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val sx = x + shiftX; val sy = y + shiftY
            val base = 60 + ((sx * 37 + sy * 91) % 70) + if (sy < 9) 80 else 0 // sky brighter
            var v = base + bright + rnd.nextInt(-4, 5)
            if (obj != null && x in obj && y in objRows) v = 15 + rnd.nextInt(0, 6) // dark car
            g[y * gw + x] = v.coerceIn(0, 255)
        }
        return g
    }

    private fun feed(v: VisionAlgorithm, frames: List<IntArray>, startMs: Long = 0): Int {
        var triggers = 0
        frames.forEachIndexed { i, f -> if (v.process(f, startMs + i * 150L).triggered) triggers++ }
        return triggers
    }

    private fun steady(n: Int) = List(n) { scene() }

    @Test fun steadyDrivingDoesNotTrigger() {
        assertEquals(0, feed(VisionAlgorithm(gw, gh), steady(200)))
    }

    @Test fun cameraShakeIsCompensated() {
        val frames = steady(30) + List(40) { i -> scene(shiftX = listOf(0, 1, 2, 1, 0, -1, -2, -1)[i % 8], shiftY = listOf(0, 1, 0, -1)[i % 4]) }
        assertEquals(0, feed(VisionAlgorithm(gw, gh), frames))
    }

    @Test fun tunnelLightingChangeIsIgnored() {
        val frames = steady(30) + List(20) { scene(bright = -45) } + List(20) { scene(bright = 40) }
        assertEquals(0, feed(VisionAlgorithm(gw, gh), frames))
    }

    @Test fun singleGlitchFrameIsIgnored() {
        val frames = steady(30) + listOf(scene(obj = 8..32)) + steady(30)
        assertEquals(0, feed(VisionAlgorithm(gw, gh), frames))
    }

    @Test fun carCuttingInTriggers() {
        // a dark object sweeps in from the side across the lane over several frames
        val sweep = List(8) { i -> val start = 30 - i * 4; scene(obj = start.coerceAtLeast(0)..(start + 14).coerceAtMost(gw - 1)) }
        val frames = steady(30) + sweep + steady(10)
        assertTrue(feed(VisionAlgorithm(gw, gh), frames) >= 1)
    }

    @Test fun lowSensitivityIsStricter() {
        val small = List(8) { i -> val s = 26 - i; scene(obj = s..(s + 3), objRows = 18..21) }
        val frames = steady(30) + small + steady(5)
        val low = VisionAlgorithm(gw, gh).apply { sensitivity = VisionAlgorithm.Level.LOW }
        assertEquals(0, feed(low, frames))
    }
}

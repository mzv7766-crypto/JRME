package com.shomer.bodycam.core.detect

import kotlin.math.sqrt

/**
 * Pure (Android-free, unit-tested) "shake to record" gesture.
 *
 * A deliberate shake is fast (≈ 3–7 back-and-forth movements per second) and strong. Walking and even
 * running produce strong jolts too, but at most ~3 per second, so the gesture needs [peaksNeeded] strong
 * jolts in a row with no gap longer than [maxGapMs] between them.
 */
class ShakeAlgorithm {
    data class Detection(val peaks: Int)

    private val G = 9.80665f
    var thresholdG = 1.9f
    private val peaksNeeded = 6
    private val maxGapMs = 250L
    private val refractoryMs = 70L        // one jolt = one peak
    private val cooldownMs = 4000L

    private val grav = FloatArray(3)
    private var haveGrav = false
    private var count = 0
    private var lastPeakAt = Long.MIN_VALUE
    private var above = false
    private var lastDetectAt = Long.MIN_VALUE / 2

    fun reset() { haveGrav = false; count = 0; lastPeakAt = Long.MIN_VALUE; above = false }

    fun onSample(tMs: Long, ax: Float, ay: Float, az: Float): Detection? {
        // very slow gravity estimate; the remainder is the movement of the phone
        if (!haveGrav) { grav[0] = ax; grav[1] = ay; grav[2] = az; haveGrav = true }
        else { val k = 0.02f; grav[0] += k * (ax - grav[0]); grav[1] += k * (ay - grav[1]); grav[2] += k * (az - grav[2]) }
        val lx = ax - grav[0]; val ly = ay - grav[1]; val lz = az - grav[2]
        val lin = sqrt(lx * lx + ly * ly + lz * lz) / G

        if (lastPeakAt != Long.MIN_VALUE && tMs - lastPeakAt > maxGapMs) count = 0
        val isAbove = lin >= thresholdG
        if (isAbove && !above && (lastPeakAt == Long.MIN_VALUE || tMs - lastPeakAt >= refractoryMs)) {
            count = if (lastPeakAt != Long.MIN_VALUE && tMs - lastPeakAt <= maxGapMs) count + 1 else 1
            lastPeakAt = tMs
            if (count >= peaksNeeded && tMs - lastDetectAt >= cooldownMs) {
                lastDetectAt = tMs; count = 0
                return Detection(peaksNeeded)
            }
        }
        above = if (above) lin >= thresholdG * 0.7f else isAbove   // hysteresis: must drop before the next peak
        return null
    }
}

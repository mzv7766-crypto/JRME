package com.shomer.bodycam.core.detect

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure (Android-free, unit-tested) "man down" detector for a camera worn on the chest.
 *
 * Feed it raw accelerometer samples (gravity INCLUDED, m/s², ~50–200 Hz). A fall is reported only when
 * all three phases of a real fall are seen, in order:
 *
 *  1. a short FREE-FALL (total force drops well below 1 g) followed within ~1.2 s by an IMPACT,
 *     or one very hard impact on its own (being knocked down);
 *  2. the body's ORIENTATION changed a lot (upright → lying), measured against the gravity direction
 *     from before the fall;
 *  3. STILLNESS: 1–3 s after the impact the wearer is not moving.
 *
 * Walking, running, sitting down hard, jumping and bumping into things fail at least one phase.
 */
class FallAlgorithm(var sensitivity: Level = Level.MEDIUM) {

    enum class Level { LOW, MEDIUM, HIGH }
    data class Detection(val peakG: Float, val angleDeg: Float)

    private val G = 9.80665f
    private val freeFallG get() = when (sensitivity) { Level.HIGH -> 0.6f; Level.MEDIUM -> 0.5f; Level.LOW -> 0.4f }
    private val freeFallMinMs get() = when (sensitivity) { Level.HIGH -> 80L; Level.MEDIUM -> 100L; Level.LOW -> 140L }
    private val impactG get() = when (sensitivity) { Level.HIGH -> 2.0f; Level.MEDIUM -> 2.4f; Level.LOW -> 3.0f }
    private val hardImpactG get() = when (sensitivity) { Level.HIGH -> 2.8f; Level.MEDIUM -> 3.3f; Level.LOW -> 4.0f }
    private val minAngleDeg get() = when (sensitivity) { Level.HIGH -> 35f; Level.MEDIUM -> 45f; Level.LOW -> 55f }
    private val stillStdG = 0.12f
    private val impactAfterFreeFallMs = 1200L
    private val stillFromMs = 1000L       // stillness window starts this long after the impact …
    private val stillToMs = 3000L         // … and ends here
    private val cooldownMs = 30_000L

    /** Live readout for the settings screen (total force in g, smoothed). */
    @Volatile var liveG = 1f; private set

    private var startMs = Long.MIN_VALUE
    private var lastDetectMs = Long.MIN_VALUE / 2
    private val grav = FloatArray(3)                  // low-pass gravity direction (unit vector)
    private var haveGrav = false
    // gravity direction history (one entry per ~100 ms, last ~3 s) to know how the wearer stood before the fall
    private val dirT = LongArray(40); private val dirX = FloatArray(40); private val dirY = FloatArray(40); private val dirZ = FloatArray(40)
    private var dirN = 0; private var dirHead = 0; private var lastDirAt = Long.MIN_VALUE

    private var freeFallSince = Long.MIN_VALUE
    private var freeFallEndedAt = Long.MIN_VALUE
    private var freeFallRef: FloatArray? = null

    // candidate (impact seen, waiting for stillness)
    private var impactAt = Long.MIN_VALUE
    private var impactPeak = 0f
    private var ref: FloatArray? = null
    private var sum = 0.0; private var sumSq = 0.0; private var n = 0

    fun reset() {
        startMs = Long.MIN_VALUE; haveGrav = false; dirN = 0; lastDirAt = Long.MIN_VALUE
        freeFallSince = Long.MIN_VALUE; freeFallEndedAt = Long.MIN_VALUE; freeFallRef = null
        impactAt = Long.MIN_VALUE; ref = null; liveG = 1f
    }

    fun onSample(tMs: Long, ax: Float, ay: Float, az: Float): Detection? {
        if (startMs == Long.MIN_VALUE) startMs = tMs
        val mag = sqrt(ax * ax + ay * ay + az * az)
        val g = mag / G
        liveG += 0.2f * (g - liveG)

        // slow gravity direction, only updated while the force is close to 1 g (not during hits)
        if (mag > 1f && g in 0.7f..1.3f) {
            val ux = ax / mag; val uy = ay / mag; val uz = az / mag
            if (!haveGrav) { grav[0] = ux; grav[1] = uy; grav[2] = uz; haveGrav = true }
            else { val k = 0.08f; grav[0] += k * (ux - grav[0]); grav[1] += k * (uy - grav[1]); grav[2] += k * (uz - grav[2]) }
            if (lastDirAt == Long.MIN_VALUE || tMs - lastDirAt >= 100) { pushDir(tMs); lastDirAt = tMs }
        }

        if (tMs - startMs < 2000) return null
        if (tMs - lastDetectMs < cooldownMs) return null

        // ---- phase 3: waiting for stillness after an impact ----
        if (impactAt != Long.MIN_VALUE) {
            val since = tMs - impactAt
            if (since in stillFromMs..stillToMs) {
                sum += g; sumSq += g * g; n++
                // moving clearly again → it was not a fall (or the wearer got up)
                if (n > 10) {
                    val mean = sum / n; val std = sqrt(max(0.0, sumSq / n - mean * mean))
                    if (std > stillStdG * 2.5) { impactAt = Long.MIN_VALUE; return null }
                }
            } else if (since > stillToMs) {
                val r = ref
                impactAt = Long.MIN_VALUE
                if (n < 10 || r == null) return null
                val mean = sum / n; val std = sqrt(max(0.0, sumSq / n - mean * mean))
                val angle = angleDeg(r, grav)
                if (std <= stillStdG && mean in 0.75..1.25 && angle >= minAngleDeg) {
                    lastDetectMs = tMs
                    return Detection(impactPeak, angle)
                }
                return null
            } else if (g > impactPeak) impactPeak = g    // still inside the impact itself
            return null
        }

        // ---- phase 1a: free-fall ----
        if (g < freeFallG) {
            if (freeFallSince == Long.MIN_VALUE) { freeFallSince = tMs; freeFallRef = dirBefore(tMs, 400) }
        } else if (freeFallSince != Long.MIN_VALUE) {
            if (tMs - freeFallSince >= freeFallMinMs) freeFallEndedAt = tMs
            else freeFallRef = null
            freeFallSince = Long.MIN_VALUE
        }

        // ---- phase 1b: impact ----
        val afterFreeFall = freeFallEndedAt != Long.MIN_VALUE && tMs - freeFallEndedAt <= impactAfterFreeFallMs
        if ((afterFreeFall && g >= impactG) || g >= hardImpactG) {
            ref = (if (afterFreeFall) freeFallRef else null) ?: dirBefore(tMs, 800)
            if (ref == null) return null
            impactAt = tMs; impactPeak = g
            sum = 0.0; sumSq = 0.0; n = 0
            freeFallEndedAt = Long.MIN_VALUE; freeFallRef = null
        }
        return null
    }

    private fun pushDir(t: Long) {
        dirT[dirHead] = t; dirX[dirHead] = grav[0]; dirY[dirHead] = grav[1]; dirZ[dirHead] = grav[2]
        dirHead = (dirHead + 1) % dirT.size
        if (dirN < dirT.size) dirN++
    }

    /** Gravity direction as it was at least [agoMs] before [t] (newest such entry). */
    private fun dirBefore(t: Long, agoMs: Long): FloatArray? {
        for (k in 1..dirN) {
            val i = (dirHead - k + dirT.size) % dirT.size
            if (t - dirT[i] >= agoMs) return floatArrayOf(dirX[i], dirY[i], dirZ[i])
        }
        return null
    }

    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val la = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); val lb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (la < 1e-3f || lb < 1e-3f) return 0f
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (la * lb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()).toFloat()
    }
}

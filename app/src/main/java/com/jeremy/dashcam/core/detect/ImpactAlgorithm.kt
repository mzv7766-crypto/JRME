package com.jeremy.dashcam.core.detect

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure (Android-free, unit-tested) impact & harsh-driving detector.
 *
 * Feed it ~100–200 Hz samples of linear acceleration (gravity removed), the gravity vector and the
 * gyroscope magnitude. It separates the acceleration into a HORIZONTAL part (perpendicular to
 * gravity — what a crash, hard braking or a swerve produces) and a VERTICAL part (potholes, speed
 * bumps), and only reports:
 *
 *  - [Type.IMPACT]: a short, strong, *sustained* horizontal hit (≈ ≥ 40 ms), not a single-sample tap.
 *  - [Type.HARSH]:  emergency braking / sharp swerve — a lower horizontal force held for ~0.4–0.6 s.
 *
 * Rejected on purpose: potholes (vertical-only), phone rattle in the mount (low-pass filtered),
 * a dropped phone (free-fall just before the hit) and the phone being grabbed (high rotation, modest force).
 */
class ImpactAlgorithm(var sensitivity: Level = Level.MEDIUM) {

    enum class Level { LOW, MEDIUM, HIGH }
    enum class Type { IMPACT, HARSH }
    data class Detection(val type: Type, val peakG: Float)

    // ---- tunables (in g = 9.81 m/s²) ----
    private val impactG get() = when (sensitivity) { Level.HIGH -> 1.6f; Level.MEDIUM -> 2.3f; Level.LOW -> 3.2f }
    private val harshG get() = when (sensitivity) { Level.HIGH -> 0.45f; Level.MEDIUM -> 0.58f; Level.LOW -> 0.72f }
    private val harshHoldMs get() = when (sensitivity) { Level.HIGH -> 380L; Level.MEDIUM -> 480L; Level.LOW -> 600L }
    private val verticalWeight = 0.45f          // potholes count less than horizontal hits
    private val sustainWindowMs = 40L           // impact must last this long …
    private val sustainFraction = 0.45f         // … at this fraction of the threshold on average
    private val handlingGyro = 5.0f             // rad/s – phone being grabbed / turned by hand
    private val freeFallG = 0.30f               // total acceleration while falling
    private val freeFallMinMs = 70L

    val impactThresholdG: Float get() = impactG
    val harshThresholdG: Float get() = harshG

    // ---- live readouts (written on the sensor thread, read from UI/fusion) ----
    @Volatile var horizontalG = 0f; private set          // smoothed, for the live meter
    @Volatile var peakG = 0f; private set                 // decaying peak, for the live meter
    @Volatile var lastBumpAtMs = Long.MIN_VALUE; private set   // last time horizontal ≥ 0.35 g (for fusion)
    @Volatile var lastBumpG = 0f; private set

    private val G = 9.80665f
    private var startMs = Long.MIN_VALUE
    private var lastDetectMs = Long.MIN_VALUE / 2
    private var lpX = 0f; private var lpY = 0f; private var lpZ = 0f       // ~25 Hz low-pass (rattle removal)
    private var slowH = 0f                                                 // ~1.5 Hz low-pass of horizontal g
    private var harshSinceMs = Long.MIN_VALUE
    private var harshPeak = 0f
    private var freeFallSinceMs = Long.MIN_VALUE
    private var dropGuardUntilMs = Long.MIN_VALUE
    private var lastT = Long.MIN_VALUE

    // short history for sustain / handling checks
    private val histT = LongArray(128); private val histE = FloatArray(128); private val histGyro = FloatArray(128)
    private var histN = 0; private var histHead = 0

    fun reset() {
        startMs = Long.MIN_VALUE; lpX = 0f; lpY = 0f; lpZ = 0f; slowH = 0f; harshSinceMs = Long.MIN_VALUE
        freeFallSinceMs = Long.MIN_VALUE; dropGuardUntilMs = Long.MIN_VALUE; histN = 0; lastT = Long.MIN_VALUE
        horizontalG = 0f; peakG = 0f
    }

    /**
     * @param tMs monotonic time in ms; (ax,ay,az) linear acceleration m/s²; (gx,gy,gz) gravity m/s² (zeros if
     * unknown → everything is treated as horizontal); gyro rotation-rate magnitude in rad/s.
     */
    fun onSample(tMs: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float, gyro: Float): Detection? {
        if (startMs == Long.MIN_VALUE) startMs = tMs
        val dt = if (lastT == Long.MIN_VALUE) 5L else (tMs - lastT).coerceIn(1, 100)
        lastT = tMs

        // 1) remove high-frequency rattle (cut-off ≈ 25 Hz regardless of sample rate)
        val a = (dt / (dt + 6.4f)).coerceIn(0.05f, 1f)
        lpX += a * (ax - lpX); lpY += a * (ay - lpY); lpZ += a * (az - lpZ)

        // 2) split into vertical (along gravity) and horizontal parts
        val gMag = sqrt(gx * gx + gy * gy + gz * gz)
        val vertical: Float
        val horizontal: Float
        if (gMag > 1f) {
            val ux = gx / gMag; val uy = gy / gMag; val uz = gz / gMag
            val v = lpX * ux + lpY * uy + lpZ * uz
            val hx = lpX - v * ux; val hy = lpY - v * uy; val hz = lpZ - v * uz
            vertical = abs(v) / G
            horizontal = sqrt(hx * hx + hy * hy + hz * hz) / G
        } else {
            vertical = 0f
            horizontal = sqrt(lpX * lpX + lpY * lpY + lpZ * lpZ) / G
        }
        val effective = sqrt(horizontal * horizontal + (vertical * verticalWeight) * (vertical * verticalWeight))

        // 3) free-fall → the phone was dropped; ignore the landing
        val total = if (gMag > 1f) sqrt((ax + gx) * (ax + gx) + (ay + gy) * (ay + gy) + (az + gz) * (az + gz)) / G else 1f
        if (gMag > 1f && total < freeFallG) {
            if (freeFallSinceMs == Long.MIN_VALUE) freeFallSinceMs = tMs
            if (tMs - freeFallSinceMs >= freeFallMinMs) dropGuardUntilMs = tMs + 1500
        } else freeFallSinceMs = Long.MIN_VALUE

        push(tMs, effective, gyro)

        // live meter
        val ah = (dt / (dt + 100f))
        slowH += ah * (horizontal - slowH)
        horizontalG = slowH
        peakG = max(peakG * 0.999f, effective)
        if (slowH >= 0.35f) {
            lastBumpG = if (lastBumpAtMs != Long.MIN_VALUE && tMs - lastBumpAtMs < 1500) max(lastBumpG, slowH) else slowH
            lastBumpAtMs = tMs
        }

        // warm-up (mounting the phone) and cooldown
        if (tMs - startMs < 3000) return null
        if (tMs - lastDetectMs < 10_000) return null
        if (tMs < dropGuardUntilMs) { harshSinceMs = Long.MIN_VALUE; return null }

        // 4) IMPACT: strong peak that is sustained, not a single spike, and not hand-handling
        if (effective >= impactG) {
            val mean = meanOver(tMs, sustainWindowMs)
            val maxGyro = maxGyroOver(tMs, 250)
            val handling = maxGyro > handlingGyro && effective < impactG * 1.5f
            if (mean >= impactG * sustainFraction && !handling) {
                lastDetectMs = tMs; harshSinceMs = Long.MIN_VALUE
                return Detection(Type.IMPACT, maxOver(tMs, 120))
            }
        }

        // 5) HARSH braking / swerve: lower force held for a while (uses the slow horizontal signal)
        if (slowH >= harshG && maxGyroOver(tMs, 250) < handlingGyro) {
            if (harshSinceMs == Long.MIN_VALUE) { harshSinceMs = tMs; harshPeak = 0f }
            harshPeak = max(harshPeak, slowH)
            if (tMs - harshSinceMs >= harshHoldMs) {
                lastDetectMs = tMs; harshSinceMs = Long.MIN_VALUE
                return Detection(Type.HARSH, harshPeak)
            }
        } else harshSinceMs = Long.MIN_VALUE
        return null
    }

    /** Was there a noticeable horizontal force (≥ 0.35 g) within [windowMs] of [tMs]? Used to confirm vision. */
    fun hadBumpNear(tMs: Long, windowMs: Long): Boolean = lastBumpAtMs != Long.MIN_VALUE && abs(tMs - lastBumpAtMs) <= windowMs

    private fun push(t: Long, e: Float, gyro: Float) {
        histT[histHead] = t; histE[histHead] = e; histGyro[histHead] = gyro
        histHead = (histHead + 1) % histT.size
        if (histN < histT.size) histN++
    }

    private inline fun forRecent(t: Long, windowMs: Long, f: (Int) -> Unit) {
        for (k in 1..histN) {
            val i = (histHead - k + histT.size) % histT.size
            if (t - histT[i] > windowMs) break
            f(i)
        }
    }

    private fun meanOver(t: Long, w: Long): Float { var s = 0f; var n = 0; forRecent(t, w) { s += histE[it]; n++ }; return if (n == 0) 0f else s / n }
    private fun maxOver(t: Long, w: Long): Float { var m = 0f; forRecent(t, w) { m = max(m, histE[it]) }; return m }
    private fun maxGyroOver(t: Long, w: Long): Float { var m = 0f; forRecent(t, w) { m = max(m, histGyro[it]) }; return m }
}

package com.jeremy.dashcam.core.detect

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure (Android-free, unit-tested) on-device scene-anomaly detector working on a small luminance grid.
 *
 * Compared with a plain frame-difference it removes the usual false alarms of a dashcam:
 *  - Lighting jumps (tunnels, headlights, auto-exposure): frames are mean-normalised, and a sudden
 *    global brightness change pauses detection briefly instead of firing.
 *  - Camera shake / potholes / turning: the global shift between frames is estimated (ego-motion)
 *    and compensated, so only things moving *differently* from the scene count.
 *  - Sky, dashboard and side scenery: only a road-ahead region of interest is scored, with the
 *    centre lane weighted double.
 *  - Single noisy frames: an anomaly must persist in ≥ 3 of the last 5 frames and stand far above the
 *    adaptive baseline (z-score) and above an absolute floor.
 *
 * Designed so a semantic on-device / cloud AI model can later replace [process] behind the same API.
 */
class VisionAlgorithm(val gw: Int = 40, val gh: Int = 30) {

    enum class Level { LOW, MEDIUM, HIGH }
    var sensitivity: Level = Level.MEDIUM

    data class Frame(val score: Float, val candidate: Boolean, val strong: Boolean, val triggered: Boolean)

    private val cellThr get() = when (sensitivity) { Level.HIGH -> 20; Level.MEDIUM -> 26; Level.LOW -> 34 }
    private val minFrac get() = when (sensitivity) { Level.HIGH -> 0.10f; Level.MEDIUM -> 0.14f; Level.LOW -> 0.20f }
    private val zThr get() = when (sensitivity) { Level.HIGH -> 3.0f; Level.MEDIUM -> 4.0f; Level.LOW -> 5.0f }
    val scoreThreshold: Float get() = minFrac

    // Region of interest: road ahead (rows 30%–85%, cols 8%–92%), centre band weighted ×2.
    private val maxShift = 3
    private val r0 = maxOf(maxShift, (gh * 0.30f).toInt()); private val r1 = minOf(gh - maxShift, (gh * 0.85f).toInt())
    private val c0 = maxOf(maxShift, (gw * 0.08f).toInt()); private val c1 = minOf(gw - 1 - maxShift, (gw * 0.92f).toInt())
    private val cc0 = (gw * 0.30f).toInt(); private val cc1 = (gw * 0.70f).toInt()

    private var prev: IntArray? = null
    private var prevMean = 0f
    private var mu = 0f; private var varr = 0f; private var frames = 0
    private val recent = BooleanArray(5); private var recentIdx = 0
    private var pauseUntil = Long.MIN_VALUE
    private var lastTrigger = Long.MIN_VALUE / 2

    @Volatile var lastScore = 0f; private set
    @Volatile var lastShiftX = 0; private set
    @Volatile var lastShiftY = 0; private set

    fun reset() { prev = null; mu = 0f; varr = 0f; frames = 0; recent.fill(false); pauseUntil = Long.MIN_VALUE }

    /** @param grid luminance 0..255, row-major gw×gh. */
    fun process(grid: IntArray, tMs: Long): Frame {
        require(grid.size == gw * gh)
        var sum = 0L
        for (v in grid) sum += v
        val mean = sum.toFloat() / grid.size
        val norm = IntArray(grid.size) { grid[it] - mean.toInt() }
        val p = prev
        prev = norm
        val lightingJump = p != null && abs(mean - prevMean) > 18f
        prevMean = mean
        if (p == null) return Frame(0f, false, false, false)
        if (lightingJump) { pauseUntil = tMs + 1000; recent.fill(false); return Frame(0f, false, false, false) }
        if (tMs < pauseUntil) return Frame(0f, false, false, false)

        // 1) ego-motion: best global shift (dx,dy) minimising SAD in the ROI
        var bestDx = 0; var bestDy = 0; var best = Long.MAX_VALUE
        for (dy in -maxShift..maxShift) for (dx in -maxShift..maxShift) {
            var sad = 0L
            for (y in r0 until r1) {
                val py = (y + dy).coerceIn(0, gh - 1)
                for (x in c0 until c1) sad += abs(norm[y * gw + x] - p[py * gw + (x + dx)])
            }
            if (sad < best) { best = sad; bestDx = dx; bestDy = dy }
        }
        lastShiftX = bestDx; lastShiftY = bestDy

        // 2) residual after compensation → fraction of (weighted) ROI cells that changed a lot
        var active = 0f; var total = 0f
        val thr = cellThr
        for (y in r0 until r1) {
            val py = (y + bestDy).coerceIn(0, gh - 1)
            for (x in c0 until c1) {
                val w = if (x in cc0 until cc1) 2f else 1f
                total += w
                if (abs(norm[y * gw + x] - p[py * gw + (x + bestDx)]) > thr) active += w
            }
        }
        val score = active / total
        lastScore = score

        // 3) adaptive baseline (EMA mean/variance) → z-score
        frames++
        if (frames <= 15) {
            val a = 1f / frames
            val d = score - mu; mu += a * d; varr = (1 - a) * (varr + a * d * d)
            return Frame(score, false, false, false)
        }
        val sd = sqrt(varr + 1e-4f)
        val z = (score - mu) / sd
        val candidate = score >= minFrac && z >= zThr
        val strong = score >= minFrac * 1.6f && z >= zThr * 1.5f
        if (!candidate) { val a = 0.05f; val d = score - mu; mu += a * d; varr = (1 - a) * (varr + a * d * d) }

        // 4) temporal consistency: ≥ 3 of the last 5 frames
        recent[recentIdx] = candidate; recentIdx = (recentIdx + 1) % recent.size
        val hits = recent.count { it }
        val triggered = hits >= 3 && tMs - lastTrigger > 15_000
        if (triggered) { lastTrigger = tMs; recent.fill(false) }
        return Frame(score, candidate, strong, triggered)
    }
}

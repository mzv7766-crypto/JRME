package com.jeremy.dashcam.core.detect

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure (Android-free, unit-tested) on-device scene-anomaly detector working on a small luminance grid.
 *
 * It is deliberately conservative – it should only react to something genuinely unusual
 * (e.g. a vehicle suddenly filling the lane), never to ordinary night-time or lighting effects:
 *  - Lighting jumps (tunnels, auto-exposure): frames are mean-normalised; a sudden global brightness
 *    change pauses detection briefly instead of firing.
 *  - Light sources (headlights, street lamps, reflections): saturated / blooming cells are ignored.
 *  - Flicker (LED lights, signs, indicators): a cell that changes and then returns to how it was two
 *    frames earlier is treated as oscillation, not motion.
 *  - Scattered changes: the change must be concentrated in one connected region, as a real object is.
 *  - Night: thresholds are raised automatically when the scene is dark.
 *  - Camera shake / turning: the global shift between frames is estimated and compensated.
 *  - Sky, dashboard and scenery: only the road-ahead region is scored, the centre lane weighted ×2.
 *  - Single noisy frames: an anomaly must persist in ≥ 3 of the last 5 frames, far above an adaptive
 *    baseline (z-score) and above an absolute floor.
 */
class VisionAlgorithm(val gw: Int = 40, val gh: Int = 30) {

    enum class Level { LOW, MEDIUM, HIGH }
    var sensitivity: Level = Level.MEDIUM

    data class Frame(val score: Float, val candidate: Boolean, val strong: Boolean, val triggered: Boolean, val night: Boolean = false)

    private fun cellThr(night: Boolean): Int {
        val b = when (sensitivity) { Level.HIGH -> 20; Level.MEDIUM -> 26; Level.LOW -> 34 }
        return if (night) (b * 1.5f).toInt() else b
    }
    private fun minFrac(night: Boolean): Float {
        val b = when (sensitivity) { Level.HIGH -> 0.08f; Level.MEDIUM -> 0.11f; Level.LOW -> 0.17f }
        return if (night) b * 1.4f else b
    }
    private fun zThr(night: Boolean): Float {
        val b = when (sensitivity) { Level.HIGH -> 3.0f; Level.MEDIUM -> 4.0f; Level.LOW -> 5.0f }
        return if (night) b * 1.25f else b
    }
    val scoreThreshold: Float get() = minFrac(false)

    private val maxShift = 3
    private val r0 = maxOf(maxShift, (gh * 0.30f).toInt()); private val r1 = minOf(gh - maxShift, (gh * 0.85f).toInt())
    private val c0 = maxOf(maxShift, (gw * 0.08f).toInt()); private val c1 = minOf(gw - 1 - maxShift, (gw * 0.92f).toInt())
    private val cc0 = (gw * 0.30f).toInt(); private val cc1 = (gw * 0.70f).toInt()
    private val roiW = c1 - c0; private val roiH = r1 - r0
    private val act = FloatArray(roiW * roiH)
    private val seen = BooleanArray(roiW * roiH)
    private val stack = IntArray(roiW * roiH)

    private var prev: IntArray? = null
    private var prev2: IntArray? = null
    private var prevRaw: IntArray? = null
    private var prevMean = 0f
    private var mu = 0f; private var varr = 0f; private var frames = 0
    private val recent = BooleanArray(5); private var recentIdx = 0
    private var pauseUntil = Long.MIN_VALUE
    private var lastTrigger = Long.MIN_VALUE / 2

    @Volatile var lastScore = 0f; private set
    @Volatile var lastShiftX = 0; private set
    @Volatile var lastShiftY = 0; private set
    @Volatile var isNight = false; private set

    fun reset() { prev = null; prev2 = null; prevRaw = null; mu = 0f; varr = 0f; frames = 0; recent.fill(false); pauseUntil = Long.MIN_VALUE }

    /** @param grid luminance 0..255, row-major gw×gh. */
    fun process(grid: IntArray, tMs: Long): Frame {
        require(grid.size == gw * gh)
        var sum = 0L
        for (v in grid) sum += v
        val mean = sum.toFloat() / grid.size
        val night = mean < 55f
        isNight = night
        val norm = IntArray(grid.size) { grid[it] - mean.toInt() }
        val p = prev; val p2 = prev2; val pr = prevRaw
        prev2 = p; prev = norm; prevRaw = grid
        val lightingJump = p != null && abs(mean - prevMean) > 18f
        prevMean = mean
        if (p == null || pr == null) return Frame(0f, false, false, false, night)
        if (lightingJump) { pauseUntil = tMs + 1000; recent.fill(false); return Frame(0f, false, false, false, night) }
        if (tMs < pauseUntil) return Frame(0f, false, false, false, night)

        val thr = cellThr(night); val minF = minFrac(night); val zT = zThr(night)

        // 1) ego-motion: best global shift, accepted only when clearly better than "no movement"
        var bestDx = 0; var bestDy = 0; var best = Long.MAX_VALUE
        for (dy in -maxShift..maxShift) for (dx in -maxShift..maxShift) {
            var sad = 0L
            for (y in r0 until r1) {
                val py = (y + dy).coerceIn(0, gh - 1)
                for (x in c0 until c1) sad += abs(norm[y * gw + x] - p[py * gw + (x + dx)])
            }
            if (sad < best) { best = sad; bestDx = dx; bestDy = dy }
        }
        var sad0 = 0L
        for (y in r0 until r1) for (x in c0 until c1) sad0 += abs(norm[y * gw + x] - p[y * gw + x])
        if (best >= sad0 * 0.85) { bestDx = 0; bestDy = 0 }
        lastShiftX = bestDx; lastShiftY = bestDy

        // 2) per-cell change after compensation, ignoring light sources and flicker
        var total = 0f; var active = 0f
        act.fill(0f)
        for (y in r0 until r1) {
            val py = (y + bestDy).coerceIn(0, gh - 1)
            for (x in c0 until c1) {
                val w = if (x in cc0 until cc1) 2f else 1f
                total += w
                val i = y * gw + x; val j = py * gw + (x + bestDx)
                if (grid[i] >= 230 || pr[j] >= 230) continue                 // headlights / lamps / glare
                if (abs(norm[i] - p[j]) <= thr) continue
                if (p2 != null) {                                              // flicker: back to 2 frames ago
                    val k = if (abs(2 * bestDx) <= maxShift && abs(2 * bestDy) <= maxShift)
                        (y + 2 * bestDy).coerceIn(0, gh - 1) * gw + (x + 2 * bestDx) else j
                    if (abs(norm[i] - p2[k]) <= thr * 0.7f) continue
                }
                act[(y - r0) * roiW + (x - c0)] = w
                active += w
            }
        }

        // 3) concentration: size of the largest connected changed region
        val largest = largestBlob()
        val score = active / total
        lastScore = score
        val concentrated = largest >= maxOf(minF * 0.45f * total, 0.35f * active)

        // 4) adaptive baseline (EMA mean/variance) → z-score
        frames++
        if (frames <= 15) {
            val a = 1f / frames
            val d = score - mu; mu += a * d; varr = (1 - a) * (varr + a * d * d)
            return Frame(score, false, false, false, night)
        }
        val sd = maxOf(0.02f, sqrt(varr))
        val z = (score - mu) / sd
        val candidate = score >= minF && z >= zT && concentrated
        val strong = !night && candidate && score >= minF * 1.6f && z >= zT * 1.5f
        // the baseline only learns from clearly normal frames, so an ongoing event can't "teach" itself away
        if (!candidate && score < minF * 0.5f) { val a = 0.05f; val d = score - mu; mu += a * d; varr = (1 - a) * (varr + a * d * d) }

        // 5) temporal consistency: ≥ 3 of the last 5 frames
        recent[recentIdx] = candidate; recentIdx = (recentIdx + 1) % recent.size
        val hits = recent.count { it }
        val triggered = hits >= 3 && tMs - lastTrigger > 15_000
        if (triggered) { lastTrigger = tMs; recent.fill(false) }
        return Frame(score, candidate, strong, triggered, night)
    }

    private fun largestBlob(): Float {
        seen.fill(false)
        var largest = 0f
        for (start in act.indices) {
            if (act[start] == 0f || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var size = 0f
            while (sp > 0) {
                val c = stack[--sp]
                size += act[c]
                val cy = c / roiW; val cx = c % roiW
                if (cy > 0) push(c - roiW, sp).also { sp = it }
                if (cy < roiH - 1) push(c + roiW, sp).also { sp = it }
                if (cx > 0) push(c - 1, sp).also { sp = it }
                if (cx < roiW - 1) push(c + 1, sp).also { sp = it }
            }
            if (size > largest) largest = size
        }
        return largest
    }

    private fun push(n: Int, sp: Int): Int {
        if (act[n] == 0f || seen[n]) return sp
        seen[n] = true; stack[sp] = n
        return sp + 1
    }
}

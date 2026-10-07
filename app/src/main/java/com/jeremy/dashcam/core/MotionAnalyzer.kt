package com.jeremy.dashcam.core

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.jeremy.dashcam.data.Sensitivity
import kotlin.math.abs

/**
 * Local "smart" detector (no server): measures how much the scene changes between frames on a
 * coarse luminance grid and fires when the change suddenly spikes far above the recent baseline
 * (e.g. a sudden swerve, hard braking with a vehicle cutting in, a collision).
 * Designed so a semantic on-device / cloud AI model can later replace [score] → [onDetected].
 */
class MotionAnalyzer(private val onDetected: () -> Unit) : ImageAnalysis.Analyzer {
    @Volatile var enabled = false
    @Volatile var sensitivity: Sensitivity = Sensitivity.MEDIUM

    private val gw = 32; private val gh = 24
    private var prev: IntArray? = null
    private var baseline = -1f
    private var lastFrameAt = 0L
    private var lastTrigger = 0L
    private var warmup = 0

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!enabled || now - lastFrameAt < 150) return // ~6 fps is enough
            lastFrameAt = now
            val grid = sample(image)
            val p = prev
            prev = grid
            if (p == null) return
            var diff = 0L
            for (i in grid.indices) diff += abs(grid[i] - p[i])
            val score = diff.toFloat() / grid.size // 0..255
            if (baseline < 0) { baseline = score; return }
            val factor = when (sensitivity) { Sensitivity.HIGH -> 2.6f; Sensitivity.MEDIUM -> 3.4f; Sensitivity.LOW -> 4.5f }
            val minAbs = when (sensitivity) { Sensitivity.HIGH -> 18f; Sensitivity.MEDIUM -> 26f; Sensitivity.LOW -> 36f }
            if (warmup < 20) { warmup++; baseline = baseline * 0.8f + score * 0.2f; return }
            if (score > baseline * factor && score > minAbs && now - lastTrigger > 15_000) {
                lastTrigger = now
                onDetected()
            }
            baseline = baseline * 0.95f + score * 0.05f
        } finally {
            image.close()
        }
    }

    fun reset() { prev = null; baseline = -1f; warmup = 0 }

    private fun sample(image: ImageProxy): IntArray {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride; val ps = plane.pixelStride
        val w = image.width; val h = image.height
        val out = IntArray(gw * gh)
        for (gy in 0 until gh) {
            val y = (gy * h / gh) + h / (2 * gh)
            for (gx in 0 until gw) {
                val x = (gx * w / gw) + w / (2 * gw)
                val idx = y * rs + x * ps
                out[gy * gw + gx] = if (idx < buf.limit()) buf.get(idx).toInt() and 0xFF else 0
            }
        }
        return out
    }
}

package com.jeremy.dashcam.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.jeremy.dashcam.core.detect.ApproachAlgorithm
import com.jeremy.dashcam.core.detect.VisionAlgorithm
import com.jeremy.dashcam.core.pro.AiDetector
import com.jeremy.dashcam.core.pro.AiSeen
import com.jeremy.dashcam.data.Sensitivity

/**
 * CameraX analyzer: samples a 40×30 luminance grid (~7 fps) and runs [VisionAlgorithm].
 * Reports candidates to [onFrame] so the service can fuse them with the motion sensors.
 * PRO: when [aiWanted] is set, also runs the on-device AI detector (~4 fps) and reports dangers to [onAi].
 */
class MotionAnalyzer(
    private val context: Context,
    private val onFrame: (VisionAlgorithm.Frame) -> Unit,
    private val onAi: (ApproachAlgorithm.Danger) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile var enabled = false
    @Volatile var sensitivity: Sensitivity = Sensitivity.MEDIUM
    @Volatile var aiWanted = false
    /** Battery saver: run the AI and the luminance scan at a lower rate so the device stays cooler. */
    @Volatile var batterySaver = false

    val algorithm = VisionAlgorithm()
    val approach = ApproachAlgorithm()
    private var lastFrameAt = 0L
    private var lastAiAt = 0L

    // owned by the analysis thread
    private var ai: AiDetector? = null
    private var aiFailed = false

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            val aiInterval = if (batterySaver) 500L else 250L
            val visionInterval = if (batterySaver) 220L else 140L
            syncAi()
            val det = ai
            if (det != null && now - lastAiAt >= aiInterval) {
                lastAiAt = now
                runAi(det, image, now)
            }
            if (!enabled || now - lastFrameAt < visionInterval) return
            lastFrameAt = now
            algorithm.sensitivity = when (sensitivity) {
                Sensitivity.LOW -> VisionAlgorithm.Level.LOW
                Sensitivity.MEDIUM -> VisionAlgorithm.Level.MEDIUM
                Sensitivity.HIGH -> VisionAlgorithm.Level.HIGH
            }
            val f = algorithm.process(sample(image), now)
            if (f.candidate || f.triggered) onFrame(f)
        } catch (_: Exception) {
            // never let analysis break the camera pipeline
        } finally {
            image.close()
        }
    }

    private fun syncAi() {
        val want = aiWanted && enabled
        if (want && ai == null && !aiFailed) {
            ai = AiDetector.create(context)
            if (ai == null) aiFailed = true else { approach.reset(); Log.i("JeremyAI", "AI detector on") }
        } else if (!want && ai != null) {
            ai?.close(); ai = null; approach.reset()
        }
    }

    private fun runAi(det: AiDetector, image: ImageProxy, now: Long) {
        try {
            approach.sensitivity = when (sensitivity) {
                Sensitivity.LOW -> ApproachAlgorithm.Level.LOW
                Sensitivity.MEDIUM -> ApproachAlgorithm.Level.MEDIUM
                Sensitivity.HIGH -> ApproachAlgorithm.Level.HIGH
            }
            val bmp = image.toBitmap()
            val boxes = det.detect(bmp, image.imageInfo.rotationDegrees)
            if (boxes.isNotEmpty()) AiSeen.add(System.currentTimeMillis(), boxes.filter { it.size >= 0.06f }.map { it.label })
            approach.process(boxes, now)?.let(onAi)
        } catch (e: Throwable) {
            Log.w("JeremyAI", "AI frame failed", e)
        }
    }

    /** Must be called when the camera is gone, from any thread – the detector is closed on the next frame or here. */
    fun release() { aiWanted = false; ai?.close(); ai = null }

    fun reset() { algorithm.reset(); approach.reset() }

    /** Box-averaged luminance (each grid cell averages a 3×3 sample patch → much less sensor noise). */
    private fun sample(image: ImageProxy): IntArray {
        val gw = algorithm.gw; val gh = algorithm.gh
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride; val ps = plane.pixelStride
        val w = image.width; val h = image.height
        val rotated = image.imageInfo.rotationDegrees % 180 != 0
        val out = IntArray(gw * gh)
        val cellW = w / (if (rotated) gh else gw); val cellH = h / (if (rotated) gw else gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            // map the upright grid cell back to sensor coordinates
            val (sx, sy) = when (image.imageInfo.rotationDegrees) {
                90 -> gy to (gw - 1 - gx)
                180 -> (gw - 1 - gx) to (gh - 1 - gy)
                270 -> (gh - 1 - gy) to gx
                else -> gx to gy
            }
            val cx = sx * cellW + cellW / 2; val cy = sy * cellH + cellH / 2
            var s = 0; var n = 0
            for (oy in -1..1) for (ox in -1..1) {
                val x = (cx + ox * (cellW / 4)).coerceIn(0, w - 1)
                val y = (cy + oy * (cellH / 4)).coerceIn(0, h - 1)
                val idx = y * rs + x * ps
                if (idx < buf.limit()) { s += buf.get(idx).toInt() and 0xFF; n++ }
            }
            out[gy * gw + gx] = if (n > 0) s / n else 0
        }
        return out
    }
}

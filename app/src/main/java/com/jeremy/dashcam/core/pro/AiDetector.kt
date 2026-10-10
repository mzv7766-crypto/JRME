package com.jeremy.dashcam.core.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.jeremy.dashcam.core.detect.ApproachAlgorithm
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * PRO: on-device object detection (EfficientDet-Lite0, COCO) via MediaPipe. Runs entirely on the phone –
 * no images leave the device. Used by [com.jeremy.dashcam.core.MotionAnalyzer] on the analysis thread.
 */
class AiDetector private constructor(private val detector: ObjectDetector) {

    fun detect(frame: Bitmap, rotationDegrees: Int): List<ApproachAlgorithm.Box> {
        val upright = if (rotationDegrees % 360 == 0) frame
        else Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, false)
        val w = upright.width.toFloat(); val h = upright.height.toFloat()
        val result = detector.detect(BitmapImageBuilder(upright).build())
        return result.detections().mapNotNull { d ->
            val c = d.categories().firstOrNull() ?: return@mapNotNull null
            val bb = d.boundingBox()
            ApproachAlgorithm.Box(c.categoryName(), c.score(), bb.left / w, bb.top / h, bb.right / w, bb.bottom / h)
        }
    }

    fun close() = runCatching { detector.close() }

    companion object {
        private const val MODEL = "efficientdet_lite0.tflite"

        fun create(context: Context): AiDetector? = try {
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(10)
                .setScoreThreshold(0.35f)
                .setCategoryAllowlist(ApproachAlgorithm.LABELS)
                .build()
            AiDetector(ObjectDetector.createFromOptions(context, opts))
        } catch (e: Throwable) {
            Log.w("JeremyAI", "AI detector unavailable", e); null
        }
    }
}

/** What the AI saw recently (for tagging saved events). Thread-safe. */
object AiSeen {
    private val seen = ConcurrentLinkedDeque<Pair<Long, String>>()

    fun add(wallMs: Long, labels: Collection<String>) {
        labels.toSet().forEach { seen.addLast(wallMs to it) }
        while (seen.size > 2000 || (seen.peekFirst()?.first ?: wallMs) < wallMs - 10 * 60_000) seen.pollFirst()
    }

    /** Hebrew labels seen between [from] and [to], most frequent first. */
    fun between(from: Long, to: Long): List<String> = seen.filter { it.first in from..to }
        .groupingBy { ApproachAlgorithm.hebrew(it.second) }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }

    fun clear() = seen.clear()
}

package com.jeremy.dashcam.core.pro

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jeremy.dashcam.core.detect.ApproachAlgorithm
import com.jeremy.dashcam.data.EventRecord
import com.jeremy.dashcam.data.EventRepository
import com.jeremy.dashcam.data.RecordKind
import com.jeremy.dashcam.data.Sensitivity
import com.jeremy.dashcam.data.SettingsStore
import com.jeremy.dashcam.data.Trigger
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * PRO, experimental: instead of running the AI live (which heats the phone while driving), the drive is
 * recorded normally and the AI scans the trip footage AFTERWARDS — only while charging — to find the
 * important moments (fast-approaching vehicles / near-misses) and cut them out as events.
 *
 * Impact/shock events and the manual/voice button still work live and are unaffected; this only moves
 * the camera-vision detection off the drive. Detection quality on recorded frames must be validated on
 * real roads and tuned from there.
 */
object PostDriveAiScan {
    private const val TAG = "JeremyScan"
    private const val SAMPLE_MS = 400L          // one AI frame every 0.4 s
    private const val PRE_MS = 6_000L           // keep this much before the danger
    private const val POST_MS = 4_000L          // and this much after
    private const val GROUP_GAP_MS = 5_000L     // danger hits closer than this are one event

    fun enqueue(context: Context, tripId: Long, eventLogPath: String? = null) {
        if (tripId <= 0L) return
        val req = OneTimeWorkRequestBuilder<PostDriveScanWorker>()
            .setInputData(workDataOf("tripId" to tripId, "eventLog" to (eventLogPath ?: "")))
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("scan_$tripId", ExistingWorkPolicy.KEEP, req)
        Log.i(TAG, "post-drive AI scan queued for trip $tripId")
    }

    /** Runs the detector over one trip clip and returns the danger windows found (video-time ms). */
    fun scanClip(context: Context, file: File): List<Window> {
        if (!file.exists()) return emptyList()
        val detector = AiDetector.create(context) ?: return emptyList()
        val approach = ApproachAlgorithm().apply {
            sensitivity = when (SettingsStore.current.smartSensitivity) {
                Sensitivity.LOW -> ApproachAlgorithm.Level.LOW
                Sensitivity.MEDIUM -> ApproachAlgorithm.Level.MEDIUM
                Sensitivity.HIGH -> ApproachAlgorithm.Level.HIGH
            }
        }
        val mmr = MediaMetadataRetriever()
        val hits = ArrayList<Hit>()
        try {
            mmr.setDataSource(file.absolutePath)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return emptyList()
            var t = 0L
            while (t < durMs) {
                val bmp = runCatching { mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
                if (bmp != null) {
                    val boxes = runCatching { detector.detect(bmp, 0) }.getOrDefault(emptyList())
                    approach.process(boxes, t)?.let { d -> hits += Hit(t, d.label) }
                    bmp.recycle()
                }
                t += SAMPLE_MS
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan failed for ${file.name}", e)
        } finally {
            runCatching { mmr.release() }
            detector.close()
        }
        return groupWindows(hits)
    }

    private fun groupWindows(hits: List<Hit>): List<Window> {
        if (hits.isEmpty()) return emptyList()
        val out = ArrayList<Window>()
        var start = hits.first().tMs
        var end = hits.first().tMs
        val labels = LinkedHashSet<String>()
        labels += hits.first().label
        for (h in hits.drop(1)) {
            if (h.tMs - end <= GROUP_GAP_MS) { end = h.tMs; labels += h.label }
            else { out += Window(start, end, labels.toList()); start = h.tMs; end = h.tMs; labels.clear(); labels += h.label }
        }
        out += Window(start, end, labels.toList())
        return out
    }

    /** Copies the [fromUs,toUs] slice of [src] into [dst] without re-encoding. */
    fun cut(src: File, dst: File, fromUs: Long, toUs: Long): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(src.absolutePath)
            val indexMap = HashMap<Int, Int>()
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var maxInputSize = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInputSize = maxOf(maxInputSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    indexMap[i] = muxer.addTrack(fmt)
                }
            }
            if (indexMap.isEmpty()) return false
            muxer.start()
            val buffer = ByteBuffer.allocate(maxInputSize)
            val info = MediaCodec.BufferInfo()
            for (track in indexMap.keys) {
                extractor.unselectTrack(track)
            }
            for ((srcTrack, dstTrack) in indexMap) {
                extractor.selectTrack(srcTrack)
                extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val ptUs = extractor.sampleTime
                    if (ptUs > toUs) break
                    if (ptUs >= fromUs) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = ptUs - fromUs
                        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(dstTrack, buffer, info)
                    }
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(srcTrack)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "cut failed", e); dst.delete(); false
        } finally {
            runCatching { muxer?.stop() }; runCatching { muxer?.release() }; runCatching { extractor.release() }
        }
    }

    data class Hit(val tMs: Long, val label: String)
    data class Window(val startMs: Long, val endMs: Long, val labels: List<String>)
}

class PostDriveScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val tripId = inputData.getLong("tripId", 0L)
        if (tripId <= 0L) return Result.success()
        EventRepository.init(applicationContext)
        SettingsStore.init(applicationContext)
        val trips = EventRepository.events.value.filter { it.isTrip && it.tripId == tripId }
        if (trips.isEmpty()) return Result.success()

        var made = 0
        for (trip in trips) {
            val src = File(trip.filePath)
            val windows = runCatching { PostDriveAiScan.scanClip(applicationContext, src) }.getOrDefault(emptyList())
            for (w in windows) {
                val durMs = runCatching {
                    MediaMetadataRetriever().apply { setDataSource(src.absolutePath) }
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } .getOrNull() ?: Long.MAX_VALUE
                val fromMs = (w.startMs - 6_000L).coerceAtLeast(0)
                val toMs = (w.endMs + 4_000L).coerceAtMost(durMs)
                if (toMs - fromMs < 1500) continue
                val triggerWall = trip.videoStartTime + fromMs
                val out = EventRepository.newEventFile(triggerWall)
                val ok = PostDriveAiScan.cut(src, out, fromMs * 1000, toMs * 1000)
                if (ok && out.exists() && out.length() > 1024) {
                    EventRepository.add(
                        EventRecord(
                            id = UUID.randomUUID().toString(), filePath = out.absolutePath, name = null,
                            videoStartTime = triggerWall, triggerTime = triggerWall, durationMs = toMs - fromMs,
                            trigger = Trigger.AI, locked = false, sizeBytes = out.length(),
                            kind = RecordKind.EVENT, aiTags = w.labels,
                        )
                    )
                    made++
                }
            }
        }
        android.util.Log.i("JeremyScan", "post-drive scan done: trip $tripId → $made event(s)")
        // Write the result back into the drive's event log so the shared report shows the scan worked.
        runCatching {
            val path = inputData.getString("eventLog")
            if (!path.isNullOrBlank()) {
                val clips = trips.size
                java.io.File(path).appendText("🤖 סריקת AI הסתיימה: נסרקו $clips קטעי נסיעה → נוצרו $made אירועים\n")
            }
        }
        return Result.success()
    }
}

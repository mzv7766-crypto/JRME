package com.shomer.bodycam.core

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Joins rolling-buffer segments into ONE continuous MP4 and BURNS a body-camera data stamp INTO THE PIXELS
 * (top-left, like professional body cameras):
 *     SHOMER • <operator / badge>
 *     2026-10-11 04:55:12 +0300      ← the real capture time of every frame, not the export time
 *     31.77612, 35.21371             ← where the frame was filmed (when location is on)
 * Must be called on a thread with a Looper (we use the main thread).
 */
@OptIn(UnstableApi::class)
class Exporter(private val context: Context) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var gen = 0
    /** Extra line burned under the date/time for a given wall-clock moment (the position). */
    @Volatile var infoAt: ((Long) -> String?)? = null
    /** Operator name / badge number shown next to the brand. */
    @Volatile var operatorId: String = ""
    private var active: Transformer? = null

    /**
     * Cancels the running transcode (levels 0/1) so a more urgent export can start right away.
     * Returns false when nothing cancellable is running. The cancelled export never calls back.
     */
    fun cancel(): Boolean {
        val t = active ?: return false
        gen++; active = null
        runCatching { t.cancel() }
        return true
    }

    data class Piece(val file: File, val clipStartMs: Long, val durationMs: Long, val wallStartMs: Long)

    data class Result(val file: File, val durationMs: Long, val sizeBytes: Long, val videoStartWall: Long)

    fun export(
        pieces: List<Piece>,
        output: File,
        showDateTime: Boolean,
        burn: Boolean = true,
        onDone: (Result?) -> Unit,
    ) {
        require(pieces.isNotEmpty())
        output.parentFile?.mkdirs()
        val myGen = ++gen
        tryLevel(if (burn) 0 else 2, pieces, output, showDateTime) { r -> if (myGen == gen) onDone(r) }
    }

    /**
     * Fallback chain so an event is NEVER lost:
     *  0 – burned overlays + audio
     *  1 – burned overlays, audio removed (some devices/emulators record audio formats the encoder can't take)
     *  2 – plain lossless join of the segments with MediaMuxer (no overlays) – last resort
     */
    private fun tryLevel(level: Int, pieces: List<Piece>, output: File, showDateTime: Boolean, onDone: (Result?) -> Unit) {
        val total = pieces.sumOf { (it.durationMs - it.clipStartMs).coerceAtLeast(0) }
        val videoStart = pieces.first().wallStartMs + pieces.first().clipStartMs
        if (level >= 2) {
            active = null
            Log.w(TAG, "export: falling back to plain join (no overlays)")
            Thread {
                val ok = runCatching { SegmentJoiner.join(pieces, output) }.onFailure { Log.e(TAG, "plain join failed", it) }.getOrDefault(false)
                main.post { onDone(if (ok) Result(output, total, output.length(), videoStart) else null) }
            }.start()
            return
        }
        val composition = runCatching { buildComposition(pieces, showDateTime, withAudio = level == 0) }
            .getOrElse { Log.e(TAG, "composition failed", it); tryLevel(level + 1, pieces, output, showDateTime, onDone); return }
        Log.i(TAG, "export level $level, ${pieces.size} pieces")
        var thisTransformer: Transformer? = null
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            // slow devices: allow up to 60 s between muxed samples instead of the 10 s default
            .setMaxDelayBetweenMuxerSamplesMs(60_000)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (active !== thisTransformer) return
                    active = null
                    val dur = if (exportResult.durationMs > 0) exportResult.durationMs else total
                    Log.i(TAG, "export level $level done: ${dur}ms, ${output.length()} bytes")
                    onDone(Result(output, dur, output.length(), videoStart))
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (active !== thisTransformer) return
                    active = null
                    Log.e(TAG, "export level $level failed: ${exportException.errorCodeName}", exportException)
                    output.delete()
                    tryLevel(level + 1, pieces, output, showDateTime, onDone)
                }
            })
            .build()
        thisTransformer = transformer
        active = transformer
        try {
            transformer.start(composition, output.absolutePath)
        } catch (e: Exception) {
            active = null
            Log.e(TAG, "export level $level could not start", e)
            output.delete()
            tryLevel(level + 1, pieces, output, showDateTime, onDone)
        }
    }

    private fun buildComposition(pieces: List<Piece>, showDateTime: Boolean, withAudio: Boolean): Composition {
        val shortSide = probeShortSide(pieces.first().file)
        val brandPx = (shortSide * 0.048f).toInt().coerceAtLeast(22)
        val timePx = (shortSide * 0.036f).toInt().coerceAtLeast(16)
        // Effects are attached to EVERY clip (not the composition): this forces a real re-encode, so the
        // overlays are burned into the pixels.
        val items = pieces.map { p ->
            val media = MediaItem.Builder()
                .setUri(Uri.fromFile(p.file))
                .apply {
                    if (p.clipStartMs > 0) setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(p.clipStartMs).build()
                    )
                }.build()
            val overlays = ImmutableList.builder<TextureOverlay>()
            val header = "SHOMER" + operatorId.trim().takeIf { it.isNotEmpty() }?.let { " • $it" }.orEmpty()
            overlays.add(StampOverlay(p.wallStartMs + p.clipStartMs, timePx, (shortSide * 0.94f).toInt(), header, brandPx, showDateTime, infoAt))
            val videoEffects = ImmutableList.of<Effect>(OverlayEffect(overlays.build()))
            EditedMediaItem.Builder(media)
                .setRemoveAudio(!withAudio)
                .setEffects(Effects(ImmutableList.of(), videoEffects))
                .build()
        }
        return Composition.Builder(ImmutableList.of(EditedMediaItemSequence(items)))
            .apply { if (withAudio) experimentalSetForceAudioTrack(true) }
            .build()
    }

    /**
     * Dynamic overlay for ONE clip: the first frame it sees is the clip start, whose real capture
     * wall-clock time is [wallStartMs]; every later frame adds its offset. So the burned time is the
     * time the frame was filmed, not the export time.
     * Date/time and the Pro info (speed, location) go on separate lines, and each line is shrunk to fit
     * [maxWidthPx] so nothing is cut off at the edges of a portrait video.
     */
    private class StampOverlay(
        private val wallStartMs: Long, private val px: Int, private val maxWidthPx: Int,
        private val header: String, private val headerPx: Int,
        private val showTime: Boolean = true, private val info: ((Long) -> String?)? = null,
    ) : TextOverlay() {
        private val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss  Z", Locale.US)
        private var basePtsUs = Long.MIN_VALUE
        private val paint = android.text.TextPaint().apply { typeface = Typeface.DEFAULT_BOLD }
        private val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(-0.94f, 0.94f) // top-left of the video
            .setOverlayFrameAnchor(-1f, 1f)
            .build()

        private fun fit(line: String, size: Int): Int {
            paint.textSize = size.toFloat()
            val w = paint.measureText(line)
            return if (w <= maxWidthPx) size else (size * maxWidthPx / w).toInt().coerceAtLeast(10)
        }

        override fun getText(presentationTimeUs: Long): SpannableString {
            if (basePtsUs == Long.MIN_VALUE || presentationTimeUs < basePtsUs) basePtsUs = presentationTimeUs
            val wall = wallStartMs + (presentationTimeUs - basePtsUs) / 1000
            val lines = listOfNotNull(
                header to headerPx,
                if (showTime) fmt.format(Date(wall)) to px else null,
                info?.invoke(wall)?.takeIf { it.isNotBlank() }?.let { it to px },
            ).map { (t, size) -> " $t " to size }
            val sb = android.text.SpannableStringBuilder()
            lines.forEachIndexed { i, (line, size) ->
                if (i > 0) sb.append("\n")
                val start = sb.length
                sb.append(line)
                sb.setSpan(AbsoluteSizeSpan(fit(line, size)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(Color.argb(130, 0, 0, 0)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                // the brand line in safety amber, the data lines in white
                if (i == 0) sb.setSpan(ForegroundColorSpan(Color.rgb(255, 176, 32)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                else sb.setSpan(ForegroundColorSpan(Color.WHITE), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return SpannableString(sb).apply { span(StyleSpan(Typeface.BOLD)) }
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
    }

    private fun probeShortSide(f: File): Int {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(f.absolutePath)
            val w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            minOf(w, h)
        } catch (e: Exception) { 1080 } finally { runCatching { m.release() } }
    }

    companion object {
        private const val TAG = "Shomer"
        private fun SpannableString.span(what: Any) = setSpan(what, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

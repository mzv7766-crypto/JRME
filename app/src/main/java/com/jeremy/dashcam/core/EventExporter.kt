package com.jeremy.dashcam.core

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
 * Joins rolling-buffer segments into ONE continuous MP4 and BURNS INTO THE PIXELS:
 *  - "Jeremy" branding (always)
 *  - capture date/time at the top (optional), computed from the real capture clock of every frame,
 *    not from the export time.
 * Must be called on a thread with a Looper (we use the main thread).
 */
@OptIn(UnstableApi::class)
class EventExporter(private val context: Context) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    data class Piece(val file: File, val clipStartMs: Long, val durationMs: Long, val wallStartMs: Long)

    data class Result(val file: File, val durationMs: Long, val sizeBytes: Long, val videoStartWall: Long)

    fun export(
        pieces: List<Piece>,
        output: File,
        showDateTime: Boolean,
        onDone: (Result?) -> Unit,
    ) {
        require(pieces.isNotEmpty())
        output.parentFile?.mkdirs()
        tryLevel(0, pieces, output, showDateTime, onDone)
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
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            // slow devices: allow up to 60 s between muxed samples instead of the 10 s default
            .setMaxDelayBetweenMuxerSamplesMs(60_000)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val dur = if (exportResult.durationMs > 0) exportResult.durationMs else total
                    Log.i(TAG, "export level $level done: ${dur}ms, ${output.length()} bytes")
                    onDone(Result(output, dur, output.length(), videoStart))
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.e(TAG, "export level $level failed: ${exportException.errorCodeName}", exportException)
                    output.delete()
                    tryLevel(level + 1, pieces, output, showDateTime, onDone)
                }
            })
            .build()
        try {
            transformer.start(composition, output.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "export level $level could not start", e)
            output.delete()
            tryLevel(level + 1, pieces, output, showDateTime, onDone)
        }
    }

    private fun buildComposition(pieces: List<Piece>, showDateTime: Boolean, withAudio: Boolean): Composition {
        val shortSide = probeShortSide(pieces.first().file)
        val brandPx = (shortSide * 0.055f).toInt().coerceAtLeast(24)
        val timePx = (shortSide * 0.042f).toInt().coerceAtLeast(18)
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
            overlays.add(brandOverlay(brandPx))
            if (showDateTime) overlays.add(TimestampOverlay(p.wallStartMs + p.clipStartMs, timePx))
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

    private fun brandOverlay(px: Int): TextOverlay {
        val text = SpannableString(" Jeremy ").apply {
            span(ForegroundColorSpan(Color.WHITE)); span(StyleSpan(Typeface.BOLD)); span(AbsoluteSizeSpan(px))
            span(BackgroundColorSpan(Color.argb(70, 0, 0, 0)))
        }
        val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0.93f, -0.90f) // bottom-right of the video
            .setOverlayFrameAnchor(1f, -1f)
            .build()
        return TextOverlay.createStaticTextOverlay(text, settings)
    }

    /**
     * Dynamic overlay for ONE clip: the first frame it sees is the clip start, whose real capture
     * wall-clock time is [wallStartMs]; every later frame adds its offset. So the burned time is the
     * time the frame was filmed, not the export time.
     */
    private class TimestampOverlay(private val wallStartMs: Long, private val px: Int) : TextOverlay() {
        private val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)
        private var basePtsUs = Long.MIN_VALUE
        private val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, 0.90f) // top-centre of the video
            .setOverlayFrameAnchor(0f, 1f)
            .build()

        override fun getText(presentationTimeUs: Long): SpannableString {
            if (basePtsUs == Long.MIN_VALUE || presentationTimeUs < basePtsUs) basePtsUs = presentationTimeUs
            val wall = wallStartMs + (presentationTimeUs - basePtsUs) / 1000
            return SpannableString(" " + fmt.format(Date(wall)) + " ").apply {
                span(ForegroundColorSpan(Color.WHITE)); span(StyleSpan(Typeface.BOLD)); span(AbsoluteSizeSpan(px))
                span(BackgroundColorSpan(Color.argb(120, 0, 0, 0)))
            }
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
        private const val TAG = "Jeremy"
        private fun SpannableString.span(what: Any) = setSpan(what, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

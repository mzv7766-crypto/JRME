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

    data class Piece(val file: File, val clipStartMs: Long, val durationMs: Long, val wallStartMs: Long)

    data class Result(val file: File, val durationMs: Long, val sizeBytes: Long, val videoStartWall: Long)

    fun export(
        pieces: List<Piece>,
        output: File,
        showDateTime: Boolean,
        onDone: (Result?) -> Unit,
    ) {
        require(pieces.isNotEmpty())
        val shortSide = probeShortSide(pieces.first().file)
        val brandPx = (shortSide * 0.055f).toInt().coerceAtLeast(24)
        val timePx = (shortSide * 0.042f).toInt().coerceAtLeast(18)

        val items = pieces.mapIndexed { i, p ->
            val media = MediaItem.Builder()
                .setUri(Uri.fromFile(p.file))
                .apply {
                    if (p.clipStartMs > 0) setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(p.clipStartMs).build()
                    )
                }.build()
            EditedMediaItem.Builder(media).build()
        }

        val overlays = ImmutableList.builder<TextureOverlay>()
        overlays.add(brandOverlay(brandPx))
        if (showDateTime) overlays.add(TimestampOverlay(pieces, timePx))

        val composition = Composition.Builder(ImmutableList.of(EditedMediaItemSequence(items)))
            .setEffects(Effects(ImmutableList.of(), ImmutableList.of<androidx.media3.common.Effect>(OverlayEffect(overlays.build()))))
            .experimentalSetForceAudioTrack(true)
            .build()

        output.parentFile?.mkdirs()
        attempt(composition, output, pieces, retriesLeft = 1, onDone = onDone)
    }

    private fun attempt(composition: Composition, output: File, pieces: List<Piece>, retriesLeft: Int, onDone: (Result?) -> Unit) {
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val dur = if (exportResult.durationMs > 0) exportResult.durationMs else pieces.sumOf { it.durationMs }
                    onDone(Result(output, dur, output.length(), pieces.first().wallStartMs + pieces.first().clipStartMs))
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.e(TAG, "export failed (retries left $retriesLeft)", exportException)
                    output.delete()
                    if (retriesLeft > 0) attempt(composition, output, pieces, retriesLeft - 1, onDone) else onDone(null)
                }
            })
            .build()
        transformer.start(composition, output.absolutePath)
    }

    private fun brandOverlay(px: Int): TextOverlay {
        val text = SpannableString("Jeremy").apply {
            span(ForegroundColorSpan(Color.WHITE)); span(StyleSpan(Typeface.BOLD)); span(AbsoluteSizeSpan(px))
            span(BackgroundColorSpan(Color.argb(70, 0, 0, 0)))
        }
        val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0.93f, -0.90f) // bottom-right of the video
            .setOverlayFrameAnchor(1f, -1f)
            .build()
        return TextOverlay.createStaticTextOverlay(text, settings)
    }

    /** Dynamic overlay: maps each frame's presentation time back to the wall-clock capture time. */
    private class TimestampOverlay(pieces: List<Piece>, private val px: Int) : TextOverlay() {
        private val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)
        private val starts: LongArray     // composition-relative start of each piece (us)
        private val wallStarts: LongArray // wall clock (ms) at that start
        private var basePtsUs = Long.MIN_VALUE
        private val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(-0.93f, 0.92f) // top-left of the video
            .setOverlayFrameAnchor(-1f, 1f)
            .build()

        init {
            var acc = 0L
            starts = LongArray(pieces.size); wallStarts = LongArray(pieces.size)
            pieces.forEachIndexed { i, p ->
                starts[i] = acc
                wallStarts[i] = p.wallStartMs + p.clipStartMs
                acc += (p.durationMs - p.clipStartMs).coerceAtLeast(0) * 1000
            }
        }

        override fun getText(presentationTimeUs: Long): SpannableString {
            // Self-calibrate: the first frame we see is the start of the composition.
            if (basePtsUs == Long.MIN_VALUE) basePtsUs = presentationTimeUs
            val rel = (presentationTimeUs - basePtsUs).coerceAtLeast(0)
            var idx = 0
            for (i in starts.indices) if (rel >= starts[i]) idx = i
            val wall = wallStarts[idx] + (rel - starts[idx]) / 1000
            return SpannableString(fmt.format(Date(wall))).apply {
                span(ForegroundColorSpan(Color.WHITE)); span(AbsoluteSizeSpan(px))
                span(BackgroundColorSpan(Color.argb(110, 0, 0, 0)))
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
        private const val TAG = "EventExporter"
        private fun SpannableString.span(what: Any) = setSpan(what, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

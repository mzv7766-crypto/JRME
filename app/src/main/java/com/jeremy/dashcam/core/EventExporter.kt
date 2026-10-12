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
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
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
    private var gen = 0
    /** PRO: extra text burned next to the date/time for a given wall-clock moment (speed, position). */
    var infoAt: ((Long) -> String?)? = null
    /** PRO users may hide the Jeremy logo. */
    @Volatile var showBrand = true
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
            if (showBrand) overlays.add(brandOverlay(brandPx))
            if (showDateTime || infoAt != null) overlays.add(TimestampOverlay(p.wallStartMs + p.clipStartMs, timePx, (shortSide * 0.92f).toInt(), showDateTime, infoAt))
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

    /** The "Jeremy" wordmark: a soft rounded pill with a mint dot, bottom-right. */
    private fun brandOverlay(px: Int): TextureOverlay {
        val bmp = Chip.render(listOf("Jeremy"), px, Color.WHITE, Int.MAX_VALUE, accent = Chip.MINT)
        val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0.955f, -0.93f) // bottom-right
            .setOverlayFrameAnchor(1f, -1f)
            .build()
        return BitmapOverlay.createStaticBitmapOverlay(bmp, settings)
    }

    /**
     * Dynamic overlay for ONE clip: the first frame it sees is the clip start, whose real capture
     * wall-clock time is [wallStartMs]; every later frame adds its offset, so the burned time is the
     * time the frame was filmed, not the export time.
     * Date/time and the Pro info (speed, location) go on separate lines inside one soft rounded card,
     * auto-fitted to [maxWidthPx] so nothing is cut off at the edges of a portrait video. The bitmap is
     * only redrawn when the displayed text changes (about once a second).
     */
    private class TimestampOverlay(
        private val wallStartMs: Long, private val px: Int, private val maxWidthPx: Int,
        private val showTime: Boolean = true, private val info: ((Long) -> String?)? = null,
    ) : BitmapOverlay() {
        private val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)
        private var basePtsUs = Long.MIN_VALUE
        private var lastKey: String? = null
        private var cached: android.graphics.Bitmap? = null
        private val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, 0.92f) // top-centre
            .setOverlayFrameAnchor(0f, 1f)
            .build()

        override fun getBitmap(presentationTimeUs: Long): android.graphics.Bitmap {
            if (basePtsUs == Long.MIN_VALUE || presentationTimeUs < basePtsUs) basePtsUs = presentationTimeUs
            val wall = wallStartMs + (presentationTimeUs - basePtsUs) / 1000
            val lines = listOfNotNull(if (showTime) fmt.format(Date(wall)) else null, info?.invoke(wall)?.takeIf { it.isNotBlank() })
                .ifEmpty { listOf(" ") }
            val key = lines.joinToString("\u0001")
            if (key != lastKey || cached == null) {
                cached = Chip.render(lines, px, Color.WHITE, maxWidthPx, accent = 0)
                lastKey = key
            }
            return cached!!
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
    }

    /** Draws one or more text lines onto a bitmap: rounded translucent card, soft shadow, modern font. */
    private object Chip {
        const val MINT = 0xFF5BE3A6.toInt()
        private val bg = Color.argb(115, 6, 16, 11) // ~45% dark green, matches the app
        private val font = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        fun render(lines: List<String>, basePx: Int, textColor: Int, maxWidthPx: Int, accent: Int): android.graphics.Bitmap {
            val tp = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font; textSize = basePx.toFloat(); color = textColor
            }
            val padX = basePx * 0.62f
            val padY = basePx * 0.42f
            val gap = basePx * 0.26f
            val dotR = if (accent != 0) basePx * 0.17f else 0f
            val dotGap = if (accent != 0) basePx * 0.42f else 0f

            // shrink so the widest line (plus padding and the dot) fits maxWidthPx
            var widest = lines.maxOf { tp.measureText(it) }
            val need = widest + 2 * padX + dotR * 2 + dotGap
            if (maxWidthPx != Int.MAX_VALUE && need > maxWidthPx) {
                tp.textSize = (tp.textSize * (maxWidthPx - 2 * padX - dotR * 2 - dotGap) / widest).coerceAtLeast(10f)
                widest = lines.maxOf { tp.measureText(it) }
            }
            tp.setShadowLayer(tp.textSize * 0.14f, 0f, tp.textSize * 0.06f, Color.argb(170, 0, 0, 0))

            val fm = tp.fontMetrics
            val lineH = -fm.ascent + fm.descent
            val textBlockW = widest + dotR * 2 + dotGap
            val w = Math.ceil((textBlockW + 2 * padX).toDouble()).toInt().coerceAtLeast(1)
            val h = Math.ceil((lineH * lines.size + gap * (lines.size - 1) + 2 * padY).toDouble()).toInt().coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)

            val radius = h * 0.28f
            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bg }
            canvas.drawRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, bgPaint)

            lines.forEachIndexed { i, line ->
                val lineW = tp.measureText(line)
                val blockLeft = (w - textBlockW) / 2f
                var x = blockLeft + (textBlockW - lineW - dotR * 2 - dotGap) / 2f + dotR * 2 + dotGap
                val baseline = padY - fm.ascent + i * (lineH + gap)
                if (accent != 0 && i == 0) {
                    val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accent }
                    canvas.drawCircle(blockLeft + dotR, padY + lineH / 2f, dotR, dotPaint)
                    x = blockLeft + dotR * 2 + dotGap
                }
                canvas.drawText(line, x, baseline, tp)
            }
            return bmp
        }
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

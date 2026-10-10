package com.jeremy.dashcam.core.pro

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PRO: reads licence plates from an event video, fully on the phone.
 * Only text ON VEHICLES counts: the on-device AI finds the cars / trucks / buses / motorcycles in each frame,
 * and only those areas are read (ML Kit text recognition). So the burned-in date, time, speed and
 * coordinates, signs and other numbers are never reported as plates. A plate must also be seen in at
 * least two frames and have a real plate format (Israeli 7/8 digits, or a short letters+digits plate).
 */
object PlateReader {
    data class Plate(val text: String, val hits: Int, val atMs: Long)

    // separators that appear on plates: dash or space (never '.', ':' or '/', which come from times, dates and coordinates)
    private val israeli7 = Regex("""(?<![\d.:/])(\d{2})[-– ]?(\d{3})[-– ]?(\d{2})(?![\d.:/])""")
    private val israeli8 = Regex("""(?<![\d.:/])(\d{3})[-– ]?(\d{2})[-– ]?(\d{3})(?![\d.:/])""")
    private val generic = Regex("""(?<![A-Z0-9.:/])(?=[A-Z0-9-]{5,9}(?![A-Z0-9]))(?=[A-Z0-9-]*[A-Z])(?=[A-Z0-9-]*\d)[A-Z0-9]{1,4}-?[A-Z0-9]{1,4}-?[A-Z0-9]{0,4}(?![A-Z0-9.:/])""")
    private val VEHICLES = setOf("car", "truck", "bus", "motorcycle")

    /** Reads plates in [fromMs]..[toMs] of the video (relative to its start). Blocking – call off the main thread. */
    fun read(context: Context, video: File, fromMs: Long, toMs: Long, stepMs: Long = 400): List<Plate> {
        val detector = AiDetector.create(context)
        if (detector == null) { Log.w("Jeremy", "plates: no vehicle detector – skipped"); return emptyList() }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val mmr = MediaMetadataRetriever()
        val found = LinkedHashMap<String, Plate>()
        try {
            mmr.setDataSource(video.absolutePath)
            var t = fromMs.coerceAtLeast(0)
            while (t <= toMs) {
                val frame: Bitmap? = mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false)
                    val cars = runCatching { detector.detect(argb, 0) }.getOrDefault(emptyList())
                        .filter { it.label in VEHICLES && it.score >= 0.4f && it.w >= 0.06f }
                    for (c in cars) {
                        val crop = crop(argb, c.l, c.t, c.r, c.b) ?: continue
                        val text = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), 5, TimeUnit.SECONDS).text }.getOrDefault("")
                        for (p in extract(text)) {
                            val old = found[p]
                            found[p] = if (old == null) Plate(p, 1, t) else old.copy(hits = old.hits + 1)
                        }
                        if (crop !== argb) crop.recycle()
                    }
                    if (argb !== frame) argb.recycle()
                    frame.recycle()
                }
                t += stepMs
            }
        } catch (e: Exception) {
            Log.w("Jeremy", "plate reading failed", e)
        } finally {
            runCatching { mmr.release() }
            recognizer.close()
            detector.close()
        }
        return found.values.filter { it.hits >= 2 }.sortedByDescending { it.hits }.take(4)
    }

    /** Vehicle box (normalised) → bitmap, slightly enlarged and upscaled so small plates are readable. */
    private fun crop(src: Bitmap, l: Float, t: Float, r: Float, b: Float): Bitmap? {
        val padX = (r - l) * 0.06f; val padY = (b - t) * 0.06f
        val x0 = ((l - padX) * src.width).toInt().coerceIn(0, src.width - 1)
        val y0 = ((t - padY) * src.height).toInt().coerceIn(0, src.height - 1)
        val x1 = ((r + padX) * src.width).toInt().coerceIn(x0 + 1, src.width)
        val y1 = ((b + padY) * src.height).toInt().coerceIn(y0 + 1, src.height)
        if (x1 - x0 < 40 || y1 - y0 < 30) return null
        val c = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
        val w = c.width
        if (w >= 640) return c
        val scale = 640f / w
        return Bitmap.createScaledBitmap(c, 640, (c.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== c) c.recycle() }
    }

    /**
     * Normalises common OCR confusions and pulls plate-shaped tokens out of free text.
     * Long lines (a plate is short text on its own) and lines that look like a date, time or coordinates are ignored.
     */
    fun extract(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in raw.lines()) {
            val compact = line.filter { it.isLetterOrDigit() }
            if (compact.length !in 5..12) continue
            if (line.contains(':') || line.contains('/') || Regex("""\d+\.\d+""").containsMatchIn(line)) continue
            val digitsFixed = line.uppercase()
                .replace('O', '0').replace('Q', '0').replace('D', '0')
                .replace('I', '1').replace('L', '1').replace('|', '1')
                .replace('S', '5').replace('B', '8').replace('Z', '2')
            israeli8.findAll(digitsFixed).forEach { out += "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
            israeli7.findAll(digitsFixed).forEach { m ->
                val s = "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
                if (out.none { it.replace("-", "").contains(s.replace("-", "")) }) out += s
            }
            if (out.isEmpty()) generic.findAll(line.uppercase()).forEach { out += it.value }
        }
        return out.toList()
    }
}

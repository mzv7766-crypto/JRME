package com.jeremy.dashcam.core.pro

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
 * PRO: reads licence plates from an event video, fully on the phone (ML Kit text recognition).
 * Samples frames around the moment of the event, OCRs them and keeps plate-shaped numbers
 * (Israeli 7/8-digit formats, plus generic 5–8 character plates), ranked by how often they were seen.
 */
object PlateReader {
    data class Plate(val text: String, val hits: Int, val atMs: Long)

    private val israeli7 = Regex("""(?<!\d)(\d{2})[-–·. ]?(\d{3})[-–·. ]?(\d{2})(?!\d)""")
    private val israeli8 = Regex("""(?<!\d)(\d{3})[-–·. ]?(\d{2})[-–·. ]?(\d{3})(?!\d)""")
    private val generic = Regex("""\b(?=[A-Z0-9-]{5,10}\b)(?=.*\d)[A-Z0-9]{1,4}-?[A-Z0-9]{1,4}-?[A-Z0-9]{0,4}\b""")

    /** Reads plates in [fromMs]..[toMs] of the video (relative to its start). Blocking – call off the main thread. */
    fun read(video: File, fromMs: Long, toMs: Long, stepMs: Long = 400): List<Plate> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val mmr = MediaMetadataRetriever()
        val found = LinkedHashMap<String, Plate>()
        try {
            mmr.setDataSource(video.absolutePath)
            var t = fromMs.coerceAtLeast(0)
            while (t <= toMs) {
                val bmp: Bitmap? = mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bmp != null) {
                    val text = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS).text }.getOrDefault("")
                    for (p in extract(text)) {
                        val old = found[p]
                        found[p] = if (old == null) Plate(p, 1, t) else old.copy(hits = old.hits + 1)
                    }
                    bmp.recycle()
                }
                t += stepMs
            }
        } catch (e: Exception) {
            Log.w("Jeremy", "plate reading failed", e)
        } finally {
            runCatching { mmr.release() }
            recognizer.close()
        }
        return found.values.sortedByDescending { it.hits }.take(6)
    }

    /** Normalises common OCR confusions and pulls plate-shaped tokens out of free text. */
    fun extract(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in raw.lines()) {
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

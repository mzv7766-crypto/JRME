package com.jeremy.dashcam.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile

/**
 * Records audio for the WHOLE drive from a SINGLE microphone stream that is opened once and never
 * closed until the drive ends. This is what stops music from stuttering: the old code recorded audio
 * inside every 10–60 s video segment, so the mic was re-opened on every segment boundary and the
 * system ducked other playback each time. Here the video segments are recorded WITHOUT audio, and the
 * sound is captured separately and continuously, then muxed back into each exported clip.
 *
 * Audio is written as raw PCM (16-bit mono) into rotating chunk files tagged with their wall-clock
 * start. Raw PCM needs no "finalize" step, so a window can be cut precisely at any moment — even mid
 * drive, while an event is being exported — and a chunk that is interrupted by a crash is still usable
 * up to the point it was written. [wavForWindows] assembles the exact slice an export needs as a WAV.
 *
 * All public methods are safe to call from any thread and never throw; a device that refuses the mic
 * simply leaves the recorder inactive and the service falls back to per-segment audio.
 */
class ContinuousAudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "JeremyAudio"
        const val SAMPLE_RATE = 44_100
        private const val BYTES_PER_SAMPLE = 2 // 16-bit mono
        private const val CHUNK_MS = 60_000L   // rotate a chunk file about once a minute
    }

    private class Chunk(val file: File, val startWall: Long) {
        @Volatile var bytes: Long = 0L            // valid bytes written so far
        val endWall: Long get() = startWall + (bytes / BYTES_PER_SAMPLE) * 1000L / SAMPLE_RATE
    }

    private val dir = File(context.filesDir, "audio").apply { mkdirs() }
    private val lock = Any()
    private val chunks = ArrayList<Chunk>()
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** True once the mic is open and capturing; false means the caller should keep audio in the segments. */
    val active: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val minBuf = runCatching {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(AudioRecord.ERROR_BAD_VALUE)
        if (minBuf <= 0) { Log.w(TAG, "min buffer size unavailable"); return }
        val bufSize = minBuf * 4
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) { Log.w(TAG, "AudioRecord create failed", e); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { runCatching { rec.release() }; Log.w(TAG, "AudioRecord not initialised"); return }

        synchronized(lock) { chunks.clear() }
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        record = rec
        running = true
        try { rec.startRecording() } catch (e: Exception) { Log.w(TAG, "startRecording failed", e); running = false; runCatching { rec.release() }; record = null; return }

        thread = Thread({ loop(rec, bufSize) }, "jeremy-audio").apply { isDaemon = true; start() }
        Log.i(TAG, "continuous audio started @${SAMPLE_RATE}Hz")
    }

    private fun loop(rec: AudioRecord, bufSize: Int) {
        val buf = ByteArray(bufSize)
        var current: Chunk? = null
        var out: RandomAccessFile? = null
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break else continue }
                val now = System.currentTimeMillis()
                var c = current
                if (c == null || now - c.startWall >= CHUNK_MS) {
                    runCatching { out?.fd?.sync() }; runCatching { out?.close() }
                    c = Chunk(File(dir, "aud_$now.pcm"), now)
                    out = RandomAccessFile(c.file, "rw")
                    synchronized(lock) { chunks += c }
                    current = c
                }
                out!!.write(buf, 0, n)
                c.bytes += n
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio loop ended", e)
        } finally {
            runCatching { out?.fd?.sync() }; runCatching { out?.close() }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { thread?.join(1500) }
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        Log.i(TAG, "continuous audio stopped")
    }

    /** Drop chunks that ended before [beforeWall]; the live chunk is always kept. */
    fun prune(beforeWall: Long) {
        synchronized(lock) {
            val keep = chunks.lastOrNull()
            val it = chunks.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (c !== keep && c.endWall < beforeWall) { runCatching { c.file.delete() }; it.remove() }
            }
        }
    }

    /**
     * Builds a WAV covering the given wall-clock windows back-to-back (one window per video piece, so
     * inter-segment gaps are skipped in the audio exactly as they are in the video). Missing stretches
     * are filled with silence so the audio length always equals the summed window length and stays in
     * sync. Returns null if nothing could be produced.
     */
    fun wavForWindows(windows: List<Pair<Long, Long>>, outFile: File): File? {
        val snapshot = synchronized(lock) { chunks.map { it to it.bytes } }
        if (snapshot.isEmpty()) return null
        var totalBytes = 0L
        return try {
            RandomAccessFile(outFile, "rw").use { raf ->
                raf.setLength(0)
                writeWavHeader(raf, 0) // placeholder, patched at the end
                val copyBuf = ByteArray(64 * 1024)
                for ((fromWall, toWall) in windows) {
                    if (toWall <= fromWall) continue
                    val want = msToBytes(toWall - fromWall)
                    var written = 0L
                    var cursorWall = fromWall
                    while (written < want) {
                        val chunkPair = snapshot.firstOrNull { (c, b) -> cursorWall >= c.startWall && cursorWall < c.startWall + bytesToMs(b) }
                        if (chunkPair == null) {
                            // no audio for this instant → silence up to the next chunk start (or the end)
                            val nextStart = snapshot.map { it.first.startWall }.filter { it > cursorWall }.minOrNull()
                            val silenceMs = if (nextStart != null && nextStart < toWall) nextStart - cursorWall else toWall - cursorWall
                            var silence = msToBytes(silenceMs).coerceAtMost(want - written)
                            if (silence <= 0L) silence = want - written
                            writeSilence(raf, silence, copyBuf)
                            written += silence; cursorWall += bytesToMs(silence)
                        } else {
                            val (c, validBytes) = chunkPair
                            val offsetInChunk = msToBytes(cursorWall - c.startWall).coerceIn(0, validBytes)
                            val avail = (validBytes - offsetInChunk).coerceAtLeast(0)
                            val take = avail.coerceAtMost(want - written)
                            if (take <= 0L) { // chunk exhausted before window end → silence the rest of this instant
                                writeSilence(raf, 2, copyBuf); written += 2; cursorWall += bytesToMs(2); continue
                            }
                            RandomAccessFile(c.file, "r").use { inRaf ->
                                inRaf.seek(offsetInChunk)
                                var left = take
                                while (left > 0) {
                                    val r = inRaf.read(copyBuf, 0, minOf(copyBuf.size.toLong(), left).toInt())
                                    if (r <= 0) break
                                    raf.write(copyBuf, 0, r); left -= r
                                }
                                val done = take - left
                                written += done; cursorWall += bytesToMs(done)
                                if (done < take) { writeSilence(raf, take - done, copyBuf); written += (take - done); cursorWall += bytesToMs(take - done) }
                            }
                        }
                    }
                    totalBytes += want
                }
                raf.seek(0)
                writeWavHeader(raf, totalBytes)
            }
            if (totalBytes <= 0L) { outFile.delete(); null } else outFile
        } catch (e: Exception) {
            Log.w(TAG, "wavForWindows failed", e); runCatching { outFile.delete() }; null
        }
    }

    private fun msToBytes(ms: Long): Long {
        val samples = ms * SAMPLE_RATE / 1000L
        return samples * BYTES_PER_SAMPLE
    }

    private fun bytesToMs(bytes: Long): Long = (bytes / BYTES_PER_SAMPLE) * 1000L / SAMPLE_RATE

    private fun writeSilence(raf: RandomAccessFile, bytes: Long, buf: ByteArray) {
        java.util.Arrays.fill(buf, 0)
        var left = bytes
        while (left > 0) { val n = minOf(buf.size.toLong(), left).toInt(); raf.write(buf, 0, n); left -= n }
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataBytes: Long) {
        val byteRate = SAMPLE_RATE * BYTES_PER_SAMPLE
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)              // PCM header size
        header.putShort(1.toShort())   // PCM format
        header.putShort(1.toShort())   // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(BYTES_PER_SAMPLE.toShort()) // block align
        header.putShort(16.toShort())  // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataBytes.toInt())
        raf.write(header.array())
    }
}

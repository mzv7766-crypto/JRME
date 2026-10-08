package com.jeremy.dashcam.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Last-resort exporter: losslessly concatenates the rolling-buffer segments into ONE MP4 with
 * MediaMuxer (no decoding/encoding, so it works even when the device can't transcode).
 * The first segment is trimmed to the nearest key frame before the requested start.
 * Audio is kept only if every segment has an MP4-compatible (AAC) audio track.
 */
object SegmentJoiner {

    fun join(pieces: List<EventExporter.Piece>, output: File): Boolean {
        if (pieces.isEmpty()) return false
        output.delete()
        val first = pieces.first().file
        val videoFormat = trackFormat(first, "video/") ?: return false
        val audioOk = pieces.all { trackFormat(it.file, "audio/")?.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
        val audioFormat = if (audioOk) trackFormat(first, "audio/") else null

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            rotation(first)?.let { muxer.setOrientationHint(it) }
            val vOut = muxer.addTrack(videoFormat)
            val aOut = audioFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.start()

            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var offsetUs = 0L
            var wroteAny = false
            for ((idx, p) in pieces.withIndex()) {
                val clipUs = if (idx == 0) p.clipStartMs * 1000 else 0L
                var startUs = Long.MIN_VALUE
                var maxUs = 0L
                for ((mime, outTrack) in listOf("video/" to vOut, "audio/" to aOut)) {
                    if (outTrack < 0) continue
                    val ex = MediaExtractor()
                    try {
                        ex.setDataSource(p.file.absolutePath)
                        val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(mime) == true } ?: continue
                        ex.selectTrack(t)
                        ex.seekTo(clipUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        while (true) {
                            buf.clear()
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) break
                            val ts = ex.sampleTime
                            if (startUs == Long.MIN_VALUE) startUs = ts   // video is processed first → anchors the piece
                            if (ts < startUs) { ex.advance(); continue }
                            info.set(0, size, offsetUs + (ts - startUs), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            muxer.writeSampleData(outTrack, buf, info)
                            wroteAny = true
                            maxUs = maxOf(maxUs, ts - startUs)
                            ex.advance()
                        }
                    } finally {
                        ex.release()
                    }
                }
                offsetUs += maxUs + 33_000
            }
            muxer.stop()
            return wroteAny && output.length() > 1024
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            runCatching { muxer.release() }
        }
    }

    private fun trackFormat(f: File, prefix: String): MediaFormat? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
        } catch (e: Exception) { null } finally { ex.release() }
    }

    private fun rotation(f: File): Int? {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(f.absolutePath)
            m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } catch (e: Exception) { null } finally { runCatching { m.release() } }
    }
}

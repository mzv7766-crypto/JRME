package com.shomer.bodycam.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Log
import com.shomer.bodycam.R
import com.shomer.bodycam.core.Evidence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** What started a recording. */
enum class Trigger(val labelRes: Int) {
    BUTTON(R.string.trigger_button),
    FLOATING(R.string.trigger_floating),
    NOTIFICATION(R.string.trigger_notification),
    VOLUME(R.string.trigger_volume),
    VOICE(R.string.trigger_voice),
    SHAKE(R.string.trigger_shake),
    FALL(R.string.trigger_fall),
    ;
    val automatic get() = this == FALL
}

/** INCIDENT = a recording the user started (with the pre-record buffer); SHIFT = part of the full-shift recording. */
enum class RecordKind { INCIDENT, SHIFT }

data class Recording(
    val id: String,
    val filePath: String,
    val title: String?,
    val note: String?,
    /** Wall-clock time of the first frame (includes the pre-record buffer). */
    val videoStartTime: Long,
    /** Wall-clock time at which recording was started. */
    val triggerTime: Long,
    val durationMs: Long,
    val trigger: Trigger,
    val locked: Boolean,
    val sizeBytes: Long,
    val kind: RecordKind = RecordKind.INCIDENT,
    val shiftId: Long = 0L,
    val operatorId: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** SHA-256 of the file, computed right after it was written. */
    val sha256: String? = null,
) {
    val isShift get() = kind == RecordKind.SHIFT
    val endTime get() = videoStartTime + durationMs
    val file: File get() = File(filePath)
    val preRecordMs get() = (triggerTime - videoStartTime).coerceAtLeast(0)
}

/** Saved recordings, persisted as a JSON index next to the MP4 files. Thread-safe via synchronized. */
object RecordRepository {
    private const val TAG = "Shomer"
    private lateinit var appContext: Context
    private val _records = MutableStateFlow<List<Recording>>(emptyList())
    val records: StateFlow<List<Recording>> = _records.asStateFlow()

    private val moviesDir by lazy { appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: appContext.filesDir }
    val incidentsDir: File by lazy { File(moviesDir, "incidents").apply { mkdirs() } }
    val shiftDir: File by lazy { File(moviesDir, "shift").apply { mkdirs() } }
    private val thumbsDir: File by lazy { File(appContext.filesDir, "thumbs").apply { mkdirs() } }
    private val indexFile: File by lazy { File(appContext.filesDir, "records.json") }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        synchronized(this) { _records.value = readIndex().filter { it.file.exists() }.sortedByDescending { it.triggerTime } }
    }

    fun newIncidentFile(t: Long) = File(incidentsDir, "SHOMER_${stamp(t)}.mp4")
    fun newShiftFile(t: Long) = File(shiftDir, "SHOMER_shift_${stamp(t)}.mp4")
    private fun stamp(t: Long) = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(t))

    fun get(id: String): Recording? = _records.value.firstOrNull { it.id == id }
    fun thumbFile(id: String) = File(thumbsDir, "$id.jpg")

    /** Adds a finished recording: thumbnail, evidence hash (also written as a .sha256 file next to the video), index. */
    fun add(record: Recording): Recording {
        val hash = runCatching { Evidence.sha256(record.file) }.onFailure { Log.w(TAG, "hash failed", it) }.getOrNull()
        val r = record.copy(sha256 = hash)
        if (hash != null) runCatching { Evidence.writeSidecar(r) }
        synchronized(this) {
            createThumbnail(r)
            _records.value = (listOf(r) + _records.value.filter { it.id != r.id }).sortedByDescending { it.triggerTime }
            writeIndex()
            enforceLocked()
        }
        return r
    }

    fun rename(id: String, title: String) = mutate(id) { it.copy(title = title.trim().ifEmpty { null }) }
    fun setNote(id: String, note: String) = mutate(id) { it.copy(note = note.trim().ifEmpty { null }) }
    fun setLocked(id: String, locked: Boolean) = mutate(id) { it.copy(locked = locked) }

    fun delete(id: String): Boolean = synchronized(this) {
        val r = get(id) ?: return false
        if (r.locked) return false
        r.file.delete(); Evidence.sidecar(r.file).delete(); thumbFile(id).delete()
        _records.value = _records.value.filter { it.id != id }
        writeIndex()
        true
    }

    fun usedBytes(kind: RecordKind? = null): Long = _records.value.filter { kind == null || it.kind == kind }.sumOf { it.sizeBytes }

    fun enforceStorageLimit() = synchronized(this) { enforceLocked() }

    /** Per kind, removes the oldest UNLOCKED recordings until under the limit. Locked ones are never touched. */
    private fun enforceLocked() {
        val s = SettingsStore.current
        val removed = mutableSetOf<String>()
        fun trim(kind: RecordKind, limitBytes: Long) {
            var used = usedBytes(kind)
            if (used <= limitBytes) return
            for (c in _records.value.filter { it.kind == kind && !it.locked }.sortedBy { it.triggerTime }) {
                if (used <= limitBytes) break
                c.file.delete(); Evidence.sidecar(c.file).delete(); thumbFile(c.id).delete(); used -= c.sizeBytes; removed += c.id
            }
        }
        if (s.autoDelete) trim(RecordKind.INCIDENT, s.maxStorageGb.toLong() shl 30)
        trim(RecordKind.SHIFT, s.shiftMaxStorageGb.toLong() shl 30)
        // the phone itself is almost full: shift clips go first
        val free = runCatching { shiftDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < (1L shl 30)) {
            var need = (1L shl 30) - free
            for (c in _records.value.filter { it.isShift && !it.locked && it.id !in removed }.sortedBy { it.triggerTime }) {
                if (need <= 0) break
                c.file.delete(); Evidence.sidecar(c.file).delete(); thumbFile(c.id).delete(); need -= c.sizeBytes; removed += c.id
            }
        }
        if (removed.isNotEmpty()) {
            _records.value = _records.value.filter { it.id !in removed }
            writeIndex()
            Log.i(TAG, "auto-deleted ${removed.size} recordings")
        }
    }

    private fun mutate(id: String, f: (Recording) -> Recording) = synchronized(this) {
        _records.value = _records.value.map { if (it.id == id) f(it) else it }
        writeIndex()
    }

    private fun createThumbnail(r: Recording) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(r.filePath)
            val atUs = minOf(r.durationMs / 2, r.preRecordMs + 1_000L) * 1000
            val bmp = mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
            val scaled = Bitmap.createScaledBitmap(bmp, 320, (320f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
            FileOutputStream(thumbFile(r.id)).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail failed", e)
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun readIndex(): List<Recording> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Recording(
                id = o.getString("id"),
                filePath = o.getString("path"),
                title = o.optString("title").takeIf { it.isNotEmpty() },
                note = o.optString("note").takeIf { it.isNotEmpty() },
                videoStartTime = o.getLong("videoStart"),
                triggerTime = o.getLong("trigger"),
                durationMs = o.getLong("duration"),
                trigger = runCatching { Trigger.valueOf(o.getString("by")) }.getOrDefault(Trigger.BUTTON),
                locked = o.optBoolean("locked"),
                sizeBytes = o.optLong("size"),
                kind = if (o.optString("kind") == "SHIFT") RecordKind.SHIFT else RecordKind.INCIDENT,
                shiftId = o.optLong("shift"),
                operatorId = o.optString("op").takeIf { it.isNotEmpty() },
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lon = if (o.has("lon")) o.getDouble("lon") else null,
                sha256 = o.optString("sha256").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrElse { Log.e(TAG, "index read failed", it); emptyList() }

    private fun writeIndex() {
        val arr = JSONArray()
        _records.value.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("path", r.filePath); put("title", r.title ?: ""); put("note", r.note ?: "")
                put("videoStart", r.videoStartTime); put("trigger", r.triggerTime)
                put("duration", r.durationMs); put("by", r.trigger.name)
                put("locked", r.locked); put("size", r.sizeBytes)
                put("kind", r.kind.name); put("shift", r.shiftId)
                r.operatorId?.let { put("op", it) }
                r.lat?.let { put("lat", it) }; r.lon?.let { put("lon", it) }
                r.sha256?.let { put("sha256", it) }
            })
        }
        val tmp = File(indexFile.parentFile, "records.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
    }
}

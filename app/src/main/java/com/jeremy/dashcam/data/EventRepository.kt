package com.jeremy.dashcam.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Log
import com.jeremy.dashcam.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class Trigger(val labelRes: Int) {
    MANUAL(R.string.trigger_manual),
    FLOATING(R.string.trigger_floating),
    NOTIFICATION(R.string.trigger_notification),
    VOICE(R.string.trigger_voice),
    VOLUME(R.string.trigger_volume),
    SHOCK(R.string.trigger_shock),
    MOTION(R.string.trigger_motion),
}

data class EventRecord(
    val id: String,
    val filePath: String,
    val name: String?,
    /** Wall-clock time of the first frame in the saved video (includes pre-event buffer). */
    val videoStartTime: Long,
    /** Wall-clock time at which the event was triggered. */
    val triggerTime: Long,
    val durationMs: Long,
    val trigger: Trigger,
    val locked: Boolean,
    val sizeBytes: Long,
) {
    val file: File get() = File(filePath)
}

/** Persists saved events as a small JSON index next to the MP4 files. Thread-safe via synchronized. */
object EventRepository {
    private const val TAG = "EventRepository"
    private lateinit var appContext: Context
    private val _events = MutableStateFlow<List<EventRecord>>(emptyList())
    val events: StateFlow<List<EventRecord>> = _events.asStateFlow()

    val eventsDir: File by lazy {
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: appContext.filesDir, "events").apply { mkdirs() }
    }
    val thumbsDir: File by lazy { File(appContext.filesDir, "thumbs").apply { mkdirs() } }
    private val indexFile: File by lazy { File(appContext.filesDir, "events.json") }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        synchronized(this) { _events.value = readIndex().filter { it.file.exists() }.sortedByDescending { it.triggerTime } }
    }

    fun newEventFile(triggerTime: Long): File = File(eventsDir, "Jeremy_${triggerTime}.mp4")

    fun get(id: String): EventRecord? = _events.value.firstOrNull { it.id == id }

    fun thumbFile(id: String) = File(thumbsDir, "$id.jpg")

    fun add(record: EventRecord) {
        synchronized(this) {
            createThumbnail(record)
            _events.value = (listOf(record) + _events.value.filter { it.id != record.id }).sortedByDescending { it.triggerTime }
            writeIndex()
            enforceLocked()
        }
    }

    fun rename(id: String, name: String) = mutate(id) { it.copy(name = name.trim().ifEmpty { null }) }
    fun setLocked(id: String, locked: Boolean) = mutate(id) { it.copy(locked = locked) }

    fun delete(id: String): Boolean = synchronized(this) {
        val r = get(id) ?: return false
        if (r.locked) return false
        r.file.delete(); thumbFile(id).delete()
        _events.value = _events.value.filter { it.id != id }
        writeIndex()
        true
    }

    fun usedBytes(): Long = _events.value.sumOf { it.sizeBytes }

    /** Auto-delete: removes the oldest UNLOCKED events until under the limit. Locked events are never touched. */
    fun enforceStorageLimit() {
        synchronized(this) { enforceLocked() }
    }

    private fun enforceLocked() {
        val s = SettingsStore.current
        if (!s.autoDelete) return
        val limit = s.maxStorageGb.toLong() * 1024 * 1024 * 1024
        var used = usedBytes()
        if (used <= limit) return
        val candidates = _events.value.filter { !it.locked }.sortedBy { it.triggerTime }
        val removed = mutableSetOf<String>()
        for (c in candidates) {
            if (used <= limit) break
            c.file.delete(); thumbFile(c.id).delete()
            used -= c.sizeBytes
            removed += c.id
        }
        if (removed.isNotEmpty()) {
            _events.value = _events.value.filter { it.id !in removed }
            writeIndex()
            Log.i(TAG, "Auto-deleted ${removed.size} events")
        }
    }

    private fun mutate(id: String, f: (EventRecord) -> EventRecord) {
        synchronized(this) {
            _events.value = _events.value.map { if (it.id == id) f(it) else it }
            writeIndex()
        }
    }

    private fun createThumbnail(r: EventRecord) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(r.filePath)
            val atUs = minOf(r.durationMs / 2, 2_000L) * 1000
            val bmp = mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
            val scaled = Bitmap.createScaledBitmap(bmp, 320, (320f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
            FileOutputStream(thumbFile(r.id)).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail failed", e)
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun readIndex(): List<EventRecord> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            EventRecord(
                id = o.getString("id"),
                filePath = o.getString("path"),
                name = o.optString("name").takeIf { it.isNotEmpty() },
                videoStartTime = o.getLong("videoStart"),
                triggerTime = o.getLong("trigger"),
                durationMs = o.getLong("duration"),
                trigger = runCatching { Trigger.valueOf(o.getString("reason")) }.getOrDefault(Trigger.MANUAL),
                locked = o.optBoolean("locked"),
                sizeBytes = o.optLong("size"),
            )
        }
    }.getOrElse { Log.e(TAG, "index read failed", it); emptyList() }

    private fun writeIndex() {
        val arr = JSONArray()
        _events.value.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("path", r.filePath); put("name", r.name ?: "")
                put("videoStart", r.videoStartTime); put("trigger", r.triggerTime)
                put("duration", r.durationMs); put("reason", r.trigger.name)
                put("locked", r.locked); put("size", r.sizeBytes)
            })
        }
        val tmp = File(indexFile.parentFile, "events.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
    }
}

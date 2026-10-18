package com.shomer.bodycam.cloud

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.shomer.bodycam.BuildConfig
import com.shomer.bodycam.R
import com.shomer.bodycam.core.Haptics
import com.shomer.bodycam.core.LocationTracker
import com.shomer.bodycam.core.ShiftController
import com.shomer.bodycam.data.Trigger
import com.shomer.bodycam.service.Notifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A company guard's phone, while on shift: reports status + location to the control room, carries out
 * commands (start / stop recording), answers live-video requests and writes timeline events.
 * Runs only while the shift is on AND the phone belongs to a company with a paid plan.
 */
object Presence {
    // the control room marks a phone "no signal" after 2 minutes, so 40 s leaves room for two missed beats
    private const val HEARTBEAT_MS = 40_000L
    private lateinit var app: Context
    private var active: Pair<String, String>? = null      // orgId, email
    private var cmdListener: ListenerRegistration? = null
    private var heartbeat: Job? = null
    private var lastWrite = 0L
    private var lastLat = 0.0; private var lastLon = 0.0
    private var cmdHandled: String? = null
    private var sessionStart = 0L

    fun init(c: Context) {
        app = c.applicationContext
        val prefs = app.getSharedPreferences("shomer_presence", Context.MODE_PRIVATE)
        cmdHandled = prefs.getString("cmd", null)
        Cloud.scope.launch {
            combine(ShiftController.state.map { it.onShift && it.phase == com.shomer.bodycam.core.ShiftPhase.ON }.distinctUntilChanged(), OrgStore.state) { on, org ->
                if (on && org.activeMember) org.org!!.id to org.me!!.email else null
            }.distinctUntilChanged().collect { target -> switchTo(target) }
        }
        // push important changes right away (recording on/off) instead of waiting for the heartbeat
        Cloud.scope.launch {
            ShiftController.state.map { Triple(it.recording, it.trigger, it.savingCount) }.distinctUntilChanged().collect { if (active != null) write() }
        }
        Cloud.scope.launch {
            LocationTracker.latest.collect { f ->
                if (active == null || f == null) return@collect
                val moved = abs(f.lat - lastLat) > 0.0002 || abs(f.lon - lastLon) > 0.0002     // ~20 m
                if (moved && System.currentTimeMillis() - lastWrite > 15_000) write()
            }
        }
    }

    private fun switchTo(target: Pair<String, String>?) {
        if (target == active) return
        val old = active
        if (old != null) {
            // shift ended on this phone
            Cloud.org(old.first).collection("devices").document(old.second)
                .set(mapOf("onShift" to false, "recording" to false, "live" to false, "updatedAt" to System.currentTimeMillis()), SetOptions.merge())
            event("shift_end")
            cmdListener?.remove(); cmdListener = null
            heartbeat?.cancel(); heartbeat = null
            LiveSender.stop()
        }
        active = target
        if (target == null) return
        sessionStart = System.currentTimeMillis()
        write()
        event("shift_start")
        heartbeat = Cloud.scope.launch { while (true) { delay(HEARTBEAT_MS); write() } }
        cmdListener = Cloud.org(target.first).collection("cmd").document(target.second).addSnapshotListener { s, e ->
            if (e != null || s == null || !s.exists()) return@addSnapshotListener
            val id = s.getString("id") ?: return@addSnapshotListener
            val at = s.getLong("at") ?: 0L
            if (id == cmdHandled) return@addSnapshotListener
            remember(id)
            // ignore commands from before this shift (e.g. sent while the phone was off)
            if (at < sessionStart - 60_000) return@addSnapshotListener
            onCommand(s.getString("type"), s.getString("by"))
        }
        LiveSender.start(app, target.first, target.second)
    }

    private fun remember(id: String) {
        cmdHandled = id
        app.getSharedPreferences("shomer_presence", Context.MODE_PRIVATE).edit().putString("cmd", id).apply()
    }

    private fun onCommand(type: String?, by: String?) {
        Log.i(Cloud.TAG, "command $type from $by")
        when (type) {
            "start" -> if (!ShiftController.state.value.recording) {
                ShiftController.startRecording(Trigger.REMOTE)
                Notifications.notice(app, app.getString(R.string.remote_started_title), app.getString(R.string.remote_started_body, by ?: ""))
            }
            "stop" -> if (ShiftController.state.value.recording) {
                ShiftController.toggleRecording(Trigger.REMOTE)
                Notifications.notice(app, app.getString(R.string.remote_stopped_title), app.getString(R.string.remote_stopped_body, by ?: ""))
            }
        }
    }

    fun onLiveChanged(on: Boolean, viewer: String?) {
        if (active == null) return
        write()
        if (on) {
            Haptics.play(app, Haptics.Pattern.SHIFT_ON, force = true)
            Notifications.notice(app, app.getString(R.string.live_on_title), app.getString(R.string.live_on_body, viewer ?: ""))
            event("live_start", viewer)
        } else event("live_end")
    }

    /** Status document read by the dashboard. */
    private fun write() {
        val (orgId, email) = active ?: return
        val s = ShiftController.state.value
        val f = LocationTracker.latest.value
        val me = OrgStore.current.me
        val m = mutableMapOf<String, Any?>(
            "name" to me?.name, "badge" to me?.badge,
            "onShift" to true, "recording" to s.recording, "trigger" to s.trigger?.name, "recordStart" to s.recordStart,
            "battery" to battery(), "live" to (LiveSender.watcher.value != null),
            "updatedAt" to System.currentTimeMillis(), "model" to Build.MANUFACTURER + " " + Build.MODEL, "app" to BuildConfig.VERSION_NAME,
        )
        if (f != null) { m["lat"] = f.lat; m["lon"] = f.lon; m["acc"] = f.accuracy.toDouble(); m["locAt"] = f.wallMs; lastLat = f.lat; lastLon = f.lon }
        lastWrite = System.currentTimeMillis()
        Cloud.org(orgId).collection("devices").document(email).set(m, SetOptions.merge())
            .addOnFailureListener { Log.w(Cloud.TAG, "status write", it) }
    }

    /** Timeline entry for the control room (no-op for non-company phones). */
    fun event(type: String, detail: String? = null, extra: Map<String, Any?> = emptyMap()) {
        val (orgId, email) = active ?: return
        val f = LocationTracker.latest.value
        val me = OrgStore.current.me
        val m = mutableMapOf<String, Any?>("type" to type, "email" to email, "name" to (me?.stamp ?: email), "at" to System.currentTimeMillis(), "detail" to detail)
        if (f != null) { m["lat"] = f.lat; m["lon"] = f.lon }
        m.putAll(extra)
        Cloud.org(orgId).collection("events").add(m).addOnFailureListener { Log.w(Cloud.TAG, "event write", it) }
    }

    private fun battery(): Int? = runCatching { app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()
}

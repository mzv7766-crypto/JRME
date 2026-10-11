package com.shomer.bodycam.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording as CameraRecording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.shomer.bodycam.R
import com.shomer.bodycam.core.BodySensors
import com.shomer.bodycam.core.Exporter
import com.shomer.bodycam.core.Haptics
import com.shomer.bodycam.core.LocationTracker
import com.shomer.bodycam.core.ShiftController
import com.shomer.bodycam.core.ShiftPhase
import com.shomer.bodycam.core.ShiftState
import com.shomer.bodycam.core.VoiceCommander
import com.shomer.bodycam.core.detect.FallAlgorithm
import com.shomer.bodycam.data.AppSettings
import com.shomer.bodycam.data.Mounting
import com.shomer.bodycam.data.RecordKind
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.Recording
import com.shomer.bodycam.data.Resolution
import com.shomer.bodycam.data.SettingsStore
import com.shomer.bodycam.data.Trigger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Camera foreground service — the SINGLE OWNER of CameraX.
 *
 * While a shift is on, the camera records continuously into short segments (the pre-record buffer).
 * Starting a recording keeps the current segment open for the whole incident; stopping it joins the
 * buffer + incident into ONE MP4 with the body-camera stamp burned in, fingerprints it (SHA-256) and
 * (by default) locks it as evidence. Fall / shake detection, voice, volume keys, the floating button
 * and the notification all end up in [toggleRecording] / [startRecordingIfIdle].
 */
class BodycamService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.shomer.bodycam.START"
        const val ACTION_STOP = "com.shomer.bodycam.STOP"
        const val ACTION_TOGGLE = "com.shomer.bodycam.TOGGLE"
        private const val SEGMENT_MS = 10_000L
        private const val LOG = "Shomer"

        @Volatile
        var instance: BodycamService? = null
            private set
    }

    private data class Segment(val file: File, val startWall: Long, val durationMs: Long) {
        val endWall get() = startWall + durationMs
    }

    private val main = Handler(Looper.getMainLooper())
    private val state get() = ShiftController._state
    private val settings get() = SettingsStore.current

    // camera
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var appliedVideoKey: String? = null
    private var rebindPending = false
    private var candidateRotation: Int? = null
    private var candidateSince = 0L
    private var physicalRotation: Int? = null
    private var segmentRotation = Surface.ROTATION_0
    private var lastRotationSwitch = 0L
    private var orientationListener: OrientationEventListener? = null

    // pre-record buffer
    private val bufferDir by lazy { File(filesDir, "buffer").apply { mkdirs() } }
    private val segments = ArrayList<Segment>()
    private val protectedRefs = HashMap<File, Int>()
    private fun protect(files: Collection<File>) = files.forEach { protectedRefs[it] = (protectedRefs[it] ?: 0) + 1 }
    private fun unprotect(files: Collection<File>) = files.forEach { f ->
        val n = (protectedRefs[f] ?: 0) - 1
        if (n <= 0) protectedRefs.remove(f) else protectedRefs[f] = n
    }
    private fun isProtected(f: File) = protectedRefs.containsKey(f)

    private var activeRecording: CameraRecording? = null
    private var activeStartWall = 0L
    private val finalizeCallbacks = ArrayList<() -> Unit>()
    private var consecutiveFailures = 0
    private val rotateSegment = Runnable { activeRecording?.stop() }
    private val startSegmentRunnable = Runnable { startSegment() }

    // export queue: one at a time, incidents before shift clips
    private class ExportJob(val isIncident: Boolean, val run: (done: () -> Unit) -> Unit)
    private val exportQueue = ArrayDeque<ExportJob>()
    private var exportBusy = false
    private var runningJob: ExportJob? = null
    private var pendingShiftJobs = 0

    // full-shift recording
    private var shiftId = 0L
    private val shiftPending = ArrayList<Segment>()
    private var shiftClipStart = 0L

    // shift
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var appInForeground = true
    private val shiftJobs = ArrayList<Job>()

    private lateinit var exporter: Exporter
    private lateinit var sensors: BodySensors
    private lateinit var voice: VoiceCommander
    private lateinit var floating: FloatingButton
    private val manDown by lazy { ManDownOverlay(this) }

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { appInForeground = true; syncFloating() }
        override fun onStop(owner: LifecycleOwner) { appInForeground = false; syncFloating() }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        SettingsStore.init(this)
        RecordRepository.init(this)
        exporter = Exporter(this)
        sensors = BodySensors(this, onFall = { d -> main.post { onFall(d) } }, onShake = { main.post { onShake() } })
        voice = VoiceCommander(
            this,
            onStart = { startRecordingIfIdle(Trigger.VOICE) },
            onStop = { if (state.value.recording) stopRecording() },
        )
        floating = FloatingButton(this, onTap = { toggleRecording(Trigger.FLOATING) }, onLongPress = { openApp() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (!running) beginShift()
            ACTION_STOP -> endShift()
            ACTION_TOGGLE -> if (running) toggleRecording(Trigger.NOTIFICATION)
            else -> if (!running) { state.value = ShiftState(); stopSelf() }   // restarted by the system without UI
        }
        if (!running && idle() && intent?.action != ACTION_START) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) teardown()
        instance = null
        voice.release()
        manDown.dismiss()
        main.removeCallbacksAndMessages(null)
        if (state.value.onShift) state.value = ShiftState()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ shift

    private fun beginShift() {
        running = true
        val now = System.currentTimeMillis()
        state.update { it.copy(phase = ShiftPhase.STARTING, shiftStart = now, savedThisShift = 0, error = null) }
        try {
            enterForeground()
        } catch (e: Exception) {
            Log.e(LOG, "startForeground failed", e)
            running = false
            state.value = ShiftState(error = e.message)
            stopSelf(); return
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Shomer:shift").apply { setReferenceCounted(false); acquire() }

        bufferDir.listFiles()?.forEach { it.delete() }
        segments.clear()
        shiftId = now; shiftPending.clear(); shiftClipStart = 0L

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                val r = if (o == ORIENTATION_UNKNOWN) null else when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                val t = SystemClock.elapsedRealtime()
                if (r != candidateRotation) { candidateRotation = r; candidateSince = t; return }
                if (t - candidateSince >= 1500 && physicalRotation != r) { physicalRotation = r; onOrientationMaybeChanged() }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }

        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!running) return@addListener
            try { provider = future.get(); bindCamera() } catch (e: Exception) { onCameraError(e) }
        }, ContextCompat.getMainExecutor(this))

        shiftJobs += lifecycleScope.launch { SettingsStore.state.collect { applySettings(it) } }
        shiftJobs += lifecycleScope.launch {
            ShiftController.previewSurface.collect { sp ->
                runCatching { preview?.setSurfaceProvider(ContextCompat.getMainExecutor(this@BodycamService), sp) }
            }
        }
        shiftJobs += lifecycleScope.launch {
            state.map { Triple(it.recording, it.savingCount, it.phase) }.distinctUntilChanged().collect {
                if (running) getSystemService(NotificationManager::class.java).notify(Notifications.ID_SHIFT, Notifications.shift(this@BodycamService, state.value))
                syncFloating()
            }
        }
        Haptics.play(this, Haptics.Pattern.SHIFT_ON)
    }

    private fun enterForeground() {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasPerm(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
        }
        ServiceCompat.startForeground(this, Notifications.ID_SHIFT, Notifications.shift(this, state.value), type)
    }

    /** Ends the shift: saves a running recording, stops camera + sensors, removes the floating button. */
    fun endShift() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { endShift() }; return }
        if (!running) { if (idle()) stopSelf(); return }
        if (state.value.recording) stopRecording()
        teardown()
        Haptics.play(this, Haptics.Pattern.SHIFT_OFF)
        maybeFinish()
    }

    private fun teardown() {
        running = false
        shiftJobs.forEach { it.cancel() }; shiftJobs.clear()
        main.removeCallbacks(rotateSegment); main.removeCallbacks(startSegmentRunnable)
        sensors.stop(); voice.stop(); LocationTracker.stop(); manDown.dismiss()
        floating.hide()
        orientationListener?.disable(); orientationListener = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        val rec = activeRecording
        if (rec != null) {
            finalizeCallbacks += { unbindCamera(); flushShift(); cleanBuffer() }
            rec.stop()
        } else {
            unbindCamera(); flushShift(); cleanBuffer()
        }
        val saving = state.value.savingCount
        val background = pendingShiftJobs > 0 || exportBusy || exportQueue.isNotEmpty() || shiftPending.isNotEmpty() || activeRecording != null
        state.value = ShiftState(savingCount = saving, backgroundSaving = background)
        if (saving > 0 || background) getSystemService(NotificationManager::class.java).notify(Notifications.ID_SHIFT, Notifications.shift(this, state.value))
    }

    private fun idle() = state.value.savingCount == 0 && pendingShiftJobs == 0 && !exportBusy && exportQueue.isEmpty()
    private fun maybeFinish() { if (!running && idle()) finishService() }

    private fun finishService() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isOnShift() = running && state.value.phase == ShiftPhase.ON

    private fun openApp() {
        runCatching { Notifications.openAppIntent(this, "camera", req = 3).send() }
    }

    // ------------------------------------------------------------------ camera

    private fun videoKey(s: AppSettings) = "${s.resolution}/${s.fps}/${s.recordAudio}/${state.value.lensFacing}"

    private fun displayRotation(): Int = runCatching {
        getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).rotation
    }.getOrDefault(Surface.ROTATION_0)

    /** Recording orientation: the way the camera is worn (setting), or follow the phone (auto). */
    private fun desiredRotation(): Int {
        val base = physicalRotation ?: displayRotation()
        val isLandscape = base == Surface.ROTATION_90 || base == Surface.ROTATION_270
        return when (settings.mounting) {
            Mounting.PORTRAIT -> Surface.ROTATION_0
            Mounting.LANDSCAPE -> if (isLandscape) base else Surface.ROTATION_90
            Mounting.AUTO -> base
        }
    }

    private fun onOrientationMaybeChanged() {
        if (!running) return
        val want = desiredRotation()
        if (want == segmentRotation || state.value.recording) return    // one orientation per recording
        val rec = activeRecording ?: return
        val t = SystemClock.elapsedRealtime()
        if (t - lastRotationSwitch < 3000 || System.currentTimeMillis() - activeStartWall < 1500) return
        lastRotationSwitch = t
        main.removeCallbacks(rotateSegment)
        rec.stop()
    }

    @SuppressLint("RestrictedApi")
    private fun bindCamera() {
        val p = provider ?: return
        val s = settings
        p.unbindAll()
        val wanted = CameraSelector.Builder().requireLensFacing(state.value.lensFacing).build()
        if (!runCatching { p.hasCamera(wanted) }.getOrDefault(false)) state.update { it.copy(lensFacing = CameraSelector.LENS_FACING_BACK) }
        val selector = CameraSelector.Builder().requireLensFacing(state.value.lensFacing).build()

        val quality = when (s.resolution) { Resolution.HD -> Quality.HD; Resolution.FHD -> Quality.FHD; Resolution.UHD -> Quality.UHD }
        val qualitySelector = QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
        val newPreview = Preview.Builder().build()

        var bound: VideoCapture<Recorder>? = null
        var lastError: Exception? = null
        for (withFps in listOf(true, false)) {
            val vc = VideoCapture.Builder(Recorder.Builder().setQualitySelector(qualitySelector).build()).apply {
                if (withFps) setTargetFrameRate(Range(s.fps, s.fps))
            }.build().also { it.targetRotation = desiredRotation() }
            try {
                p.unbindAll()
                p.bindToLifecycle(this, selector, newPreview, vc)
                bound = vc
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(LOG, "bind failed fps=$withFps", e)
            }
        }
        if (bound == null) { onCameraError(lastError ?: IllegalStateException("bind failed")); return }

        preview = newPreview
        videoCapture = bound
        appliedVideoKey = videoKey(s)
        newPreview.setSurfaceProvider(ContextCompat.getMainExecutor(this), ShiftController.previewSurface.value)
        consecutiveFailures = 0
        val now = System.currentTimeMillis()
        state.update {
            it.copy(
                phase = ShiftPhase.ON, error = null, audioActive = s.recordAudio && hasPerm(Manifest.permission.RECORD_AUDIO),
                cameraSince = if (it.cameraSince == 0L || it.phase != ShiftPhase.ON) now else it.cameraSince,
            )
        }
        startSegment()
    }

    private fun unbindCamera() {
        runCatching { provider?.unbindAll() }
        preview = null; videoCapture = null; appliedVideoKey = null
    }

    private fun requestRebind() {
        if (provider == null || !running) return
        val rec = activeRecording
        if (rec != null) { rebindPending = true; rec.stop() } else bindCamera()
    }

    fun switchCamera() {
        if (!running || state.value.recording) return
        state.update {
            it.copy(lensFacing = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
        }
        requestRebind()
    }

    private fun onCameraError(e: Throwable) {
        Log.e(LOG, "camera error", e)
        state.update { it.copy(error = getString(R.string.camera_error, e.message ?: e.javaClass.simpleName)) }
    }

    // ------------------------------------------------------------------ pre-record buffer

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        if (!running || activeRecording != null) return
        val vc = videoCapture ?: return
        if (!state.value.recording) { segmentRotation = desiredRotation(); vc.targetRotation = segmentRotation }
        val land = segmentRotation == Surface.ROTATION_90 || segmentRotation == Surface.ROTATION_270
        if (state.value.recordingLandscape != land) state.update { it.copy(recordingLandscape = land) }
        val file = File(bufferDir, "seg_${System.currentTimeMillis()}.mp4")
        try {
            var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            if (settings.recordAudio && hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
            activeStartWall = System.currentTimeMillis()
            activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { ev -> onRecordEvent(file, ev) }
            // while recording an incident the segment is NOT rotated, so the incident is one gap-free take
            if (!state.value.recording) main.postDelayed(rotateSegment, SEGMENT_MS)
        } catch (e: Exception) {
            Log.e(LOG, "start segment failed", e)
            activeRecording = null
            scheduleRetry()
        }
    }

    private fun onRecordEvent(file: File, ev: VideoRecordEvent) {
        when (ev) {
            is VideoRecordEvent.Start -> activeStartWall = System.currentTimeMillis()
            is VideoRecordEvent.Finalize -> {
                main.removeCallbacks(rotateSegment)
                activeRecording = null
                val durMs = ev.recordingStats.recordedDurationNanos / 1_000_000
                val fatal = ev.error in setOf(
                    VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
                    VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
                    VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS,
                    VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR,
                )
                if (!fatal && file.exists() && file.length() > 1024 && durMs > 300) {
                    val seg = Segment(file, activeStartWall, durMs)
                    segments += seg
                    consecutiveFailures = 0
                    onShiftSegment(seg)
                } else {
                    file.delete()
                    if (ev.error != VideoRecordEvent.Finalize.ERROR_NONE) { Log.w(LOG, "segment failed: ${ev.error}", ev.cause); consecutiveFailures++ }
                }
                val callbacks = finalizeCallbacks.toList(); finalizeCallbacks.clear()
                callbacks.forEach { runCatching(it).onFailure { t -> Log.e(LOG, "finalize callback", t) } }
                pruneBuffer()
                when {
                    !running -> Unit
                    rebindPending -> { rebindPending = false; bindCamera() }
                    consecutiveFailures > 0 -> scheduleRetry()
                    else -> startSegment()
                }
            }
            else -> Unit
        }
    }

    private fun scheduleRetry() {
        main.removeCallbacks(startSegmentRunnable)
        main.postDelayed(startSegmentRunnable, 1000L * consecutiveFailures.coerceIn(1, 10))
        if (consecutiveFailures >= 5) requestRebind()
    }

    private fun pruneBuffer() {
        val s = state.value
        val pre = settings.preRecordSeconds * 1000L
        val keepFrom = if (s.recording) s.recordStart - pre - SEGMENT_MS else System.currentTimeMillis() - pre - 2 * SEGMENT_MS
        val it = segments.iterator()
        while (it.hasNext()) {
            val seg = it.next()
            if (seg.endWall < keepFrom && !isProtected(seg.file)) { seg.file.delete(); it.remove() }
        }
    }

    private fun cleanBuffer() {
        val it = segments.iterator()
        while (it.hasNext()) { val seg = it.next(); if (!isProtected(seg.file)) { seg.file.delete(); it.remove() } }
    }

    // ------------------------------------------------------------------ recording

    fun toggleRecording(trigger: Trigger) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { toggleRecording(trigger) }; return }
        if (state.value.recording) stopRecording() else startRecording(trigger)
    }

    fun startRecordingIfIdle(trigger: Trigger) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { startRecordingIfIdle(trigger) }; return }
        if (!state.value.recording) startRecording(trigger)
    }

    fun onVolumeAction(up: Boolean): Boolean {
        if (!running || !up) return false
        toggleRecording(Trigger.VOLUME)
        return true
    }

    private fun startRecording(trigger: Trigger) {
        if (!running || state.value.phase != ShiftPhase.ON || state.value.recording) return
        main.removeCallbacks(rotateSegment) // keep the current segment open for the whole recording
        val now = System.currentTimeMillis()
        state.update { it.copy(recording = true, recordStart = now, trigger = trigger) }
        Log.i(LOG, "recording started by $trigger")
        Haptics.play(this, Haptics.Pattern.STARTED)
        if (settings.pocketOnRecord && trigger != Trigger.FALL) ShiftController.pocket.value = true
    }

    /** Ends the running recording WITHOUT saving (only from inside the app, after confirmation). */
    fun discardRecording() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { discardRecording() }; return }
        if (!state.value.recording) return
        state.update { it.copy(recording = false, trigger = null) }
        main.post { applyPendingVideoChange() }
        if (activeRecording != null) { main.removeCallbacks(rotateSegment); main.postDelayed(rotateSegment, 2_000) }
        pruneBuffer()
        Haptics.play(this, Haptics.Pattern.STOPPED)
    }

    /** Ends the recording: closes the current segment, then joins pre-record + recording into ONE MP4. */
    private fun stopRecording() {
        val s = state.value
        if (!s.recording) return
        val stopTime = System.currentTimeMillis()
        val start = s.recordStart
        val trigger = s.trigger ?: Trigger.BUTTON
        val fix = LocationTracker.at(start) ?: LocationTracker.latest.value
        val pre = settings.preRecordSeconds * 1000L
        val showTime = settings.stampTime
        val operator = settings.operatorId.trim().ifEmpty { null }
        val lock = settings.autoLock
        state.update { it.copy(recording = false, trigger = null, savingCount = it.savingCount + 1) }
        Haptics.play(this, Haptics.Pattern.STOPPED)
        main.postDelayed({ applyPendingVideoChange() }, 1500)

        val collectAndExport = {
            val from = start - pre
            val chosen = segments.filter { it.endWall > from && it.startWall < stopTime }.sortedBy { it.startWall }
            if (chosen.isEmpty()) onExportFinished(null, null)
            else {
                val pieces = chosen.mapIndexed { i, seg ->
                    val clip = if (i == 0) (from - seg.startWall).coerceIn(0, (seg.durationMs - 200).coerceAtLeast(0)) else 0L
                    Exporter.Piece(seg.file, clip, seg.durationMs, seg.startWall)
                }
                val out = RecordRepository.newIncidentFile(start)
                protect(chosen.map { it.file })
                enqueueExport(ExportJob(isIncident = true) { done ->
                    exporter.export(pieces, out, showTime) { result ->
                        unprotect(chosen.map { it.file })
                        if (running) pruneBuffer() else cleanBuffer()
                        val record = result?.let {
                            Recording(
                                id = UUID.randomUUID().toString(), filePath = it.file.absolutePath, title = null, note = null,
                                videoStartTime = it.videoStartWall, triggerTime = start, durationMs = it.durationMs,
                                trigger = trigger, locked = lock, sizeBytes = it.sizeBytes,
                                operatorId = operator, lat = fix?.lat, lon = fix?.lon,
                            )
                        }
                        onExportFinished(record, out)
                        done()
                    }
                })
            }
        }
        if (activeRecording != null) { finalizeCallbacks += collectAndExport; activeRecording?.stop() } else collectAndExport()
    }

    private fun onExportFinished(record: Recording?, out: File?) {
        Thread {
            val saved = record?.let { RecordRepository.add(it) }   // hash + thumbnail + index
            main.post {
                state.update { it.copy(savingCount = (it.savingCount - 1).coerceAtLeast(0), savedThisShift = it.savedThisShift + if (saved != null) 1 else 0) }
                if (saved != null) {
                    Notifications.saved(this, saved)
                    ShiftController.lastSavedId.value = saved.id
                    Haptics.play(this, Haptics.Pattern.SAVED)
                } else {
                    out?.delete()
                    Notifications.failed(this)
                }
                maybeFinish()
            }
        }.start()
    }

    private fun enqueueExport(job: ExportJob) {
        val incidentsQueued = exportQueue.count { it.isIncident }
        if (job.isIncident) exportQueue.add(incidentsQueued, job) else exportQueue.addLast(job)
        // an incident never waits behind a long shift-clip transcode: pause the shift clip and redo it after
        val r = runningJob
        if (job.isIncident && r != null && !r.isIncident && exporter.cancel()) {
            exportQueue.add(incidentsQueued + 1, r)
            runningJob = null; exportBusy = false
        }
        pumpExports()
    }

    private fun pumpExports() {
        if (exportBusy) return
        val job = exportQueue.removeFirstOrNull() ?: return
        exportBusy = true; runningJob = job
        job.run {
            main.post {
                if (runningJob === job) { runningJob = null; exportBusy = false }
                pumpExports(); maybeFinish()
            }
        }
    }

    // ------------------------------------------------------------------ full-shift recording

    private fun onShiftSegment(seg: Segment) {
        if (!settings.fullShift) { if (shiftPending.isNotEmpty()) flushShift(); return }
        if (shiftPending.isEmpty()) shiftClipStart = seg.startWall
        shiftPending += seg
        protect(listOf(seg.file))
        if (seg.endWall - shiftClipStart >= settings.shiftClipMinutes * 60_000L) flushShift()
    }

    private fun flushShift() {
        if (shiftPending.isEmpty()) return
        val segs = shiftPending.toList(); shiftPending.clear()
        val files = segs.map { it.file }
        if (segs.sumOf { it.durationMs } < 1500) { unprotect(files); return }
        val pieces = segs.map { Exporter.Piece(it.file, 0, it.durationMs, it.startWall) }
        val start = segs.first().startWall
        val out = RecordRepository.newShiftFile(start)
        val thisShift = shiftId
        val showTime = settings.stampTime
        val operator = settings.operatorId.trim().ifEmpty { null }
        pendingShiftJobs++
        enqueueExport(ExportJob(isIncident = false) { done ->
            val burn = exportQueue.count { !it.isIncident } < 2   // falling behind → plain join, never lose the shift
            exporter.export(pieces, out, showTime, burn) { result ->
                unprotect(files)
                if (running) pruneBuffer() else cleanBuffer()
                val record = result?.let {
                    Recording(
                        id = UUID.randomUUID().toString(), filePath = it.file.absolutePath, title = null, note = null,
                        videoStartTime = it.videoStartWall, triggerTime = start, durationMs = it.durationMs,
                        trigger = Trigger.BUTTON, locked = false, sizeBytes = it.sizeBytes,
                        kind = RecordKind.SHIFT, shiftId = thisShift, operatorId = operator,
                    )
                }
                Thread {
                    if (record != null) RecordRepository.add(record) else out.delete()
                    main.post {
                        pendingShiftJobs--
                        if (!running && pendingShiftJobs == 0) state.update { it.copy(backgroundSaving = false) }
                        done()
                    }
                }.start()
            }
        })
    }

    // ------------------------------------------------------------------ sensors: man-down & shake

    private fun onFall(d: FallAlgorithm.Detection) {
        if (!running || !settings.fallDetection) return
        Log.i(LOG, "fall detected: peak ${d.peakG}g, turned ${d.angleDeg}°")
        startRecordingIfIdle(Trigger.FALL)
        Haptics.play(this, Haptics.Pattern.ALARM, force = true)
        val s = settings
        if (s.sosEnabled && s.sosNumber.isNotBlank()) {
            manDown.show(s.sosSeconds, s.sosName, s.sosNumber, test = false) { ManDownOverlay.call(this, s.sosNumber) }
        }
    }

    private fun onShake() {
        if (!running || !settings.shakeToRecord) return
        startRecordingIfIdle(Trigger.SHAKE)
    }

    // ------------------------------------------------------------------ settings / overlay

    private fun applySettings(s: AppSettings) {
        if (!running) return
        sensors.update(s.fallDetection, s.fallSensitivity, s.shakeToRecord)
        val locOk = s.location && (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION))
        if (locOk) LocationTracker.start(this) else LocationTracker.stop()
        exporter.infoAt = if (locOk && s.stampLocation) { t -> LocationTracker.at(t)?.let { LocationTracker.label(it) } } else null
        exporter.operatorId = s.operatorId
        onOrientationMaybeChanged()
        if (s.voiceCommands && hasPerm(Manifest.permission.RECORD_AUDIO)) voice.start(s.voiceStartPhrase, s.voiceStopPhrase) else voice.stop()
        syncFloating()
        if (appliedVideoKey != null && appliedVideoKey != videoKey(s) && !state.value.recording) requestRebind()
    }

    private fun applyPendingVideoChange() {
        if (running && appliedVideoKey != null && appliedVideoKey != videoKey(settings) && !state.value.recording) requestRebind()
    }

    private fun syncFloating() {
        val s = state.value
        if (running && s.onShift && !appInForeground && settings.floatingButton) floating.show(s.recording) else floating.hide()
        floating.setEventActive(s.recording)
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

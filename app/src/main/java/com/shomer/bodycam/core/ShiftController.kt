package com.shomer.bodycam.core

import android.content.Context
import android.content.Intent
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import com.shomer.bodycam.data.Trigger
import com.shomer.bodycam.service.BodycamService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ShiftPhase { OFF, STARTING, ON }

/**
 * A body camera has two states:
 *  - ON SHIFT (standby): the camera runs in the background and keeps only the last N seconds (pre-record);
 *    nothing is saved.
 *  - RECORDING: an incident is being documented. When it stops, the pre-record buffer + the recording are
 *    saved as one fingerprinted video.
 */
data class ShiftState(
    val phase: ShiftPhase = ShiftPhase.OFF,
    val shiftStart: Long = 0L,
    /** When the camera actually started buffering (pre-record fills up from here). */
    val cameraSince: Long = 0L,
    val recording: Boolean = false,
    val recordStart: Long = 0L,
    val trigger: Trigger? = null,
    val recordingLandscape: Boolean? = null,
    val savingCount: Int = 0,
    val backgroundSaving: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val audioActive: Boolean = false,
    val savedThisShift: Int = 0,
    val error: String? = null,
    /** Why the camera runs in power-saving mode right now (NONE = full quality). */
    val power: PowerGuard.Reason = PowerGuard.Reason.NONE,
    /** The live-video stream to the control room is attached to the camera. */
    val liveStream: Boolean = false,
) {
    val onShift get() = phase != ShiftPhase.OFF
}

/**
 * The single shared state between UI and [BodycamService].
 * Only the service ever touches CameraX; the UI sends commands and offers a preview surface.
 */
object ShiftController {
    internal val _state = MutableStateFlow(ShiftState())
    val state: StateFlow<ShiftState> = _state.asStateFlow()

    /** Set by the camera screen while visible; null when not visible. */
    val previewSurface = MutableStateFlow<Preview.SurfaceProvider?>(null)

    /** Pocket mode: black screen while the camera keeps working (UI only). */
    val pocket = MutableStateFlow(false)

    /** Emits a recording id when one has been saved. */
    val lastSavedId = MutableStateFlow<String?>(null)

    fun startShift(context: Context) {
        if (_state.value.onShift) return
        _state.update { it.copy(phase = ShiftPhase.STARTING, error = null) }
        ContextCompat.startForegroundService(context, Intent(context, BodycamService::class.java).setAction(BodycamService.ACTION_START))
    }

    fun endShift() {
        val svc = BodycamService.instance
        if (svc != null) svc.endShift() else _state.value = ShiftState()
        pocket.value = false
    }

    fun toggleRecording(trigger: Trigger) { BodycamService.instance?.toggleRecording(trigger) }
    fun startRecording(trigger: Trigger) { BodycamService.instance?.startRecordingIfIdle(trigger) }
    fun discardRecording() { BodycamService.instance?.discardRecording() }
    fun switchCamera() { BodycamService.instance?.switchCamera() }
}

package com.jeremy.dashcam.core

import android.content.Context
import android.content.Intent
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import com.jeremy.dashcam.data.Trigger
import com.jeremy.dashcam.service.DashcamService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DrivePhase { OFF, STARTING, RUNNING }

data class DashcamState(
    val phase: DrivePhase = DrivePhase.OFF,
    val driveStartTime: Long = 0L,
    val eventActive: Boolean = false,
    val eventStartTime: Long = 0L,
    val eventTrigger: Trigger? = null,
    val savingCount: Int = 0,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val audioActive: Boolean = false,
    val error: String? = null,
) {
    val driveActive get() = phase != DrivePhase.OFF
}

/**
 * The single shared state between UI and [DashcamService].
 *
 * Architecture rule: ONLY DashcamService ever touches ProcessCameraProvider / CameraX.
 * The UI can only (a) send commands here and (b) offer a Preview.SurfaceProvider which the
 * service attaches to its own Preview use case. This removes Activity/Service camera races.
 */
object DashcamController {
    internal val _state = MutableStateFlow(DashcamState())
    val state: StateFlow<DashcamState> = _state.asStateFlow()

    /** Set by the camera screen while visible; null when not visible. Observed by the service. */
    val previewSurface = MutableStateFlow<Preview.SurfaceProvider?>(null)

    /** Emits an event id when an event has been saved (UI may navigate to it). */
    val lastSavedEventId = MutableStateFlow<String?>(null)

    fun startDrive(context: Context) {
        if (_state.value.driveActive) return
        _state.update { it.copy(phase = DrivePhase.STARTING, error = null) }
        val i = Intent(context, DashcamService::class.java).setAction(DashcamService.ACTION_START)
        ContextCompat.startForegroundService(context, i)
    }

    fun stopDrive(context: Context) {
        val svc = DashcamService.instance
        if (svc != null) svc.shutdown() else _state.value = DashcamState()
    }

    fun toggleEvent(trigger: Trigger) {
        DashcamService.instance?.toggleEvent(trigger)
    }

    fun discardEvent() {
        DashcamService.instance?.discardEvent()
    }

    fun switchCamera() {
        DashcamService.instance?.switchCamera()
    }
}

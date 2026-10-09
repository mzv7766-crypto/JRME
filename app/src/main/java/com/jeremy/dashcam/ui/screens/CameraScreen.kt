package com.jeremy.dashcam.ui.screens

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeremy.dashcam.R
import com.jeremy.dashcam.core.DashcamController
import com.jeremy.dashcam.core.DrivePhase
import com.jeremy.dashcam.data.AppSettings
import com.jeremy.dashcam.data.SettingsStore
import com.jeremy.dashcam.data.Trigger
import com.jeremy.dashcam.service.Notifications
import com.jeremy.dashcam.ui.components.Chip
import com.jeremy.dashcam.ui.components.RoundAction
import com.jeremy.dashcam.ui.theme.J
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CameraScreen(onSettings: () -> Unit, onStartDrive: () -> Unit) {
    val state by DashcamController.state.collectAsStateWithLifecycle()
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var quick by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }

    if (!state.driveActive) { CameraOff(); return }

    val onEvent = { DashcamController.toggleEvent(Trigger.MANUAL) }
    if (landscape) {
      // Event button on the physical RIGHT side in landscape, also in Hebrew (RTL).
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(Modifier.fillMaxSize())
            PreviewOverlays(settings, now, state.eventActive, state.eventStartTime, Modifier.fillMaxSize().padding(end = 110.dp))
            TopInfo(settings, state.eventActive, state.audioActive, Modifier.align(Alignment.TopStart).padding(12.dp))
            // Controls on the right side in landscape
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(104.dp).padding(vertical = 10.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)).background(Color.Black.copy(alpha = 0.35f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (state.eventActive) DiscardAction(46.dp)
                else RoundAction(Icons.Filled.Cameraswitch, stringResource(R.string.switch_camera), { DashcamController.switchCamera() }, size = 46.dp)
                EventButton(state.eventActive, state.eventStartTime, now, 84.dp, onEvent)
                RoundAction(Icons.Filled.Tune, stringResource(R.string.quick_settings), { quick = true }, size = 46.dp)
            }
        }
      }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(Color.Black)) {
                CameraPreview(Modifier.fillMaxSize())
                PreviewOverlays(settings, now, state.eventActive, state.eventStartTime, Modifier.fillMaxSize())
                TopInfo(settings, state.eventActive, state.audioActive, Modifier.align(Alignment.TopStart).padding(12.dp))
                IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Settings, null, tint = Color.White)
                    }
                }
                if (state.phase == DrivePhase.STARTING) CircularProgressIndicator(Modifier.align(Alignment.Center), color = J.Mint)
            }
            // Event button bottom-centre in portrait
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.eventActive) DiscardAction(52.dp)
                else RoundAction(Icons.Filled.Cameraswitch, stringResource(R.string.switch_camera), { DashcamController.switchCamera() })
                EventButton(state.eventActive, state.eventStartTime, now, 96.dp, onEvent)
                RoundAction(Icons.Filled.Tune, stringResource(R.string.quick_settings), { quick = true })
            }
        }
    }
    state.error?.let {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(it, color = Color.White, modifier = Modifier.background(J.Red.copy(alpha = 0.85f), RoundedCornerShape(10.dp)).padding(12.dp))
        }
    }
    if (quick) QuickSettingsDialog(settings, onMore = { quick = false; onSettings() }) { quick = false }
}

/** The service owns the camera — this view only OFFERS a surface, and withdraws it when hidden. */
@Composable
private fun CameraPreview(modifier: Modifier) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> DashcamController.previewSurface.value = previewView.surfaceProvider
                Lifecycle.Event.ON_STOP -> DashcamController.previewSurface.value = null
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            DashcamController.previewSurface.value = previewView.surfaceProvider
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            if (DashcamController.previewSurface.value === previewView.surfaceProvider) DashcamController.previewSurface.value = null
        }
    }
    AndroidView({ previewView }, modifier)
}

/** On-screen preview of what gets burned into the saved MP4 (date/time on top, Jeremy at the bottom). */
@Composable
private fun PreviewOverlays(s: AppSettings, now: Long, eventActive: Boolean, eventStart: Long, modifier: Modifier) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US) }
    Box(modifier.padding(14.dp)) {
        if (s.showDateTime) {
            Text(
                fmt.format(Date(now)), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text(
            "Jeremy", color = Color.White.copy(alpha = 0.92f), fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
        val autoAt = DashcamController.state.collectAsStateWithLifecycle().value.autoStopAt
        if (eventActive && autoAt > 0) {
            Text(
                stringResource(R.string.auto_stop_in, ((autoAt - now) / 1000).coerceAtLeast(0)),
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 30.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (eventActive) {
            Text(
                "● " + stringResource(R.string.event_recording) + "  " + Notifications.formatDuration(now - eventStart),
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).background(J.Red, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TopInfo(s: AppSettings, eventActive: Boolean, audio: Boolean, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (eventActive) Chip(stringResource(R.string.rec), leading = { Icon(Icons.Filled.FiberManualRecord, null, tint = J.Red, modifier = Modifier.size(12.dp)) })
        val land = DashcamController.state.collectAsStateWithLifecycle().value.recordingLandscape
        if (land != null) Chip(stringResource(if (land) R.string.rec_landscape else R.string.rec_portrait))
        Chip(
            "${s.resolution.height}P • ${s.fps} FPS",
            leading = { Icon(if (audio) Icons.Filled.Mic else Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(14.dp)) },
        )
    }
}

/** Ends the running event without saving a video. */
@Composable
private fun DiscardAction(size: Dp) {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                .border(2.dp, J.Red, CircleShape).clickable { DashcamController.discardEvent() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, null, tint = J.Red, modifier = Modifier.size(size * 0.5f)) }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.discard_event), color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp)
    }
}

@Composable
private fun EventButton(active: Boolean, eventStart: Long, now: Long, size: Dp, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "r")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).scale(if (active) 0.96f + 0.04f * pulse else 1f).clip(CircleShape)
                .background(if (active) J.RedDark else J.Red)
                .border(4.dp, Color.White.copy(alpha = if (active) 0.5f + 0.5f * pulse else 0.85f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (active) Icons.Filled.Stop else Icons.Filled.Warning, null, tint = Color.White, modifier = Modifier.size(size * 0.34f))
                Text(
                    if (active) Notifications.formatDuration(now - eventStart) else stringResource(R.string.save_event),
                    color = Color.White, fontSize = if (size > 90.dp) 13.sp else 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
            }
        }
        if (active) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.stop_event), color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CameraOff() {
    val start = rememberDriveStarter()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.VideocamOff, null, tint = J.TextDim, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.camera_off_title), color = J.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.camera_off_body), color = J.TextDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(36.dp)).background(J.mintButton).clickable(onClick = start),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Videocam, null, tint = Color(0xFF032012))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.start_drive).replace("\n", " "), color = Color(0xFF032012), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun QuickSettingsDialog(s: AppSettings, onMore: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = J.Surface,
        title = { Text(stringResource(R.string.quick_settings)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                QuickToggle(stringResource(R.string.record_audio), s.recordAudio) { v -> SettingsStore.update { it.copy(recordAudio = v) } }
                QuickToggle(stringResource(R.string.show_datetime), s.showDateTime) { v -> SettingsStore.update { it.copy(showDateTime = v) } }
                QuickToggle(stringResource(R.string.shock_detect), s.shockDetection) { v -> SettingsStore.update { it.copy(shockDetection = v) } }
                QuickToggle(stringResource(R.string.smart_detect), s.smartDetection) { v -> SettingsStore.update { it.copy(smartDetection = v) } }
                QuickToggle(stringResource(R.string.voice_cmds), s.voiceCommands) { v -> SettingsStore.update { it.copy(voiceCommands = v) } }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onMore) { Text(stringResource(R.string.settings_title)) } },
    )
}

@Composable
private fun QuickToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = J.Text)
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = J.Green, checkedThumbColor = Color.White))
    }
}

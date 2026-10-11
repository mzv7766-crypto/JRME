package com.shomer.bodycam.ui.screens

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shomer.bodycam.R
import com.shomer.bodycam.core.LocationTracker
import com.shomer.bodycam.core.ShiftController
import com.shomer.bodycam.core.ShiftPhase
import com.shomer.bodycam.data.SettingsStore
import com.shomer.bodycam.data.Trigger
import com.shomer.bodycam.service.Notifications
import com.shomer.bodycam.ui.components.HudChip
import com.shomer.bodycam.ui.components.PrimaryButton
import com.shomer.bodycam.ui.components.RecordButton
import com.shomer.bodycam.ui.components.RoundAction
import com.shomer.bodycam.ui.theme.S
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CameraScreen(onGoShift: () -> Unit) {
    val state by ShiftController.state.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }

    if (!state.onShift) { CameraOff(onGoShift); return }

    val recLabel = if (state.recording) Notifications.formatDuration(now - state.recordStart) else stringResource(R.string.record)
    val record: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { size ->
        RecordButton(
            state.recording, recLabel, size, enabled = state.phase == ShiftPhase.ON,
            onStart = { ShiftController.startRecording(Trigger.BUTTON) },
            onStop = { ShiftController.toggleRecording(Trigger.BUTTON) },
        )
    }
    val leftAction: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { size ->
        if (state.recording) RoundAction(Icons.Filled.DeleteOutline, stringResource(R.string.discard), { confirmDiscard = true }, size = size, tint = S.Red)
        else RoundAction(Icons.Filled.Cameraswitch, stringResource(R.string.switch_camera), { ShiftController.switchCamera() }, size = size)
    }
    val rightAction: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { size ->
        RoundAction(Icons.Filled.DarkMode, stringResource(R.string.pocket_mode), { ShiftController.pocket.value = true }, size = size)
    }

    if (landscape) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Viewfinder(now, Modifier.fillMaxSize().padding(end = 112.dp))
                Column(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(110.dp).background(Color.Black.copy(alpha = 0.4f)).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly,
                ) { leftAction(46.dp); record(92.dp); rightAction(46.dp) }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Viewfinder(now, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)))
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) { leftAction(52.dp); record(104.dp); rightAction(52.dp) }
            Text(
                stringResource(if (state.recording) R.string.hold_to_stop_short else R.string.volume_hint),
                color = S.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false }, containerColor = S.Surface,
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = { TextButton({ confirmDiscard = false; ShiftController.discardRecording() }) { Text(stringResource(R.string.discard), color = S.Red) } },
            dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.keep_recording), color = S.Amber) } },
        )
    }
}

/** Live picture with a body-camera HUD: exactly what will be stamped into the video, plus REC / buffer status. */
@Composable
private fun Viewfinder(now: Long, modifier: Modifier) {
    val state by ShiftController.state.collectAsStateWithLifecycle()
    val s by SettingsStore.state.collectAsStateWithLifecycle()
    val fix by LocationTracker.latest.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US) }
    val blink by rememberInfiniteTransition(label = "b").animateFloat(0.25f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Box(modifier.background(Color.Black).let { if (state.recording) it.border(3.dp, S.Red.copy(alpha = blink), RoundedCornerShape(18.dp)) else it }) {
        CameraPreview(Modifier.fillMaxSize())
        // viewfinder corners
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            val l = 22.dp.toPx(); val w = 2.dp.toPx(); val c = Color.White.copy(alpha = 0.55f)
            listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f),
                Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f)).forEach { (p, d) ->
                drawLine(c, p, Offset(p.x + l * d.x, p.y), w); drawLine(c, p, Offset(p.x, p.y + l * d.y), w)
            }
        }
        // stamp preview (top-left, as in the saved video)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.align(Alignment.TopStart).padding(18.dp)) {
                Stamp("SHOMER" + s.operatorId.trim().takeIf { it.isNotEmpty() }?.let { " • $it" }.orEmpty(), S.Amber, 13)
                if (s.stampTime) Stamp(fmt.format(Date(now)), Color.White, 12)
                if (s.stampLocation && s.location) Stamp(fix?.let { LocationTracker.label(it) } ?: "GPS …", Color.White, 12)
            }
            Column(Modifier.align(Alignment.TopEnd).padding(18.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.recording) HudChip("REC " + Notifications.formatDuration(now - state.recordStart), dot = S.Red.copy(alpha = blink))
                else HudChip("STBY  −${s.preRecordSeconds}s", dot = S.Amber)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.audioActive) Icons.Filled.Mic else Icons.Filled.MicOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    HudChip("${s.resolution.label} ${s.fps}")
                }
            }
        }
        if (state.phase == ShiftPhase.STARTING) CircularProgressIndicator(Modifier.align(Alignment.Center), color = S.Amber)
        state.error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(20.dp).background(S.Red.copy(alpha = 0.85f), RoundedCornerShape(10.dp)).padding(12.dp))
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, sizeSp: Int) {
    Text(
        text, color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.Bold, fontFamily = S.Mono, maxLines = 1,
        modifier = Modifier.padding(bottom = 2.dp).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** The service owns the camera — this view only OFFERS a surface, and withdraws it when hidden. */
@Composable
private fun CameraPreview(modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember {
        PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (!ShiftController.pocket.value) ShiftController.previewSurface.value = view.surfaceProvider
                Lifecycle.Event.ON_STOP -> ShiftController.previewSurface.value = null
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !ShiftController.pocket.value) ShiftController.previewSurface.value = view.surfaceProvider
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (ShiftController.previewSurface.value === view.surfaceProvider) ShiftController.previewSurface.value = null
        }
    }
    // nothing to show under the black pocket screen → stop drawing the preview (saves battery)
    val pocket by ShiftController.pocket.collectAsStateWithLifecycle()
    LaunchedEffect(pocket) {
        if (pocket) { if (ShiftController.previewSurface.value === view.surfaceProvider) ShiftController.previewSurface.value = null }
        else if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) ShiftController.previewSurface.value = view.surfaceProvider
    }
    AndroidView({ view }, modifier)
}

@Composable
private fun CameraOff(onGoShift: () -> Unit) {
    val start = rememberShiftStarter()
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.VideocamOff, null, tint = S.TextDim, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.camera_off_title), color = S.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.camera_off_body), color = S.TextDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.start_shift), Icons.Filled.Shield, Modifier.fillMaxWidth().height(64.dp)) { start(); onGoShift() }
    }
}

/**
 * Pocket mode: a black screen over everything (OLED: practically no power), touches are ignored so the
 * phone can sit in a pocket or vest; only a DOUBLE TAP leaves. The camera keeps working, and the volume-up
 * key still starts/stops recording. A tiny dim dot shows the state.
 */
@Composable
fun PocketMode() {
    val state by ShiftController.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { ShiftController.pocket.value = false })
        },
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background((if (state.recording) S.Red else S.Amber).copy(alpha = 0.35f)))
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.recording) "REC " + Notifications.formatDuration(now - state.recordStart) else "STBY",
                color = Color.White.copy(alpha = 0.18f), fontFamily = S.Mono, fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.pocket_exit_hint), color = Color.White.copy(alpha = 0.14f), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

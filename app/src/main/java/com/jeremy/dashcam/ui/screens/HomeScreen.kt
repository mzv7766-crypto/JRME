package com.jeremy.dashcam.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeremy.dashcam.R
import com.jeremy.dashcam.core.DashcamController
import com.jeremy.dashcam.core.DrivePhase
import com.jeremy.dashcam.data.SettingsStore
import com.jeremy.dashcam.service.Notifications
import com.jeremy.dashcam.ui.components.JCard
import com.jeremy.dashcam.ui.components.RoadBackdrop
import com.jeremy.dashcam.ui.components.ScreenHeader
import com.jeremy.dashcam.ui.components.StatCard
import com.jeremy.dashcam.ui.components.StatusDot
import com.jeremy.dashcam.ui.theme.J
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onShowCamera: () -> Unit, onSettings: () -> Unit) {
    val state by DashcamController.state.collectAsStateWithLifecycle()
    val starter = rememberDriveStarter()

    Box(Modifier.fillMaxSize()) {
        if (!state.driveActive) RoadBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Jeremy",
                end = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, null, tint = J.TextDim) } },
            )
            if (!state.driveActive) IdleHome(onStart = starter) else ActiveHome(onShowCamera)
        }
    }
}

@Composable
private fun IdleHome(onStart: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val btn = (minOf(maxWidth, maxHeight) * 0.62f).coerceIn(180.dp, 280.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = maxHeight).padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StartDriveButton(btn, onStart)
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.start_drive_hint), color = J.Text, fontSize = 17.sp,
                    textAlign = TextAlign.Center, lineHeight = 24.sp, modifier = Modifier.width(btn + 40.dp),
                )
            }
        }
    }
}

@Composable
private fun StartDriveButton(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "p",
    )
    Box(Modifier.size(size + 40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(J.Green.copy(alpha = 0.35f + 0.2f * pulse), Color.Transparent), radius = r), r)
            drawCircle(J.Mint.copy(alpha = 0.25f + 0.25f * pulse), r * 0.86f, style = Stroke(2.dp.toPx()))
        }
        Box(
            Modifier.size(size).scale(0.98f + 0.02f * pulse).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF1C6B44), Color(0xFF0B3D27))))
                .border(4.dp, Brush.verticalGradient(listOf(J.Mint, J.Green)), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Videocam, null, tint = Color.White, modifier = Modifier.size(size * 0.24f))
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.start_drive), color = Color.White, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 32.sp,
                )
            }
        }
    }
}

@Composable
private fun ActiveHome(onShowCamera: () -> Unit) {
    val ctx = LocalContext.current
    val state by DashcamController.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val freeGb = remember(now / 30_000) { freeStorageGb(ctx) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Status card
        JCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(J.GreenDark, J.Card))).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.phase == DrivePhase.STARTING) CircularProgressIndicator(Modifier.size(18.dp), color = J.Mint, strokeWidth = 2.dp)
                else StatusDot(if (state.eventActive) J.Red else J.Green)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.drive_active), color = J.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        stringResource(if (state.phase == DrivePhase.STARTING) R.string.camera_starting else R.string.camera_running_bg),
                        color = J.TextDim, fontSize = 14.sp,
                    )
                }
            }
        }

        state.error?.let { Text(it, color = J.Red, fontSize = 14.sp) }

        if (state.eventActive || state.savingCount > 0) {
            JCard(Modifier.fillMaxWidth(), onClick = { DashcamController.toggleEvent(com.jeremy.dashcam.data.Trigger.MANUAL) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.eventActive) Icons.Filled.Stop else Icons.Filled.Warning, null, tint = if (state.eventActive) J.Red else J.Amber)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (state.eventActive) stringResource(R.string.event_recording) + " • " +
                            Notifications.formatDuration(now - state.eventStartTime) + "   —   " + stringResource(R.string.stop_event)
                        else stringResource(R.string.event_saving),
                        color = J.Text, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Big "show camera" button
        Box(
            Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(42.dp)).background(J.mintButton).clickable(onClick = onShowCamera),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Videocam, null, tint = Color(0xFF032012), modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.show_camera), color = Color(0xFF032012), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Separate "turn off" button
        Box(
            Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(35.dp)).border(2.dp, J.Mint, RoundedCornerShape(35.dp))
                .clickable { DashcamController.stopDrive(ctx) },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Stop, null, tint = J.Text, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.stop_camera), color = J.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(stringResource(R.string.drive_duration), Notifications.formatDuration(now - state.driveStartTime).let { if (it.length == 5) "00:$it" else it }, Modifier.weight(1f))
            StatCard(stringResource(R.string.free_storage), "%.1f GB".format(freeGb), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun freeStorageGb(c: Context): Double =
    runCatching { StatFs(c.filesDir.absolutePath).availableBytes / 1_073_741_824.0 }.getOrDefault(0.0)

/**
 * Returns a click handler that requests permissions and immediately starts drive mode
 * (camera starts at once — no second "start camera" tap). Also offers the overlay permission once.
 */
@Composable
fun rememberDriveStarter(): () -> Unit {
    val ctx = LocalContext.current
    var askOverlay by remember { mutableStateOf(false) }
    val needCam = stringResource(R.string.perm_camera_needed)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val camOk = res[Manifest.permission.CAMERA] ?: hasPerm(ctx, Manifest.permission.CAMERA)
        if (camOk) {
            DashcamController.startDrive(ctx)
            if (!Settings.canDrawOverlays(ctx) && !SettingsStore.current.overlayPromptShown) askOverlay = true
        } else Toast.makeText(ctx, needCam, Toast.LENGTH_LONG).show()
    }

    if (askOverlay) {
        AlertDialog(
            onDismissRequest = { askOverlay = false; SettingsStore.update { it.copy(overlayPromptShown = true) } },
            title = { Text(stringResource(R.string.perm_overlay)) },
            text = { Text(stringResource(R.string.overlay_prompt)) },
            confirmButton = {
                TextButton({
                    askOverlay = false
                    SettingsStore.update { it.copy(overlayPromptShown = true) }
                    openOverlaySettings(ctx)
                }) { Text(stringResource(R.string.perm_grant)) }
            },
            dismissButton = { TextButton({ askOverlay = false; SettingsStore.update { it.copy(overlayPromptShown = true) } }) { Text(stringResource(R.string.cancel)) } },
            containerColor = J.Surface,
        )
    }

    return {
        val perms = buildList {
            add(Manifest.permission.CAMERA)
            if (SettingsStore.current.recordAudio || SettingsStore.current.voiceCommands) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { !hasPerm(ctx, it) }
        if (perms.isEmpty()) {
            DashcamController.startDrive(ctx)
            if (!Settings.canDrawOverlays(ctx) && !SettingsStore.current.overlayPromptShown) askOverlay = true
        } else launcher.launch(perms.toTypedArray())
    }
}

fun hasPerm(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

fun openOverlaySettings(c: Context) {
    c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

package com.shomer.bodycam.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.shomer.bodycam.R
import com.shomer.bodycam.core.ShiftController
import com.shomer.bodycam.core.ShiftPhase
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.Resolution
import com.shomer.bodycam.data.SettingsStore
import com.shomer.bodycam.data.Trigger
import com.shomer.bodycam.service.Notifications
import com.shomer.bodycam.ui.components.HoldButton
import com.shomer.bodycam.ui.components.PrimaryButton
import com.shomer.bodycam.ui.components.RecordButton
import com.shomer.bodycam.ui.components.SCard
import com.shomer.bodycam.ui.components.ScreenTitle
import com.shomer.bodycam.ui.components.SectionLabel
import com.shomer.bodycam.ui.components.ShomerMark
import com.shomer.bodycam.ui.components.StatTile
import com.shomer.bodycam.ui.components.Tag
import com.shomer.bodycam.ui.theme.S
import kotlinx.coroutines.delay

@Composable
fun ShiftScreen(onCamera: () -> Unit, onOpenRecord: (String) -> Unit) {
    val state by ShiftController.state.collectAsStateWithLifecycle()
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("SHOMER", end = {
            if (settings.operatorId.isNotBlank()) Tag(settings.operatorId, S.AmberSoft, Modifier.padding(end = 10.dp))
        })
        if (!state.onShift) OffShift() else OnShift(onCamera, onOpenRecord)
    }
}

// ---------------------------------------------------------------- off shift

@Composable
private fun OffShift() {
    val ctx = LocalContext.current
    val start = rememberShiftStarter()
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { while (true) { tick++; delay(5000) } } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShomerMark(96.dp, recLight = 0.15f)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.off_shift_title), color = S.Text, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.off_shift_body, settings.preRecordSeconds), color = S.TextDim, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(stringResource(R.string.start_shift), Icons.Filled.Shield, Modifier.fillMaxWidth().height(72.dp), onClick = start)

        SectionLabel(stringResource(R.string.readiness))
        val battery = remember(tick) { batteryPercent(ctx) }
        val hours = remember(tick, settings.resolution) { recordHoursLeft(ctx, settings.resolution) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.battery), "$battery%", Modifier.weight(1f), if (battery in 0..20) S.Red else S.Text)
            StatTile(stringResource(R.string.storage_hours), if (hours >= 10) "${hours.toInt()}h" else "%.1fh".format(hours), Modifier.weight(1f))
            StatTile(stringResource(R.string.pre_record_short), "${settings.preRecordSeconds}s", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Readiness(tick)
        Spacer(Modifier.height(24.dp))
    }
}

/** Checklist before going out: each line that isn't ready can be fixed with one tap. */
@Composable
private fun Readiness(tick: Int) {
    val ctx = LocalContext.current
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val items = remember(tick, settings) {
        buildList {
            add(Triple(R.string.ready_camera, hasPerm(ctx, Manifest.permission.CAMERA)) { launcher.launch(arrayOf(Manifest.permission.CAMERA)) })
            if (settings.recordAudio) add(Triple(R.string.ready_mic, hasPerm(ctx, Manifest.permission.RECORD_AUDIO)) { launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) })
            if (settings.location) add(Triple(R.string.ready_location, hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            })
            if (settings.floatingButton || settings.sosEnabled) add(Triple(R.string.ready_overlay, Settings.canDrawOverlays(ctx)) { openOverlaySettings(ctx) })
            add(Triple(R.string.ready_battery, ignoresBatteryOptimizations(ctx)) { requestBatteryExemption(ctx) })
            if (settings.sosEnabled) add(Triple(R.string.ready_call, hasPerm(ctx, Manifest.permission.CALL_PHONE)) { launcher.launch(arrayOf(Manifest.permission.CALL_PHONE)) })
        }
    }
    SCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            items.forEach { (label, ok, fix) ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !ok) { fix() }.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, tint = if (ok) S.Green else S.Amber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label), color = S.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (!ok) Text(stringResource(R.string.fix), color = S.Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- on shift

@Composable
private fun OnShift(onCamera: () -> Unit, onOpenRecord: (String) -> Unit) {
    val ctx = LocalContext.current
    val state by ShiftController.state.collectAsStateWithLifecycle()
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val lastId by ShiftController.lastSavedId.collectAsStateWithLifecycle()
    val records by RecordRepository.records.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val battery = remember(now / 30_000) { batteryPercent(ctx) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val watcher by com.shomer.bodycam.cloud.LiveSender.watcher.collectAsStateWithLifecycle()
        val org by com.shomer.bodycam.cloud.OrgStore.state.collectAsStateWithLifecycle()
        if (org.activeMember) {
            Text(stringResource(R.string.connected_to, org.org!!.name), color = S.AmberSoft, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        }
        // the guard always sees when the control room is watching
        if (watcher != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(S.RedDeep).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Videocam, null, tint = S.Red)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.watched_banner, watcher ?: ""), color = S.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        if (state.onShift && state.power != com.shomer.bodycam.core.PowerGuard.Reason.NONE) {
            val p = state.power
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(S.Card).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.BatterySaver, null, tint = if (p == com.shomer.bodycam.core.PowerGuard.Reason.SAVER) S.Green else S.Amber)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(when (p) {
                    com.shomer.bodycam.core.PowerGuard.Reason.HEAT -> R.string.power_banner_heat
                    com.shomer.bodycam.core.PowerGuard.Reason.BATTERY -> R.string.power_banner_battery
                    else -> R.string.power_banner_saver
                }), color = S.Text, fontSize = 13.sp)
            }
        }
        StatusBanner(now)
        state.error?.let { Text(it, color = S.Red, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }

        Spacer(Modifier.height(22.dp))
        RecordButton(
            recording = state.recording,
            label = if (state.recording) Notifications.formatDuration(now - state.recordStart) else stringResource(R.string.record),
            size = 176.dp,
            enabled = state.phase == ShiftPhase.ON,
            onStart = { ShiftController.startRecording(Trigger.BUTTON) },
            onStop = { ShiftController.toggleRecording(Trigger.BUTTON) },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(if (state.recording) R.string.hold_to_stop else R.string.tap_to_record, settings.preRecordSeconds),
            color = S.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile(Icons.Filled.DarkMode, stringResource(R.string.pocket_mode), stringResource(R.string.pocket_mode_sub), Modifier.weight(1f)) { ShiftController.pocket.value = true }
            BigTile(Icons.Filled.Videocam, stringResource(R.string.view_camera), stringResource(R.string.view_camera_sub), Modifier.weight(1f), onCamera)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.shift_time), Notifications.formatDuration(now - state.shiftStart), Modifier.weight(1f))
            StatTile(stringResource(R.string.saved_this_shift), state.savedThisShift.toString(), Modifier.weight(1f))
            StatTile(stringResource(R.string.battery), "$battery%", Modifier.weight(1f), if (battery in 0..20) S.Red else S.Text)
        }
        if (state.savingCount > 0) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = S.Amber, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.saving_and_sealing), color = S.TextDim, fontSize = 13.sp)
            }
        }
        records.firstOrNull { it.id == lastId }?.let { r ->
            Spacer(Modifier.height(10.dp))
            SCard(Modifier.fillMaxWidth(), onClick = { onOpenRecord(r.id) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r.locked) Icons.Filled.Lock else Icons.Filled.CheckCircle, null, tint = S.Green)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.last_saved), color = S.Text, fontWeight = FontWeight.Bold)
                        Text(formatTime(r.triggerTime) + "  •  " + Notifications.formatDuration(r.durationMs), color = S.TextDim, fontSize = 13.sp, fontFamily = S.Mono)
                    }
                    Text(stringResource(R.string.open), color = S.Amber, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        HoldButton(stringResource(R.string.hold_to_end_shift), Icons.Filled.PowerSettingsNew, Modifier.fillMaxWidth().height(56.dp)) { ShiftController.endShift() }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatusBanner(now: Long) {
    val state by ShiftController.state.collectAsStateWithLifecycle()
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val rec = state.recording
    SCard(Modifier.fillMaxWidth(), border = if (rec) S.Red else S.Amber.copy(alpha = 0.6f)) {
        Column(Modifier.background(if (rec) S.RedDeep else S.AmberDeep.copy(alpha = 0.5f)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (rec) S.Red else S.Amber))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(when { state.phase == ShiftPhase.STARTING -> R.string.camera_starting; rec -> R.string.status_recording; else -> R.string.status_standby }),
                    color = S.Text, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f),
                )
                if (rec) state.trigger?.let { Tag(stringResource(it.labelRes), S.Red) }
            }
            Spacer(Modifier.height(6.dp))
            if (rec) {
                Text(stringResource(R.string.status_recording_body, settings.preRecordSeconds), color = S.TextDim, fontSize = 13.sp)
            } else {
                val pre = settings.preRecordSeconds
                val filled = if (state.cameraSince > 0) ((now - state.cameraSince) / 1000).coerceIn(0, pre.toLong()).toInt() else 0
                Text(stringResource(R.string.status_standby_body, filled, pre), color = S.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { filled / pre.toFloat().coerceAtLeast(1f) }, color = S.Amber, trackColor = S.Stroke,
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

@Composable
private fun BigTile(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    SCard(modifier, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Icon(icon, null, tint = S.Amber, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, color = S.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(sub, color = S.TextDim, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2)
        }
    }
}

// ---------------------------------------------------------------- helpers

/** Asks for what is missing, then starts the shift right away (camera starts at once). */
@Composable
fun rememberShiftStarter(): () -> Unit {
    val ctx = LocalContext.current
    var askOverlay by remember { mutableStateOf(false) }
    val needCam = stringResource(R.string.perm_camera_needed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] ?: hasPerm(ctx, Manifest.permission.CAMERA)) {
            ShiftController.startShift(ctx)
            if (!Settings.canDrawOverlays(ctx) && !SettingsStore.current.overlayPromptShown) askOverlay = true
        } else Toast.makeText(ctx, needCam, Toast.LENGTH_LONG).show()
    }
    if (askOverlay) {
        val dismiss = { askOverlay = false; SettingsStore.update { it.copy(overlayPromptShown = true) } }
        AlertDialog(
            onDismissRequest = dismiss, containerColor = S.Surface,
            title = { Text(stringResource(R.string.overlay_title)) },
            text = { Text(stringResource(R.string.overlay_prompt)) },
            confirmButton = { TextButton({ dismiss(); openOverlaySettings(ctx) }) { Text(stringResource(R.string.allow), color = S.Amber) } },
            dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.not_now), color = S.TextDim) } },
        )
    }
    return {
        val s = SettingsStore.current
        val perms = buildList {
            add(Manifest.permission.CAMERA)
            if (s.recordAudio || s.voiceCommands) add(Manifest.permission.RECORD_AUDIO)
            if (s.location) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { !hasPerm(ctx, it) }
        if (perms.isEmpty()) {
            ShiftController.startShift(ctx)
            if (!Settings.canDrawOverlays(ctx) && !SettingsStore.current.overlayPromptShown) askOverlay = true
        } else launcher.launch(perms.toTypedArray())
    }
}

fun hasPerm(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

fun openOverlaySettings(c: Context) {
    runCatching { c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun ignoresBatteryOptimizations(c: Context) = c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true

@SuppressLint("BatteryLife")
fun requestBatteryExemption(c: Context) {
    runCatching {
        c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { runCatching { c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

fun batteryPercent(c: Context): Int = runCatching { c.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)

/** Rough hours of video that still fit on the phone at the chosen quality. */
fun recordHoursLeft(c: Context, r: Resolution): Double {
    val free = runCatching { StatFs(c.filesDir.absolutePath).availableBytes }.getOrDefault(0L) - (1L shl 30)
    val mbps = when (r) { Resolution.HD -> 7.0; Resolution.FHD -> 16.0; Resolution.UHD -> 45.0 }
    return (free.coerceAtLeast(0) * 8.0 / (mbps * 1_000_000) / 3600.0)
}


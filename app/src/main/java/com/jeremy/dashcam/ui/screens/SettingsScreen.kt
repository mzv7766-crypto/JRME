package com.jeremy.dashcam.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import com.jeremy.dashcam.core.DashcamController
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeremy.dashcam.BuildConfig
import com.jeremy.dashcam.R
import com.jeremy.dashcam.data.AppLanguage
import com.jeremy.dashcam.data.EventRepository
import com.jeremy.dashcam.data.LayoutDir
import com.jeremy.dashcam.data.Resolution
import com.jeremy.dashcam.data.ScreenOrientation
import com.jeremy.dashcam.data.Sensitivity
import com.jeremy.dashcam.data.SettingsStore
import com.jeremy.dashcam.ui.components.JCard
import com.jeremy.dashcam.ui.components.ScreenHeader
import com.jeremy.dashcam.ui.theme.J

private data class Choice<T>(val title: String, val options: List<T>, val label: (T) -> String, val selected: T, val onPick: (T) -> Unit)

@Composable
fun SettingsScreen(onOpenPro: () -> Unit = {}) {
    val s by SettingsStore.state.collectAsStateWithLifecycle()
    val events by EventRepository.events.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var choice by remember { mutableStateOf<Choice<*>?>(null) }
    var editPhrase by remember { mutableStateOf<Boolean?>(null) } // true = start, false = stop

    val secFmt = stringResource(R.string.seconds_fmt)
    val gbFmt = stringResource(R.string.gb_fmt)
    val minFmt = stringResource(R.string.min_fmt)
    val sensLabel: (Sensitivity) -> String = { ctx.getString(it.labelRes) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.settings_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {

            ProSettingsCard(onOpenPro)

            Section(stringResource(R.string.sec_video)) {
                Nav(stringResource(R.string.pre_event), secFmt.format(s.preEventSeconds)) {
                    choice = Choice(ctx.getString(R.string.pre_event), SettingsStore.PRE_EVENT_OPTIONS, { secFmt.format(it) }, s.preEventSeconds) { v ->
                        SettingsStore.update { it.copy(preEventSeconds = v) }
                    }
                }
                Nav(stringResource(R.string.resolution), s.resolution.label) {
                    choice = Choice(ctx.getString(R.string.resolution), Resolution.entries, { it.label }, s.resolution) { v -> SettingsStore.update { it.copy(resolution = v) } }
                }
                Nav(stringResource(R.string.fps), s.fps.toString()) {
                    choice = Choice(ctx.getString(R.string.fps), SettingsStore.FPS_OPTIONS, { "$it FPS" }, s.fps) { v -> SettingsStore.update { it.copy(fps = v) } }
                }
                Toggle(stringResource(R.string.record_audio), s.recordAudio) { v -> SettingsStore.update { it.copy(recordAudio = v) } }
                Toggle(stringResource(R.string.show_datetime), s.showDateTime, last = true) { v -> SettingsStore.update { it.copy(showDateTime = v) } }
            }

            Section(stringResource(R.string.sec_detect)) {
                Toggle(stringResource(R.string.shock_detect), s.shockDetection) { v -> SettingsStore.update { it.copy(shockDetection = v) } }
                Nav(stringResource(R.string.shock_sens), sensLabel(s.shockSensitivity), enabled = s.shockDetection) {
                    choice = Choice(ctx.getString(R.string.shock_sens), Sensitivity.entries, sensLabel, s.shockSensitivity) { v -> SettingsStore.update { it.copy(shockSensitivity = v) } }
                }
                Toggle(stringResource(R.string.smart_detect), s.smartDetection) { v -> SettingsStore.update { it.copy(smartDetection = v) } }
                Nav(stringResource(R.string.smart_sens), sensLabel(s.smartSensitivity), enabled = s.smartDetection) {
                    choice = Choice(ctx.getString(R.string.smart_sens), Sensitivity.entries, sensLabel, s.smartSensitivity) { v -> SettingsStore.update { it.copy(smartSensitivity = v) } }
                }
                Toggle(stringResource(R.string.auto_stop), s.autoStop) { v -> SettingsStore.update { it.copy(autoStop = v) } }
                Nav(stringResource(R.string.auto_stop_after), secFmt.format(s.autoStopSeconds), enabled = s.autoStop, last = true) {
                    choice = Choice(ctx.getString(R.string.auto_stop_after), SettingsStore.AUTO_STOP_OPTIONS, { secFmt.format(it) }, s.autoStopSeconds) { v ->
                        SettingsStore.update { it.copy(autoStopSeconds = v) }
                    }
                }
            }

            Text(stringResource(R.string.detect_note), color = J.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            LiveSensorsCard()

            Section(stringResource(R.string.sec_voice)) {
                Toggle(stringResource(R.string.voice_cmds), s.voiceCommands) { v -> SettingsStore.update { it.copy(voiceCommands = v) } }
                Nav(stringResource(R.string.voice_start), "\"${s.voiceStartPhrase}\"") { editPhrase = true }
                Nav(stringResource(R.string.voice_stop), "\"${s.voiceStopPhrase}\"") { editPhrase = false }
                Toggle(stringResource(R.string.voice_feedback), s.voiceFeedback, last = true) { v -> SettingsStore.update { it.copy(voiceFeedback = v) } }
            }
            Text(stringResource(R.string.voice_note), color = J.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

            Section(stringResource(R.string.volume_keys)) {
                Toggle(stringResource(R.string.volume_keys), s.volumeKeys) { v -> SettingsStore.update { it.copy(volumeKeys = v) } }
                Text(stringResource(R.string.volume_keys_desc), color = J.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            Section(stringResource(R.string.sec_trip)) {
                Toggle(stringResource(R.string.trip_record), s.tripRecording) { v -> SettingsStore.update { it.copy(tripRecording = v) } }
                Nav(stringResource(R.string.trip_clip_len), minFmt.format(s.tripClipMinutes), enabled = s.tripRecording) {
                    choice = Choice(ctx.getString(R.string.trip_clip_len), SettingsStore.TRIP_CLIP_OPTIONS, { minFmt.format(it) }, s.tripClipMinutes) { v ->
                        SettingsStore.update { it.copy(tripClipMinutes = v) }
                    }
                }
                Nav(stringResource(R.string.trip_max_storage), gbFmt.format(s.tripMaxStorageGb), enabled = s.tripRecording) {
                    choice = Choice(ctx.getString(R.string.trip_max_storage), SettingsStore.TRIP_STORAGE_OPTIONS, { gbFmt.format(it) }, s.tripMaxStorageGb) { v ->
                        SettingsStore.update { it.copy(tripMaxStorageGb = v) }; Thread { EventRepository.enforceStorageLimit() }.start()
                    }
                }
                Toggle(stringResource(R.string.trip_burn), s.tripBurn) { v -> SettingsStore.update { it.copy(tripBurn = v) } }
                Nav(
                    stringResource(R.string.storage_used),
                    "%.2f GB • %d".format(events.filter { it.isTrip }.sumOf { it.sizeBytes } / 1_073_741_824.0, events.count { it.isTrip }),
                    clickable = false, last = true,
                ) {}
            }
            Text(stringResource(R.string.trip_note), color = J.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

            Section(stringResource(R.string.sec_storage)) {
                Toggle(stringResource(R.string.auto_delete), s.autoDelete) { v ->
                    SettingsStore.update { it.copy(autoDelete = v) }; Thread { EventRepository.enforceStorageLimit() }.start()
                }
                Nav(stringResource(R.string.max_storage), gbFmt.format(s.maxStorageGb), enabled = s.autoDelete) {
                    choice = Choice(ctx.getString(R.string.max_storage), SettingsStore.STORAGE_OPTIONS, { gbFmt.format(it) }, s.maxStorageGb) { v ->
                        SettingsStore.update { it.copy(maxStorageGb = v) }; Thread { EventRepository.enforceStorageLimit() }.start()
                    }
                }
                Nav(stringResource(R.string.storage_used), "%.2f GB • %d".format(events.filter { !it.isTrip }.sumOf { it.sizeBytes } / 1_073_741_824.0, events.count { !it.isTrip }), clickable = false, last = true) {}
            }
            Text(stringResource(R.string.auto_delete_note), color = J.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

            Section(stringResource(R.string.sec_display)) {
                Nav(stringResource(R.string.language), stringResource(s.language.labelRes)) {
                    choice = Choice(ctx.getString(R.string.language), AppLanguage.entries, { ctx.getString(it.labelRes) }, s.language) { v ->
                        SettingsStore.update { it.copy(language = v) }
                        AppCompatDelegate.setApplicationLocales(
                            if (v.tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(v.tag)
                        )
                    }
                }
                Nav(stringResource(R.string.orientation), stringResource(s.orientation.labelRes)) {
                    choice = Choice(ctx.getString(R.string.orientation), ScreenOrientation.entries, { ctx.getString(it.labelRes) }, s.orientation) { v -> SettingsStore.update { it.copy(orientation = v) } }
                }
                Nav(stringResource(R.string.direction), stringResource(s.layoutDir.labelRes), last = true) {
                    choice = Choice(ctx.getString(R.string.direction), LayoutDir.entries, { ctx.getString(it.labelRes) }, s.layoutDir) { v -> SettingsStore.update { it.copy(layoutDir = v) } }
                }
            }

            Spacer(Modifier.height(10.dp))
            PermissionsCard()
            Text(
                stringResource(R.string.version_fmt, BuildConfig.VERSION_NAME), color = J.TextDim, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    (choice as Choice<Any?>?)?.let { c -> ChoiceDialog(c) { choice = null } }

    editPhrase?.let { isStart ->
        var text by remember(isStart) { mutableStateOf(if (isStart) s.voiceStartPhrase else s.voiceStopPhrase) }
        AlertDialog(
            onDismissRequest = { editPhrase = null }, containerColor = J.Surface,
            title = { Text(stringResource(if (isStart) R.string.voice_start else R.string.voice_stop)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val t = text.trim()
                    if (t.isNotEmpty()) SettingsStore.update { if (isStart) it.copy(voiceStartPhrase = t) else it.copy(voiceStopPhrase = t) }
                    editPhrase = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton({ editPhrase = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(title, color = J.Mint, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    JCard(Modifier.fillMaxWidth()) { Column { content() } }
}

@Composable
private fun Toggle(label: String, value: Boolean, last: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = J.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = J.Green, checkedThumbColor = Color.White, uncheckedTrackColor = J.Surface))
    }
    if (!last) HorizontalDivider(color = J.Stroke, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun Nav(label: String, value: String, enabled: Boolean = true, clickable: Boolean = true, last: Boolean = false, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && clickable, onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = J.Text.copy(alpha = alpha), fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = J.TextDim.copy(alpha = alpha), fontSize = 14.sp)
        if (clickable) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = J.TextDim.copy(alpha = alpha), modifier = Modifier.size(20.dp))
        }
    }
    if (!last) HorizontalDivider(color = J.Stroke, modifier = Modifier.padding(horizontal = 16.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceDialog(c: Choice<Any?>, onDismiss: () -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = J.Surface,
        title = { Text(c.title) },
        text = {
            if (landscape) {
                // Landscape: all options as large chips side by side – everything visible without scrolling.
                FlowRow(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    c.options.forEach { o ->
                        val sel = o == c.selected
                        Box(
                            Modifier.clip(RoundedCornerShape(14.dp))
                                .background(if (sel) J.Green.copy(alpha = 0.25f) else J.Card)
                                .border(if (sel) 2.dp else 1.dp, if (sel) J.Green else J.Stroke, RoundedCornerShape(14.dp))
                                .clickable { c.onPick(o); onDismiss() }
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                        ) { Text(c.label(o), color = if (sel) J.Mint else J.Text, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp) }
                    }
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    c.options.forEach { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { c.onPick(o); onDismiss() }.background(if (o == c.selected) J.Green.copy(alpha = 0.1f) else Color.Transparent)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(o == c.selected, { c.onPick(o); onDismiss() }, colors = RadioButtonDefaults.colors(selectedColor = J.Green))
                            Text(c.label(o), color = J.Text)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LiveSensorsCard() {
    val m by DashcamController.metrics.collectAsStateWithLifecycle()
    Spacer(Modifier.height(6.dp))
    JCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.live_sensors), color = J.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val x = m
            if (x == null) {
                Text(stringResource(R.string.live_off), color = J.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            } else {
                Spacer(Modifier.height(10.dp))
                Meter(stringResource(R.string.live_force), "%.2fG".format(x.horizontalG), x.horizontalG / x.impactThresholdG, x.harshThresholdG / x.impactThresholdG, x.sensorsOn)
                Text(
                    stringResource(R.string.live_peak) + ": %.2fG".format(x.peakG), color = J.TextDim, fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Meter(stringResource(R.string.live_vision), "%d%%".format((x.visionScore * 100).toInt()), x.visionScore / (x.visionThreshold * 2), 0.5f, x.visionOn)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.live_impact_thr, x.impactThresholdG, x.harshThresholdG), color = J.TextDim, fontSize = 12.sp)
            }
        }
    }
}

/** Horizontal bar; [marker] is a 0..1 tick (e.g. the braking threshold), full bar = trigger threshold. */
@Composable
private fun Meter(label: String, value: String, fraction: Float, marker: Float, enabled: Boolean) {
    val f = fraction.coerceIn(0f, 1f)
    val color = when { !enabled -> J.TextDim; f >= 1f -> J.Red; f >= marker -> J.Amber; else -> J.Green }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = J.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(J.Surface)) {
        Box(Modifier.fillMaxWidth(f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Box(Modifier.fillMaxWidth(marker.coerceIn(0f, 1f)).height(10.dp)) {
            Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(10.dp).background(J.Text.copy(alpha = 0.6f)))
        }
    }
}

/** DEMO: Pro entry point + Pro-only features (locked unless Pro). */
@Composable
private fun ProSettingsCard(onOpenPro: () -> Unit) {
    val p by com.jeremy.dashcam.data.ProStore.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Spacer(Modifier.height(10.dp))
    JCard(Modifier.fillMaxWidth(), onClick = onOpenPro) {
        Row(
            Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(J.GreenDark, J.Card))).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(androidx.compose.material.icons.Icons.Filled.Star, null, tint = if (p.isPro) J.Mint else J.Amber, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Jeremy Pro", color = J.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    when {
                        p.isAdmin -> "מנהל • Pro מלא"
                        p.isFreeAccount -> "חשבון חינם • Pro מלא"
                        p.trialActive -> "תקופת ניסיון • נותרו ${com.jeremy.dashcam.data.ProStore.daysLeft(p.trialEndsAt)} ימים"
                        p.isPro -> "Pro פעיל"
                        else -> "${com.jeremy.dashcam.data.ProStore.TRIAL_DAYS} ימי ניסיון חינם, אחר כך ${com.jeremy.dashcam.data.ProStore.PRICE} לחודש"
                    },
                    color = J.TextDim, fontSize = 13.sp,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = J.TextDim)
        }
    }
    ProFeaturesSection(p.isPro, onOpenPro)
}


/** PRO features (demo). When not Pro every row shows a lock and opens the Pro screen. */
@Composable
private fun ProFeaturesSection(isPro: Boolean, onOpenPro: () -> Unit) {
    val ctx = LocalContext.current
    val st by SettingsStore.state.collectAsStateWithLifecycle()
    var pickCar by remember { mutableStateOf(false) }
    var editSos by remember { mutableStateOf(false) }

    val locPerm = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) SettingsStore.update { it.copy(proSpeedLocation = true) }
    }
    val btPerm = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) pickCar = true }
    val callPerm = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { _ -> editSos = true }
    fun has(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @Composable
    fun Locked(label: String) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenPro).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = J.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Lock, null, tint = J.Amber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("PRO", color = J.Amber, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        HorizontalDivider(color = J.Stroke, modifier = Modifier.padding(horizontal = 16.dp))
    }

    Section("תכונות Pro") {
        if (!isPro) {
            Locked("מהירות ומיקום בסרטון")
            Locked("הפעלה אוטומטית עם ה־Bluetooth של הרכב")
            Locked("קריאת לוחיות רישוי באירועים")
            Locked("התראת חירום עם חיוג")
            Locked("זיהוי חכם עם בינה מלאכותית (בקרוב)")
            Locked("גיבוי ל־Google Drive (בקרוב)")
            return@Section
        }
        Toggle("מהירות ומיקום בסרטון", st.proSpeedLocation && has(android.Manifest.permission.ACCESS_FINE_LOCATION)) { v ->
            if (v && !has(android.Manifest.permission.ACCESS_FINE_LOCATION))
                locPerm.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            else SettingsStore.update { it.copy(proSpeedLocation = v) }
        }
        Nav("הפעלה אוטומטית ברכב", st.carBtName ?: "כבוי") {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !has(android.Manifest.permission.BLUETOOTH_CONNECT)) btPerm.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            else pickCar = true
        }
        Toggle("קריאת לוחיות רישוי באירועים", st.proPlates) { v -> SettingsStore.update { it.copy(proPlates = v) } }
        Nav("התראת חירום", if (st.sosEnabled && st.sosNumber.isNotBlank()) "${st.sosName.ifBlank { st.sosNumber }} • ${st.sosSeconds} שנ׳" else "כבוי", last = true) {
            if (!has(android.Manifest.permission.CALL_PHONE)) callPerm.launch(android.Manifest.permission.CALL_PHONE) else editSos = true
        }
    }
    Text(
        "הפעלה אוטומטית ברכב והתראת חירום עובדות גם כשהאפליקציה סגורה, בתנאי שהרשאת \"הצגה מעל אפליקציות\" מאושרת.",
        color = J.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )

    if (pickCar) CarPicker(onDismiss = { pickCar = false })
    if (editSos) SosDialog(st, onDismiss = { editSos = false })
}

@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun CarPicker(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val devices = remember {
        runCatching {
            ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices?.map { (it.name ?: it.address) to it.address }
        }.getOrNull().orEmpty().sortedBy { it.first }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = J.Surface,
        title = { Text("בחר את הרכב") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Jeremy תתחיל לצלם כשהטלפון מתחבר אליו, ותסיים כשהוא מתנתק.", color = J.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (devices.isEmpty()) Text("לא נמצאו מכשירי Bluetooth מותאמים. חבר קודם את הטלפון לרכב בהגדרות ה־Bluetooth.", color = J.Text)
                devices.forEach { (name, addr) ->
                    Text(name, color = J.Text, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable {
                        SettingsStore.update { it.copy(carBtAddress = addr, carBtName = name) }; onDismiss()
                    }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton({ SettingsStore.update { it.copy(carBtAddress = null, carBtName = null) }; onDismiss() }) { Text("כבה") } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SosDialog(st: com.jeremy.dashcam.data.AppSettings, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(st.sosName) }
    var number by remember { mutableStateOf(st.sosNumber) }
    var secs by remember { mutableStateOf(st.sosSeconds) }
    var g by remember { mutableStateOf(st.sosMinG) }
    var on by remember { mutableStateOf(st.sosEnabled || st.sosNumber.isBlank()) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = J.Surface,
        title = { Text("התראת חירום") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("אחרי מכה חזקה יופיע מסך אדום עם ספירה לאחור. אם לא תלחץ \"אני בסדר\", Jeremy תחייג אוטומטית למספר הזה.", color = J.TextDim, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("פעיל", color = J.Text, modifier = Modifier.weight(1f))
                    Switch(on, { on = it }, colors = SwitchDefaults.colors(checkedTrackColor = J.Green))
                }
                OutlinedTextField(name, { name = it }, label = { Text("שם איש הקשר") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    number, { number = it }, label = { Text("מספר טלפון") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                )
                Text("המתנה לפני חיוג", color = J.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { v -> ChoiceChip("$v שנ׳", secs == v) { secs = v } }
                }
                Text("עוצמת מכה שמפעילה", color = J.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2.5f to "רגישה", 3.5f to "בינונית", 5f to "חזקה בלבד").forEach { (v, l) -> ChoiceChip(l, g == v) { g = v } }
                }
                val ctx = LocalContext.current
                TextButton({
                    SettingsStore.update { it.copy(sosName = name, sosNumber = number, sosSeconds = secs, sosMinG = g, sosEnabled = on && number.isNotBlank()) }
                    com.jeremy.dashcam.service.DashcamService.instance?.testSos()
                        ?: android.widget.Toast.makeText(ctx, "הפעל נסיעה כדי לבדוק", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("בדיקה (בלי חיוג)", color = J.Amber) }
            }
        },
        confirmButton = {
            TextButton({
                SettingsStore.update { it.copy(sosName = name.trim(), sosNumber = number.trim(), sosSeconds = secs, sosMinG = g, sosEnabled = on && number.isNotBlank()) }
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) J.Mint else J.Text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) J.Green.copy(alpha = 0.22f) else J.Card)
            .border(1.dp, if (selected) J.Green else J.Stroke, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}


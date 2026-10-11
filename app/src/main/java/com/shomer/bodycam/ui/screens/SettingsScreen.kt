package com.shomer.bodycam.ui.screens

import android.Manifest
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shomer.bodycam.R
import com.shomer.bodycam.core.ShiftController
import com.shomer.bodycam.data.AppLanguage
import com.shomer.bodycam.data.Mounting
import com.shomer.bodycam.data.RecordKind
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.Resolution
import com.shomer.bodycam.data.Sensitivity
import com.shomer.bodycam.data.SettingsStore
import com.shomer.bodycam.data.Locked
import com.shomer.bodycam.ui.components.Tag
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.foundation.layout.size
import com.shomer.bodycam.service.ManDownOverlay
import com.shomer.bodycam.ui.components.ScreenTitle
import com.shomer.bodycam.ui.components.SectionLabel
import com.shomer.bodycam.ui.theme.S

@Composable
fun SettingsScreen(onOpenAccount: () -> Unit) {
    val ctx = LocalContext.current
    val s by SettingsStore.state.collectAsStateWithLifecycle()
    val lk by SettingsStore.locked.collectAsStateWithLifecycle()
    val proActive by SettingsStore.proActive.collectAsStateWithLifecycle()
    val acc by com.shomer.bodycam.cloud.AccountStore.state.collectAsStateWithLifecycle()
    val org by com.shomer.bodycam.cloud.OrgStore.state.collectAsStateWithLifecycle()
    val needProMsg = stringResource(R.string.needs_pro)
    val needPro: () -> Unit = { Toast.makeText(ctx, needProMsg, Toast.LENGTH_SHORT).show(); onOpenAccount() }
    val shift by ShiftController.state.collectAsStateWithLifecycle()
    val records by RecordRepository.records.collectAsStateWithLifecycle()
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    var showGuide by remember { mutableStateOf(false) }
    val gb = { b: Long -> "%.1f GB".format(b / 1_073_741_824.0) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.nav_settings))
      androidx.compose.runtime.CompositionLocalProvider(LocalNeedPro provides needPro) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            // account, Pro and company – one row that says where you stand
            Spacer(Modifier.height(4.dp))
            com.shomer.bodycam.ui.components.SCard(Modifier.fillMaxWidth(), onClick = onOpenAccount, border = if (proActive) S.Amber else S.Stroke) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WorkspacePremium, null, tint = S.Amber, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                org.isManager -> org.org!!.name
                                org.isMember -> org.org!!.name
                                acc.personalPro -> "Shomer Pro"
                                else -> stringResource(R.string.acc_row_title)
                            }, color = S.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        )
                        Text(
                            when {
                                org.isManager -> stringResource(R.string.role_manager)
                                org.isMember -> stringResource(R.string.acc_row_member)
                                acc.signedIn -> acc.email!!
                                else -> stringResource(R.string.acc_row_sub)
                            }, color = S.TextDim, fontSize = 12.sp,
                        )
                    }
                    Text("›", color = S.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }

            SectionLabel(stringResource(R.string.sec_identity))
            Group {
                TextRow(stringResource(R.string.operator_id), s.operatorId, stringResource(R.string.operator_id_hint), locked = Locked.OPERATOR in lk) { v -> SettingsStore.update { it.copy(operatorId = v.take(32)) } }
            }

            SectionLabel(stringResource(R.string.sec_recording))
            Group {
                ChoiceRow(stringResource(R.string.pre_record), s.preRecordSeconds, SettingsStore.PRE_RECORD_OPTIONS, { stringResource(R.string.seconds_n, it) }, stringResource(R.string.pre_record_sub),
                    locked = Locked.PRE_RECORD in lk, proOnly = { it > SettingsStore.FREE_MAX_PRE }) { v -> SettingsStore.update { it.copy(preRecordSeconds = v) } }
                ChoiceRow(stringResource(R.string.quality), s.resolution, Resolution.entries, { it.label }, proOnly = { it == Resolution.UHD }) { v -> SettingsStore.update { it.copy(resolution = v) } }
                ChoiceRow(stringResource(R.string.fps), s.fps, SettingsStore.FPS_OPTIONS, { "$it FPS" }) { v -> SettingsStore.update { it.copy(fps = v) } }
                SwitchRow(stringResource(R.string.record_audio), s.recordAudio, locked = Locked.AUDIO in lk) { v ->
                    if (v && !hasPerm(ctx, Manifest.permission.RECORD_AUDIO)) perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    SettingsStore.update { it.copy(recordAudio = v) }
                }
                ChoiceRow(stringResource(R.string.mounting), s.mounting, Mounting.entries, { stringResource(it.labelRes) }, stringResource(R.string.mounting_sub)) { v -> SettingsStore.update { it.copy(mounting = v) } }
                if (shift.recording) Note(stringResource(R.string.applies_after_recording))
            }

            SectionLabel(stringResource(R.string.sec_quick_start))
            Group {
                SwitchRow(stringResource(R.string.volume_keys), s.volumeKeys, stringResource(R.string.volume_keys_sub)) { v -> SettingsStore.update { it.copy(volumeKeys = v) } }
                SwitchRow(stringResource(R.string.shake), s.shakeToRecord, stringResource(R.string.shake_sub)) { v -> SettingsStore.update { it.copy(shakeToRecord = v) } }
                SwitchRow(stringResource(R.string.floating), s.floatingButton, stringResource(R.string.floating_sub)) { v ->
                    if (v && !Settings.canDrawOverlays(ctx)) openOverlaySettings(ctx)
                    SettingsStore.update { it.copy(floatingButton = v) }
                }
                SwitchRow(stringResource(R.string.voice), s.voiceCommands, stringResource(R.string.voice_sub, s.voiceStartPhrase, s.voiceStopPhrase)) { v ->
                    if (v && !hasPerm(ctx, Manifest.permission.RECORD_AUDIO)) perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    SettingsStore.update { it.copy(voiceCommands = v) }
                }
                if (s.voiceCommands) {
                    TextRow(stringResource(R.string.voice_start_phrase), s.voiceStartPhrase) { v -> if (v.isNotBlank()) SettingsStore.update { it.copy(voiceStartPhrase = v) } }
                    TextRow(stringResource(R.string.voice_stop_phrase), s.voiceStopPhrase) { v -> if (v.isNotBlank()) SettingsStore.update { it.copy(voiceStopPhrase = v) } }
                }
            }

            SectionLabel(stringResource(R.string.sec_safety))
            Group {
                SwitchRow(stringResource(R.string.fall), s.fallDetection, stringResource(R.string.fall_sub), locked = Locked.FALL in lk) { v -> SettingsStore.update { it.copy(fallDetection = v) } }
                if (s.fallDetection) ChoiceRow(stringResource(R.string.sensitivity), s.fallSensitivity, Sensitivity.entries, { stringResource(it.labelRes) }) { v -> SettingsStore.update { it.copy(fallSensitivity = v) } }
                SwitchRow(stringResource(R.string.sos), s.sosEnabled, stringResource(R.string.sos_sub)) { v ->
                    if (v) {
                        val need = listOf(Manifest.permission.CALL_PHONE).filter { !hasPerm(ctx, it) }
                        if (need.isNotEmpty()) perms.launch(need.toTypedArray())
                        if (!Settings.canDrawOverlays(ctx)) openOverlaySettings(ctx)
                    }
                    SettingsStore.update { it.copy(sosEnabled = v) }
                }
                if (s.sosEnabled) {
                    TextRow(stringResource(R.string.sos_name), s.sosName) { v -> SettingsStore.update { it.copy(sosName = v) } }
                    TextRow(stringResource(R.string.sos_number), s.sosNumber, keyboard = KeyboardType.Phone) { v -> SettingsStore.update { it.copy(sosNumber = v) } }
                    ChoiceRow(stringResource(R.string.sos_countdown), s.sosSeconds, SettingsStore.SOS_SECONDS_OPTIONS, { stringResource(R.string.seconds_n, it) }) { v -> SettingsStore.update { it.copy(sosSeconds = v) } }
                    val needOverlay = stringResource(R.string.overlay_needed)
                    ActionRow(stringResource(R.string.sos_test), stringResource(R.string.sos_test_sub)) {
                        if (!Settings.canDrawOverlays(ctx)) { Toast.makeText(ctx, needOverlay, Toast.LENGTH_LONG).show(); openOverlaySettings(ctx) }
                        else ManDownOverlay(ctx.applicationContext).show(10, s.sosName, s.sosNumber, test = true) {}
                    }
                }
            }

            SectionLabel(stringResource(R.string.sec_stamp))
            Group {
                SwitchRow(stringResource(R.string.stamp_time), s.stampTime) { v -> SettingsStore.update { it.copy(stampTime = v) } }
                SwitchRow(stringResource(R.string.location), s.location, stringResource(R.string.location_sub), locked = Locked.LOCATION in lk) { v ->
                    if (v && !hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    SettingsStore.update { it.copy(location = v) }
                }
                if (s.location) SwitchRow(stringResource(R.string.stamp_location), s.stampLocation) { v -> SettingsStore.update { it.copy(stampLocation = v) } }
            }

            SectionLabel(stringResource(R.string.sec_feedback))
            Group {
                SwitchRow(stringResource(R.string.haptics), s.haptics, stringResource(R.string.haptics_sub)) { v -> SettingsStore.update { it.copy(haptics = v) } }
                SwitchRow(stringResource(R.string.pocket_auto), s.pocketOnRecord, stringResource(R.string.pocket_auto_sub)) { v -> SettingsStore.update { it.copy(pocketOnRecord = v) } }
            }

            SectionLabel(stringResource(R.string.sec_storage))
            Group {
                SwitchRow(stringResource(R.string.auto_lock), s.autoLock, stringResource(R.string.auto_lock_sub), locked = Locked.AUTO_LOCK in lk) { v -> SettingsStore.update { it.copy(autoLock = v) } }
                SwitchRow(stringResource(R.string.auto_delete), s.autoDelete, stringResource(R.string.auto_delete_sub, gb(RecordRepository.usedBytes(RecordKind.INCIDENT)))) { v ->
                    SettingsStore.update { it.copy(autoDelete = v) }; Thread { RecordRepository.enforceStorageLimit() }.start()
                }
                if (s.autoDelete) ChoiceRow(stringResource(R.string.max_storage), s.maxStorageGb, SettingsStore.STORAGE_OPTIONS, { "$it GB" }) { v ->
                    SettingsStore.update { it.copy(maxStorageGb = v) }; Thread { RecordRepository.enforceStorageLimit() }.start()
                }
                SwitchRow(stringResource(R.string.full_shift), s.fullShift, stringResource(R.string.full_shift_sub), pro = true) { v -> SettingsStore.update { it.copy(fullShift = v) } }
                if (s.fullShift) {
                    ChoiceRow(stringResource(R.string.shift_clip_len), s.shiftClipMinutes, SettingsStore.SHIFT_CLIP_OPTIONS, { stringResource(R.string.minutes_n, it) }) { v -> SettingsStore.update { it.copy(shiftClipMinutes = v) } }
                    ChoiceRow(stringResource(R.string.shift_storage), s.shiftMaxStorageGb, SettingsStore.STORAGE_OPTIONS, { "$it GB" }, stringResource(R.string.shift_storage_sub, gb(RecordRepository.usedBytes(RecordKind.SHIFT)))) { v ->
                        SettingsStore.update { it.copy(shiftMaxStorageGb = v) }; Thread { RecordRepository.enforceStorageLimit() }.start()
                    }
                }
                Note(stringResource(R.string.storage_summary, records.count { !it.isShift }, records.count { it.locked }))
            }

            SectionLabel(stringResource(R.string.sec_backup))
            Group {
                DriveRows(s.driveBackup, s.driveCellular)
            }

            SectionLabel(stringResource(R.string.sec_general))
            Group {
                ChoiceRow(stringResource(R.string.language), s.language, AppLanguage.entries, { stringResource(it.labelRes) }) { v ->
                    SettingsStore.update { it.copy(language = v) }
                    AppCompatDelegate.setApplicationLocales(if (v.tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(v.tag))
                }
                ActionRow(stringResource(R.string.battery_opt), stringResource(if (ignoresBatteryOptimizations(ctx)) R.string.battery_opt_ok else R.string.battery_opt_sub)) { requestBatteryExemption(ctx) }
                ActionRow(stringResource(R.string.how_it_works), stringResource(R.string.how_it_works_sub)) { showGuide = true }
            }
            Text(
                "Shomer " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty(),
                color = S.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 20.dp),
            )
        }
      }
    }
    if (showGuide) GuideDialog { showGuide = false }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(S.Card)) { content() }
}

@Composable
private fun Note(text: String) {
    Text(text, color = S.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** Opens the account screen when a Pro-only option is touched without Pro. */
private val LocalNeedPro = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

@Composable
private fun RowText(title: String, sub: String?, modifier: Modifier, locked: Boolean = false, pro: Boolean = false) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = if (locked) S.TextDim else S.Text, fontSize = 15.sp)
            if (pro) { Spacer(Modifier.width(6.dp)); Tag("PRO", S.Amber) }
        }
        if (locked) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = S.AmberSoft, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.set_by_company), color = S.AmberSoft, fontSize = 12.sp)
        } else if (!sub.isNullOrBlank()) Text(sub, color = S.TextDim, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun SwitchRow(title: String, value: Boolean, sub: String? = null, locked: Boolean = false, pro: Boolean = false, onChange: (Boolean) -> Unit) {
    val needPro = LocalNeedPro.current
    val proActive by SettingsStore.proActive.collectAsStateWithLifecycle()
    val change: (Boolean) -> Unit = { v -> if (pro && !proActive && v) needPro() else onChange(v) }
    Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { change(!value) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(title, sub, Modifier.weight(1f), locked, pro && !proActive)
        Spacer(Modifier.width(10.dp))
        Switch(value, change, enabled = !locked, colors = SwitchDefaults.colors(checkedTrackColor = S.Amber, checkedThumbColor = S.OnAmber, uncheckedTrackColor = S.CardHi))
    }
}

@Composable
private fun ActionRow(title: String, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(title, sub, Modifier.weight(1f))
        Text("›", color = S.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String, value: T, options: List<T>, label: @Composable (T) -> String, sub: String? = null,
    locked: Boolean = false, proOnly: (T) -> Boolean = { false }, onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val needPro = LocalNeedPro.current
    val proActive by SettingsStore.proActive.collectAsStateWithLifecycle()
    val pick: (T) -> Unit = { o -> if (proOnly(o) && !proActive) needPro() else onPick(o) }
    Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { open = true }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(title, sub, Modifier.weight(1f), locked)
        Spacer(Modifier.width(10.dp))
        Text(label(value), color = S.Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false }, containerColor = S.Surface,
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { o ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pick(o); open = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(o == value, { pick(o); open = false }, colors = RadioButtonDefaults.colors(selectedColor = S.Amber))
                            Text(label(o), color = S.Text)
                            if (proOnly(o) && !proActive) { Spacer(Modifier.width(6.dp)); Tag("PRO", S.Amber) }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.close), color = S.TextDim) } },
        )
    }
}

@Composable
private fun TextRow(title: String, value: String, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, locked: Boolean = false, onSave: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { open = true }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(title, null, Modifier.weight(1f), locked)
        Spacer(Modifier.width(10.dp))
        Text(value.ifBlank { placeholder.ifBlank { "—" } }, color = if (value.isBlank()) S.TextDim else S.Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
    if (open) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { open = false }, containerColor = S.Surface,
            title = { Text(title) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(placeholder, color = S.TextDim) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard)) },
            confirmButton = { TextButton({ onSave(text.trim()); open = false }) { Text(stringResource(R.string.save), color = S.Amber) } },
            dismissButton = { TextButton({ open = false }) { Text(stringResource(R.string.cancel), color = S.TextDim) } },
        )
    }
}

/** Pro: Google Drive backup switch, with the one-time Google permission. */
@Composable
private fun DriveRows(on: Boolean, cellular: Boolean) {
    val ctx = LocalContext.current
    val acc by com.shomer.bodycam.cloud.AccountStore.state.collectAsStateWithLifecycle()
    val needsAuth by com.shomer.bodycam.cloud.DriveBackup.needsAuth.collectAsStateWithLifecycle()
    val needSignIn = stringResource(R.string.drive_sign_in_first)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val res = com.shomer.bodycam.cloud.DriveBackup.resultFromIntent(ctx, r.data)
        if (r.resultCode == android.app.Activity.RESULT_OK && res?.accessToken != null) {
            com.shomer.bodycam.cloud.DriveBackup.needsAuth.value = false
            SettingsStore.update { it.copy(driveBackup = true) }
        }
    }
    fun enable() {
        val a = ctx.findActivity() ?: return
        if (!acc.signedIn) { Toast.makeText(ctx, needSignIn, Toast.LENGTH_LONG).show(); return }
        com.shomer.bodycam.cloud.DriveBackup.authorize(a,
            launch = { pi -> consent.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()) },
            onGranted = { SettingsStore.update { it.copy(driveBackup = true) } },
            onError = { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() })
    }
    SwitchRow(stringResource(R.string.drive_backup), on, stringResource(if (needsAuth) R.string.drive_needs_auth else R.string.drive_backup_sub), pro = true) { v ->
        if (v) enable() else SettingsStore.update { it.copy(driveBackup = false) }
    }
    if (needsAuth && on) ActionRow(stringResource(R.string.drive_reauth), null) { enable() }
    if (on) SwitchRow(stringResource(R.string.drive_cellular), cellular, stringResource(R.string.drive_cellular_sub)) { v -> SettingsStore.update { it.copy(driveCellular = v) } }
}

@Composable
fun GuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = S.Surface,
        title = { Text(stringResource(R.string.how_it_works)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    R.string.guide_1_t to R.string.guide_1, R.string.guide_2_t to R.string.guide_2, R.string.guide_3_t to R.string.guide_3,
                    R.string.guide_4_t to R.string.guide_4, R.string.guide_5_t to R.string.guide_5, R.string.guide_6_t to R.string.guide_6,
                ).forEach { (t, b) ->
                    Text(stringResource(t), color = S.AmberSoft, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(stringResource(b), color = S.Text, fontSize = 14.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.got_it), color = S.Amber) } },
    )
}


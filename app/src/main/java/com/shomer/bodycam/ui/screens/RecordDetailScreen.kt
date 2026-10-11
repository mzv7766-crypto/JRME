package com.shomer.bodycam.ui.screens

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.shomer.bodycam.R
import com.shomer.bodycam.core.Evidence
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.Recording
import com.shomer.bodycam.service.Notifications
import com.shomer.bodycam.ui.theme.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun RecordDetailScreen(id: String, onBack: () -> Unit) {
    val records by RecordRepository.records.collectAsStateWithLifecycle()
    val r = records.firstOrNull { it.id == id }
    val ctx = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = S.Text) }
            Text(
                r?.let { recordTitle(it) } ?: "", color = S.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable(enabled = r != null) { rename = true },
            )
            val orgH by com.shomer.bodycam.cloud.OrgStore.state.collectAsStateWithLifecycle()
            val keeps = orgH.activeMember && orgH.org?.policy?.allowDelete != true
            if (r != null) IconButton({ if (!(keeps && r.locked)) RecordRepository.setLocked(r.id, !r.locked) }) {
                Icon(if (r.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, tint = if (r.locked) S.Amber else S.TextDim)
            }
        }
        if (r == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.record_not_found), color = S.TextDim) }
            return
        }
        val lockedMsg = stringResource(R.string.delete_locked)
        val actions: @Composable (Modifier) -> Unit = { m ->
            ActionTile(Icons.Filled.Share, stringResource(R.string.share), m) { share(ctx, r) }
            ActionTile(Icons.Filled.EditNote, stringResource(R.string.note), m) { note = true }
            // a company may keep its guards from unlocking / deleting evidence
            val org by com.shomer.bodycam.cloud.OrgStore.state.collectAsStateWithLifecycle()
            val companyKeeps = org.activeMember && org.org?.policy?.allowDelete != true
            val keepsMsg = stringResource(R.string.company_keeps)
            ActionTile(if (r.locked) Icons.Filled.LockOpen else Icons.Filled.Lock, stringResource(if (r.locked) R.string.unlock else R.string.lock), m, tint = if (companyKeeps && r.locked) S.TextDim else S.Text) {
                if (companyKeeps && r.locked) Toast.makeText(ctx, keepsMsg, Toast.LENGTH_SHORT).show() else RecordRepository.setLocked(r.id, !r.locked)
            }
            ActionTile(Icons.Filled.Delete, stringResource(R.string.delete), m, tint = if (r.locked || companyKeeps) S.TextDim else S.Red) {
                when {
                    companyKeeps -> Toast.makeText(ctx, keepsMsg, Toast.LENGTH_SHORT).show()
                    r.locked -> Toast.makeText(ctx, lockedMsg, Toast.LENGTH_SHORT).show()
                    else -> confirmDelete = true
                }
            }
        }
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color.Black)) { Player(r) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    EvidencePanel(r)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions(Modifier.weight(1f)) }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) { Player(r) }
            Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                Spacer(Modifier.height(8.dp))
                EvidencePanel(r)
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions(Modifier.weight(1f)) }
        }
    }

    if (rename && r != null) TextDialog(stringResource(R.string.rename), r.title ?: "", singleLine = true, onDismiss = { rename = false }) { RecordRepository.rename(r.id, it) }
    if (note && r != null) TextDialog(stringResource(R.string.note), r.note ?: "", singleLine = false, onDismiss = { note = false }) { RecordRepository.setNote(r.id, it) }
    if (confirmDelete && r != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = S.Surface,
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = { TextButton({ confirmDelete = false; if (RecordRepository.delete(r.id)) onBack() }) { Text(stringResource(R.string.delete), color = S.Red) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel), color = S.TextDim) } },
        )
    }
}

/** The "evidence sheet": when, how long, what started it, who, where, and the fingerprint with a live check. */
@Composable
private fun EvidencePanel(r: Recording) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var check by remember(r.id) { mutableStateOf<Evidence.Check?>(null) }
    var checking by remember(r.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(S.Card).border(1.dp, S.Stroke, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Line(stringResource(R.string.f_started), formatDateTime(r.triggerTime))
        Line(stringResource(R.string.f_length), Notifications.formatDuration(r.durationMs) +
            if (r.preRecordMs > 500) "  (" + stringResource(R.string.f_incl_pre, r.preRecordMs / 1000) + ")" else "")
        if (!r.isShift) Line(stringResource(R.string.f_trigger), stringResource(r.trigger.labelRes))
        r.operatorId?.let { Line(stringResource(R.string.f_operator), it) }
        if (r.lat != null && r.lon != null) {
            Row(Modifier.fillMaxWidth().clickable {
                val uri = android.net.Uri.parse("geo:${r.lat},${r.lon}?q=${r.lat},${r.lon}(Shomer)")
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) {
                Line(stringResource(R.string.f_location), String.format(Locale.US, "%.5f, %.5f", r.lat, r.lon), Modifier.weight(1f))
                Text(stringResource(R.string.map), color = S.Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Line(stringResource(R.string.f_size), "%.1f MB".format(r.sizeBytes / 1_048_576.0))
        r.note?.let { Line(stringResource(R.string.note), it) }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(S.Stroke))
        Spacer(Modifier.height(8.dp))
        Text("SHA-256", color = S.AmberSoft, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(r.sha256 ?: stringResource(R.string.no_fingerprint), color = S.Text, fontSize = 11.sp, fontFamily = S.Mono, lineHeight = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, color, text) = when (check) {
                Evidence.Check.INTACT -> Triple(Icons.Filled.VerifiedUser, S.Green, stringResource(R.string.check_intact))
                Evidence.Check.CHANGED -> Triple(Icons.Filled.GppBad, S.Red, stringResource(R.string.check_changed))
                Evidence.Check.MISSING -> Triple(Icons.Filled.GppBad, S.Red, stringResource(R.string.check_missing))
                Evidence.Check.NO_FINGERPRINT -> Triple(Icons.Filled.GppBad, S.TextDim, stringResource(R.string.no_fingerprint))
                null -> Triple(Icons.Filled.VerifiedUser, S.TextDim, stringResource(R.string.check_hint))
            }
            if (checking) CircularProgressIndicator(Modifier.size(18.dp), color = S.Amber, strokeWidth = 2.dp)
            else Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (r.sha256 != null) Text(
                stringResource(R.string.check_now), color = S.OnAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(S.Amber).clickable(enabled = !checking) {
                    checking = true
                    scope.launch {
                        check = withContext(Dispatchers.IO) { Evidence.verify(r) }
                        checking = false
                    }
                }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Line(k: String, v: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = S.TextDim, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Text(v, color = S.Text, fontSize = 13.sp, fontFamily = S.Mono)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, tint: Color = S.Text, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(S.Card).border(1.dp, S.Stroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = S.Text, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun TextDialog(title: String, initial: String, singleLine: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = S.Surface,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = singleLine, minLines = if (singleLine) 1 else 3) },
        confirmButton = { TextButton({ onSave(text); onDismiss() }) { Text(stringResource(R.string.save), color = S.Amber) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = S.TextDim) } },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun Player(r: Recording) {
    val ctx = LocalContext.current
    val player = remember(r.filePath) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(r.file)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Shares ONE video file plus a short evidence note (time, length, place and the full SHA-256 fingerprint),
 * so whoever receives it can check that nothing was changed.
 */
private fun share(ctx: Context, r: Recording) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", r.file)
    val text = buildString {
        append("Shomer\n")
        append("🕒 ").append(formatDateTime(r.triggerTime)).append("  (").append(Notifications.formatDuration(r.durationMs)).append(")\n")
        r.operatorId?.let { append("👤 ").append(it).append("\n") }
        if (r.lat != null && r.lon != null) append("📍 https://maps.google.com/?q=").append(String.format(Locale.US, "%.6f,%.6f", r.lat, r.lon)).append("\n")
        r.sha256?.let { append("SHA-256: ").append(it) }
    }
    val send = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri) }
    send.putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

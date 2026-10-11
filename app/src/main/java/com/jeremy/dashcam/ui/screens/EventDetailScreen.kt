package com.jeremy.dashcam.ui.screens

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import com.jeremy.dashcam.R
import com.jeremy.dashcam.data.EventRecord
import com.jeremy.dashcam.data.EventRepository
import com.jeremy.dashcam.service.Notifications
import com.jeremy.dashcam.ui.theme.J

@Composable
fun EventDetailScreen(id: String, onBack: () -> Unit) {
    val events by EventRepository.events.collectAsStateWithLifecycle()
    val e = events.firstOrNull { it.id == id }
    val ctx = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = J.Text) }
            Text(
                e?.let { it.name ?: if (it.isTrip) stringResource(R.string.trip_clip) + " " + formatTime(it.videoStartTime) + "–" + formatTime(it.endTime) else stringResource(it.trigger.labelRes) } ?: "", color = J.Text, fontSize = 19.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (e != null) {
                IconButton({ EventRepository.setLocked(e.id, !e.locked) }) {
                    Icon(if (e.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, tint = if (e.locked) J.Mint else J.TextDim)
                }
                IconButton({ info = true }) { Icon(Icons.Filled.Info, "מה משותף", tint = J.TextDim) }
            }
        }
        if (e == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.event_not_found), color = J.TextDim) }
            return
        }
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val lockedMsg = stringResource(R.string.delete_locked)
        val actions: @Composable (Modifier) -> Unit = { m ->
            ActionTile(Icons.Filled.Share, stringResource(R.string.share), m) { share(ctx, e, shareText(ctx, e)) }
            ActionTile(Icons.Filled.Edit, stringResource(R.string.rename), m) { rename = true }
            ActionTile(
                if (e.locked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                stringResource(if (e.locked) R.string.unlock else R.string.lock), m,
            ) { EventRepository.setLocked(e.id, !e.locked) }
            ActionTile(Icons.Filled.Delete, stringResource(R.string.delete), m, tint = if (e.locked) J.TextDim else J.Red) {
                if (e.locked) Toast.makeText(ctx, lockedMsg, Toast.LENGTH_SHORT).show() else confirmDelete = true
            }
        }
        val proInfo: @Composable () -> Unit = {
            if (e.lat != null && e.lon != null) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val uri = android.net.Uri.parse("geo:${e.lat},${e.lon}?q=${e.lat},${e.lon}(Jeremy)")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📍 " + String.format(java.util.Locale.US, "%.5f, %.5f", e.lat, e.lon) + (e.speedKmh?.let { "  •  ${it.toInt()} קמ״ש" } ?: ""), color = J.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("פתח במפה", color = J.Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (e.plates.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("לוחיות: ", color = J.TextDim, fontSize = 13.sp)
                    e.plates.take(4).forEach { pl ->
                        Text(
                            pl, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFD000))
                                .clickable {
                                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("plate", pl))
                                    Toast.makeText(ctx, "הועתק: $pl", Toast.LENGTH_SHORT).show()
                                }.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            } else if (e.platesChecked) {
                Text("לא זוהו לוחיות רישוי באירוע", color = J.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp))
            }
            if (e.aiTags.isNotEmpty()) {
                Text("🤖 זוהו: " + e.aiTags.take(5).joinToString(", "), color = J.Text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            }
            DriveRow(e)
        }
        val details: @Composable () -> Unit = {
            if (false) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                    InfoLine(stringResource(R.string.reason), if (e.isTrip) stringResource(R.string.trip_clip) else stringResource(e.trigger.labelRes) + (e.peakG?.let { " • %.1fG".format(it) } ?: ""))
                    InfoLine(stringResource(R.string.duration), Notifications.formatDuration(e.durationMs))
                    InfoLine(stringResource(R.string.size), "%.1f MB".format(e.sizeBytes / 1_048_576.0))
                    InfoLine("⏱", formatDateTime(e.triggerTime))
                }
            } else {
                Text(
                    formatDateTime(e.triggerTime) + "  •  " + Notifications.formatDuration(e.durationMs),
                    color = J.TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
        if (landscape) {
            // Landscape: video on the side, compact actions in a column – nothing covers the video.
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Color.Black)) { VideoPlayer(e) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.width(112.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions(Modifier.fillMaxWidth().weight(1f))
                }
            }
        } else {
            // Video takes the free space; actions sit BELOW it so they never cover the video.
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
                VideoPlayer(e)
            }
            details()
            proInfo()
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions(Modifier.weight(1f))
            }
        }
    }

    if (rename && e != null) {
        var text by remember { mutableStateOf(e.name ?: "") }
        AlertDialog(
            onDismissRequest = { rename = false }, containerColor = J.Surface,
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ EventRepository.rename(e.id, text); rename = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ rename = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (info && e != null) {
        AlertDialog(
            onDismissRequest = { info = false }, containerColor = J.Surface,
            title = { Text("מה משותף מעכשיו") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text("בהודעה (נשלח לצד הסרטון בשיתוף)", color = J.Mint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    ShareOptions()
                    Spacer(Modifier.height(12.dp))
                    Text("בצריבה (בתוך סרטונים חדשים)", color = J.Mint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("לא משנה סרטונים שכבר נשמרו.", color = J.TextDim, fontSize = 12.sp)
                    BurnOptions(onOpenPro = { Toast.makeText(ctx, "זמין ב־Jeremy Pro – הגדרות ← Jeremy Pro", Toast.LENGTH_LONG).show() })
                    Spacer(Modifier.height(12.dp))
                    Text("פרטי הסרטון", color = J.Mint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    InfoLine(stringResource(R.string.reason), if (e.isTrip) stringResource(R.string.trip_clip) else stringResource(e.trigger.labelRes) + (e.peakG?.let { " • %.1fG".format(it) } ?: ""))
                    InfoLine(stringResource(R.string.duration), Notifications.formatDuration(e.durationMs))
                    InfoLine(stringResource(R.string.size), "%.1f MB".format(e.sizeBytes / 1_048_576.0))
                    InfoLine("⏱", formatDateTime(e.triggerTime))
                }
            },
            confirmButton = { TextButton({ info = false }) { Text("סגור") } },
        )
    }
    if (confirmDelete && e != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = J.Surface,
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = { TextButton({ confirmDelete = false; if (EventRepository.delete(e.id)) onBack() }) { Text(stringResource(R.string.delete), color = J.Red) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = J.TextDim, fontSize = 14.sp, modifier = Modifier.width(90.dp))
        Text(v, color = J.Text, fontSize = 14.sp)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, tint: Color = J.Text, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(J.Card).border(1.dp, J.Stroke, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = J.Text, fontSize = 12.sp, maxLines = 1)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(e: EventRecord) {
    val ctx = LocalContext.current
    val player = remember(e.filePath) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(e.file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); useController = true } },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Shares exactly ONE continuous MP4 file. */
private fun share(ctx: Context, e: EventRecord, text: String? = null) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", e.file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** PRO: Google Drive backup state / manual backup button for one clip. */
@Composable
private fun DriveRow(e: EventRecord) {
    val ctx = LocalContext.current
    val pro by com.jeremy.dashcam.data.ProStore.state.collectAsStateWithLifecycle()
    val st by com.jeremy.dashcam.data.SettingsStore.state.collectAsStateWithLifecycle()
    val work by remember(e.id) { com.jeremy.dashcam.core.pro.DriveBackup.status(ctx, e.id) }.collectAsStateWithLifecycle(null)
    if (!pro.isPro || pro.email == null) return
    if (e.driveId == null && st.driveMode == com.jeremy.dashcam.data.DriveMode.OFF) return
    val (text, action) = when {
        e.driveId != null -> "☁ מגובה בגוגל דרייב" to "פתח"
        work == androidx.work.WorkInfo.State.RUNNING -> "☁ מעלה לדרייב…" to null
        work == androidx.work.WorkInfo.State.ENQUEUED || work == androidx.work.WorkInfo.State.BLOCKED ->
            (if (st.driveCellular) "☁ ממתין לאינטרנט" else "☁ ממתין ל־Wi-Fi") to "עכשיו"
        work == androidx.work.WorkInfo.State.FAILED -> "☁ הגיבוי נכשל" to "נסה שוב"
        else -> "☁ לא מגובה" to "גבה לדרייב"
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = action != null) {
            if (e.driveId != null) runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(com.jeremy.dashcam.core.pro.DriveBackup.openUrl(e.driveId))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else com.jeremy.dashcam.core.pro.DriveBackup.enqueue(ctx, e, manual = true)
        }.padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = J.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) Text(action, color = J.Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** The message sent next to a shared video, built from the choices in Settings → "מה נשלח יחד עם הסרטון". */
private fun shareText(ctx: Context, e: EventRecord): String? {
    val on = { k: String -> com.jeremy.dashcam.data.SharePrefs.isOn(ctx, k) }
    val reason = if (e.isTrip) ctx.getString(R.string.trip_clip) else ctx.getString(e.trigger.labelRes)
    val lines = listOfNotNull(
        if (on("name")) e.name?.let { "📝 $it" } else null,
        if (on("time")) "🕒 " + formatDateTime(e.triggerTime) else null,
        if (on("reason")) "⚠️ " + reason + (e.peakG?.let { " (%.1fG)".format(it) } ?: "") else null,
        if (on("map") && e.lat != null && e.lon != null) "📍 https://maps.google.com/?q=" + String.format(java.util.Locale.US, "%.6f,%.6f", e.lat, e.lon) else null,
        if (on("speed")) e.speedKmh?.let { "🚗 ${it.toInt()} קמ״ש" } else null,
        if (on("plates") && e.plates.isNotEmpty()) "🔢 " + e.plates.joinToString(", ") else null,
        if (on("ai") && e.aiTags.isNotEmpty()) "🤖 " + e.aiTags.joinToString(", ") else null,
    )
    return if (lines.isEmpty()) null else "Jeremy\n" + lines.joinToString("\n")
}

package com.jeremy.dashcam.ui.screens

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
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
                e?.let { it.name ?: stringResource(it.trigger.labelRes) } ?: "", color = J.Text, fontSize = 19.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (e != null) {
                IconButton({ EventRepository.setLocked(e.id, !e.locked) }) {
                    Icon(if (e.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, tint = if (e.locked) J.Mint else J.TextDim)
                }
                IconButton({ info = !info }) { Icon(Icons.Filled.Info, null, tint = J.TextDim) }
            }
        }
        if (e == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.event_not_found), color = J.TextDim) }
            return
        }
        // Video takes the free space; actions sit BELOW it so they never cover the video.
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
            VideoPlayer(e)
        }
        if (info) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                InfoLine(stringResource(R.string.reason), stringResource(e.trigger.labelRes))
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
        // Compact action menu
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(Icons.Filled.Share, stringResource(R.string.share), Modifier.weight(1f)) { share(ctx, e) }
            ActionTile(Icons.Filled.Edit, stringResource(R.string.rename), Modifier.weight(1f)) { rename = true }
            ActionTile(
                if (e.locked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                stringResource(if (e.locked) R.string.unlock else R.string.lock), Modifier.weight(1f),
            ) { EventRepository.setLocked(e.id, !e.locked) }
            val lockedMsg = stringResource(R.string.delete_locked)
            ActionTile(Icons.Filled.Delete, stringResource(R.string.delete), Modifier.weight(1f), tint = if (e.locked) J.TextDim else J.Red) {
                if (e.locked) Toast.makeText(ctx, lockedMsg, Toast.LENGTH_SHORT).show() else confirmDelete = true
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
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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
private fun share(ctx: Context, e: EventRecord) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", e.file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

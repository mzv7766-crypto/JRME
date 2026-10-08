package com.jeremy.dashcam.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Route
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeremy.dashcam.R
import com.jeremy.dashcam.data.EventRecord
import com.jeremy.dashcam.data.EventRepository
import com.jeremy.dashcam.data.Trigger
import com.jeremy.dashcam.service.Notifications
import com.jeremy.dashcam.ui.components.JCard
import com.jeremy.dashcam.ui.components.ScreenHeader
import com.jeremy.dashcam.ui.theme.J
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun EventsScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val all by EventRepository.events.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = events, 1 = full drive
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val events = remember(all, tab) { all.filter { if (tab == 0) !it.isTrip else it.isTrip } }

    val filtered = remember(events, query) {
        if (query.isBlank()) events else events.filter {
            val label = it.name ?: ctx.getString(if (it.isTrip) R.string.trip_clip else it.trigger.labelRes)
            label.contains(query, ignoreCase = true) || formatDate(it.triggerTime).contains(query)
        }
    }
    val today = stringResource(R.string.today)
    val yesterday = stringResource(R.string.yesterday)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            stringResource(R.string.events_title),
            end = {
                IconButton({ searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, stringResource(R.string.search), tint = J.TextDim)
                }
            },
        )
        // Events | Full drive
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(J.Card).padding(4.dp),
        ) {
            listOf(R.string.tab_events, R.string.tab_trips).forEachIndexed { i, label ->
                val sel = tab == i
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (sel) J.Green.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { tab = i }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(label), color = if (sel) J.Mint else J.TextDim, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (searching) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.search)) }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = J.Green, unfocusedBorderColor = J.Stroke),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (events.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (tab == 0) Icons.Filled.VideoLibrary else Icons.Filled.Route, null, tint = J.TextDim, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(if (tab == 0) R.string.events_empty else R.string.trips_empty), color = J.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(if (tab == 0) R.string.events_empty_hint else R.string.trips_empty_hint), color = J.TextDim, textAlign = TextAlign.Center)
            }
            return
        }
        if (tab == 0) {
            val groups = remember(filtered, today, yesterday) { filtered.groupBy { dayLabel(it.triggerTime, today, yesterday) } }
            LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                groups.forEach { (label, list) ->
                    item(key = "h_$label") {
                        Text(label, color = J.TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
                    }
                    items(list, key = { it.id }) { e -> EventRow(e) { onOpen(e.id) } }
                }
            }
        } else {
            // one group per drive, newest drive first, clips in driving order
            val drives = remember(filtered) { filtered.groupBy { it.tripId }.toList().sortedByDescending { it.first } }
            LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                drives.forEach { (id, clips) ->
                    val sorted = clips.sortedBy { it.videoStartTime }
                    item(key = "d_$id") { DriveHeader(sorted, today, yesterday) }
                    items(sorted, key = { it.id }) { e -> TripRow(e) { onOpen(e.id) } }
                }
            }
        }
    }
}

@Composable
private fun DriveHeader(clips: List<EventRecord>, today: String, yesterday: String) {
    val start = clips.first().videoStartTime
    val end = clips.last().endTime
    val mins = ((end - start) / 60_000).coerceAtLeast(1)
    val size = clips.sumOf { it.sizeBytes } / 1_048_576.0
    Column(Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp)) {
        Text(dayLabel(start, today, yesterday), color = J.TextDim, fontSize = 13.sp)
        Text(
            stringResource(R.string.drive_header, formatTime(start), formatTime(end), mins.toInt(), clips.size) + " • %.0f MB".format(size),
            color = J.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TripRow(e: EventRecord, onClick: () -> Unit) {
    JCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(e, Modifier.width(96.dp).height(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(formatTime(e.videoStartTime) + " – " + formatTime(e.endTime), color = J.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(e.name ?: Notifications.formatDuration(e.durationMs), color = J.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (e.locked) Icon(Icons.Filled.Lock, null, tint = J.Mint, modifier = Modifier.size(18.dp).padding(end = 4.dp))
        }
    }
}

@Composable
private fun EventRow(e: EventRecord, onClick: () -> Unit) {
    JCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(e, Modifier.width(118.dp).height(70.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(formatTime(e.triggerTime), color = J.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    (e.name ?: stringResource(e.trigger.labelRes)) + (e.peakG?.let { " • %.1fG".format(it) } ?: ""),
                    color = J.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Notifications.formatDuration(e.durationMs), color = J.TextDim, fontSize = 13.sp)
                    if (e.locked) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Lock, null, tint = J.Mint, modifier = Modifier.size(14.dp))
                    }
                }
            }
            val (icon, tint) = triggerIcon(e.trigger)
            Icon(icon, null, tint = tint, modifier = Modifier.size(30.dp).padding(end = 4.dp))
        }
    }
}

@Composable
fun Thumbnail(e: EventRecord, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, e.id) {
        value = withContext(Dispatchers.IO) {
            EventRepository.thumbFile(e.id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Filled.VideoLibrary, null, tint = J.TextDim)
    }
}

fun triggerIcon(t: Trigger): Pair<ImageVector, Color> = when (t) {
    Trigger.SHOCK -> Icons.Filled.DirectionsCar to J.Red
    Trigger.HARSH -> Icons.Filled.Speed to J.Amber
    Trigger.MOTION -> Icons.Filled.Sensors to J.Amber
    Trigger.VOICE -> Icons.Filled.Mic to J.Mint
    Trigger.VOLUME -> Icons.Filled.VolumeUp to J.Mint
    Trigger.FLOATING -> Icons.Filled.TouchApp to J.Mint
    Trigger.NOTIFICATION -> Icons.Filled.Notifications to J.Mint
    Trigger.MANUAL -> Icons.Filled.PanTool to J.Green
}

fun formatTime(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
fun formatDate(t: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(t))
fun formatDateTime(t: Long): String = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.getDefault()).format(Date(t))

private fun dayLabel(t: Long, today: String, yesterday: String): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val now = Calendar.getInstance()
    val monthYear = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(t))
    val sameDay = { a: Calendar, b: Calendar -> a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) }
    if (sameDay(c, now)) return "$today - $monthYear"
    now.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(c, now)) return "$yesterday - $monthYear"
    return SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date(t))
}

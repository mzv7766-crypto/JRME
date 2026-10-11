package com.shomer.bodycam.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shomer.bodycam.R
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.Recording
import com.shomer.bodycam.service.Notifications
import com.shomer.bodycam.ui.components.ScreenTitle
import com.shomer.bodycam.ui.components.Tag
import com.shomer.bodycam.ui.theme.S
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Filter(val label: Int) { INCIDENTS(R.string.filter_incidents), LOCKED(R.string.filter_evidence), SHIFT(R.string.filter_shift) }

@Composable
fun RecordsScreen(onOpen: (String) -> Unit) {
    val all by RecordRepository.records.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.INCIDENTS) }
    val list = when (filter) {
        Filter.INCIDENTS -> all.filter { !it.isShift }
        Filter.LOCKED -> all.filter { it.locked }
        Filter.SHIFT -> all.filter { it.isShift }
    }
    val today = stringResource(R.string.today); val yesterday = stringResource(R.string.yesterday)

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.nav_records))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f ->
                val sel = f == filter
                Text(
                    stringResource(f.label), color = if (sel) S.OnAmber else S.Text, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) S.Amber else S.Card).clickable { filter = f }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.VideoLibrary, null, tint = S.TextDim, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.no_records), color = S.TextDim, textAlign = TextAlign.Center)
            }
            return
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var lastDay = ""
            list.forEach { r ->
                val day = dayLabel(r.triggerTime, today, yesterday)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "d_$day") { Text(day, color = S.AmberSoft, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                }
                item(key = r.id) { RecordRow(r) { onOpen(r.id) } }
            }
        }
    }
}

@Composable
private fun RecordRow(r: Recording, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(S.Card).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumbnail(r, Modifier.size(width = 96.dp, height = 72.dp).clip(RoundedCornerShape(10.dp)))
            Text(
                Notifications.formatDuration(r.durationMs), color = Color.White, fontSize = 11.sp, fontFamily = S.Mono,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(recordTitle(r), color = S.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatTime(r.triggerTime), color = S.TextDim, fontSize = 13.sp, fontFamily = S.Mono)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!r.isShift) Tag(stringResource(r.trigger.labelRes), if (r.trigger.automatic) S.Red else S.AmberSoft)
                if (r.sha256 != null) Tag("SHA-256", S.Green)
            }
        }
        if (r.locked) Icon(Icons.Filled.Lock, null, tint = S.Amber, modifier = Modifier.padding(horizontal = 6.dp).size(20.dp))
    }
}

@Composable
fun recordTitle(r: Recording): String = r.title ?: if (r.isShift) stringResource(R.string.shift_clip) + " " + formatTime(r.videoStartTime) + "–" + formatTime(r.endTime)
    else stringResource(R.string.incident) + " " + formatTime(r.triggerTime)

@Composable
fun Thumbnail(r: Recording, modifier: Modifier) {
    val bmp = remember(r.id) { RecordRepository.thumbFile(r.id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) } }
    if (bmp != null) Image(bmp.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(S.CardHi))
}

fun formatTime(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
fun formatDateTime(t: Long): String = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.getDefault()).format(Date(t))

private fun dayLabel(t: Long, today: String, yesterday: String): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val n = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(c, n)) return today
    n.add(Calendar.DAY_OF_YEAR, -1)
    if (same(c, n)) return yesterday
    return SimpleDateFormat("EEEE dd/MM/yyyy", Locale.getDefault()).format(Date(t))
}

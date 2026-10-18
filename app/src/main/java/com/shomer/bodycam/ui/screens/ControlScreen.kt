package com.shomer.bodycam.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shomer.bodycam.R
import com.shomer.bodycam.cloud.AccountStore
import com.shomer.bodycam.cloud.Billing
import com.shomer.bodycam.cloud.LiveViewer
import com.shomer.bodycam.cloud.OrgStore
import com.shomer.bodycam.cloud.Policy
import com.shomer.bodycam.cloud.Rtc
import com.shomer.bodycam.data.SettingsStore
import com.shomer.bodycam.ui.components.PrimaryButton
import com.shomer.bodycam.ui.components.SCard
import com.shomer.bodycam.ui.components.ScreenTitle
import com.shomer.bodycam.ui.components.SectionLabel
import com.shomer.bodycam.ui.components.Tag
import com.shomer.bodycam.ui.theme.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private enum class CTab(val label: Int) { BOARD(R.string.c_board), ALERTS(R.string.c_alerts), RECORDINGS(R.string.c_recordings), TEAM(R.string.c_team), PLAN(R.string.c_plan), RULES(R.string.c_rules) }

/** Manager mode inside the app: the same control room as the web dashboard, in a phone layout. */
@Composable
fun ControlScreen() {
    val org by OrgStore.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(CTab.BOARD) }
    DisposableEffect(org.org?.id) { OrgStore.openBoard(); onDispose { OrgStore.closeBoard() } }
    val events by OrgStore.events.collectAsStateWithLifecycle()
    val openAlerts = events.count { it.type in ALARM_TYPES && !it.acked }
    val o = org.org
    if (!org.isManager || o == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.c_not_manager), color = S.TextDim) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        var web by remember { mutableStateOf(false) }
        ScreenTitle(o.name, end = {
            Tag(stringResource(R.string.team_seats, o.memberCount, o.seats), S.AmberSoft, Modifier.padding(end = 4.dp))
            IconButton(onClick = { web = true }) { Icon(Icons.Filled.Computer, stringResource(R.string.c_web), tint = S.Amber) }
        })
        if (web) WebDashDialog { web = false }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CTab.entries.forEach { t ->
                val sel = t == tab
                val label = stringResource(t.label) + if (t == CTab.ALERTS && openAlerts > 0) " ($openAlerts)" else ""
                Text(
                    label, color = if (sel) S.OnAmber else if (t == CTab.ALERTS && openAlerts > 0) S.Red else S.Text, fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) S.Amber else S.Card).clickable { tab = t }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (openAlerts > 0 && tab != CTab.ALERTS) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(S.RedDeep).border(1.dp, S.Red, RoundedCornerShape(12.dp))
                    .clickable { tab = CTab.ALERTS }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = S.Red)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.c_alert_banner, openAlerts), color = S.Text, fontWeight = FontWeight.Bold)
            }
        }
        when (tab) {
            CTab.BOARD -> Board()
            CTab.ALERTS -> Alerts()
            CTab.RECORDINGS -> Recordings()
            CTab.TEAM -> Team()
            CTab.PLAN -> Plan()
            CTab.RULES -> Rules(o.policy)
        }
    }
}

val ALARM_TYPES = setOf("fall", "sos_call")

const val WEB_DASHBOARD_URL = "https://mzv7766-crypto.github.io/JRME/"

/** The web control room: open it here or send the link to a computer. */
@Composable
private fun WebDashDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = S.Surface,
        title = { Text(stringResource(R.string.c_web), color = S.Text) },
        text = {
            Column {
                Text(stringResource(R.string.c_web_desc), color = S.TextDim)
                Spacer(Modifier.height(10.dp))
                Text(WEB_DASHBOARD_URL, color = S.AmberSoft, fontFamily = S.Mono, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEB_DASHBOARD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                onDismiss()
            }) { Text(stringResource(R.string.c_web_open), color = S.Amber) }
        },
        dismissButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, WEB_DASHBOARD_URL)
                runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                onDismiss()
            }) { Text(stringResource(R.string.c_web_share), color = S.Text) }
        },
    )
}

@Composable
private fun Board() {
    val members by OrgStore.members.collectAsStateWithLifecycle()
    val devices by OrgStore.devices.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }
    var live by remember { mutableStateOf<OrgStore.Member?>(null) }
    if (members.isEmpty()) {
        Text(stringResource(R.string.c_no_members), color = S.TextDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
        return
    }
    // on shift first, then by name
    val rows = members.map { m -> m to devices.firstOrNull { it.email == m.email } }
        .sortedWith(compareByDescending<Pair<OrgStore.Member, OrgStore.Device?>> { it.second?.let { d -> d.onShift && !d.stale(now) } == true }.thenBy { it.first.name })
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.first.email }) { (m, d) ->
            val online = d != null && d.onShift && !d.stale(now)
            SCard(Modifier.fillMaxWidth(), border = if (d?.recording == true && online) S.Red else S.Stroke) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(when {
                            online && d!!.recording -> S.Red; online -> S.Green; d?.stale(now) == true -> S.Amber; else -> S.TextDim
                        }))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name.ifBlank { m.email }, color = S.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    online && d!!.recording -> stringResource(R.string.st_recording)
                                    online -> stringResource(R.string.st_on_shift)
                                    d?.stale(now) == true -> stringResource(R.string.st_no_signal, ago(now - d.updatedAt))
                                    else -> stringResource(R.string.st_off_shift)
                                } + (m.badge.takeIf { it.isNotBlank() }?.let { "  •  $it" } ?: ""),
                                color = S.TextDim, fontSize = 12.sp,
                            )
                        }
                        d?.battery?.let {
                            Icon(Icons.Filled.BatteryStd, null, tint = if (it <= 20) S.Red else S.TextDim, modifier = Modifier.size(16.dp))
                            Text("$it%", color = if (it <= 20) S.Red else S.TextDim, fontSize = 12.sp, fontFamily = S.Mono)
                        }
                    }
                    if (online) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallAction(Icons.Filled.Videocam, stringResource(R.string.c_watch), Modifier.weight(1f)) { live = m }
                            if (d!!.recording) SmallAction(Icons.Filled.Stop, stringResource(R.string.c_stop_rec), Modifier.weight(1f), S.Red) { OrgStore.sendCommand(m.email, "stop") }
                            else SmallAction(Icons.Filled.FiberManualRecord, stringResource(R.string.c_start_rec), Modifier.weight(1f), S.Red) { OrgStore.sendCommand(m.email, "start") }
                            if (d.lat != null && d.lon != null) SmallAction(Icons.Filled.Map, stringResource(R.string.map), Modifier.weight(1f)) {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${d.lat},${d.lon}?q=${d.lat},${d.lon}(${Uri.encode(m.name)})")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }
                    }
                }
            }
        }
    }
    live?.let { m -> LiveDialog(m) { live = null } }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, tint: Color = S.Amber, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(S.CardHi).clickable(onClick = onClick).padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = S.Text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun LiveDialog(m: OrgStore.Member, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val orgId = OrgStore.current.org?.id ?: return
    val renderer = remember {
        SurfaceViewRenderer(ctx).apply {
            init(Rtc.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }
    val viewer = remember { LiveViewer(ctx, orgId, m.email, renderer) }
    val status by viewer.status.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { viewer.start(); onDispose { viewer.stop(); renderer.release() } }
    AlertDialog(
        onDismissRequest = onClose, containerColor = S.Surface,
        title = { Text(m.name.ifBlank { m.email }) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                    AndroidView({ renderer }, Modifier.fillMaxSize())
                    when (status) {
                        LiveViewer.Status.CONNECTING -> CircularProgressIndicator(color = S.Amber)
                        LiveViewer.Status.FAILED -> Text(stringResource(R.string.live_failed), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                        LiveViewer.Status.ENDED -> Text(stringResource(R.string.live_ended), color = Color.White)
                        LiveViewer.Status.LIVE -> Text("● LIVE", color = S.Red, fontFamily = S.Mono, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                    }
                }
                Text(stringResource(R.string.live_note), color = S.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close), color = S.Amber) } },
    )
}

@Composable
private fun Alerts() {
    val events by OrgStore.events.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    if (events.isEmpty()) { Text(stringResource(R.string.c_no_events), color = S.TextDim, modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center); return }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(events, key = { it.id }) { e ->
            val alarm = e.type in ALARM_TYPES
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (alarm && !e.acked) S.RedDeep else S.Card).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(eventLabel(e.type) + "  •  " + e.name, color = if (alarm) S.Red else S.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(formatDateTime(e.at) + (e.detail?.let { "  •  $it" } ?: ""), color = S.TextDim, fontSize = 12.sp, fontFamily = S.Mono)
                }
                if (e.lat != null && e.lon != null) IconButton({
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${e.lat},${e.lon}?q=${e.lat},${e.lon}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Icon(Icons.Filled.Map, null, tint = S.Amber) }
                if (alarm && !e.acked) TextButton({ OrgStore.ackEvent(e.id) }) { Text(stringResource(R.string.c_ack), color = S.Amber, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun eventLabel(type: String): String = stringResource(when (type) {
    "fall" -> R.string.ev_fall
    "fall_ok" -> R.string.ev_fall_ok
    "sos_call" -> R.string.ev_sos
    "rec_start" -> R.string.ev_rec_start
    "rec_saved" -> R.string.ev_rec_saved
    "shift_start" -> R.string.ev_shift_start
    "shift_end" -> R.string.ev_shift_end
    "live_start" -> R.string.ev_live_start
    "live_end" -> R.string.ev_live_end
    else -> R.string.ev_other
})

@Composable
private fun Team() {
    val org by OrgStore.state.collectAsStateWithLifecycle()
    val members by OrgStore.members.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var add by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<OrgStore.Member?>(null) }
    val o = org.org ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(stringResource(R.string.c_team_hint), color = S.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        PrimaryButton(stringResource(R.string.c_add_guard), Icons.Filled.PersonAdd, Modifier.fillMaxWidth().height(52.dp)) {
            if (o.memberCount >= o.seats) Toast.makeText(ctx, ctx.getString(R.string.c_no_seats), Toast.LENGTH_LONG).show() else add = true
        }
        Spacer(Modifier.height(12.dp))
        members.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(S.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name.ifBlank { "—" } + (m.badge.takeIf { it.isNotBlank() }?.let { "  •  $it" } ?: ""), color = S.Text, fontWeight = FontWeight.Bold)
                    Text(m.email, color = S.TextDim, fontSize = 12.sp)
                }
                IconButton({ remove = m }) { Icon(Icons.Filled.Delete, null, tint = S.TextDim) }
            }
        }
    }
    if (add) {
        var email by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var badge by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { add = false }, containerColor = S.Surface,
            title = { Text(stringResource(R.string.c_add_guard)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(email, { email = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.c_guard_email)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.c_guard_name)) })
                    OutlinedTextField(badge, { badge = it }, singleLine = true, label = { Text(stringResource(R.string.c_guard_badge)) })
                    Text(stringResource(R.string.c_add_note), color = S.TextDim, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton({
                    if (busy) return@TextButton
                    busy = true
                    scope.launch {
                        val r = OrgStore.addMember(email, name, badge)
                        busy = false
                        val msg = when (r) {
                            OrgStore.AddResult.OK -> { add = false; null }
                            OrgStore.AddResult.NO_SEATS -> ctx.getString(R.string.c_no_seats)
                            OrgStore.AddResult.TAKEN -> ctx.getString(R.string.c_taken)
                            OrgStore.AddResult.BAD_EMAIL -> ctx.getString(R.string.bad_email)
                            OrgStore.AddResult.FAILED -> ctx.getString(R.string.action_failed)
                        }
                        msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                    }
                }) { Text(stringResource(R.string.add), color = S.Amber, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton({ add = false }) { Text(stringResource(R.string.cancel), color = S.TextDim) } },
        )
    }
    remove?.let { m ->
        AlertDialog(
            onDismissRequest = { remove = null }, containerColor = S.Surface,
            title = { Text(stringResource(R.string.c_remove_title)) },
            text = { Text(stringResource(R.string.c_remove_body, m.name.ifBlank { m.email })) },
            confirmButton = { TextButton({ remove = null; scope.launch { OrgStore.removeMember(m.email) } }) { Text(stringResource(R.string.delete), color = S.Red) } },
            dismissButton = { TextButton({ remove = null }) { Text(stringResource(R.string.cancel), color = S.TextDim) } },
        )
    }
}

@Composable
private fun Plan() {
    val acc by AccountStore.state.collectAsStateWithLifecycle()
    val org by OrgStore.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val o = org.org ?: return
    LaunchedEffect(Unit) { Billing.refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.plan_current), color = S.TextDim, fontSize = 13.sp)
                Text(
                    if (o.seats > 0) stringResource(R.string.plan_devices, o.seats) else stringResource(R.string.plan_none),
                    color = S.Text, fontWeight = FontWeight.Black, fontSize = 22.sp,
                )
                Text(stringResource(R.string.team_seats, o.memberCount, o.seats), color = S.TextDim, fontSize = 13.sp)
                if (o.planSource == "admin") Text(stringResource(R.string.plan_admin), color = S.Green, fontSize = 12.sp)
                if (o.planSource == "play" && o.planVerified) Text(stringResource(R.string.plan_verified, formatDateTime(o.planExpiry)), color = S.Green, fontSize = 12.sp)
            }
        }
        SectionLabel(stringResource(R.string.plan_choose))
        if (acc.teamOffers.isEmpty()) Text(stringResource(R.string.product_not_ready), color = S.TextDim, fontSize = 13.sp)
        acc.teamOffers.forEach { off ->
            val current = o.seats == off.seats && o.planSource == "play"
            SCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), border = if (current) S.Amber else S.Stroke, onClick = if (current) null else {
                {
                    if (off.seats < o.memberCount) Toast.makeText(ctx, ctx.getString(R.string.plan_too_small, o.memberCount), Toast.LENGTH_LONG).show()
                    else ctx.findActivity()?.let { a -> Billing.launchTeam(a, off)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }
                }
            }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.plan_devices, off.seats), color = S.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(stringResource(R.string.plan_per_month, off.price), color = S.TextDim, fontSize = 13.sp)
                    }
                    if (current) Tag(stringResource(R.string.plan_active), S.Green) else Text("›", color = S.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(stringResource(R.string.plan_note), color = S.TextDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
        if (acc.teamPurchase != null) TextButton({ runCatching { ctx.startActivity(Billing.manageIntent(Billing.TEAM)) } }) { Text(stringResource(R.string.manage_sub), color = S.Amber) }
    }
}

@Composable
private fun Rules(p: Policy) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun save(np: Policy) { scope.launch { if (!OrgStore.setPolicy(np)) Toast.makeText(ctx, ctx.getString(R.string.action_failed), Toast.LENGTH_SHORT).show() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(stringResource(R.string.rules_hint), color = S.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(S.Card)) {
            Text(stringResource(R.string.pre_record), color = S.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<Int?>(null) + SettingsStore.PRE_RECORD_OPTIONS).forEach { v ->
                    val sel = p.preRecordSeconds == v
                    Text(
                        v?.let { "${it}s" } ?: stringResource(R.string.rules_guard_choice), color = if (sel) S.OnAmber else S.Text, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) S.Amber else S.CardHi).clickable { save(p.copy(preRecordSeconds = v)) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            RuleSwitch(stringResource(R.string.record_audio), p.recordAudio == true) { save(p.copy(recordAudio = it)) }
            RuleSwitch(stringResource(R.string.fall), p.fallDetection == true) { save(p.copy(fallDetection = it)) }
            RuleSwitch(stringResource(R.string.auto_lock), p.autoLock) { save(p.copy(autoLock = it)) }
            RuleSwitch(stringResource(R.string.rules_allow_discard), p.allowDiscard) { save(p.copy(allowDiscard = it)) }
            RuleSwitch(stringResource(R.string.rules_allow_delete), p.allowDelete) { save(p.copy(allowDelete = it)) }
        }
        SectionLabel(stringResource(R.string.cloud_company))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(S.Card).padding(bottom = 6.dp)) {
            Text(stringResource(R.string.rules_cloud_sub), color = S.TextDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            Chips(listOf(Policy.CLOUD_ALL to R.string.rules_cloud_all, Policy.CLOUD_WIFI to R.string.rules_cloud_wifi, Policy.CLOUD_OFF to R.string.rules_cloud_off), p.cloudBackup) { save(p.copy(cloudBackup = it)) }
            Text(stringResource(R.string.rules_retention), color = S.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
            Chips(Policy.RETENTION_OPTIONS.map { it to 0 }, p.retentionDays, label = { stringResource(R.string.days_n, it) }) { save(p.copy(retentionDays = it)) }
        }
        Text(stringResource(R.string.rules_fixed), color = S.TextDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, Int>>, selected: T, label: (@Composable (T) -> String)? = null, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, res) ->
            val sel = v == selected
            Text(
                label?.invoke(v) ?: stringResource(res), color = if (sel) S.OnAmber else S.Text, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) S.Amber else S.CardHi).clickable { onPick(v) }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Company cloud: every guard's recordings, newest first – play, keep, delete. */
@Composable
private fun Recordings() {
    DisposableEffect(Unit) { OrgStore.openRecordings(); onDispose { OrgStore.closeRecordings() } }
    val recs by OrgStore.recordings.collectAsStateWithLifecycle()
    val org by OrgStore.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var del by remember { mutableStateOf<OrgStore.CloudRec?>(null) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val guards = recs.map { it.email to it.name }.distinctBy { it.first }
    Column(Modifier.fillMaxSize()) {
        if (guards.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf<Pair<String?, String>>(null to stringResource(R.string.all)) + guards).forEach { (e, n) ->
                val sel = filter == e
                Text(n.ifBlank { e ?: "" }, color = if (sel) S.OnAmber else S.Text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) S.Amber else S.Card).clickable { filter = e }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        val shown = recs.filter { filter == null || it.email == filter }
        if (shown.isEmpty()) {
            Text(stringResource(R.string.c_no_recordings, org.org?.policy?.retentionDays ?: 30), color = S.TextDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(S.Card).padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val trig = com.shomer.bodycam.data.Trigger.entries.firstOrNull { it.name == r.trigger }
                        Text(r.name.ifBlank { r.email } + (trig?.let { "  •  " + stringResource(it.labelRes) } ?: ""),
                            color = S.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatDateTime(r.start) + "  •  " + "%d:%02d".format(r.durationMs / 60_000, r.durationMs / 1000 % 60) + "  •  " + "%.0f MB".format(r.size / 1_048_576.0),
                            color = S.TextDim, fontSize = 12.sp, fontFamily = S.Mono)
                        if (r.keep) Text(stringResource(R.string.rec_keep_on), color = S.Green, fontSize = 11.sp)
                    }
                    // opens in the Drive app / browser (the guard's company folder is shared with the managers)
                    IconButton({
                        if (r.link.isBlank()) Toast.makeText(ctx, ctx.getString(R.string.action_failed), Toast.LENGTH_SHORT).show()
                        else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Icon(Icons.Filled.PlayCircle, null, tint = S.Amber) }
                    IconButton({ OrgStore.setKeep(r, !r.keep) }) { Icon(if (r.keep) Icons.Filled.Lock else Icons.Filled.LockOpen, stringResource(R.string.rec_keep), tint = if (r.keep) S.Green else S.TextDim) }
                    IconButton({ del = r }) { Icon(Icons.Filled.Delete, null, tint = S.TextDim) }
                }
            }
        }
    }
    del?.let { r ->
        AlertDialog(
            onDismissRequest = { del = null }, containerColor = S.Surface,
            title = { Text(stringResource(R.string.rec_delete_title)) },
            text = { Text(stringResource(R.string.rec_delete_body)) },
            confirmButton = { TextButton({ del = null; scope.launch { if (!OrgStore.removeRecording(r)) Toast.makeText(ctx, ctx.getString(R.string.action_failed), Toast.LENGTH_SHORT).show() } }) { Text(stringResource(R.string.rec_remove), color = S.Red) } },
            dismissButton = { TextButton({ del = null }) { Text(stringResource(R.string.cancel), color = S.TextDim) } },
        )
    }
}

@Composable
private fun RuleSwitch(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = S.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = S.Amber, checkedThumbColor = S.OnAmber, uncheckedTrackColor = S.CardHi))
    }
}

@Composable
fun ago(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) stringResource(R.string.ago_min, m.coerceAtLeast(1)) else stringResource(R.string.ago_hours, m / 60)
}


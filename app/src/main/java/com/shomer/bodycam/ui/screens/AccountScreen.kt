package com.shomer.bodycam.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shomer.bodycam.R
import com.shomer.bodycam.cloud.AccountStore
import com.shomer.bodycam.cloud.Billing
import com.shomer.bodycam.cloud.OrgStore
import com.shomer.bodycam.ui.components.PrimaryButton
import com.shomer.bodycam.ui.components.SCard
import com.shomer.bodycam.ui.components.SectionLabel
import com.shomer.bodycam.ui.components.Tag
import com.shomer.bodycam.ui.theme.S
import kotlinx.coroutines.launch

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Account, Shomer Pro (personal) and Shomer Team (companies). */
@Composable
fun AccountScreen(onBack: () -> Unit, onOpenControl: () -> Unit) {
    val s by AccountStore.state.collectAsStateWithLifecycle()
    val org by OrgStore.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Billing.refresh(); AccountStore.refreshFree() }
    LaunchedEffect(s.message) { s.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); AccountStore.message(null) } }
    val signIn: () -> Unit = {
        ctx.findActivity()?.let { a -> scope.launch { AccountStore.signIn(a)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } } }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = S.Text) }
            Text(stringResource(R.string.acc_title), color = S.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // ---- Google account
            SCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(S.AmberDeep), contentAlignment = Alignment.Center) {
                        Text(s.email?.first()?.uppercase() ?: "G", color = S.Amber, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (s.signedIn) (s.name ?: s.email!!) else stringResource(R.string.acc_signed_out), color = S.Text, fontWeight = FontWeight.Bold)
                        Text(s.email ?: stringResource(R.string.acc_why_sign_in), color = S.TextDim, fontSize = 12.sp)
                    }
                    when {
                        s.busy -> CircularProgressIndicator(Modifier.size(20.dp), color = S.Amber, strokeWidth = 2.dp)
                        s.signedIn -> TextButton({ scope.launch { AccountStore.signOut(ctx) } }) { Text(stringResource(R.string.acc_sign_out), color = S.TextDim) }
                        else -> TextButton(signIn) { Text(stringResource(R.string.acc_sign_in), color = S.Amber, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            if (!s.configured) Text(stringResource(R.string.acc_not_configured), color = S.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

            // ---- company (Team)
            SectionLabel(stringResource(R.string.team_title))
            TeamCard(signedIn = s.signedIn, onSignIn = signIn, onOpenControl = onOpenControl)

            // ---- personal Pro (not shown for company guards – their company already gives them Pro)
            if (!org.activeMember) {
                SectionLabel("Shomer Pro")
                ProCard(onSignIn = signIn)
            }

            if (s.isAdmin) {
                SectionLabel(stringResource(R.string.admin_orgs_title)); AdminOrgs()
                SectionLabel(stringResource(R.string.admin_free_title)); AdminFree()
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private val proFeatures = listOf(
    Triple(Icons.Filled.CloudUpload, R.string.pro_f_drive, R.string.pro_f_drive_sub),
    Triple(Icons.Filled.History, R.string.pro_f_pre, R.string.pro_f_pre_sub),
    Triple(Icons.Filled.Timelapse, R.string.pro_f_shift, R.string.pro_f_shift_sub),
    Triple(Icons.Filled.HighQuality, R.string.pro_f_4k, R.string.pro_f_4k_sub),
)

@Composable
private fun ProCard(onSignIn: () -> Unit) {
    val s by AccountStore.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    SCard(Modifier.fillMaxWidth(), border = if (s.personalPro) S.Amber else S.Stroke) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WorkspacePremium, null, tint = S.Amber, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text("Shomer Pro", color = S.Text, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
                if (s.personalPro) Tag(stringResource(R.string.pro_active), S.Green)
            }
            Spacer(Modifier.height(10.dp))
            proFeatures.forEach { (icon, t, sub) -> Feature(icon, stringResource(t), stringResource(sub)) }
            Text(stringResource(R.string.pro_free_note), color = S.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            when {
                s.proSubscribed -> TextButton({ runCatching { ctx.startActivity(Billing.manageIntent(Billing.PRO)) } }) { Text(stringResource(R.string.manage_sub), color = S.Amber) }
                s.personalPro -> Text(stringResource(if (s.isAdmin) R.string.pro_admin else R.string.pro_free_account), color = S.Green, fontSize = 13.sp)
                !s.signedIn -> PrimaryButton(stringResource(R.string.pro_sign_in_first), null, Modifier.fillMaxWidth().height(54.dp), onClick = onSignIn)
                !s.proAvailable -> Text(stringResource(R.string.product_not_ready), color = S.TextDim, fontSize = 13.sp)
                else -> {
                    PrimaryButton(
                        if (s.proTrialAvailable) stringResource(R.string.pro_start_trial, AccountStore.TRIAL_DAYS) else stringResource(R.string.pro_subscribe, s.proPrice),
                        null, Modifier.fillMaxWidth().height(56.dp),
                    ) { ctx.findActivity()?.let { a -> Billing.launchPro(a)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } } }
                    Text(
                        if (s.proTrialAvailable) stringResource(R.string.pro_trial_terms, AccountStore.TRIAL_DAYS, s.proPrice) else stringResource(R.string.pro_terms, s.proPrice),
                        color = S.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    TextButton({ Billing.refresh() }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.restore_purchase), color = S.TextDim, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun TeamCard(signedIn: Boolean, onSignIn: () -> Unit, onOpenControl: () -> Unit) {
    val org by OrgStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    SCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when {
                org.loading -> CircularProgressIndicator(Modifier.size(22.dp), color = S.Amber, strokeWidth = 2.dp)
                org.error != null && org.org == null -> {
                    Text(stringResource(R.string.org_error, org.error ?: ""), color = S.Red, fontSize = 13.sp)
                    TextButton({ OrgStore.refresh() }) { Text(stringResource(R.string.retry), color = S.Amber, fontWeight = FontWeight.Bold) }
                }
                org.isManager -> {
                    val o = org.org!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Business, null, tint = S.Amber)
                        Spacer(Modifier.width(8.dp))
                        Text(o.name, color = S.Text, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Tag(stringResource(R.string.role_manager), S.AmberSoft)
                    }
                    Text(stringResource(R.string.team_seats, o.memberCount, o.seats), color = S.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(stringResource(R.string.open_control), Icons.Filled.SettingsRemote, Modifier.fillMaxWidth().height(54.dp), onClick = onOpenControl)
                }
                org.isMember -> {
                    val o = org.org!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Business, null, tint = S.Amber)
                        Spacer(Modifier.width(8.dp))
                        Text(o.name, color = S.Text, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Tag(org.me?.badge?.ifBlank { null } ?: stringResource(R.string.role_member), S.AmberSoft)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (org.activeMember) stringResource(R.string.member_info) else stringResource(R.string.member_no_plan),
                        color = if (org.activeMember) S.TextDim else S.Red, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                }
                else -> {
                    Text(stringResource(R.string.team_pitch_title), color = S.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Feature(Icons.Filled.Map, stringResource(R.string.team_f_map), stringResource(R.string.team_f_map_sub))
                    Feature(Icons.Filled.Videocam, stringResource(R.string.team_f_live), stringResource(R.string.team_f_live_sub))
                    Feature(Icons.Filled.SettingsRemote, stringResource(R.string.team_f_remote), stringResource(R.string.team_f_remote_sub))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.team_guard_hint), color = S.TextDim, fontSize = 12.sp, lineHeight = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    if (!signedIn) PrimaryButton(stringResource(R.string.acc_sign_in), null, Modifier.fillMaxWidth().height(54.dp), onClick = onSignIn)
                    else {
                        var name by remember { mutableStateOf("") }
                        var busy by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            name, { name = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.company_name)) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = S.Amber, cursorColor = S.Amber),
                        )
                        Spacer(Modifier.height(10.dp))
                        val failed = stringResource(R.string.action_failed)
                        PrimaryButton(stringResource(R.string.create_company), Icons.Filled.Business, Modifier.fillMaxWidth().height(54.dp)) {
                            if (name.isBlank() || busy) return@PrimaryButton
                            busy = true
                            scope.launch {
                                if (!OrgStore.createOrg(name)) Toast.makeText(ctx, failed, Toast.LENGTH_LONG).show()
                                busy = false
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(S.AmberDeep), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = S.Amber, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = S.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(sub, color = S.TextDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** System admin: every company, and how many devices it gets for free (pilots, friends, testing). */
@Composable
private fun AdminOrgs() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var orgs by remember { mutableStateOf<List<OrgStore.AdminOrg>>(emptyList()) }
    var edit by remember { mutableStateOf<OrgStore.AdminOrg?>(null) }
    LaunchedEffect(Unit) { orgs = OrgStore.adminList() }
    SCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.admin_orgs_sub), color = S.TextDim, fontSize = 12.sp, lineHeight = 16.sp)
            if (orgs.isEmpty()) Text(stringResource(R.string.admin_no_orgs), color = S.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            orgs.forEach { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.name, color = S.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${o.owner} • ${o.members}/${o.seats}" + (o.source?.let { " • $it" } ?: ""), color = S.TextDim, fontSize = 12.sp)
                    }
                    TextButton({ edit = o }) { Text(stringResource(R.string.admin_set_seats), color = S.Amber, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
    edit?.let { o ->
        var n by remember { mutableStateOf(o.seats.toString()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { edit = null }, containerColor = S.Surface,
            title = { Text(o.name) },
            text = {
                Column {
                    Text(stringResource(R.string.admin_seats_q), color = S.TextDim, fontSize = 13.sp)
                    OutlinedTextField(n, { n = it.filter(Char::isDigit).take(4) }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton({
                    val seats = n.toIntOrNull() ?: return@TextButton
                    edit = null
                    scope.launch {
                        if (OrgStore.adminSetSeats(o.id, seats)) orgs = OrgStore.adminList()
                        else Toast.makeText(ctx, ctx.getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.save), color = S.Amber) }
            },
            dismissButton = { TextButton({ edit = null }) { Text(stringResource(R.string.cancel), color = S.TextDim) } },
        )
    }
}

@Composable
private fun AdminFree() {
    val s by AccountStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { AccountStore.loadFreeList() }
    SCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.admin_free_sub), color = S.TextDim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("name@gmail.com") })
                TextButton({ val e = input; scope.launch { if (AccountStore.addFree(e)) input = "" } }) { Text(stringResource(R.string.add), color = S.Amber, fontWeight = FontWeight.Bold) }
            }
            s.freeAccounts.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e, color = S.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton({ scope.launch { AccountStore.removeFree(e) } }) { Icon(Icons.Filled.Delete, null, tint = S.TextDim) }
                }
            }
        }
    }
}

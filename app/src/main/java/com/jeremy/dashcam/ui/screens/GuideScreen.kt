package com.jeremy.dashcam.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jeremy.dashcam.R
import com.jeremy.dashcam.ui.components.JCard
import com.jeremy.dashcam.ui.components.ScreenHeader
import com.jeremy.dashcam.ui.theme.J

private data class GuideItem(val icon: ImageVector, val tint: Color, val title: Int, val sub: Int, val body: Int)

private val guide = listOf(
    GuideItem(Icons.Filled.PlayArrow, J.Green, R.string.g_quick, R.string.g_quick_sub, R.string.g_quick_body),
    GuideItem(Icons.Filled.DirectionsCar, J.Mint, R.string.g_drive, R.string.g_drive_sub, R.string.g_drive_body),
    GuideItem(Icons.Filled.Warning, J.Amber, R.string.g_event, R.string.g_event_sub, R.string.g_event_body),
    GuideItem(Icons.Filled.RadioButtonChecked, J.Green, R.string.g_float, R.string.g_float_sub, R.string.g_float_body),
    GuideItem(Icons.Filled.Mic, J.Mint, R.string.g_voice, R.string.g_voice_sub, R.string.g_voice_body),
    GuideItem(Icons.Filled.Share, J.Green, R.string.g_share, R.string.g_share_sub, R.string.g_share_body),
    GuideItem(Icons.Filled.Settings, J.Mint, R.string.g_settings, R.string.g_settings_sub, R.string.g_settings_body),
)

@Composable
fun GuideScreen() {
    var open by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.guide_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            guide.forEachIndexed { i, g ->
                JCard(Modifier.fillMaxWidth().animateContentSize(), onClick = { open = if (open == i) -1 else i }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(g.tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(g.icon, null, tint = g.tint, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(g.title), color = J.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(stringResource(g.sub), color = J.TextDim, fontSize = 13.sp)
                            }
                            Icon(if (open == i) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = J.TextDim)
                        }
                        if (open == i) {
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(g.body), color = J.Text, fontSize = 15.sp, lineHeight = 22.sp)
                        }
                    }
                }
            }
            PermissionsCard()
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Shows (and lets the user fix) the two system settings that most often break background recording. */
@Composable
fun PermissionsCard() {
    val ctx = LocalContext.current
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var battery by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            overlay = Settings.canDrawOverlays(ctx); battery = isIgnoringBattery(ctx)
        }
    }
    JCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.sec_perms), color = J.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            PermRow(Icons.Filled.Layers, stringResource(R.string.perm_overlay), overlay) { openOverlaySettings(ctx) }
            PermRow(Icons.Filled.BatteryChargingFull, stringResource(R.string.perm_battery), battery) { requestIgnoreBattery(ctx) }

        }
    }
}

@Composable
private fun PermRow(icon: ImageVector, label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = !granted, onClick = onGrant).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = J.Mint)
        Spacer(Modifier.width(12.dp))
        Text(label, color = J.Text, modifier = Modifier.weight(1f))
        if (granted) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = J.Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.perm_granted), color = J.Green, fontSize = 13.sp)
        } else TextButton(onGrant) { Text(stringResource(R.string.perm_grant), color = J.Mint) }
    }
}

fun isIgnoringBattery(c: Context): Boolean =
    (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

@SuppressLint("BatteryLife")
fun requestIgnoreBattery(c: Context) {
    runCatching {
        c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

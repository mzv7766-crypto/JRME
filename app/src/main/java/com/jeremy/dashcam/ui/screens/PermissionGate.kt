package com.jeremy.dashcam.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jeremy.dashcam.ui.components.JCard
import com.jeremy.dashcam.ui.components.JeremyMark
import com.jeremy.dashcam.ui.theme.J

/**
 * Mandatory permission gate: Jeremy does not open until every permission it needs is granted.
 * The Pro-only sensitive permissions (phone call for SOS, Bluetooth for car auto-start) are NOT asked here –
 * they are requested only when the user turns those Pro features on, as Google Play policy expects.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    // re-check whenever the user comes back from a system settings screen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val reqOne = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    tick // read so the screen re-evaluates after each grant / resume

    data class Item(val icon: ImageVector, val title: String, val desc: String, val granted: Boolean, val grant: () -> Unit)

    val items = buildList {
        add(Item(Icons.Filled.Videocam, "מצלמה", "כדי לצלם – חובה", has(Manifest.permission.CAMERA)) { reqOne.launch(Manifest.permission.CAMERA) })
        add(Item(Icons.Filled.Mic, "מיקרופון", "שמע בסרטונים ופקודות קוליות", has(Manifest.permission.RECORD_AUDIO)) { reqOne.launch(Manifest.permission.RECORD_AUDIO) })
        add(Item(Icons.Filled.LocationOn, "מיקום", "מהירות ומיקום בסרטון", has(Manifest.permission.ACCESS_FINE_LOCATION)) { reqOne.launch(Manifest.permission.ACCESS_FINE_LOCATION) })
        if (Build.VERSION.SDK_INT >= 33)
            add(Item(Icons.Filled.Notifications, "התראות", "כדי להציג שהצילום פעיל", has(Manifest.permission.POST_NOTIFICATIONS)) { reqOne.launch(Manifest.permission.POST_NOTIFICATIONS) })
        add(Item(Icons.Filled.Layers, "הצגה מעל אפליקציות", "כפתור צף והפעלה ברקע", Settings.canDrawOverlays(ctx)) { openOverlaySettings(ctx) })
        add(Item(Icons.Filled.BatteryChargingFull, "ללא חיסכון בסוללה", "שהצילום לא ייעצר ברקע", isIgnoringBattery(ctx)) { requestIgnoreBattery(ctx) })
    }

    if (items.all { it.granted }) { content(); return }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            JeremyMark(72.dp, withRoad = false)
            Spacer(Modifier.height(10.dp))
            Text("כדי ש־Jeremy יעבוד", color = J.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("צריך לאשר את כל ההרשאות הבאות", color = J.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))

            items.forEach { item ->
                JCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background((if (item.granted) J.Green else J.Amber).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Icon(item.icon, null, tint = if (item.granted) J.Green else J.Amber, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = J.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(item.desc, color = J.TextDim, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (item.granted) {
                            Icon(Icons.Filled.CheckCircle, null, tint = J.Green, modifier = Modifier.size(26.dp))
                        } else {
                            Box(
                                Modifier.clip(RoundedCornerShape(20.dp)).background(J.mintButton).clickable(onClick = item.grant).padding(horizontal = 18.dp, vertical = 9.dp),
                            ) { Text("אפשר", color = Color(0xFF032012), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            val missing = items.count { !it.granted }
            Text("נותרו $missing הרשאות לאישור", color = J.Mint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "אם כפתור \"אפשר\" לא פותח חלון, ההרשאה נדחתה בעבר – פתח ידנית דרך הגדרות האפליקציה.",
                color = J.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).clickable {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("פתח הגדרות אפליקציה", color = J.Mint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

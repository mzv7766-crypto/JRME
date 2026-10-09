package com.jeremy.dashcam.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeremy.dashcam.data.ProPlan
import com.jeremy.dashcam.data.ProState
import com.jeremy.dashcam.data.ProStore
import com.jeremy.dashcam.ui.components.JCard
import com.jeremy.dashcam.ui.components.JeremyMark
import com.jeremy.dashcam.ui.theme.J
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DEMO: banner shown on every Pro screen so nobody mistakes it for a real purchase. */
@Composable
fun DemoBanner() {
    Text(
        "מצב הדגמה – אין התחברות אמיתית ואין חיוב",
        color = Color(0xFF1A1300), fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().background(Color(0xFFFFC107)).padding(vertical = 6.dp),
    )
}

private val proFeatures = listOf(
    Triple(Icons.Filled.AutoAwesome, "זיהוי חכם עם בינה מלאכותית", "מזהה רכבים, הולכי רגל וסכנות אמיתיות – בלי הפעלות שווא"),
    Triple(Icons.Filled.CloudUpload, "גיבוי אוטומטי ל־Google Drive", "האירועים נשמרים גם בחשבון ה־Google שלך"),
    Triple(Icons.Filled.SupportAgent, "תמיכה מועדפת", "מענה מהיר לשאלות ובעיות"),
)

@Composable
fun ProScreen(onBack: () -> Unit) {
    val s by ProStore.state.collectAsStateWithLifecycle()
    var signIn by remember { mutableStateOf(false) }
    var playSheet by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        DemoBanner()
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = J.Text) }
            Text("Jeremy Pro", color = J.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Hero
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                JeremyMark(84.dp, withRoad = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Jeremy ", color = J.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "PRO", color = Color(0xFF032012), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(J.mintButton).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text("כל מה שיש היום נשאר חינם. Pro מוסיף יכולות מתקדמות.", color = J.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center)
            }

            AccountCard(s, onSignIn = { signIn = true })
            StatusCard(s)

            // Features
            JCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    proFeatures.forEach { (icon, title, sub) -> FeatureRow(icon, title, sub, s.isPro) }
                }
            }

            // Action
            when {
                s.email == null -> BigButton("התחבר עם Google כדי להמשיך") { signIn = true }
                s.isPro && (s.isAdmin || s.isFreeAccount) -> Unit
                s.plan == ProPlan.TRIAL || s.plan == ProPlan.ACTIVE -> OutlineButton("ביטול מנוי") { confirmCancel = true }
                else -> {
                    BigButton(if (s.canStartTrial) "התחל ${ProStore.TRIAL_DAYS} ימי ניסיון חינם" else "הירשם ב־${ProStore.PRICE} לחודש") { playSheet = true }
                    Text(
                        if (s.canStartTrial) "אחרי תקופת הניסיון ${ProStore.PRICE} לחודש. אפשר לבטל בכל עת דרך Google Play."
                        else "חיוב חודשי דרך Google Play. אפשר לבטל בכל עת.",
                        color = J.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (s.isAdmin) AdminCard(s)

            if (s.plan == ProPlan.TRIAL) TextButton({ ProStore.endTrialNow() }, Modifier.fillMaxWidth()) {
                Text("(הדגמה) דלג לסוף תקופת הניסיון", color = J.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (signIn) GoogleAccountPicker(onPick = { ProStore.signIn(it); signIn = false }, onDismiss = { signIn = false })
    if (playSheet) PlayPurchaseSheet(
        trial = s.canStartTrial, email = s.email ?: "",
        onBuy = { if (s.canStartTrial) ProStore.startTrial() else ProStore.subscribe(); playSheet = false },
        onDismiss = { playSheet = false },
    )
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false }, containerColor = J.Surface,
        title = { Text("לבטל את המנוי?") },
        text = { Text("Pro יישאר פעיל עד סוף התקופה ששולמה, ואז האפליקציה תחזור לגרסה החינמית.") },
        confirmButton = { TextButton({ ProStore.cancel(); confirmCancel = false }) { Text("בטל מנוי", color = J.Red) } },
        dismissButton = { TextButton({ confirmCancel = false }) { Text("השאר") } },
    )
}

@Composable
private fun AccountCard(s: ProState, onSignIn: () -> Unit) {
    JCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (s.email != null) J.Green else J.Surface), contentAlignment = Alignment.Center) {
                Text(s.email?.first()?.uppercase() ?: "G", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (s.email != null) "מחובר עם Google" else "לא מחובר", color = J.Text, fontWeight = FontWeight.Bold)
                Text(s.email ?: "ההתחברות נדרשת רק בשביל Pro", color = J.TextDim, fontSize = 13.sp)
            }
            if (s.email != null) IconButton({ ProStore.signOut() }) { Icon(Icons.Filled.Logout, "התנתק", tint = J.TextDim) }
            else TextButton(onSignIn) { Text("התחבר", color = J.Mint) }
        }
    }
}

@Composable
private fun StatusCard(s: ProState) {
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val (title, sub, color) = when {
        s.email == null -> Triple("גרסה חינמית", "כל התכונות הבסיסיות זמינות בחינם", J.TextDim)
        s.isAdmin -> Triple("מנהל – Pro מלא", "חשבון המנהל מקבל Pro תמיד", J.Mint)
        s.isFreeAccount -> Triple("חשבון חינם", "הוגדר על ידי המנהל – Pro מלא בלי תשלום", J.Mint)
        s.trialActive -> Triple("תקופת ניסיון פעילה", "נותרו ${ProStore.daysLeft(s.trialEndsAt)} ימים • חיוב ראשון ב־${fmt.format(Date(s.trialEndsAt))}", J.Mint)
        s.plan == ProPlan.ACTIVE -> Triple("Pro פעיל", "${ProStore.PRICE} לחודש • מתחדש ב־${fmt.format(Date(s.renewsAt))}", J.Mint)
        s.plan == ProPlan.CANCELLED && s.subscribed -> Triple("המנוי בוטל", "Pro פעיל עד ${fmt.format(Date(s.renewsAt))}", J.Amber)
        else -> Triple("גרסה חינמית", "שדרג ל־Pro כדי לפתוח את התכונות המתקדמות", J.TextDim)
    }
    JCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (s.isPro) Icons.Filled.Star else Icons.Filled.CardGiftcard, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(sub, color = J.TextDim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, sub: String, unlocked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(J.Green.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = J.Mint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = J.Text, fontWeight = FontWeight.SemiBold)
            Text(sub, color = J.TextDim, fontSize = 12.sp)
        }
        if (unlocked) Icon(Icons.Filled.CheckCircle, null, tint = J.Green, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(J.mintButton).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color(0xFF032012), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun OutlineButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).border(2.dp, J.Stroke, RoundedCornerShape(26.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = J.TextDim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** Admin-only: emails that get Pro for free. */
@Composable
private fun AdminCard(s: ProState) {
    var input by remember { mutableStateOf("") }
    JCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AdminPanelSettings, null, tint = J.Amber)
                Spacer(Modifier.width(8.dp))
                Text("ניהול חשבונות חינם (מנהל בלבד)", color = J.Text, fontWeight = FontWeight.Bold)
            }
            Text("כל כתובת ברשימה מקבלת Pro מלא בלי תשלום ובלי כרטיס אשראי.", color = J.TextDim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    input, { input = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("name@gmail.com") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = J.Green, unfocusedBorderColor = J.Stroke),
                )
                Spacer(Modifier.width(8.dp))
                TextButton({ ProStore.addFree(input); input = "" }) { Text("הוסף", color = J.Mint, fontWeight = FontWeight.Bold) }
            }
            if (s.freeAccounts.isEmpty()) Text("אין עדיין חשבונות ברשימה", color = J.TextDim, fontSize = 13.sp)
            s.freeAccounts.sorted().forEach { e ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(J.Surface).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e, color = J.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton({ ProStore.removeFree(e) }) { Icon(Icons.Filled.Delete, "הסר", tint = J.Red) }
                }
            }
        }
    }
}

/** DEMO imitation of the Google account chooser. */
@Composable
private fun GoogleAccountPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var other by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp)) {
            Text("G", color = Color(0xFF4285F4), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("בחר חשבון", color = Color(0xFF202124), fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("כדי להמשיך ל־Jeremy Pro", color = Color(0xFF5F6368), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(14.dp))
            listOf(ProStore.ADMIN_EMAIL, "tester@gmail.com").forEach { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(e) }.padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF34A853)), contentAlignment = Alignment.Center) {
                        Text(e.first().uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(e, color = Color(0xFF202124), fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(other, { other = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("חשבון אחר (הדגמה)") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss) { Text("ביטול", color = Color(0xFF1A73E8)) }
                TextButton({ if (other.contains("@")) onPick(other) }) { Text("המשך", color = Color(0xFF1A73E8)) }
            }
        }
    }
}

/** DEMO imitation of the Google Play subscription sheet. */
@Composable
private fun PlayPurchaseSheet(trial: Boolean, email: String, onBuy: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Google Play", color = Color(0xFF5F6368), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(J.Bg), contentAlignment = Alignment.Center) { JeremyMark(34.dp, withRoad = false) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Jeremy Pro (Jeremy)", color = Color(0xFF202124), fontWeight = FontWeight.Bold)
                    Text("מנוי חודשי", color = Color(0xFF5F6368), fontSize = 13.sp)
                }
            }
            if (trial) {
                Text("${ProStore.TRIAL_DAYS} ימי ניסיון חינם", color = Color(0xFF188038), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("לאחר מכן ${ProStore.PRICE} לחודש. לא תחויב היום.", color = Color(0xFF202124), fontSize = 14.sp)
            } else Text("${ProStore.PRICE} לחודש", color = Color(0xFF202124), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("חשבון: $email\nאמצעי תשלום: כרטיס הדגמה (ללא חיוב)", color = Color(0xFF5F6368), fontSize = 12.sp)
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF01875F)).clickable(onClick = onBuy),
                contentAlignment = Alignment.Center,
            ) { Text(if (trial) "התחל ניסיון חינם" else "הירשם", color = Color.White, fontWeight = FontWeight.Bold) }
            Text("אפשר לבטל בכל עת ב־Google Play ← מנויים", color = Color(0xFF5F6368), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Sos
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.CircularProgressIndicator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import com.jeremy.dashcam.core.pro.Account
import com.jeremy.dashcam.core.pro.Billing
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeremy.dashcam.data.ProState
import com.jeremy.dashcam.data.ProStore
import com.jeremy.dashcam.ui.components.JCard
import com.jeremy.dashcam.ui.components.JeremyMark
import com.jeremy.dashcam.ui.theme.J

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private val proFeatures = listOf(
    Triple(Icons.Filled.AutoAwesome, "זיהוי חכם עם בינה מלאכותית", "מזהה רכבים והולכי רגל שמתקרבים מהר – ישירות בטלפון"),
    Triple(Icons.Filled.CloudUpload, "גיבוי ל־Google Drive", "האירועים (ואם תרצה גם הנסיעות) נשמרים בדרייב שלך"),
    Triple(Icons.Filled.Speed, "מהירות ומיקום בסרטון", "נצרבים על הסרטון ונשמרים עם האירוע"),
    Triple(Icons.Filled.Videocam, "מצלמה כפולה", "הכביש והנהג בסרטון אחד (במכשירים תומכים)"),
    Triple(Icons.Filled.Bluetooth, "הפעלה אוטומטית ברכב", "מתחיל לצלם כשהטלפון מתחבר לרכב"),
    Triple(Icons.Filled.Pin, "קריאת לוחיות רישוי", "מספרי הרכבים מהאירוע, בלחיצה"),
    Triple(Icons.Filled.Sos, "התראת חירום", "אחרי מכה חזקה – חיוג למספר שבחרת"),
)

@Composable
fun ProScreen(onBack: () -> Unit) {
    val s by ProStore.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { Billing.refresh(); Account.refreshFree() }
    LaunchedEffect(s.message) {
        s.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); ProStore.message(null) }
    }
    val signIn: () -> Unit = {
        val act = ctx.findActivity()
        if (act != null) scope.launch { Account.signIn(act)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = J.Text) }
            Text("Jeremy Pro", color = J.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (s.busy) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), color = J.Mint, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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

            AccountCard(s, onSignIn = signIn, onSignOut = { scope.launch { Account.signOut(ctx) } })
            StatusCard(s)

            JCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    proFeatures.forEach { (icon, title, sub) -> FeatureRow(icon, title, sub, s.isPro) }
                }
            }

            when {
                !s.configured && !s.subscribed -> Text(
                    "ההרשמה ל־Pro תיפתח בקרוב.", color = J.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                s.email == null && !s.subscribed -> BigButton("התחבר עם Google כדי להמשיך", onClick = signIn)
                s.subscribed -> OutlineButton("ניהול המנוי ב־Google Play") { runCatching { ctx.startActivity(Billing.manageIntent()) } }
                s.isAdmin || s.isFreeAccount -> Unit
                !s.productAvailable -> Text(
                    "המנוי עדיין לא זמין בחנות. נסה שוב בעוד כמה דקות.", color = J.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                else -> {
                    BigButton(if (s.trialAvailable) "התחל ${ProStore.TRIAL_DAYS} ימי ניסיון חינם" else "הירשם ב־${s.price} לחודש") {
                        val act = ctx.findActivity()
                        if (act != null) Billing.launch(act)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                    }
                    Text(
                        if (s.trialAvailable) "לא תחויב היום. אחרי ${ProStore.TRIAL_DAYS} ימים ${s.price} לחודש, דרך Google Play. אפשר לבטל בכל עת לפני סוף הניסיון."
                        else "${s.price} לחודש דרך Google Play. אפשר לבטל בכל עת.",
                        color = J.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (s.configured && !s.subscribed && s.email != null && !s.isAdmin && !s.isFreeAccount) TextButton({ Billing.refresh() }, Modifier.fillMaxWidth()) {
                Text("כבר נרשמת? שחזר רכישה", color = J.TextDim, fontSize = 13.sp)
            }

            if (s.isAdmin) AdminCard(s)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AccountCard(s: ProState, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    JCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (s.email != null) J.Green else J.Surface), contentAlignment = Alignment.Center) {
                Text(s.email?.first()?.uppercase() ?: "G", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (s.email != null) (s.name ?: "מחובר עם Google") else "לא מחובר", color = J.Text, fontWeight = FontWeight.Bold)
                Text(s.email ?: "ההתחברות נדרשת רק בשביל Pro", color = J.TextDim, fontSize = 13.sp)
            }
            if (s.email != null) IconButton(onSignOut) { Icon(Icons.Filled.Logout, "התנתק", tint = J.TextDim) }
            else if (s.configured) TextButton(onSignIn) { Text("התחבר", color = J.Mint) }
        }
    }
}

@Composable
private fun StatusCard(s: ProState) {
    val (title, sub, color) = when {
        s.isAdmin -> Triple("מנהל – Pro מלא", "חשבון המנהל מקבל Pro תמיד", J.Mint)
        s.isFreeAccount && s.email != null -> Triple("חשבון חינם", "הוגדר על ידי המנהל – Pro מלא בלי תשלום", J.Mint)
        s.subscribed && s.autoRenewing -> Triple("Pro פעיל", "מנוי חודשי דרך Google Play (כולל תקופת ניסיון)", J.Mint)
        s.subscribed -> Triple("המנוי בוטל", "Pro פעיל עד סוף התקופה ששולמה", J.Amber)
        else -> Triple("גרסה חינמית", "כל התכונות הבסיסיות זמינות בחינם", J.TextDim)
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
    ) { Text(text, color = Color(0xFF032012), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

@Composable
private fun OutlineButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).border(2.dp, J.Stroke, RoundedCornerShape(26.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = J.TextDim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** Admin-only: e-mails that get Pro for free (stored in Firestore, applies to everyone immediately). */
@Composable
private fun AdminCard(s: ProState) {
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Account.loadFreeList() }
    JCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AdminPanelSettings, null, tint = J.Amber)
                Spacer(Modifier.width(8.dp))
                Text("ניהול חשבונות חינם (מנהל בלבד)", color = J.Text, fontWeight = FontWeight.Bold)
            }
            Text("כל כתובת Gmail ברשימה מקבלת Pro מלא בלי תשלום. השינוי חל מיד – המשתמש רק צריך להתחבר עם הכתובת הזו.", color = J.TextDim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    input, { input = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("name@gmail.com") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = J.Green, unfocusedBorderColor = J.Stroke),
                )
                Spacer(Modifier.width(8.dp))
                TextButton({ val e = input; scope.launch { if (Account.addFree(e)) input = "" } }) { Text("הוסף", color = J.Mint, fontWeight = FontWeight.Bold) }
            }
            if (s.freeAccounts.isEmpty()) Text("אין עדיין חשבונות ברשימה", color = J.TextDim, fontSize = 13.sp)
            s.freeAccounts.forEach { e ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(J.Surface).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e, color = J.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton({ scope.launch { Account.removeFree(e) } }) { Icon(Icons.Filled.Delete, "הסר", tint = J.Red) }
                }
            }
        }
    }
}

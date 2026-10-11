package com.shomer.bodycam.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shomer.bodycam.R
import com.shomer.bodycam.ui.components.ShomerMark
import com.shomer.bodycam.ui.theme.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Routes {
    const val SHIFT = "shift"
    const val CAMERA = "camera"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val RECORD = "record/{id}"
    fun record(id: String) = "record/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.SHIFT, R.string.nav_shift, Icons.Filled.Shield),
    Tab(Routes.CAMERA, R.string.nav_camera, Icons.Filled.Videocam),
    Tab(Routes.RECORDS, R.string.nav_records, Icons.Filled.VideoLibrary),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/** Bottom navigation with an amber top bar on the selected tab (like a tab on a vest). */
@Composable
fun BottomBar(current: String?, recording: Boolean, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(S.BgDeep).windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (recording) S.Red else S.Stroke))
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            tabs.forEach { t -> TabItem(t, current == t.route, Modifier.weight(1f).fillMaxHeight(), recording && t.route == Routes.CAMERA) { onSelect(t.route) } }
        }
    }
}

@Composable
fun SideRail(current: String?, recording: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(84.dp).fillMaxHeight().background(S.BgDeep), verticalArrangement = Arrangement.SpaceEvenly) {
            tabs.forEach { t -> TabItem(t, current == t.route, Modifier.fillMaxWidth().height(72.dp), recording && t.route == Routes.CAMERA) { onSelect(t.route) } }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(if (recording) S.Red else S.Stroke))
    }
}

@Composable
private fun TabItem(t: Tab, sel: Boolean, modifier: Modifier, recDot: Boolean, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)).background(if (sel) S.Amber else Color.Transparent))
        Spacer(Modifier.weight(1f))
        Box {
            Icon(t.icon, null, tint = if (sel) S.Amber else S.TextDim, modifier = Modifier.size(24.dp))
            if (recDot) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(S.Red))
        }
        Spacer(Modifier.height(3.dp))
        Text(stringResource(t.label), fontSize = 11.sp, color = if (sel) S.Text else S.TextDim, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        Spacer(Modifier.weight(1f))
    }
}

/**
 * Opening: the camera badge snaps in, its REC light blinks twice while a scan line sweeps the picture
 * like a viewfinder, then SHOMER types in under it.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit = {}) {
    val appear = remember { Animatable(0f) }
    val scan = remember { Animatable(0f) }
    val rec = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { appear.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        launch { scan.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }
        delay(350)
        repeat(2) { rec.animateTo(1f, tween(130)); delay(120); rec.animateTo(0f, tween(130)); delay(90) }
        rec.animateTo(1f, tween(120))
        title.animateTo(1f, tween(500))
        delay(450)
        onFinished()
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(S.Bg)) {
        val h = maxHeight
        // viewfinder corners
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }) {
            val m = 28.dp.toPx(); val l = 34.dp.toPx(); val w = 3.dp.toPx(); val c = S.Amber.copy(alpha = 0.55f)
            val top = size.height * 0.16f; val bottom = size.height * 0.84f
            listOf(Offset(m, top) to Offset(1f, 1f), Offset(size.width - m, top) to Offset(-1f, 1f),
                Offset(m, bottom) to Offset(1f, -1f), Offset(size.width - m, bottom) to Offset(-1f, -1f)).forEach { (p, d) ->
                drawLine(c, p, Offset(p.x + l * d.x, p.y), w); drawLine(c, p, Offset(p.x, p.y + l * d.y), w)
            }
            // scan line
            val y = top + (bottom - top) * scan.value
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, S.Amber.copy(alpha = 0.22f), Color.Transparent), startY = y - 40.dp.toPx(), endY = y + 4.dp.toPx()),
                Offset(m, y - 40.dp.toPx()), androidx.compose.ui.geometry.Size(size.width - 2 * m, 44.dp.toPx()))
            drawLine(S.Amber.copy(alpha = 0.7f * (1f - scan.value * 0.6f)), Offset(m, y), Offset(size.width - m, y), 1.5.dp.toPx())
        }
        ShomerMark(
            150.dp,
            Modifier.align(Alignment.TopCenter).offset(y = h * 0.28f).graphicsLayer {
                val k = 0.7f + 0.3f * appear.value; scaleX = k; scaleY = k; alpha = appear.value
            },
            recLight = rec.value,
        )
        Column(
            Modifier.align(Alignment.TopCenter).offset(y = h * 0.28f + 170.dp + 16.dp * (1f - title.value)).graphicsLayer { alpha = title.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val letters = "SHOMER"
            Text(letters.take((letters.length * title.value + 0.5f).toInt().coerceIn(0, letters.length)), color = S.Text,
                fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp, fontFamily = S.Mono)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tagline), color = S.TextDim, fontSize = 17.sp, textAlign = TextAlign.Center)
        }
        Text("● REC", color = S.Red.copy(alpha = rec.value), fontFamily = S.Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = h * 0.16f + 12.dp, end = 40.dp))
    }
}

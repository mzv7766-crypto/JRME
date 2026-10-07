package com.jeremy.dashcam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeremy.dashcam.R
import com.jeremy.dashcam.ui.components.JeremyMark
import com.jeremy.dashcam.ui.components.RoadBackdrop
import com.jeremy.dashcam.ui.theme.J

object Routes {
    const val HOME = "home"
    const val EVENTS = "events"
    const val CAMERA = "camera"
    const val GUIDE = "guide"
    const val SETTINGS = "settings"
    const val EVENT = "event/{id}"
    fun event(id: String) = "event/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    Tab(Routes.EVENTS, R.string.nav_events, Icons.Filled.VideoLibrary),
    Tab(Routes.CAMERA, R.string.nav_camera, Icons.Filled.PhotoCamera),
    Tab(Routes.GUIDE, R.string.nav_guide, Icons.AutoMirrored.Filled.MenuBook),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/** Fixed bottom navigation: Home | Events | Camera | Guide | Settings. */
@Composable
fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(J.BgDeep.copy(alpha = 0.96f)).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(color = J.Stroke, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            tabs.forEach { t ->
                val sel = current == t.route
                Column(
                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(14.dp)).clickable { onSelect(t.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) J.Green.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 3.dp),
                    ) { Icon(t.icon, null, tint = if (sel) J.Mint else J.TextDim, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(t.label), fontSize = 11.sp, color = if (sel) J.Mint else J.TextDim,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(J.Bg)) {
        RoadBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            JeremyMark(130.dp)
            Spacer(Modifier.height(18.dp))
            Text("Jeremy", color = J.Text, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.tagline), color = J.TextDim, fontSize = 18.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
        }
    }
}

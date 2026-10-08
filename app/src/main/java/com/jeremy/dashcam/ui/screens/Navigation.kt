package com.jeremy.dashcam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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

/**
 * Opening animation: the Jeremy camera drives up the road toward the viewer (road dashes and
 * reflectors stream past), then lifts to the centre while the "Jeremy" name fades in.
 */
/** Landscape navigation: vertical rail on the side of the screen. */
@Composable
fun SideRail(current: String?, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(84.dp).fillMaxHeight().background(J.BgDeep.copy(alpha = 0.96f)).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tabs.forEach { t ->
                val sel = current == t.route
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).clickable { onSelect(t.route) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) J.Green.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 3.dp),
                    ) { Icon(t.icon, null, tint = if (sel) J.Mint else J.TextDim, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(t.label), fontSize = 11.sp, color = if (sel) J.Mint else J.TextDim, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(J.Stroke))
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit = {}) {
    val approach = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        approach.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
        launch { lift.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        delay(250)
        title.animateTo(1f, tween(550))
        delay(450)
        onFinished() // end exactly when the animation is done (also on slow phones)
    }
    val inf = rememberInfiniteTransition(label = "drive")
    val road by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing)), label = "road")
    val bob by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(180), RepeatMode.Reverse), label = "bob")

    BoxWithConstraints(Modifier.fillMaxSize().background(J.Bg)) {
        val h = maxHeight
        val horizon = h * 0.42f
        val p = approach.value
        val l = lift.value
        RoadBackdrop(Modifier.fillMaxSize(), phase = road)

        val camSize = 150.dp * (0.18f + 0.82f * p)
        val onRoadY = lerp(horizon, h * 0.70f, p)          // centre of camera while driving
        val centreY = lerp(onRoadY, h * 0.34f, l)            // then lifts to the middle
        val shake = 2.5.dp * bob * (1f - l) * p

        // shadow on the asphalt
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset(y = onRoadY + camSize * 0.32f)
                .size(camSize * 0.9f, camSize * 0.16f)
                .graphicsLayer { alpha = 0.55f * (1f - l) }
                .background(Brush.radialGradient(listOf(Color.Black, Color.Transparent)), CircleShape),
        )
        // headlight glow on the road ahead of the camera
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset(y = onRoadY)
                .size(camSize * 1.6f, camSize * 1.1f)
                .graphicsLayer { alpha = 0.6f * p * (1f - l) }
                .background(Brush.radialGradient(listOf(J.Mint.copy(alpha = 0.35f), Color.Transparent)), CircleShape),
        )
        JeremyMark(
            camSize,
            Modifier.align(Alignment.TopCenter).offset(y = centreY - camSize / 2 + shake),
            withRoad = false,
        )

        Column(
            Modifier.align(Alignment.TopCenter).offset(y = h * 0.34f + 95.dp + 20.dp * (1f - title.value))
                .graphicsLayer { alpha = title.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Jeremy", color = J.Text, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.tagline), color = J.TextDim, fontSize = 18.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
        }
    }
}

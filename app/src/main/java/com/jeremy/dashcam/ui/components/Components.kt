package com.jeremy.dashcam.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeremy.dashcam.ui.theme.J

/** Night road with green hills — the hero background used on home & splash (pure vector, no assets). */
@Composable
fun RoadBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0E3A25), Color(0xFF082016), Color(0xFF041009)), endY = h))
        val horizon = h * 0.42f
        // hills
        val hill1 = Path().apply {
            moveTo(0f, horizon); cubicTo(w * 0.2f, horizon - h * 0.10f, w * 0.35f, horizon - h * 0.02f, w * 0.5f, horizon)
            cubicTo(w * 0.65f, horizon - h * 0.12f, w * 0.85f, horizon - h * 0.04f, w, horizon - h * 0.02f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(hill1, Color(0xFF0B2C1D))
        val hill2 = Path().apply {
            moveTo(0f, horizon + h * 0.03f); cubicTo(w * 0.3f, horizon - h * 0.03f, w * 0.6f, horizon + h * 0.05f, w, horizon)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(hill2, Color(0xFF07170F))
        // road
        val road = Path().apply {
            moveTo(w * 0.47f, horizon + h * 0.01f); lineTo(w * 0.53f, horizon + h * 0.01f)
            lineTo(w * 0.95f, h); lineTo(w * 0.05f, h); close()
        }
        drawPath(road, Brush.verticalGradient(listOf(Color(0xFF16261E), Color(0xFF0E1A14)), startY = horizon, endY = h))
        // edge lines
        drawLine(J.Mint.copy(alpha = 0.35f), Offset(w * 0.47f, horizon + h * 0.01f), Offset(w * 0.09f, h), 2.dp.toPx())
        drawLine(J.Mint.copy(alpha = 0.35f), Offset(w * 0.53f, horizon + h * 0.01f), Offset(w * 0.91f, h), 2.dp.toPx())
        // dashed centre line with perspective
        var t = 0.04f
        while (t < 1f) {
            val y0 = horizon + (h - horizon) * t * t
            val y1 = horizon + (h - horizon) * (t + 0.05f) * (t + 0.05f)
            drawLine(Color.White.copy(alpha = 0.55f), Offset(w / 2, y0), Offset(w / 2, y1.coerceAtMost(h)), (1f + 5f * t).dp.toPx(), StrokeCap.Round)
            t += 0.11f
        }
        // vignette for legibility
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, J.Bg.copy(alpha = 0.85f)), startY = h * 0.55f, endY = h))
    }
}

/** Jeremy mark: camera over a road — matches the launcher icon. */
@Composable
fun JeremyMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.075f
        val bodyTop = s * 0.18f; val bodyH = s * 0.44f
        val bodyLeft = s * 0.12f; val bodyW = s * 0.76f
        // glow
        drawCircle(Brush.radialGradient(listOf(J.Green.copy(alpha = 0.35f), Color.Transparent), radius = s * 0.6f), s * 0.6f, Offset(s / 2, s * 0.42f))
        // top bump
        drawRoundRect(J.Mint, Offset(s * 0.34f, bodyTop - s * 0.08f), Size(s * 0.32f, s * 0.12f), CornerRadius(s * 0.05f))
        // body
        drawRoundRect(J.Mint, Offset(bodyLeft, bodyTop), Size(bodyW, bodyH), CornerRadius(s * 0.12f), style = Stroke(stroke))
        drawRoundRect(J.GreenDark, Offset(bodyLeft + stroke / 2, bodyTop + stroke / 2), Size(bodyW - stroke, bodyH - stroke), CornerRadius(s * 0.1f))
        // lens
        val c = Offset(s / 2, bodyTop + bodyH / 2)
        drawCircle(J.Mint, s * 0.15f, c, style = Stroke(stroke))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF5FE3FF), Color(0xFF0A4A5C)), center = c, radius = s * 0.11f), s * 0.11f, c)
        drawCircle(Color.White.copy(alpha = 0.8f), s * 0.03f, Offset(c.x - s * 0.04f, c.y - s * 0.04f))
        // road
        val roadTop = bodyTop + bodyH + s * 0.06f
        val road = Path().apply {
            moveTo(s * 0.42f, roadTop); lineTo(s * 0.58f, roadTop); lineTo(s * 0.82f, s * 0.98f); lineTo(s * 0.18f, s * 0.98f); close()
        }
        drawPath(road, J.Mint)
        drawLine(J.GreenDark, Offset(s / 2, roadTop + s * 0.04f), Offset(s / 2, roadTop + s * 0.12f), s * 0.035f)
        drawLine(J.GreenDark, Offset(s / 2, roadTop + s * 0.19f), Offset(s / 2, roadTop + s * 0.30f), s * 0.045f)
    }
}

@Composable
fun JCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(J.Card)
            .border(1.dp, J.Stroke, RoundedCornerShape(18.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    ) { content() }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    JCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = J.TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = J.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, start: (@Composable () -> Unit)? = null, end: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { start?.invoke() }
        Text(title, Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleLarge, color = J.Text)
        Box(Modifier.align(Alignment.CenterEnd)) { end?.invoke() }
    }
}

/** Round icon + caption action used on the camera screen. */
@Composable
fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    Column(modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.48f)) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FullScreenBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(J.screenBg)) { content() }
}

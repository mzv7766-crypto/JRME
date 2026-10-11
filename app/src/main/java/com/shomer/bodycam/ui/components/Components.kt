package com.shomer.bodycam.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shomer.bodycam.ui.theme.S
import kotlinx.coroutines.launch

/** The Shomer mark: a chest-worn camera with its clip, big lens and REC light — matches the launcher icon. */
@Composable
fun ShomerMark(size: Dp, modifier: Modifier = Modifier, recLight: Float = 1f, glow: Boolean = true) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        if (glow) drawCircle(Brush.radialGradient(listOf(S.Amber.copy(alpha = 0.28f), Color.Transparent), radius = s * 0.62f), s * 0.62f, Offset(s / 2, s * 0.52f))
        // clip
        drawRoundRect(Color(0xFF3A3F4A), Offset(s * 0.38f, s * 0.08f), Size(s * 0.24f, s * 0.12f), CornerRadius(s * 0.03f))
        // body
        val left = s * 0.24f; val top = s * 0.16f; val w = s * 0.52f; val h = s * 0.74f
        drawRoundRect(S.Amber, Offset(left, top), Size(w, h), CornerRadius(s * 0.11f))
        drawRoundRect(Color(0xFFE58A00), Offset(left, top + h * 0.64f), Size(w, h * 0.36f), CornerRadius(s * 0.11f))
        drawRect(Color(0xFFE58A00), Offset(left, top + h * 0.64f), Size(w, h * 0.12f))
        // lens
        val c = Offset(s / 2, top + h * 0.36f)
        drawCircle(Color(0xFF15171C), s * 0.17f, c)
        drawCircle(Color(0xFF2C3A4E), s * 0.105f, c)
        drawCircle(Color(0xFF4F6D92), s * 0.065f, c)
        drawCircle(Color.White.copy(alpha = 0.8f), s * 0.024f, Offset(c.x - s * 0.04f, c.y - s * 0.035f))
        // REC light
        drawCircle(Color(0xFFFF3B30).copy(alpha = 0.25f + 0.75f * recLight), s * 0.034f, Offset(left + w * 0.82f, top + h * 0.09f))
        // mic slot
        drawRoundRect(Color(0xFF15171C), Offset(s * 0.4f, top + h * 0.8f), Size(s * 0.2f, s * 0.03f), CornerRadius(s * 0.015f))
    }
}

@Composable
fun SCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, border: Color = S.Stroke, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(S.Card)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    ) { content() }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = S.Text) {
    SCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = S.Mono, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(label, color = S.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, end: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = S.Text, modifier = Modifier.weight(1f))
        end?.invoke()
    }
}

/** Small uppercase-style label above a group, with an amber tick (a "tab" on a uniform). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(S.Amber))
        Spacer(Modifier.width(8.dp))
        Text(text, color = S.AmberSoft, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Text(
        text, color = if (filled) S.OnAmber else color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Dark translucent chip used over the camera picture. */
@Composable
fun HudChip(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = S.Mono)
    }
}

/** Round icon action with a caption (camera screen). */
@Composable
fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp, tint: Color = Color.White) {
    Column(modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(Color.Black.copy(alpha = 0.5f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(size * 0.32f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.46f)) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp, maxLines = 2)
    }
}

/**
 * The record control. Idle: ONE TAP starts recording (speed matters in an incident).
 * Recording: you must HOLD it for [holdMs] to stop — a ring fills while holding — so a bump can't end
 * the evidence by accident.
 */
@Composable
fun RecordButton(recording: Boolean, label: String, size: Dp, onStart: () -> Unit, onStop: () -> Unit, holdMs: Int = 1200, enabled: Boolean = true) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Box(
        Modifier.size(size).pointerInput(recording, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown()
                if (!recording) {
                    val up = waitForUpOrCancellation()
                    if (up != null) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onStart() }
                } else {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStop()
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) job.cancel()
                    scope.launch { progress.animateTo(0f, tween(200)) }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            val ring = 6.dp.toPx()
            drawCircle(if (recording) S.Red.copy(alpha = 0.18f + 0.18f * pulse) else S.Amber.copy(alpha = 0.16f), r)
            drawCircle(Color.White.copy(alpha = 0.9f), r - ring, style = Stroke(3.dp.toPx()))
            if (progress.value > 0f) {
                drawArc(S.AmberSoft, -90f, 360f * progress.value, false, Offset(ring / 2, ring / 2),
                    Size(this.size.width - ring, this.size.height - ring), style = Stroke(ring, cap = StrokeCap.Round))
            }
            if (recording) {
                val sq = r * 0.62f
                drawRoundRect(S.Red, Offset(center.x - sq / 2, center.y - sq / 2), Size(sq, sq), CornerRadius(sq * 0.18f))
            } else drawCircle(S.Red, r * 0.68f)
        }
        Text(label, color = Color.White, fontSize = if (size > 100.dp) 15.sp else 12.sp, fontWeight = FontWeight.Black,
            fontFamily = if (recording) S.Mono else null, textAlign = TextAlign.Center)
    }
}

/** Wide button that only fires after being held (ending a shift etc.). */
@Composable
fun HoldButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, color: Color = S.Text, holdMs: Int = 900, onConfirmed: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).border(1.5.dp, color.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfirmed()
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) job.cancel()
                    scope.launch { progress.animateTo(0f, tween(200)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().padding(end = 0.dp)) {
            Canvas(Modifier.fillMaxSize()) { drawRect(color.copy(alpha = 0.16f), size = Size(size.width * progress.value, size.height)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(S.amberButton).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = S.OnAmber, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)) }
            Text(text, color = S.OnAmber, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun FullScreenBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(S.screenBg)) { content() }
}

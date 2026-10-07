package com.jeremy.dashcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object J {
    val Bg = Color(0xFF06130D)
    val BgDeep = Color(0xFF030B07)
    val Surface = Color(0xFF0C1F16)
    val Card = Color(0xFF10291D)
    val CardHi = Color(0xFF153525)
    val Stroke = Color(0xFF1F4A33)
    val Green = Color(0xFF22C55E)
    val GreenDark = Color(0xFF0B3D27)
    val Mint = Color(0xFF6EF0A8)
    val MintSoft = Color(0xFFB9F7D3)
    val Text = Color(0xFFEAFBF1)
    val TextDim = Color(0xFF9DBFAE)
    val Red = Color(0xFFE53935)
    val RedDark = Color(0xFFB71C1C)
    val Amber = Color(0xFFFFC107)

    val mintButton = Brush.verticalGradient(listOf(Color(0xFF7CF5B0), Color(0xFF2FD36F)))
    val screenBg = Brush.verticalGradient(listOf(Color(0xFF0A2318), Bg, BgDeep))
}

private val scheme = darkColorScheme(
    primary = J.Green, onPrimary = Color(0xFF02150B),
    secondary = J.Mint, onSecondary = Color(0xFF02150B),
    background = J.Bg, onBackground = J.Text,
    surface = J.Surface, onSurface = J.Text,
    surfaceVariant = J.Card, onSurfaceVariant = J.TextDim,
    surfaceContainer = J.Surface, surfaceContainerHigh = J.Card, surfaceContainerHighest = J.CardHi,
    outline = J.Stroke, error = J.Red,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun JeremyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

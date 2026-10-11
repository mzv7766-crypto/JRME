package com.shomer.bodycam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Shomer palette: graphite + safety amber (high-visibility gear), red only for "recording". */
object S {
    val Bg = Color(0xFF101216)
    val BgDeep = Color(0xFF0A0B0E)
    val Surface = Color(0xFF171A20)
    val Card = Color(0xFF1C2028)
    val CardHi = Color(0xFF252A34)
    val Stroke = Color(0xFF2F3541)
    val Amber = Color(0xFFFF9F0A)
    val AmberSoft = Color(0xFFFFC56B)
    val AmberDeep = Color(0xFF3B2A0C)
    val OnAmber = Color(0xFF1E1300)
    val Text = Color(0xFFF3F1EC)
    val TextDim = Color(0xFF9BA1AC)
    val Red = Color(0xFFE62E2E)
    val RedDeep = Color(0xFF4A1212)
    val Green = Color(0xFF3DD68C)

    val amberButton = Brush.verticalGradient(listOf(Color(0xFFFFB22E), Color(0xFFF08C00)))
    val screenBg = Brush.verticalGradient(listOf(Color(0xFF181B21), Bg, BgDeep))
    val Mono = FontFamily.Monospace
}

private val scheme = darkColorScheme(
    primary = S.Amber, onPrimary = S.OnAmber,
    secondary = S.AmberSoft, onSecondary = S.OnAmber,
    background = S.Bg, onBackground = S.Text,
    surface = S.Surface, onSurface = S.Text,
    surfaceVariant = S.Card, onSurfaceVariant = S.TextDim,
    surfaceContainer = S.Surface, surfaceContainerHigh = S.Card, surfaceContainerHighest = S.CardHi,
    outline = S.Stroke, error = S.Red,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Black, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ShomerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

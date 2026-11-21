package com.omni.llm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A deliberately dark-first palette: this app is used in bed, on a phone, while a 2 GB model
// pins the CPU. Bright UI does not make it faster; it just drains the battery.
private val Green = Color(0xFF4ADE80)
private val Cyan = Color(0xFF22D3EE)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)
private val Ink = Color(0xFF0B0F14)
private val Surface = Color(0xFF131A22)
private val SurfaceHigh = Color(0xFF1C2530)

/**
 * Named palette for the accent colours Material3 does not expose directly.
 *
 * Declared as `object` rather than `val x = object { ... }`: members of an anonymous object have
 * no accessible type, so `OmniPalette.ok` fails to resolve from other files ("Unresolved reference").
 */
object OmniPalette {
    val ok: Color = Green
    val info: Color = Cyan
    val warn: Color = Amber
    val danger: Color = Red
    val surface: Color = Surface
    val surfaceHigh: Color = SurfaceHigh
    val ink: Color = Ink
}

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF04140A),
    primaryContainer = Color(0xFF123321),
    onPrimaryContainer = Color(0xFFB9F5CE),
    secondary = Cyan,
    onSecondary = Color(0xFF00131A),
    secondaryContainer = Color(0xFF0E2A33),
    onSecondaryContainer = Color(0xFFB6ECF6),
    tertiary = Amber,
    background = Ink,
    onBackground = Color(0xFFE6EDF3),
    surface = Surface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFA9B7C6),
    error = Red,
    outline = Color(0xFF32404F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF166534),
    secondary = Color(0xFF0E7490),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEAF0F5),
    error = Color(0xFFB91C1C),
)

private val OmniTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun OmniTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = OmniTypography,
        content = content,
    )
}

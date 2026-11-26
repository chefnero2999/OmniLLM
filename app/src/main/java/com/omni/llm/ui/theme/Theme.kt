package com.omni.llm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// OmniLLM V3 "Obsidian Volt" / "Frost Volt" Theme Tokens
private val Volt = Color(0xFFB8FF3C)
private val EmeraldLight = Color(0xFF10B981)
private val Cobalt = Color(0xFF4F7CFF)
private val Copper = Color(0xFFFF7A45)
private val Rose = Color(0xFFF43F5E)
private val GeminiPurple = Color(0xFF7B61FF)
private val Red = Color(0xFFFF5252)

// Dark Surfaces (Obsidian)
private val Ink = Color(0xFF07090E)
private val Surface = Color(0xFF0E131D)
private val SurfaceHigh = Color(0xFF151C2B)
private val SurfaceCard = Color(0xFF111723)

// Light Surfaces (Frost)
private val LightInk = Color(0xFFF4F6FA)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceHigh = Color(0xFFE7ECF5)

/**
 * Named palette for static semantic highlights. Note: screens should prefer
 * `MaterialTheme.colorScheme` for backgrounds, surfaces, and primary accent so
 * Light Mode, Accent Picker, and Font Picker apply consistently across all tabs.
 */
object OmniPalette {
    val ok: Color = Volt
    val volt: Color = Volt
    val emerald: Color = EmeraldLight
    val info: Color = Cobalt
    val cobalt: Color = Cobalt
    val copper: Color = Copper
    val geminiBlue: Color = Cobalt
    val geminiPurple: Color = GeminiPurple
    val claude: Color = Copper
    val warn: Color = Copper
    val danger: Color = Red
    val surface: Color = Surface
    val surfaceHigh: Color = SurfaceHigh
    val surfaceCard: Color = SurfaceCard
    val ink: Color = Ink
}

data class AccentOption(val id: String, val label: String, val darkPrimary: Color, val lightPrimary: Color)

val ACCENT_OPTIONS = listOf(
    AccentOption("volt", "Acid Volt", Volt, Color(0xFF4D7C0F)),
    AccentOption("cobalt", "Cobalt", Cobalt, Color(0xFF2563EB)),
    AccentOption("copper", "Copper", Copper, Color(0xFFD9480F)),
    AccentOption("emerald", "Emerald", EmeraldLight, Color(0xFF059669)),
    AccentOption("rose", "Crimson Rose", Rose, Color(0xFFE11D48)),
)

val FONT_OPTIONS = listOf(
    "default" to "System UI",
    "sans" to "Clean Sans",
    "mono" to "Tech Mono",
    "serif" to "Editorial Serif",
)

private fun fontFamilyFor(id: String): FontFamily = when (id) {
    "sans" -> FontFamily.SansSerif
    "mono" -> FontFamily.Monospace
    "serif" -> FontFamily.Serif
    else -> FontFamily.Default
}

@Composable
fun OmniTheme(
    themeMode: String = "system",
    accentId: String = "volt",
    fontId: String = "default",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val accent = ACCENT_OPTIONS.firstOrNull { it.id == accentId } ?: ACCENT_OPTIONS.first()
    val family = fontFamilyFor(fontId)

    val colors = if (darkTheme) {
        darkColorScheme(
            primary = accent.darkPrimary,
            onPrimary = Color(0xFF07090E),
            primaryContainer = accent.darkPrimary.copy(alpha = 0.18f),
            onPrimaryContainer = accent.darkPrimary,
            secondary = Cobalt,
            onSecondary = Color(0xFF061028),
            secondaryContainer = Color(0xFF141E38),
            onSecondaryContainer = Color(0xFFBDCEFF),
            tertiary = Copper,
            background = Ink,
            onBackground = Color(0xFFEEF2F8),
            surface = Surface,
            onSurface = Color(0xFFEEF2F8),
            surfaceVariant = SurfaceHigh,
            onSurfaceVariant = Color(0xFF8492A6),
            error = Red,
            outline = Color(0xFF232D42),
        )
    } else {
        lightColorScheme(
            primary = accent.lightPrimary,
            onPrimary = Color.White,
            primaryContainer = accent.lightPrimary.copy(alpha = 0.14f),
            onPrimaryContainer = accent.lightPrimary,
            secondary = Color(0xFF1D4ED8),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDBEAFE),
            onSecondaryContainer = Color(0xFF1E3A8A),
            tertiary = Color(0xFFD9480F),
            background = LightInk,
            onBackground = Color(0xFF0F172A),
            surface = LightSurface,
            onSurface = Color(0xFF0F172A),
            surfaceVariant = LightSurfaceHigh,
            onSurfaceVariant = Color(0xFF475569),
            error = Color(0xFFDC2626),
            outline = Color(0xFFCBD5E1),
        )
    }

    val typography = Typography(
        bodyLarge = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 17.sp),
        labelSmall = TextStyle(fontFamily = family, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
        labelMedium = TextStyle(fontFamily = family, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontFamily = family, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontFamily = family, fontSize = 21.sp, fontWeight = FontWeight.Bold),
    )

    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        content = content,
    )
}

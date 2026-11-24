package app.fanboxlibrary.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
internal data class AndroidGalleryColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val secondary: Color,
    val secondarySoft: Color,
    val emerald: Color,
    val emeraldSoft: Color,
    val cinemaBg: Color,
)

internal val AndroidDarkColors = AndroidGalleryColors(
    isDark = true,
    bg = Color(0xFF0C0E13),
    surface = Color(0xFF171B25),
    surfaceElevated = Color(0xFF202736),
    border = Color(0xFF2B3347),
    ink = Color(0xFFEEF1F7),
    muted = Color(0xFF8B95A8),
    accent = Color(0xFFFF5C7C),
    accentSoft = Color(0x2EFF5C7C),
    secondary = Color(0xFF6366F1),
    secondarySoft = Color(0x2E6366F1),
    emerald = Color(0xFF10B981),
    emeraldSoft = Color(0x2A10B981),
    cinemaBg = Color(0xFF06070A),
)

internal val AndroidLightColors = AndroidGalleryColors(
    isDark = false,
    bg = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFE8EDF6),
    border = Color(0xFFD8DFEB),
    ink = Color(0xFF131722),
    muted = Color(0xFF5D677B),
    accent = Color(0xFFE11D48),
    accentSoft = Color(0x1EE11D48),
    secondary = Color(0xFF4F46E5),
    secondarySoft = Color(0x1E4F46E5),
    emerald = Color(0xFF059669),
    emeraldSoft = Color(0x1E059669),
    cinemaBg = Color(0xFF0A0C12),
)

internal val LocalAndroidGalleryColors = staticCompositionLocalOf { AndroidDarkColors }

@Composable
internal fun FanboxAndroidTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) AndroidDarkColors else AndroidLightColors
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.ink,
            secondary = palette.secondary,
            onSecondary = Color.White,
            secondaryContainer = palette.secondarySoft,
            onSecondaryContainer = palette.ink,
            tertiary = palette.emerald,
            background = palette.bg,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.accent,
            secondary = palette.secondary,
            onSecondary = Color.White,
            secondaryContainer = palette.secondarySoft,
            onSecondaryContainer = palette.secondary,
            tertiary = palette.emerald,
            background = palette.bg,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
        )
    }

    CompositionLocalProvider(LocalAndroidGalleryColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

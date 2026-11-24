package app.fanboxlibrary.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal const val APP_VERSION = "1.0.0"

@Immutable
internal data class GalleryColors(
    val isDark: Boolean,
    val bg: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val borderStrong: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val secondary: Color,
    val secondarySoft: Color,
    val emerald: Color,
    val emeraldSoft: Color,
    val cinemaBg: Color,
    val noticeBg: Color,
)

internal val DarkGalleryColors = GalleryColors(
    isDark = true,
    bg = Color(0xFF0C0E13),
    sidebar = Color(0xFF12151D),
    surface = Color(0xFF171B25),
    surfaceElevated = Color(0xFF1F2533),
    border = Color(0xFF282F40),
    borderStrong = Color(0xFF3A445C),
    ink = Color(0xFFEEF1F7),
    muted = Color(0xFF8B95A8),
    accent = Color(0xFFFF5C7C),
    accentSoft = Color(0x2EFF5C7C),
    secondary = Color(0xFF6366F1),
    secondarySoft = Color(0x2E6366F1),
    emerald = Color(0xFF10B981),
    emeraldSoft = Color(0x2A10B981),
    cinemaBg = Color(0xFF07080B),
    noticeBg = Color(0xFF1E2235),
)

internal val LightGalleryColors = GalleryColors(
    isDark = false,
    bg = Color(0xFFF4F6FA),
    sidebar = Color(0xFFECEFF6),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFE9EEF7),
    border = Color(0xFFD8DFEB),
    borderStrong = Color(0xFFBCC6D8),
    ink = Color(0xFF131722),
    muted = Color(0xFF5D677B),
    accent = Color(0xFFE11D48),
    accentSoft = Color(0x1EE11D48),
    secondary = Color(0xFF4F46E5),
    secondarySoft = Color(0x1E4F46E5),
    emerald = Color(0xFF059669),
    emeraldSoft = Color(0x1E059669),
    cinemaBg = Color(0xFF0E1016),
    noticeBg = Color(0xFFE9EBF8),
)

internal val LocalGalleryColors = staticCompositionLocalOf { DarkGalleryColors }

private val GalleryTypography = Typography(
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
)

@Composable
internal fun FanboxLibraryTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkGalleryColors else LightGalleryColors
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
            outlineVariant = palette.borderStrong,
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
            outlineVariant = palette.borderStrong,
        )
    }

    CompositionLocalProvider(LocalGalleryColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GalleryTypography,
            content = content,
        )
    }
}

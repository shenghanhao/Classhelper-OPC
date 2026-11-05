package com.classmemory.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

@Immutable
data class ClassMemoryPalette(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val primary: Color,
    val onPrimary: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val success: Color,
    val successBackground: Color,
    val warning: Color,
    val warningBackground: Color,
    val error: Color,
    val errorBackground: Color,
)

private val LightPalette = ClassMemoryPalette(
    background = Color(0xFFFAFAFF), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFEEEBFF),
    primary = Color(0xFF4E36E9), onPrimary = Color.White, text = Color(0xFF1B1B28),
    muted = Color(0xFF686A80), border = Color(0xFFDCDCF0),
    success = Color(0xFF008777), successBackground = Color(0xFFE0F5EF),
    warning = Color(0xFFAB5D09), warningBackground = Color(0xFFFFF3E2),
    error = Color(0xFFB53650), errorBackground = Color(0xFFFFEAF0),
)

private val DarkPalette = ClassMemoryPalette(
    background = Color(0xFF14131D), surface = Color(0xFF1E1D2B), surfaceAlt = Color(0xFF302A4C),
    primary = Color(0xFFBCB0FF), onPrimary = Color(0xFF24125D), text = Color(0xFFF1EFFB),
    muted = Color(0xFFB8B4CA), border = Color(0xFF454156),
    success = Color(0xFF76D9C4), successBackground = Color(0xFF153D37),
    warning = Color(0xFFFFC784), warningBackground = Color(0xFF443320),
    error = Color(0xFFFFACC0), errorBackground = Color(0xFF492736),
)

private val LocalClassMemoryPalette = compositionLocalOf { LightPalette }

object ClassMemoryColors {
    val current: ClassMemoryPalette
        @Composable get() = LocalClassMemoryPalette.current
}

object ClassMemorySpacing {
    val screen = 20.dp
    val section = 24.dp
    val card = 16.dp
    val item = 10.dp
}

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(15.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun ClassMemoryTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = palette.primary, onPrimary = palette.onPrimary,
        primaryContainer = palette.surfaceAlt, onPrimaryContainer = palette.text,
        secondary = palette.primary, onSecondary = palette.onPrimary,
        secondaryContainer = palette.surfaceAlt, onSecondaryContainer = palette.text,
        tertiary = palette.warning, onTertiary = palette.background,
        tertiaryContainer = palette.warningBackground, onTertiaryContainer = palette.warning,
        background = palette.background, onBackground = palette.text,
        surface = palette.surface, onSurface = palette.text,
        surfaceVariant = palette.surfaceAlt, onSurfaceVariant = palette.muted,
        outline = palette.border, outlineVariant = palette.border,
        error = palette.error, onError = palette.background,
        errorContainer = palette.errorBackground, onErrorContainer = palette.error,
    ) else lightColorScheme(
        primary = palette.primary, onPrimary = palette.onPrimary,
        primaryContainer = palette.surfaceAlt, onPrimaryContainer = palette.text,
        secondary = palette.primary, onSecondary = palette.onPrimary,
        secondaryContainer = palette.surfaceAlt, onSecondaryContainer = palette.text,
        tertiary = palette.warning, onTertiary = palette.onPrimary,
        tertiaryContainer = palette.warningBackground, onTertiaryContainer = palette.warning,
        background = palette.background, onBackground = palette.text,
        surface = palette.surface, onSurface = palette.text,
        surfaceVariant = palette.surfaceAlt, onSurfaceVariant = palette.muted,
        outline = palette.border, outlineVariant = palette.border,
        error = palette.error, onError = palette.onPrimary,
        errorContainer = palette.errorBackground, onErrorContainer = palette.error,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalClassMemoryPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

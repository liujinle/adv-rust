package com.adv.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apple-flavoured neutral surface palette: one accent colour, generous radii,
 * quiet borders instead of heavy elevation, and a real dark scheme rather than
 * an inverted light one.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEEFF),
    onPrimaryContainer = Color(0xFF00325C),
    secondary = Color(0xFF5E5CE6),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFF5C5C68),
    outline = Color(0xFFD6D6DE),
    outlineVariant = Color(0xFFE7E7EC),
    error = Color(0xFFE5383B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE2E1),
    onErrorContainer = Color(0xFF5C0A06),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF64D2FF),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF10405A),
    onPrimaryContainer = Color(0xFFD6EEFF),
    secondary = Color(0xFF9C9BFF),
    onSecondary = Color(0xFF1B1A3D),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFF2F2F6),
    surface = Color(0xFF15151B),
    onSurface = Color(0xFFF2F2F6),
    surfaceVariant = Color(0xFF212129),
    onSurfaceVariant = Color(0xFFA6A6B2),
    outline = Color(0xFF2F2F3A),
    outlineVariant = Color(0xFF232330),
    error = Color(0xFFFF6B6E),
    onError = Color(0xFF3A0505),
    errorContainer = Color(0xFF4A1313),
    onErrorContainer = Color(0xFFFFDADA),
)

private val AdvTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3f).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2f).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2f.sp,
    ),
)

private val AdvShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AdvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AdvTypography,
        shapes = AdvShapes,
        content = content,
    )
}

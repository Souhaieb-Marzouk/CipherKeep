// Generated theme shell. Colors and dimensions come from design-tokens.json.
package dev.cipherkeep.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp

enum class ThemeMode {
    System,
    Light,
    Dark,
}

private val LightColors = lightColorScheme(
    primary = CKColor.LightPrimary,
    onPrimary = CKColor.LightOnPrimary,
    primaryContainer = CKColor.LightPrimaryContainer,
    onPrimaryContainer = CKColor.LightOnPrimaryContainer,
    secondary = Color(0xFFD97706),
    onSecondary = Color.White,
    tertiary = CKColor.LightAccent,
    onTertiary = CKColor.LightOnAccent,
    background = CKColor.LightBackground,
    onBackground = CKColor.LightText,
    surface = CKColor.LightSurface,
    onSurface = CKColor.LightText,
    surfaceVariant = CKColor.LightSurfaceMuted,
    onSurfaceVariant = CKColor.LightTextMuted,
    outline = CKColor.LightBorderStrong,
    error = CKColor.LightDanger,
    onError = CKColor.LightSurface,
    errorContainer = CKColor.LightDangerContainer,
    onErrorContainer = CKColor.LightOnDangerContainer,
)

private val DarkColors = darkColorScheme(
    primary = CKColor.DarkPrimary,
    onPrimary = CKColor.DarkOnPrimary,
    primaryContainer = CKColor.DarkPrimaryContainer,
    onPrimaryContainer = CKColor.DarkOnPrimaryContainer,
    secondary = Color(0xFFFBBF24),
    onSecondary = Color(0xFF2A1000),
    tertiary = CKColor.DarkAccent,
    onTertiary = CKColor.DarkOnAccent,
    background = CKColor.DarkBackground,
    onBackground = CKColor.DarkText,
    surface = CKColor.DarkSurface,
    onSurface = CKColor.DarkText,
    surfaceVariant = CKColor.DarkSurfaceMuted,
    onSurfaceVariant = CKColor.DarkTextMuted,
    outline = CKColor.DarkBorderStrong,
    error = CKColor.DarkDanger,
    onError = CKColor.DarkOnPrimary,
    errorContainer = CKColor.DarkDangerContainer,
    onErrorContainer = CKColor.DarkOnDangerContainer,
)

private val CipherKeepTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = CKType.DisplaySize,
        lineHeight = CKType.DisplayLineHeight,
        letterSpacing = (-0.02f).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = CKType.H1Size,
        lineHeight = CKType.H1LineHeight,
        letterSpacing = (-0.01f).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = CKType.H2Size,
        lineHeight = CKType.H2LineHeight,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = CKType.H3Size,
        lineHeight = CKType.H3LineHeight,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = CKType.BodyStrongSize,
        lineHeight = CKType.BodyStrongLineHeight,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = CKType.BodySize,
        lineHeight = CKType.BodyLineHeight,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = CKType.SmallSize,
        lineHeight = CKType.SmallLineHeight,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = CKType.CaptionSize,
        lineHeight = CKType.CaptionLineHeight,
        letterSpacing = 0.01.sp,
    ),
)

private val CipherKeepShapes = Shapes(
    extraSmall = RoundedCornerShape(CKDimens.RadiusSm),
    small = RoundedCornerShape(CKDimens.RadiusSm),
    medium = RoundedCornerShape(CKDimens.RadiusMd),
    large = RoundedCornerShape(CKDimens.RadiusLg),
    extraLarge = RoundedCornerShape(CKDimens.RadiusLg),
)

@Composable
fun CipherKeepTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = CipherKeepTypography,
        shapes = CipherKeepShapes,
        content = content,
    )
}

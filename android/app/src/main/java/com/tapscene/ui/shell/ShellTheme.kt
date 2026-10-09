package com.tapscene.ui.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared product tokens. Contrast and the restrained paper/ink palette remain deterministic. */
object ShellColors {
    val Background = Color(0xFFF8F5EF)
    val Surface = Color(0xFFFFFDFA)
    val Ink = Color(0xFF292622)
    val Muted = Color(0xFF746D64)
    val Accent = Color(0xFFB74325)
    val AccentSoft = Color(0xFFF3E3DB)
    val Divider = Color(0xFFDDD7CF)
    val Quiet = Color(0xFFEFEAE2)
}

object ShellSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Page = 16.dp
    val Section = 24.dp
}

private val TapSceneColors = lightColorScheme(
    primary = ShellColors.Accent,
    onPrimary = Color.White,
    primaryContainer = ShellColors.AccentSoft,
    onPrimaryContainer = ShellColors.Accent,
    secondary = ShellColors.Ink,
    onSecondary = ShellColors.Surface,
    secondaryContainer = ShellColors.Quiet,
    onSecondaryContainer = ShellColors.Ink,
    tertiary = ShellColors.Muted,
    onTertiary = ShellColors.Surface,
    tertiaryContainer = ShellColors.Quiet,
    onTertiaryContainer = ShellColors.Ink,
    background = ShellColors.Background,
    onBackground = ShellColors.Ink,
    surface = ShellColors.Surface,
    surfaceDim = Color(0xFFE5DFD6),
    surfaceBright = ShellColors.Surface,
    surfaceContainerLowest = ShellColors.Surface,
    surfaceContainerLow = ShellColors.Background,
    surfaceContainer = Color(0xFFF2EDE5),
    surfaceContainerHigh = Color(0xFFECE6DC),
    surfaceContainerHighest = Color(0xFFE5DED3),
    surfaceTint = ShellColors.Accent,
    inverseSurface = ShellColors.Ink,
    inverseOnSurface = ShellColors.Background,
    inversePrimary = Color(0xFFEAA68F),
    onSurface = ShellColors.Ink,
    surfaceVariant = ShellColors.Quiet,
    onSurfaceVariant = ShellColors.Muted,
    outline = ShellColors.Muted,
    outlineVariant = ShellColors.Divider,
    error = Color(0xFFAF342C),
    errorContainer = Color(0xFFF5E2DE),
)

private val TapSceneTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun TapSceneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TapSceneColors,
        typography = TapSceneTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}

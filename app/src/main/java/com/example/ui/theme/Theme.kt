package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocalAppLanguage

private val DarkColorScheme = darkColorScheme(
    primary = NetPrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = NetPrimaryBlueContainer,
    onPrimaryContainer = NetTextPrimaryDark,
    secondary = NetDownloadTeal,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = NetDownloadTealContainer,
    onSecondaryContainer = NetDownloadTeal,
    tertiary = NetUploadViolet,
    onTertiary = Color(0xFF1E1B4B),
    tertiaryContainer = NetUploadVioletContainer,
    onTertiaryContainer = NetUploadViolet,
    background = NetBgDark,
    onBackground = NetTextPrimaryDark,
    surface = NetCardDark,
    onSurface = NetTextPrimaryDark,
    surfaceVariant = NetCardElevatedDark,
    onSurfaceVariant = NetTextSecondaryDark,
    outline = NetBorderDark,
    error = NetBlockCoral,
    onError = Color.White,
    errorContainer = NetBlockCoralContainer,
    onErrorContainer = NetBlockCoral
)

private val LightColorScheme = lightColorScheme(
    primary = NetPrimaryBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = NetDownloadTealLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF134E4A),
    tertiary = NetUploadVioletLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),
    background = NetBgLight,
    onBackground = NetTextPrimaryLight,
    surface = NetCardLight,
    onSurface = NetTextPrimaryLight,
    surfaceVariant = NetCardElevatedLight,
    onSurfaceVariant = NetTextSecondaryLight,
    outline = NetBorderLight,
    error = NetBlockCoralLight,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B)
)

val NetBalancerShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun NetBalancerTheme(
    darkTheme: Boolean = true,
    appLanguage: AppLanguage = AppLanguage.AR,
    forceRtl: Boolean = appLanguage.isRtl,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val direction = if (forceRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalLayoutDirection provides direction,
        LocalAppLanguage provides appLanguage
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = NetBalancerShapes,
            content = content
        )
    }
}

// Keep compatibility wrapper for existing tests or previews
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    NetBalancerTheme(darkTheme = darkTheme, appLanguage = AppLanguage.AR, content = content)
}

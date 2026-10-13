package com.whopclipper.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF176B50), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEFE3), onPrimaryContainer = Color(0xFF103B2C),
    secondary = Color(0xFF52665B), secondaryContainer = Color(0xFFE4EDE5),
    background = Color(0xFFF4F6F2), onBackground = Color(0xFF192B23),
    surface = Color(0xFFFCFDF9), onSurface = Color(0xFF192B23),
    surfaceVariant = Color(0xFFE7EDE6), onSurfaceVariant = Color(0xFF526157),
    outline = Color(0xFF748277), outlineVariant = Color(0xFFD8E1D7),
    error = Color(0xFFA83227), errorContainer = Color(0xFFFFDAD4), onErrorContainer = Color(0xFF6C170F)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FD5B2), onPrimary = Color(0xFF053825),
    primaryContainer = Color(0xFF204E39), onPrimaryContainer = Color(0xFFD2F3DF),
    secondary = Color(0xFFB9CDBF), secondaryContainer = Color(0xFF344B3E),
    background = Color(0xFF101813), onBackground = Color(0xFFE2ECE2),
    surface = Color(0xFF19221C), onSurface = Color(0xFFE2ECE2),
    surfaceVariant = Color(0xFF28362C), onSurfaceVariant = Color(0xFFBACABD),
    outline = Color(0xFF8B9B8D), outlineVariant = Color(0xFF3C4B3F),
    error = Color(0xFFFFB4A8), errorContainer = Color(0xFF6C2219), onErrorContainer = Color(0xFFFFDAD4)
)

private val AppTypography = Typography().let { base -> base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp)
) }

@Composable
fun PhoneTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography, content = content
    )
}

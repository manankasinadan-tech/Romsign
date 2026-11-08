package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SoftSky,
    onPrimary = Color(0xFF0D1B2A),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFD0E4FF),
    secondary = SoftEmerald,
    onSecondary = Color(0xFF003822),
    background = SlateDarkBg,
    onBackground = Color(0xFFE2E6EE),
    surface = SlateDarkSurface,
    onSurface = Color(0xFFE2E6EE),
    surfaceVariant = SlateDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A1B8),
    outline = SlateDarkOutline,
    outlineVariant = SlateDarkOutline,
    error = SoftCoral,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = DeepIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = SoftEmerald,
    onSecondary = Color.White,
    background = SlateLightBg,
    onBackground = Color(0xFF191C20),
    surface = SlateLightSurface,
    onSurface = Color(0xFF191C20),
    surfaceVariant = SlateLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF5F6B7C),
    outline = SlateLightOutline,
    outlineVariant = SlateLightOutline,
    error = SoftCoral,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Preserve intentional brand colors
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

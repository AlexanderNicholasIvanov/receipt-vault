package com.alexivanov.receiptvault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

private val LightFallback = lightColorScheme(
    primary = Color(0xFF0F6E56),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2D6),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C6358),
    tertiary = Color(0xFF3E6374),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFF8AD6BA),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513E),
    onPrimaryContainer = Color(0xFFA6F2D6),
    secondary = Color(0xFFB3CCBF),
    tertiary = Color(0xFFA6CCDF),
)

/**
 * Material You: on One UI the dynamic scheme follows the user's wallpaper
 * palette (Settings > Wallpaper and style > Color palette).
 */
@Composable
fun ReceiptVaultTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        // minSdk 33 always has dynamic color; the fallbacks only serve previews.
        LocalInspectionMode.current -> if (darkTheme) DarkFallback else LightFallback
        darkTheme -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

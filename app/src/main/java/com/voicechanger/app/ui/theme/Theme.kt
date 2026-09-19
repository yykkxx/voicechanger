package com.voicechanger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9CD67A),
    secondary = Color(0xFFB6C4A2),
    tertiary = Color(0xFFD6B87A),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3E6837),
    secondary = Color(0xFF56624A),
    tertiary = Color(0xFF725C1D),
)

@Composable
fun VoiceChangerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
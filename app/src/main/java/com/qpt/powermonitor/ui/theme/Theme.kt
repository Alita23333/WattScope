package com.qpt.powermonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF33D6A6),
    secondary = Color(0xFF8FD7FF),
    tertiary = Color(0xFFFFCF66),
    background = Color(0xFF0E1116),
    surface = Color(0xFF151A21),
    surfaceVariant = Color(0xFF202733),
    onPrimary = Color(0xFF031511),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
)

@Composable
fun QptTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}

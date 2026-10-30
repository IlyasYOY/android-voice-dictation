package io.github.ilyasyoy.voicedictation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF28594A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E9D7),
    onPrimaryContainer = Color(0xFF12382C),
    background = Color(0xFFF7F6F0),
    surface = Color(0xFFF7F6F0),
    surfaceContainer = Color(0xFFEFEEE6),
    onSurface = Color(0xFF202923),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFABD0B7),
    primaryContainer = Color(0xFF234B3C),
    background = Color(0xFF141C18),
    surface = Color(0xFF141C18),
    surfaceContainer = Color(0xFF202B24),
    onSurface = Color(0xFFE2E8DF),
)

@Composable
fun VoiceDictationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

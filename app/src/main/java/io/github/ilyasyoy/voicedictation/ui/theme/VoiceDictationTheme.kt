package io.github.ilyasyoy.voicedictation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.ilyasyoy.voicedictation.ui.design.SoftCurrentPalette as P

private val LightColors = lightColorScheme(
    primary = Color(P.Accent), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E0F4), onPrimaryContainer = Color(P.Plum),
    background = Color(P.Paper), onBackground = Color(P.Plum),
    surface = Color(P.Surface), surfaceContainer = Color(0xFFECE5F3),
    onSurface = Color(P.Plum), onSurfaceVariant = Color(P.Muted), outline = Color(P.Outline),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFB8F2), onPrimary = Color(P.Plum),
    primaryContainer = Color(0xFF4C3B64), onPrimaryContainer = Color(P.NightText),
    background = Color(P.Night), onBackground = Color(P.NightText),
    surface = Color(P.NightSurface), surfaceContainer = Color(0xFF3B334B),
    onSurface = Color(P.NightText), onSurfaceVariant = Color(0xFFCAC0D7), outline = Color(0xFF766A86),
)
private val CurrentType = Typography(
    headlineLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
)
@Composable
fun VoiceDictationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = CurrentType, content = content)
}

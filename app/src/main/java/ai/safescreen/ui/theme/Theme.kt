package ai.safescreen.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF141925),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(primary = Color(0xFF1A73E8))

@Composable
fun SafeScreenTheme(useDark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (useDark) DarkColors else LightColors, content = content)
}

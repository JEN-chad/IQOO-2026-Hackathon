package ai.safescreen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** On-screen "proof it runs on-device" overlay. */
data class HudState(
    val backend: String = "—",
    val tier1Ms: Long = 0,
    val fps: Float = 0f,
    val usingModels: Boolean = false,
    val level: String = "PRIVATE",
    val status: String = "MONITORING",
    val isDegraded: Boolean = false,
)

@Composable
fun TelemetryHud(state: HudState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xEE0B0E14))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            "🛡️ SafeScreen AI • [${state.level}]",
            color = Color(0xFF6FA8FF),
            fontSize = 12.sp,
            fontFamily = FontFamily.SansSerif,
        )
        Text(
            "🔒 LOCAL AI • NO CLOUD (0 bytes transmitted)",
            color = Color(0xFF8FE3A0),
            fontSize = 10.sp,
            fontFamily = FontFamily.SansSerif,
        )
        Text(
            "status: ${state.status}",
            color = if (state.isDegraded) Color(0xFFFF6B6B) else Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Text(
            "backend: ${state.backend}${if (!state.usingModels) " (heuristic)" else ""}",
            color = Color(0xFFD0D7E5),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Text(
            "latency: ${state.tier1Ms}ms | ~${"%.1f".format(state.fps)} fps",
            color = Color(0xFFD0D7E5),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}


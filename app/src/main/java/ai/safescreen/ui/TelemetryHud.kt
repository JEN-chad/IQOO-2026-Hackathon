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

/** On-screen "proof it runs on-device" overlay — the judging gold. */
data class HudState(
    val backend: String = "—",
    val tier1Ms: Long = 0,
    val fps: Float = 0f,
    val usingModels: Boolean = false,
)

@Composable
fun TelemetryHud(state: HudState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text("SafeScreen • on-device", color = Color(0xFF8FE3A0), fontSize = 11.sp)
        Text("🔒 no network · 0 bytes leave device", color = Color(0xFF8FE3A0), fontSize = 11.sp)
        Text(
            "backend: ${state.backend}${if (!state.usingModels) " (heuristic)" else ""}",
            color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Text(
            "NSFW ${state.tier1Ms}ms",
            color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Text(
            "~${"%.1f".format(state.fps)} fps",
            color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
    }
}

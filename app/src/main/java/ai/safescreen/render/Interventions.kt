package ai.safescreen.render

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ai.safescreen.policy.Action
import ai.safescreen.policy.Decision

/** Renders an image with the severity-tiered intervention applied. */
@Composable
fun ProtectedImage(bitmap: Bitmap, decision: Decision?, modifier: Modifier = Modifier) {
    var revealed by remember(bitmap) { mutableStateOf(false) }
    Box(modifier) {
        val blurRadius = when {
            decision == null -> 0.dp
            decision.action == Action.BLUR_REVEAL && !revealed -> 22.dp
            decision.action == Action.BLOCK -> 28.dp
            else -> 0.dp
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = decision?.reason,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(14.dp))
                .blur(blurRadius),
        )
        when (decision?.action) {
            Action.BLOCK -> SafetyCard(decision)
            Action.BLUR_REVEAL -> if (!revealed) RevealGate(decision) { revealed = true }
            else -> {}
        }
    }
}

@Composable
private fun BoxScope.SafetyCard(decision: Decision) {
    Column(
        Modifier
            .matchParentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6111418)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFFFF6B6B))
        Spacer(Modifier.height(8.dp))
        Text("Content blocked", color = Color.White)
        Text(decision.reason, color = Color(0xFFB9C2D0))
    }
}

@Composable
private fun BoxScope.RevealGate(decision: Decision, onReveal: () -> Unit) {
    Column(
        Modifier
            .matchParentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x99000000))
            .clickable { onReveal() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFD166))
        Spacer(Modifier.height(8.dp))
        Text("Tap to view anyway", color = Color.White)
        Text(decision.reason, color = Color(0xFFB9C2D0))
    }
}

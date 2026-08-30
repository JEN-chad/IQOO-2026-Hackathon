package ai.safescreen.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.safescreen.policy.Thresholds

/** Live threshold tuning for the demo. Writes straight back into the shared [Thresholds]. */
@Composable
fun SettingsDialog(thresholds: Thresholds, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Detection thresholds") },
        text = {
            Column {
                ThresholdSlider("Blur ≥", thresholds.nsfwBlur) { thresholds.nsfwBlur = it }
                ThresholdSlider("Block ≥", thresholds.nsfwBlock) { thresholds.nsfwBlock = it }
                ThresholdSlider("Skin backstop weight", thresholds.skinWeight) {
                    thresholds.skinWeight = it
                }
                ThresholdSlider("NSFW floor (skin gate)", thresholds.mlNsfwFloor) {
                    thresholds.mlNsfwFloor = it
                }
            }
        },
    )
}

@Composable
private fun ThresholdSlider(label: String, initial: Float, onChange: (Float) -> Unit) {
    var value by remember { mutableFloatStateOf(initial) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("$label ${"%.2f".format(value)}")
        Slider(
            value = value,
            onValueChange = { value = it; onChange(it) },
            valueRange = 0f..1f,
        )
    }
}

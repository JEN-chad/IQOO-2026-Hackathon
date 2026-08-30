package ai.safescreen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import ai.safescreen.bench.Benchmark
import ai.safescreen.bench.BenchmarkResult
import ai.safescreen.bench.PowerMeter
import ai.safescreen.capture.ScreenCaptureService
import ai.safescreen.feed.DemoContent
import ai.safescreen.feed.InAppFeedScreen
import ai.safescreen.policy.Action
import ai.safescreen.policy.Decision
import ai.safescreen.ui.theme.SafeScreenTheme
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionLauncher = registerForActivityResult(StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, res.resultCode, data)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        enableEdgeToEdge()
        setContent {
            SafeScreenTheme {
                var screen by remember { mutableStateOf("home") }
                val engine = remember { SafeScreenEngine.get(applicationContext) }
                if (screen == "feed") {
                    InAppFeedScreen(engine)
                } else {
                    HomeScreen(
                        engine = engine,
                        onStart = { startProtection() },
                        onStop = { ScreenCaptureService.stop(this) },
                        onTestFeed = { screen = "feed" },
                    )
                }
            }
        }
    }

    /** Ensure overlay permission, then request the per-session screen-capture consent. */
    private fun startProtection() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    engine: SafeScreenEngine,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTestFeed: () -> Unit,
) {
    var running by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var benchRunning by remember { mutableStateOf(false) }
    var bench by remember { mutableStateOf<BenchmarkResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<Decision?>(null) }

    // Private on-device test: pick a photo, decode it cleanly (no screen-capture degradation) and run
    // the SAME detection path the live monitor uses (multi-crop ML + skin backstop). Nothing leaves the device.
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                scanning = true
                scan = null
                val bmp = withContext(Dispatchers.IO) { decodeForAnalysis(context, uri) }
                scan = bmp?.let { engine.analyzeScreen(it).decision }
                scanning = false
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("SafeScreen AI") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("On-device screen protection", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Continuously scans your screen for explicit, abusive, or AI-generated content and " +
                    "blurs it before you engage. 100% on-device — nothing ever leaves your phone.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "🔒 Private by construction",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF2E7D32),
                    )
                    Text(
                        "• No INTERNET permission — the app literally cannot upload anything",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "• Frames analyzed in memory on the NPU/CPU — never stored or sent",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "• Works fully offline — try it in airplane mode",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!running) {
                Button(onClick = { onStart(); running = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start protection")
                }
            } else {
                Button(onClick = { onStop(); running = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop protection")
                }
                Text("Protection active — scanning on-device", color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(onClick = onTestFeed, modifier = Modifier.fillMaxWidth()) {
                Text("Open test feed")
            }
            OutlinedButton(
                enabled = !scanning,
                onClick = {
                    scan = null
                    picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (scanning) "Analyzing…" else "Scan a photo (private)") }
            if (scanning) CircularProgressIndicator()
            scan?.let { ScanResultCard(it) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("On-device benchmark", style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = !benchRunning,
                onClick = {
                    scope.launch {
                        benchRunning = true
                        val pm = PowerMeter(context)
                        val bmp = DemoContent.items(context).first().bitmap
                        bench = Benchmark.run(engine, pm, bmp, n = 100)
                        benchRunning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (benchRunning) "Running…" else "Run benchmark (100 inferences)") }
            if (benchRunning) CircularProgressIndicator()
            bench?.let { BenchmarkCard(it) }

            Spacer(Modifier.height(8.dp))
            Text(
                "Grant “Display over other apps” and screen capture when prompted.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun BenchmarkCard(r: BenchmarkResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Backend: ${r.backend}", style = MaterialTheme.typography.titleSmall)
            Text("Latency: ${"%.1f".format(r.avgMs)} ms avg · ${r.p50Ms} ms p50")
            Text("Throughput: ${"%.1f".format(r.fps)} fps  (n=${r.n})")
            if (r.pluggedIn) {
                Text(
                    "⚠ Unplug USB for a valid energy number (charging skews battery current).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Power: ${"%.0f".format(r.avgPowerMw)} mW · " +
                        "${"%.1f".format(r.energyPerInferenceMj)} mJ/inf · " +
                        "${"%.0f".format(r.inferencesPerJoule)} inf/J",
                )
            }
            Text(
                "Whole-device estimate (not NPU-isolated; rigorous number via Snapdragon Profiler).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ScanResultCard(d: Decision) {
    val (verdict, tint) = when (d.action) {
        Action.BLOCK -> "BLOCKED — explicit" to Color(0xFFC62828)
        Action.BLUR_REVEAL -> "BLURRED — possibly explicit" to Color(0xFFEF6C00)
        Action.SHOW -> "Clear — no risk detected" to Color(0xFF2E7D32)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(verdict, style = MaterialTheme.typography.titleSmall, color = tint)
            Text("NSFW: ${"%.0f".format(d.nsfw * 100)}%")
            Text(
                "Analyzed full-resolution on-device (no screen capture). Nothing left the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * Decode a picked image to a SOFTWARE-allocated bitmap (the detectors and skinRatio call getPixel(),
 * which throws on the default HARDWARE bitmap), downscaled so a 12-MP photo can't OOM.
 */
private fun decodeForAnalysis(context: Context, uri: Uri): Bitmap? = try {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        val longSide = maxOf(info.size.width, info.size.height)
        if (longSide > 1280) decoder.setTargetSampleSize(longSide / 1280)
    }
} catch (t: Throwable) {
    android.util.Log.e("MainActivity", "decodeForAnalysis failed: ${t.message}")
    null
}

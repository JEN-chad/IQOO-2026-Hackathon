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
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import ai.safescreen.bench.Benchmark
import ai.safescreen.bench.BenchmarkResult
import ai.safescreen.bench.PowerMeter
import ai.safescreen.capture.ScreenCaptureService
import ai.safescreen.feed.DemoContent
import ai.safescreen.feed.InAppFeedScreen
import ai.safescreen.policy.Action
import ai.safescreen.policy.Decision
import ai.safescreen.policy.ProtectionLevel
import ai.safescreen.ui.SettingsDialog
import ai.safescreen.ui.theme.SafeScreenTheme
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
                    InAppFeedScreen(engine = engine, onBack = { screen = "home" })
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
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
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
    val running by ScreenCaptureService.isRunning.collectAsState()
    var currentLevel by remember { mutableStateOf(engine.currentLevel) }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var benchRunning by remember { mutableStateOf(false) }
    var bench by remember { mutableStateOf<BenchmarkResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<Decision?>(null) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = Color(0xFF6FA8FF),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("SafeScreen AI", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Thresholds")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Truthful Privacy & Security Badge
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101E17)),
                border = BorderStroke(1.dp, Color(0xFF1E4632)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF8FE3A0)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "LOCAL AI • NO CLOUD PROCESSING",
                            color = Color(0xFF8FE3A0),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.SansSerif,
                        )
                        Text(
                            "0 bytes leave your phone · In-memory frame analysis · No internet permission",
                            color = Color(0xFFC0D3C5),
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Real-Time System Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141925)),
                border = BorderStroke(1.dp, Color(0xFF222B3F)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "System Status",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFF90A0B8),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (running) Color(0xFF4CAF50) else Color(0xFF9E9E9E)),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (running) "SHIELD ACTIVE" else "IDLE / READY",
                                fontWeight = FontWeight.Bold,
                                color = if (running) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Text(
                        "Backend: ${engine.backend} • Model: ${if (engine.usingModels) "Marqo ViT-384" else "Heuristic"}",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Text(
                        "Active Level: ${currentLevel.title} (${currentLevel.subtitle})",
                        color = Color(0xFF6FA8FF),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }

            // Protection Level Selector (Feature 1)
            Text(
                "Select Protection Level",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            ProtectionLevel.entries.forEach { lvl ->
                val isSelected = currentLevel == lvl
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentLevel = lvl
                            engine.setProtectionLevel(lvl)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF1C273C) else Color(0xFF121620),
                    ),
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) Color(0xFF6FA8FF) else Color(0xFF222B3F),
                    ),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    lvl.title,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color(0xFF6FA8FF) else Color.White,
                                    fontSize = 15.sp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "— ${lvl.subtitle}",
                                    color = Color(0xFF90A0B8),
                                    fontSize = 12.sp,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                lvl.description,
                                color = Color(0xFFCAD4E2),
                                fontSize = 12.sp,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint = Color(0xFF6FA8FF),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            // Start / Stop Protection Button
            if (!running) {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("START PRIVACY SHIELD", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            } else {
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("STOP PRIVACY SHIELD", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            // Controlled Test Actions
            Text(
                "Deterministic Verification & Testing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            OutlinedButton(
                onClick = onTestFeed,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Controlled Test Feed")
            }

            OutlinedButton(
                enabled = !scanning,
                onClick = {
                    scan = null
                    picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (scanning) "Analyzing On-Device…" else "Scan Single Photo (Private)")
            }
            if (scanning) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            scan?.let { ScanResultCard(it) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // On-Device Benchmark
            Text("On-Device Performance Benchmark", style = MaterialTheme.typography.titleMedium)
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
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (benchRunning) "Benchmarking (100 passes)…" else "Run 100-Inference Benchmark")
            }
            if (benchRunning) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            bench?.let { BenchmarkCard(it) }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSettings) {
        SettingsDialog(engine.thresholds) { showSettings = false }
    }
}

@Composable
private fun BenchmarkCard(r: BenchmarkResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141925)),
        border = BorderStroke(1.dp, Color(0xFF222B3F)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Backend: ${r.backend}", fontWeight = FontWeight.Bold, color = Color(0xFF6FA8FF))
            Text("Latency: ${"%.1f".format(r.avgMs)} ms avg · ${r.p50Ms} ms p50", color = Color.White)
            Text("Throughput: ${"%.1f".format(r.fps)} fps (n=${r.n})", color = Color.White)
            if (r.pluggedIn) {
                Text(
                    "⚡ Device plugged in (power sampled during inference loop)",
                    color = Color(0xFF8FE3A0),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Power: ${"%.0f".format(r.avgPowerMw)} mW · ${"%.1f".format(r.energyPerInferenceMj)} mJ/inf",
                    color = Color(0xFF8FE3A0),
                )
            }
        }
    }
}

@Composable
private fun ScanResultCard(d: Decision) {
    val (verdict, tint) = when (d.action) {
        Action.BLOCK -> "BLOCKED — Graphic Content" to Color(0xFFFF6B6B)
        Action.BLUR_REVEAL -> "SHIELDED — Sensitive Content" to Color(0xFFFFD166)
        Action.SHOW -> "CLEAR — No Threat Detected" to Color(0xFF8FE3A0)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141925)),
        border = BorderStroke(1.dp, tint),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(verdict, style = MaterialTheme.typography.titleSmall, color = tint, fontWeight = FontWeight.Bold)
            Text("NSFW Probability Score: ${"%.1f".format(d.nsfw * 100)}%", color = Color.White)
            Text(
                "Analyzed locally via ExecuTorch. Zero bytes left the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF90A0B8),
            )
        }
    }
}

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


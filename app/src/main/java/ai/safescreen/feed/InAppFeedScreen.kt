package ai.safescreen.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.safescreen.SafeScreenEngine
import ai.safescreen.policy.Decision
import ai.safescreen.policy.ProtectionLevel
import ai.safescreen.policy.Severity
import ai.safescreen.render.ProtectedImage
import ai.safescreen.ui.HudState
import ai.safescreen.ui.SettingsDialog
import ai.safescreen.ui.TelemetryHud

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppFeedScreen(engine: SafeScreenEngine, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val items = remember { DemoContent.items(context) }
    val decisions = remember { mutableStateMapOf<String, Decision>() }
    var hud by remember {
        mutableStateOf(
            HudState(
                backend = engine.backend,
                usingModels = engine.usingModels,
                level = engine.currentLevel.title,
            ),
        )
    }
    var selectedLevel by remember { mutableStateOf(engine.currentLevel) }
    var showSettings by remember { mutableStateOf(false) }

    // Re-run analysis on level change
    LaunchedEffect(selectedLevel) {
        engine.setProtectionLevel(selectedLevel)
        items.forEach { item ->
            val analyzed = engine.analyze(item)
            decisions[item.id] = analyzed.decision
            hud = analyzed.hud
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Controlled Test Feed") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Protection level selector bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProtectionLevel.entries.forEach { lvl ->
                    FilterChip(
                        selected = selectedLevel == lvl,
                        onClick = { selectedLevel = lvl },
                        label = { Text(lvl.title) },
                    )
                }
            }

            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        FeedCard(item, decisions[item.id])
                    }
                }
                TelemetryHud(
                    hud,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
        }
    }

    if (showSettings) SettingsDialog(engine.thresholds) { showSettings = false }
}

@Composable
private fun FeedCard(item: FeedItem, decision: Decision?) {
    Column {
        ProtectedImage(item.bitmap, decision, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(item.caption, style = MaterialTheme.typography.bodyMedium)
        if (decision != null && decision.severity != Severity.NONE) {
            Text(
                decision.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}


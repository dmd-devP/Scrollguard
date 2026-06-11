package com.dmd.scrollguard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.dmd.scrollguard.data.SessionStore
import kotlinx.coroutines.launch

/**
 * One-screen config. Personal tool: function over beauty.
 */
class MainActivity : ComponentActivity() {

    private lateinit var store: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE0566D))) {
                val cfg by store.configFlow.collectAsState(initial = SessionStore.Config())
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Text("ScrollGuard", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Guards Instagram: intention gate, dim, check-ins, rest & block.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(16.dp))

                        // ---- required permissions ----
                        SectionTitle("Setup (required)")
                        OutlinedButton(onClick = { openOverlaySettings() }, Modifier.fillMaxWidth()) {
                            Text("1. Allow \"Display over other apps\"")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { openAccessibilitySettings() }, Modifier.fillMaxWidth()) {
                            Text("2. Enable ScrollGuard in Accessibility")
                        }

                        Spacer(Modifier.height(20.dp))
                        SectionTitle("Master")
                        SwitchRow("ScrollGuard enabled", cfg.enabled) { v ->
                            save { store.set(SessionStore.ENABLED, v) }
                        }

                        Spacer(Modifier.height(12.dp))
                        SectionTitle("Dim (less addictive colors)")
                        SwitchRow("Dim the feed", cfg.dimEnabled) { v ->
                            save { store.set(SessionStore.DIM_ENABLED, v) }
                        }
                        Text("Intensity: ${(cfg.dimIntensity * 100).toInt()}%")
                        Slider(
                            value = cfg.dimIntensity,
                            onValueChange = { v -> save { store.set(SessionStore.DIM_INTENSITY, v) } },
                            valueRange = 0.1f..0.8f
                        )
                        Text(
                            "Dim turns OFF automatically while you're posting (camera, editor, story).",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(Modifier.height(12.dp))
                        SectionTitle("Intention gate")
                        SwitchRow("Ask before opening Instagram", cfg.gateEnabled) { v ->
                            save { store.set(SessionStore.GATE_ENABLED, v) }
                        }
                        IntRow("Forced pause (seconds)", cfg.gatePauseSec, 1..10) { v ->
                            save { store.set(SessionStore.GATE_PAUSE_SEC, v) }
                        }
                        IntRow("Gate cooldown (minutes)", cfg.gateCooldownMin, 1..60) { v ->
                            save { store.set(SessionStore.GATE_COOLDOWN_MIN, v) }
                        }

                        Spacer(Modifier.height(12.dp))
                        SectionTitle("Check-in")
                        IntRow("Ask every (minutes of feed time)", cfg.checkinIntervalMin, 1..60) { v ->
                            save { store.set(SessionStore.CHECKIN_INTERVAL_MIN, v) }
                        }

                        Spacer(Modifier.height(12.dp))
                        SectionTitle("Rest")
                        IntRow("Block duration (minutes)", cfg.blockMinutes, 1..120) { v ->
                            save { store.set(SessionStore.BLOCK_MINUTES, v) }
                        }
                        var music by remember(cfg.musicUri) { mutableStateOf(cfg.musicUri) }
                        OutlinedTextField(
                            value = music,
                            onValueChange = { music = it },
                            label = { Text("Resting playlist link (Spotify/YT Music…)") },
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text("Share → Copy link in your music app, paste here. \"Rest music\" opens it and leaves Instagram.")
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { save { store.set(SessionStore.MUSIC_URI, music) } }) {
                            Text("Save playlist link")
                        }

                        Spacer(Modifier.height(28.dp))
                        Text(
                            "Offline by design — no internet permission; nothing leaves this phone.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(40.dp))
                    }
                }
            }
        }
    }

    private fun save(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun IntRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
    }
}

package com.omni.llm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.ui.theme.ACCENT_OPTIONS
import com.omni.llm.ui.theme.FONT_OPTIONS
import com.omni.llm.ui.theme.OmniPalette
import com.omni.llm.util.Formatters
import java.util.Locale
import kotlin.math.roundToInt

private data class ChangelogEntry(
    val version: String,
    val title: String,
    val date: String,
    val badge: String,
    val notes: List<String>,
)

private val BUILD_CHANGELOGS = listOf(
    ChangelogEntry(
        version = "v3.2",
        title = "Unified Obsidian/Frost V3, 200K Window & Core Customization",
        date = "2026-11-26",
        badge = "LATEST",
        notes = listOf(
            "Carried the V3 Telemetry Capsule & Timeline aesthetic cleanly across Vault (Models), Forge (Build), and Core (Settings).",
            "Fixed Light Mode contrast across all surfaces + added live Accent Color & Typography Font Pickers.",
            "Fixed Unrestricted Mode DataStore persistence so it stays enabled across reloads.",
            "Expanded Context Length & Max Answer Length up to 200,000 tokens with live RAM/limit warnings past 8,000 tokens.",
            "Redesigned About & Credits for chefnero2999 and introduced this Changelog viewer.",
        ),
    ),
    ChangelogEntry(
        version = "v3.1",
        title = "Obsidian Volt V3 Workspace & Multimodal Upload Engine",
        date = "2026-11-26",
        badge = "MAJOR",
        notes = listOf(
            "Fixed persistent cloud-mode 'No model is loaded' post-generation error in ChatViewModel.",
            "Replaced generic chat bubbles with a Vertical Timeline Spine, Obsidian Code Deck cards, and ▶ RUN LIVE artifacts.",
            "Added Image/Vision uploads (auto-routed to Qwen3-VL) + PDF/Document text extraction + Live Deep Web Search citations.",
            "Added Android TextToSpeech Read Aloud, Edit & Resend, and live Arena/Studio execution.",
        ),
    ),
    ChangelogEntry(
        version = "v2.0",
        title = "Autonomous Forge, Live Voice & Multi-Image Fleet",
        date = "2026-11-23",
        badge = "ENGINE",
        notes = listOf(
            "Added multi-model keyless image generator (Flux, Turbo, Sana, Realism, Anime, 3D) with persistence.",
            "Wired SpeechRecognizer live voice overlay and fixed idle WakeLock battery drain in LlmService.",
        ),
    ),
    ChangelogEntry(
        version = "v1.0",
        title = "OmniLLM Hybrid Foundation",
        date = "2026-11-18",
        badge = "BASE",
        notes = listOf(
            "Built isolated :llm inference service over llama.cpp (arm64-v8a SIMD + Vulkan) with keyless cloud router.",
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsViewModel.UiState,
    onContextLength: (Int) -> Unit,
    onGpuLayers: (Int) -> Unit,
    onThreads: (Int) -> Unit,
    onBatchSize: (Int) -> Unit,
    onMmap: (Boolean) -> Unit,
    onMlock: (Boolean) -> Unit,
    onFlashAttention: (Boolean) -> Unit,
    onQ8Kv: (Boolean) -> Unit,
    onSpeculativeEnabled: (Boolean) -> Unit,
    onDraftModel: (String?) -> Unit,
    onDraftTokens: (Int) -> Unit,
    onTemperature: (Float) -> Unit,
    onTopP: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onCloudEnabled: (Boolean) -> Unit,
    onAutoSwitchModels: (Boolean) -> Unit,
    onUnrestrictedMode: (Boolean) -> Unit,
    onThemeMode: (String) -> Unit = {},
    onAccentColor: (String) -> Unit = {},
    onFontFamily: (String) -> Unit = {},
    onApplyDefaults: () -> Unit,
    onPanic: () -> Unit,
    onClearCrashLog: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    // 0 = Controls, 1 = Changelog, 2 = Credits
    var subTab by remember { mutableStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    var confirmPanic by remember { mutableStateOf(false) }
    var confirmGpu by remember { mutableStateOf(false) }
    var promptEditing by remember { mutableStateOf(false) }
    var promptDraft by remember { mutableStateOf(state.settings.systemPrompt) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("CORE CONFIGURATION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("Engine, Appearance, Changelogs & Attribution", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickModePill("⚙️ Controls", active = subTab == 0, tint = MaterialTheme.colorScheme.primary, onClick = { subTab = 0 })
                    QuickModePill("📜 Changelog", active = subTab == 1, tint = MaterialTheme.colorScheme.secondary, onClick = { subTab = 1 })
                    QuickModePill("✨ About & Credits", active = subTab == 2, tint = OmniPalette.copper, onClick = { subTab = 2 })
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (subTab) {
                1 -> ChangelogPane()
                2 -> CreditsPane()
                else -> {
                    state.settings.pendingCrashNotice?.let { notice ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = OmniPalette.danger.copy(alpha = 0.14f)),
                            border = BorderStroke(1.dp, OmniPalette.danger.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Crash recovered", fontWeight = FontWeight.Bold, color = OmniPalette.danger)
                                Text(notice, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onDismissNotice) { Text("Got it") }
                            }
                        }
                    }

                    if (state.needsReload) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = OmniPalette.copper.copy(alpha = 0.14f)),
                            border = BorderStroke(1.dp, OmniPalette.copper.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Reload required", fontWeight = FontWeight.Bold, color = OmniPalette.copper)
                                Text(
                                    "Engine parameters apply on next model load in the Vault tab.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    // Appearance: Theme + Accent + Font Picker
                    SectionCard("Appearance & Typography") {
                        Text("Theme Mode", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("system" to "System", "dark" to "Obsidian Dark", "light" to "Frost Light").forEach { (mode, label) ->
                                val selected = state.themeMode == mode
                                if (selected) {
                                    Button(onClick = { onThemeMode(mode) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                        Text(label, fontSize = 11.sp, maxLines = 1)
                                    }
                                } else {
                                    OutlinedButton(onClick = { onThemeMode(mode) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                        Text(label, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                        Text("Accent Color", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ACCENT_OPTIONS.forEach { opt ->
                                val selected = state.accentColor == opt.id
                                val swatch = opt.darkPrimary
                                Surface(
                                    onClick = { onAccentColor(opt.id) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) swatch else MaterialTheme.colorScheme.outline),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            Modifier
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(swatch)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(opt.label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                        Text("Font Family", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FONT_OPTIONS.forEach { (id, label) ->
                                val selected = state.fontFamily == id
                                val previewFamily = when (id) {
                                    "sans" -> FontFamily.SansSerif
                                    "mono" -> FontFamily.Monospace
                                    "serif" -> FontFamily.Serif
                                    else -> FontFamily.Default
                                }
                                Surface(
                                    onClick = { onFontFamily(id) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                                ) {
                                    Text(
                                        label,
                                        fontFamily = previewFamily,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Device
                    SectionCard("Device Hardware") {
                        InfoRow("Model", state.profile.model)
                        InfoRow("RAM", String.format(Locale.US, "%.1f GB total · %.1f GB free", state.profile.totalRamGb, state.profile.availRamGb))
                        InfoRow("Cores", "${state.profile.cores} (${state.profile.performanceCores} performance)")
                        InfoRow("Thermals", containerThermalLabel(state))
                        InfoRow("GPU (Vulkan)", if (state.gpuAvailable) "Available" else "Not compiled")
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = onApplyDefaults, shape = RoundedCornerShape(10.dp)) {
                            Text("Reset to device defaults")
                        }
                    }

                    // Routing, Modes & Token Limits (up to 200,000!)
                    SectionCard("Inference & Token Limits") {
                        SwitchRow(
                            title = "Cloud fallback",
                            description = "Keyless cloud models (DeepSeek, Qwen3-VL, GPT-OSS) active when no local GGUF is loaded.",
                            checked = state.settings.cloudFallbackEnabled,
                            onChange = onCloudEnabled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SwitchRow(
                            title = "Auto-switch models",
                            description = "Automatically rotates to another free model if rate-limited.",
                            checked = state.settings.autoSwitchModels,
                            onChange = onAutoSwitchModels,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SwitchRow(
                            title = "Unrestricted mode",
                            description = "Unlocked by default. Uses unfiltered system prompt and prioritizes uncensored local models. Persisted cleanly.",
                            checked = state.settings.unrestrictedMode,
                            onChange = onUnrestrictedMode,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                        SliderRow(
                            title = "Temperature",
                            description = "Higher = more creative, lower = more deterministic.",
                            value = state.settings.temperature,
                            valueRange = 0f..1.5f,
                            steps = 14,
                            label = Formatters.compact(state.settings.temperature),
                            onChange = onTemperature,
                        )
                        SliderRow(
                            title = "Top-P",
                            description = "Nucleus sampling cutoff (default 0.9).",
                            value = state.settings.topP,
                            valueRange = 0.1f..1f,
                            steps = 8,
                            label = Formatters.compact(state.settings.topP),
                            onChange = onTopP,
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                        // Max Answer Length up to 200,000
                        SliderRow(
                            title = "Max answer length",
                            description = "Output token cap per response (up to 200,000).",
                            value = state.settings.maxTokens.toFloat(),
                            valueRange = 256f..200_000f,
                            steps = 0,
                            label = "${state.settings.maxTokens} tokens",
                            onChange = { onMaxTokens(((it / 256f).roundToInt() * 256).coerceIn(256, 200_000)) },
                        )
                        TokenPresetRow(
                            presets = listOf(1024 to "1K", 4096 to "4K", 8000 to "8K", 32000 to "32K", 128000 to "128K", 200000 to "200K"),
                            current = state.settings.maxTokens,
                            onSelect = onMaxTokens,
                        )
                        if (state.settings.maxTokens > 8_000) {
                            TokenWarningBanner("Max answer length is ${state.settings.maxTokens} (> 8,000 tokens). Some free cloud providers enforce lower output caps or may take longer to finish.")
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                        // Context Length up to 200,000
                        SliderRow(
                            title = "Context length",
                            description = "Total prompt + answer window (up to 200,000 tokens). Estimated local KV: ${Formatters.bytes(state.kvEstimateBytes)}.",
                            value = state.settings.contextLength.toFloat(),
                            valueRange = 512f..200_000f,
                            steps = 0,
                            label = "${state.settings.contextLength} tok",
                            onChange = { onContextLength(((it / 512f).roundToInt() * 512).coerceIn(512, 200_000)) },
                        )
                        TokenPresetRow(
                            presets = listOf(2048 to "2K", 4096 to "4K", 8000 to "8K", 32000 to "32K", 128000 to "128K", 200000 to "200K"),
                            current = state.settings.contextLength,
                            onSelect = onContextLength,
                        )
                        if (state.settings.contextLength > 8_000) {
                            TokenWarningBanner("Context length is ${state.settings.contextLength} (> 8,000 tokens). Recommended for long cloud models (DeepSeek V4.1 1M / Gemini); high values on local GGUF can trigger Android OOM kills.")
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("System prompt", fontWeight = FontWeight.Medium)
                                Text(
                                    state.settings.systemPrompt.take(90) + if (state.settings.systemPrompt.length > 90) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { promptDraft = state.settings.systemPrompt; promptEditing = true }) {
                                Text("Edit")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { advanced = !advanced },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (advanced) "Hide Engine Danger Zone" else "Engine Tuning (Danger Zone)")
                        Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }

                    AnimatedVisibility(visible = advanced) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionCard("Memory & KV Cache") {
                                SwitchRow(
                                    title = "Memory-map the model (mmap)",
                                    description = "Zero-copy file-backed weights. Keeps OOM killer away.",
                                    checked = state.settings.useMmap,
                                    onChange = onMmap,
                                )
                                SwitchRow(
                                    title = "Lock model in RAM (mlock)",
                                    description = "Pins weights in RAM. Disable on 4–6 GB devices.",
                                    checked = state.settings.useMlock,
                                    onChange = onMlock,
                                )
                                SwitchRow(
                                    title = "Quantised KV cache (Q8)",
                                    description = "Halves KV cache RAM when Flash Attention is active.",
                                    checked = state.settings.q8KvCache,
                                    onChange = onQ8Kv,
                                )
                            }

                            SectionCard("Speculative Decoding") {
                                SwitchRow(
                                    title = "Use a draft model",
                                    description = "Small draft model proposes tokens for faster verification.",
                                    checked = state.settings.speculativeEnabled,
                                    onChange = onSpeculativeEnabled,
                                )
                                if (state.settings.speculativeEnabled) {
                                    val chosen = state.settings.draftModelId
                                    if (state.draftCandidates.isEmpty()) {
                                        Text("Download a smaller GGUF in Vault first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        state.draftCandidates.forEach { entry ->
                                            val selected = entry.id == chosen
                                            Row(
                                                Modifier.fillMaxWidth().clickable { onDraftModel(if (selected) null else entry.id) },
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                RadioButton(selected = selected, onClick = { onDraftModel(if (selected) null else entry.id) })
                                                Column(Modifier.weight(1f)) {
                                                    Text(entry.name, fontSize = 13.sp)
                                                    Text("${entry.quantization} · ${Formatters.bytes(entry.fileSizeBytes)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                    SliderRow(
                                        title = "Draft tokens per pass",
                                        description = "Candidates proposed per verification pass.",
                                        value = state.settings.draftTokens.toFloat(),
                                        valueRange = 2f..8f,
                                        steps = 5,
                                        label = "${state.settings.draftTokens} tokens",
                                        onChange = { onDraftTokens(it.roundToInt()) },
                                    )
                                }
                            }

                            SectionCard("Hardware Acceleration") {
                                SliderRow(
                                    title = "GPU offload layers",
                                    description = if (!state.gpuAvailable) "Vulkan backend unavailable on this runtime; CPU mode enforced." else "Vulkan GPU layers (increase by 5 safely).",
                                    value = state.settings.gpuLayers.toFloat(),
                                    valueRange = 0f..(state.caps.maxGpuLayers.coerceAtLeast(1)).toFloat(),
                                    steps = ((state.caps.maxGpuLayers.coerceAtLeast(1) / 5) - 1).coerceAtLeast(0),
                                    label = "${state.settings.gpuLayers} / ${state.caps.maxGpuLayers}",
                                    onChange = { raw ->
                                        val snapped = ((raw / 5f).roundToInt() * 5).coerceIn(0, state.caps.maxGpuLayers.coerceAtLeast(0))
                                        if (snapped > 0 && !state.gpuAvailable) confirmGpu = true else onGpuLayers(snapped)
                                    },
                                    enabled = state.gpuAvailable,
                                )
                                SwitchRow(
                                    title = "Flash attention",
                                    description = "Disabled automatically when GPU layers > 0.",
                                    checked = state.settings.flashAttention,
                                    onChange = onFlashAttention,
                                    enabled = state.settings.gpuLayers == 0,
                                )
                                SliderRow(
                                    title = "CPU Threads",
                                    description = "Performance threads for local GGUF generation.",
                                    value = state.settings.threads.toFloat(),
                                    valueRange = 1f..SettingsViewModel.maxSliderThreads(state.profile).toFloat(),
                                    steps = (SettingsViewModel.maxSliderThreads(state.profile) - 2).coerceAtLeast(0),
                                    label = "${state.settings.threads} threads",
                                    onChange = { onThreads(it.roundToInt()) },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SettingsViewModel.BATCH_STEPS.forEach { size ->
                                        val selected = state.settings.batchSize == size
                                        Button(
                                            onClick = { onBatchSize(size) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                        ) { Text(size.toString(), fontSize = 12.sp) }
                                    }
                                }
                            }

                            SectionCard("Diagnostics & Recovery") {
                                Text(state.backendReport, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.crashLog.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(state.crashLog, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    TextButton(onClick = onClearCrashLog) { Text("Clear crash log") }
                                }
                                Button(
                                    onClick = { confirmPanic = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = OmniPalette.danger, contentColor = Color.White),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                ) { Text("Panic Reset (CPU Safe Defaults)") }
                            }
                        }
                    }

                    state.info?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirmPanic) {
        AlertDialog(
            onDismissRequest = { confirmPanic = false },
            title = { Text("Unload and reset?") },
            text = { Text("Unloads the model and resets expert settings to CPU-safe defaults. Files on disk are kept.") },
            confirmButton = { TextButton(onClick = { confirmPanic = false; onPanic() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmPanic = false }) { Text("Cancel") } },
        )
    }

    if (confirmGpu) {
        AlertDialog(
            onDismissRequest = { confirmGpu = false },
            title = { Text("Vulkan backend unavailable") },
            text = { Text("No compatible Vulkan backend detected on this device; running CPU-only.") },
            confirmButton = { TextButton(onClick = { confirmGpu = false }) { Text("OK") } },
        )
    }

    if (promptEditing) {
        AlertDialog(
            onDismissRequest = { promptEditing = false },
            title = { Text("System prompt") },
            text = {
                OutlinedTextField(
                    value = promptDraft,
                    onValueChange = { promptDraft = it },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { promptEditing = false; onSystemPrompt(promptDraft) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { promptEditing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChangelogPane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BUILD_CHANGELOGS.forEachIndexed { index, entry ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, if (index == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    entry.version,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = if (index == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(entry.badge, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        }
                        Text(entry.date, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    entry.notes.forEach { note ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("▸ ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CreditsPane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Creator Hero Badge
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("APP CREATOR & LEAD DESIGNER", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Text("chefnero2999", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Conceived, directed, and designed OmniLLM Studio — including the Obsidian Volt V3 UI system, multimodal command dock, Forge app studio workflow, and hybrid keyless routing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
            }
        }

        SectionCard("Open-Source Runtime & Infrastructure") {
            InfoRow("Local Inference Core", "llama.cpp (open-source community)")
            InfoRow("Cloud Inference Routing", "Public keyless open-weight endpoints")
            InfoRow("Image Generation Fleet", "Pollinations / Flux / Sana")
            InfoRow("Target ABI", "arm64-v8a (SIMD + Vulkan)")
            InfoRow("App License", "Apache 2.0")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TokenPresetRow(
    presets: List<Pair<Int, String>>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        presets.forEach { (value, label) ->
            val selected = current == value
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(8.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            ) {
                Text(
                    label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TokenWarningBanner(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = OmniPalette.copper.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, OmniPalette.copper.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = OmniPalette.copper, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 15.sp)
        }
    }
}

private fun containerThermalLabel(state: SettingsViewModel.UiState): String {
    val status = state.profile.thermalStatus
    return when {
        status >= 4 -> "Critical ($status)"
        status >= 3 -> "Severe ($status)"
        status >= 2 -> "Moderate ($status) — threads halved"
        status >= 1 -> "Light ($status)"
        else -> "Nominal"
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title.uppercase(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: String,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps.coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

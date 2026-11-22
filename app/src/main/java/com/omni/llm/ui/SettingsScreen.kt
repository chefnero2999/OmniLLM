package com.omni.llm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.ui.theme.OmniPalette
import com.omni.llm.util.Formatters
import kotlin.math.roundToInt

/**
 * Two-level settings: the toggles a normal person needs on top, the knobs that can crash the app
 * behind "Advanced (Danger Zone)". Every control carries a one-line plain-English trade-off.
 */
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
    onApplyDefaults: () -> Unit,
    onPanic: () -> Unit,
    onClearCrashLog: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }
    var confirmPanic by remember { mutableStateOf(false) }
    var confirmGpu by remember { mutableStateOf(false) }
    var promptEditing by remember { mutableStateOf(false) }
    var promptDraft by remember { mutableStateOf(state.settings.systemPrompt) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.settings.pendingCrashNotice?.let { notice ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = OmniPalette.danger.copy(alpha = 0.16f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Crash recovered", fontWeight = FontWeight.SemiBold)
                        Text(notice, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onDismissNotice) { Text("Got it") }
                    }
                }
            }

            if (state.needsReload) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = OmniPalette.warn.copy(alpha = 0.16f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Reload required", fontWeight = FontWeight.SemiBold, color = OmniPalette.warn)
                        Text(
                            "Context length, threads, batch, GPU layers and mmap only apply when the model " +
                                "is loaded. Unload and load it again in the Models tab to apply them.",
                            style = MaterialTheme.typography.bodyMedium, fontSize = 12.sp,
                        )
                    }
                }
            }

            SectionCard("Appearance") {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                )
                Text(
                    "Choose between dark mode, light mode, or match your device system setting.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val currentMode = state.themeMode
                    listOf("system" to "System", "dark" to "Dark", "light" to "Light").forEach { (mode, label) ->
                        val selected = currentMode == mode
                        if (selected) {
                            Button(
                                onClick = { onThemeMode(mode) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(label, fontSize = 13.sp)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onThemeMode(mode) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            SectionCard("Device") {
                InfoRow("Model", state.profile.model)
                InfoRow("RAM", String.format("%.1f GB total · %.1f GB free", state.profile.totalRamGb, state.profile.availRamGb))
                InfoRow("Cores", "${state.profile.cores} (${state.profile.performanceCores} performance)")
                InfoRow("Thermals", containerThermalLabel(state))
                InfoRow("GPU (Vulkan)", if (state.gpuAvailable) "available" else "not compiled in this build")
                Text(
                    "Defaults for this device: ${state.defaults.contextLength} ctx · ${state.defaults.gpuLayers} GPU layers · " +
                        "${state.defaults.threads} threads",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onApplyDefaults) { Text("Reset to device defaults") }
            }

            SectionCard("Everyday") {
                SwitchRow(
                    title = "Cloud fallback",
                    description = "Off by default. When on and no local model is loaded, prompts go to a keyless provider. " +
                        "Nothing is sent while local inference is active.",
                    checked = state.settings.cloudFallbackEnabled,
                    onChange = onCloudEnabled,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                SwitchRow(
                    title = "Auto-switch models",
                    description = "When the model you picked is rate-limited, out of free quota, " +
                        "or simply broken, move to the next free model and keep going. In Chat it " +
                        "continues the conversation; in Build it resumes mid-task from the files " +
                        "already written.",
                    checked = state.settings.autoSwitchModels,
                    onChange = onAutoSwitchModels,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                SwitchRow(
                    title = "Unrestricted mode",
                    description = "Removes OmniLLM's own hedging from the system prompt and puts " +
                        "unfiltered models first in the picker. Be clear on the limit: this cannot " +
                        "turn off a cloud provider's server-side filter — only the provider can. " +
                        "Local uncensored models (Models tab → Uncensored) have no filter at all, " +
                        "because nothing leaves the phone.",
                    checked = state.settings.unrestrictedMode,
                    onChange = onUnrestrictedMode,
                )

                if (state.settings.cloudFallbackEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Text(
                        "Which cloud model to use now lives in the Chat tab — tap the model name at " +
                            "the top. Each model there keeps its own conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = OmniPalette.info,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                SliderRow(
                    title = "Temperature",
                    description = "Higher = more creative, lower = more deterministic. 0.7 is a good default.",
                    value = state.settings.temperature,
                    valueRange = 0f..1.5f,
                    steps = 14,
                    label = Formatters.compact(state.settings.temperature),
                    onChange = onTemperature,
                )
                SliderRow(
                    title = "Top-P",
                    description = "Nucleus sampling cutoff. Lower narrows the word pool; 0.9 is a " +
                        "good default. This was stored but had no control until now.",
                    value = state.settings.topP,
                    valueRange = 0.1f..1f,
                    steps = 8,
                    label = Formatters.compact(state.settings.topP),
                    onChange = onTopP,
                )
                SliderRow(
                    title = "Max answer length",
                    description = "Hard cap on generated tokens per reply. Lower is faster and safer.",
                    value = state.settings.maxTokens.toFloat(),
                    valueRange = 64f..2048f,
                    steps = 30,
                    label = "${state.settings.maxTokens} tokens",
                    onChange = { onMaxTokens(it.roundToInt()) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("System prompt", fontWeight = FontWeight.Medium)
                        Text(
                            state.settings.systemPrompt.take(90) + if (state.settings.systemPrompt.length > 90) "…" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
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
            ) {
                Text(if (advanced) "Hide advanced settings" else "Advanced (Danger Zone)")
                Icon(
                    if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }

            AnimatedVisibility(visible = advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard("Context & memory") {
                        SliderRow(
                            title = "Context length",
                            description = "Total tokens the model can hold (prompt + answer). " +
                                "Estimated KV cache: ${Formatters.bytes(state.kvEstimateBytes)}. " +
                                "Bigger context = more RAM and slower prompt processing.",
                            value = state.settings.contextLength.toFloat(),
                            valueRange = 512f..state.caps.maxContext.toFloat(),
                            steps = (state.caps.maxContext / 512) - 1,
                            label = "${state.settings.contextLength} tokens ≈ ${Formatters.bytes(state.kvEstimateBytes)} KV",
                            onChange = { raw ->
                                val snapped = ((raw / 512f).roundToInt() * 512).coerceIn(512, state.caps.maxContext)
                                onContextLength(snapped)
                            },
                        )
                        SwitchRow(
                            title = "Memory-map the model (mmap)",
                            description = "Zero-copy file-backed weights instead of a private heap copy. " +
                                "This is the single biggest protection against the Low Memory Killer. Leave it on.",
                            checked = state.settings.useMmap,
                            onChange = onMmap,
                        )
                        SwitchRow(
                            title = "Lock model in RAM (mlock)",
                            description = "Stops the kernel evicting weights, so generation does not stall on I/O. " +
                                "Uses more memory and can trigger the killer on 4 GB phones.",
                            checked = state.settings.useMlock,
                            onChange = onMlock,
                        )
                        SwitchRow(
                            title = "Quantised KV cache (Q8)",
                            description = "Halves KV cache RAM. Requires flash attention; may reduce answer quality slightly.",
                            checked = state.settings.q8KvCache,
                            onChange = onQ8Kv,
                        )
                    }

                    SectionCard("Speculative decoding") {
                        SwitchRow(
                            title = "Use a draft model",
                            description = "A small model guesses the next few tokens, the main model checks them " +
                                "in one pass. Only pays off when the draft is much cheaper than the target and the " +
                                "phone has RAM for both — on many phones this is SLOWER, so measure before keeping it. " +
                                "Off by default.",
                            checked = state.settings.speculativeEnabled,
                            onChange = onSpeculativeEnabled,
                        )
                        if (state.settings.speculativeEnabled) {
                            val chosen = state.settings.draftModelId
                            if (state.draftCandidates.isEmpty()) {
                                Text(
                                    "No other downloaded model is available. Download a smaller model first " +
                                        "and it will appear here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "Draft model", fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                )
                                state.draftCandidates.forEach { entry ->
                                    val selected = entry.id == chosen
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onDraftModel(if (selected) null else entry.id) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = selected, onClick = {
                                            onDraftModel(if (selected) null else entry.id)
                                        })
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.name, fontSize = 13.sp)
                                            Text(
                                                "${entry.quantization} · ${Formatters.bytes(entry.fileSizeBytes)}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            SliderRow(
                                title = "Draft tokens per pass",
                                description = "How many tokens the draft proposes before the main model verifies. " +
                                    "More tokens = bigger potential win but more wasted work when the guesses are wrong.",
                                value = state.settings.draftTokens.toFloat(),
                                valueRange = 2f..8f,
                                steps = 5,
                                label = "${state.settings.draftTokens} tokens",
                                onChange = { onDraftTokens(it.roundToInt()) },
                            )
                            when {
                                state.draftChecking -> Text(
                                    "Checking compatibility…", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                state.draftVerdict != null -> Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = OmniPalette.warn.copy(alpha = 0.16f),
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text("This pair will not work", fontWeight = FontWeight.SemiBold, color = OmniPalette.warn)
                                        Text(state.draftVerdict, fontSize = 12.sp)
                                        Text(
                                            "The model still loads — it just runs without the draft.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SectionCard("Speed") {
                        SliderRow(
                            title = "GPU offload layers",
                            description = if (!state.gpuAvailable)
                                "UNAVAILABLE in this build: the native library was compiled CPU-only " +
                                    "(-Pomni.vulkan=true is required at build time). The slider is disabled " +
                                    "rather than pretending to work."
                            else
                                "Layers computed on the Vulkan GPU. 0 = CPU only (default, safest). " +
                                    "Raise in steps of 5 and check the tok/s in Chat. If the app crashes, the " +
                                    "next launch returns to your last stable value automatically.",
                            value = state.settings.gpuLayers.toFloat(),
                            valueRange = 0f..(state.caps.maxGpuLayers.coerceAtLeast(1)).toFloat(),
                            steps = ((state.caps.maxGpuLayers.coerceAtLeast(1) / 5) - 1).coerceAtLeast(0),
                            label = if (!state.gpuAvailable) "0 (no Vulkan backend in this build)"
                            else "${state.settings.gpuLayers} / ${state.caps.maxGpuLayers} layers",
                            onChange = { raw ->
                                val snapped = ((raw / 5f).roundToInt() * 5)
                                    .coerceIn(0, state.caps.maxGpuLayers.coerceAtLeast(0))
                                if (snapped > 0 && !state.gpuAvailable) confirmGpu = true else onGpuLayers(snapped)
                            },
                            enabled = state.gpuAvailable,
                        )
                        SwitchRow(
                            title = "Flash attention",
                            description = "Faster attention with less KV memory — but llama.cpp on Android forces it " +
                                "OFF whenever GPU layers are used. Automatic.",
                            checked = state.settings.flashAttention,
                            onChange = onFlashAttention,
                            enabled = state.settings.gpuLayers == 0,
                        )
                        SliderRow(
                            title = "Threads",
                            description = "CPU threads used for generation. More is not always faster: on big.LITTLE " +
                                "SoCs the little cores become the bottleneck and drain the battery.",
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
                                        containerColor = if (selected) OmniPalette.ok else OmniPalette.surfaceHigh,
                                        contentColor = if (selected) OmniPalette.ink
                                        else MaterialTheme.colorScheme.onSurface,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                ) { Text(size.toString(), fontSize = 12.sp) }
                            }
                        }
                        Text(
                            "Batch size: prompt tokens processed per pass. Larger = faster prefill, more RAM.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (state.warnings.isNotEmpty()) {
                        SectionCard("Active limits") {
                            state.warnings.forEach {
                                Text("• $it", style = MaterialTheme.typography.bodyMedium, fontSize = 12.sp, color = OmniPalette.warn)
                            }
                        }
                    }

                    SectionCard("Diagnostics") {
                        Text(
                            state.backendReport,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.crashLog.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Crash signatures", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(
                                state.crashLog,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onClearCrashLog) { Text("Clear log") }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = OmniPalette.danger.copy(alpha = 0.14f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Panic button", fontWeight = FontWeight.SemiBold, color = OmniPalette.danger)
                            Text(
                                "Unloads the model, clears the KV cache and resets to CPU-only safe defaults. " +
                                    "Use this if the phone is hot, slow, or behaving strangely.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { confirmPanic = true },
                                colors = ButtonDefaults.buttonColors(containerColor = OmniPalette.danger),
                            ) { Text("Unload everything & reset") }
                        }
                    }
                }
            }

            SectionCard("About & Credits") {
                Text(
                    "OmniLLM Studio",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    color = OmniPalette.ok,
                )
                Text(
                    "Autonomous on-device & keyless cloud agent workstation for Android.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                InfoRow("Author / Creator", "chefnero2999")
                InfoRow("Architecture", "arm64-v8a (SIMD + Vulkan)")
                InfoRow("Engine", "llama.cpp + keyless cloud router")
                InfoRow("License", "Apache 2.0")
            }

            state.info?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = OmniPalette.ok, fontSize = 12.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmPanic) {
        AlertDialog(
            onDismissRequest = { confirmPanic = false },
            title = { Text("Unload and reset?") },
            text = { Text("The model will be unloaded and all expert settings returned to CPU-only safe defaults. Your downloaded files are kept.") },
            confirmButton = {
                TextButton(onClick = { confirmPanic = false; onPanic() }) { Text("Do it") }
            },
            dismissButton = { TextButton(onClick = { confirmPanic = false }) { Text("Cancel") } },
        )
    }

    if (confirmGpu) {
        AlertDialog(
            onDismissRequest = { confirmGpu = false },
            title = { Text("No Vulkan backend in this build") },
            text = {
                Text(
                    "This APK was compiled without the Vulkan backend, so GPU offload cannot run. " +
                        "Rebuild with ./gradlew assembleRelease -Pomni.vulkan=true on a machine with the " +
                        "NDK's glslc available. For now, CPU-only is the safe choice."
                )
            },
            confirmButton = { TextButton(onClick = { confirmGpu = false }) { Text("OK") } },
        )
    }

    if (promptEditing) {
        AlertDialog(
            onDismissRequest = { promptEditing = false },
            title = { Text("System prompt") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = promptDraft,
                    onValueChange = { promptDraft = it },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { promptEditing = false; onSystemPrompt(promptDraft) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { promptEditing = false }) { Text("Cancel") } },
        )
    }
}

private fun containerThermalLabel(state: SettingsViewModel.UiState): String {
    val status = state.profile.thermalStatus
    return when {
        status >= 4 -> "critical ($status)"
        status >= 3 -> "severe ($status)"
        status >= 2 -> "moderate ($status) — threads auto-halve"
        status >= 1 -> "light ($status)"
        else -> "nominal"
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OmniPalette.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = OmniPalette.info)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = OmniPalette.ok, fontSize = 11.sp)
        }
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps.coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

package com.omni.llm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.ui.theme.OmniPalette

/**
 * Real Google AI Studio Canvas:
 * System Prompt engineering playground with real-time temperature, top-P, and max token sliders
 * wired to [ChatViewModel.runStudioInference].
 */
@Composable
fun AiStudioCanvasScreen(
    state: ChatViewModel.UiState,
    onRunInference: (sys: String, user: String, temp: Float, topP: Float, maxTokens: Int) -> Unit,
    onStopInference: () -> Unit,
) {
    var systemPrompt by remember {
        mutableStateOf("You are an expert software architect. Respond directly with clean, production-ready answers.")
    }
    var userPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var topP by remember { mutableFloatStateOf(0.95f) }
    var maxOutputTokens by remember { mutableFloatStateOf(2048f) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Tune, contentDescription = null, tint = OmniPalette.geminiBlue, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "AI Studio Canvas",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Active model: ${state.activeModel?.name ?: "Cloud Default"} · live parameters",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            border = BorderStroke(1.dp, OmniPalette.geminiBlue.copy(alpha = 0.35f)),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "System Instructions",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = OmniPalette.geminiBlue,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !state.studioRunning,
                    maxLines = 4,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = userPrompt,
            onValueChange = { userPrompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("User Prompt") },
            placeholder = { Text("Enter a prompt to test with custom temperature & system rules...") },
            shape = RoundedCornerShape(12.dp),
            enabled = !state.studioRunning,
            maxLines = 4,
        )

        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Model Parameters",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Temperature", style = MaterialTheme.typography.bodySmall)
                    Text(String.format("%.2f", temperature), fontWeight = FontWeight.Bold, color = OmniPalette.ok)
                }
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    enabled = !state.studioRunning,
                    colors = SliderDefaults.colors(thumbColor = OmniPalette.ok, activeTrackColor = OmniPalette.ok),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Top-P", style = MaterialTheme.typography.bodySmall)
                    Text(String.format("%.2f", topP), fontWeight = FontWeight.Bold, color = OmniPalette.info)
                }
                Slider(
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0f..1f,
                    enabled = !state.studioRunning,
                    colors = SliderDefaults.colors(thumbColor = OmniPalette.info, activeTrackColor = OmniPalette.info),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Max Output Tokens", style = MaterialTheme.typography.bodySmall)
                    Text("${maxOutputTokens.toInt()}", fontWeight = FontWeight.Bold, color = OmniPalette.geminiPurple)
                }
                Slider(
                    value = maxOutputTokens,
                    onValueChange = { maxOutputTokens = it },
                    valueRange = 256f..8192f,
                    steps = 15,
                    enabled = !state.studioRunning,
                    colors = SliderDefaults.colors(thumbColor = OmniPalette.geminiPurple, activeTrackColor = OmniPalette.geminiPurple),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onRunInference(systemPrompt, userPrompt, temperature, topP, maxOutputTokens.toInt())
                },
                enabled = userPrompt.isNotBlank() && !state.studioRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OmniPalette.geminiBlue,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (state.studioRunning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Running...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run Inference", fontWeight = FontWeight.Bold)
                }
            }
            if (state.studioRunning) {
                OutlinedButton(onClick = onStopInference, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        state.studioError?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = OmniPalette.danger.copy(alpha = 0.16f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(err, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.studioOutput.isNotBlank() || state.studioStats.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, OmniPalette.geminiBlue.copy(alpha = 0.4f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "Model Output",
                            style = MaterialTheme.typography.labelSmall,
                            color = OmniPalette.geminiBlue,
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.studioStats.isNotBlank()) {
                            Text(
                                state.studioStats,
                                style = MaterialTheme.typography.labelSmall,
                                color = OmniPalette.ok,
                                fontSize = 10.sp,
                            )
                        }
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                        IconButton(
                            onClick = {
                                clip.setText(androidx.compose.ui.text.AnnotatedString(state.studioOutput))
                                android.widget.Toast.makeText(ctx, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy output", modifier = Modifier.size(14.dp), tint = Color(0xFFE2E8F0))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.studioOutput.ifBlank { "Waiting for tokens..." },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFE2E8F0),
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

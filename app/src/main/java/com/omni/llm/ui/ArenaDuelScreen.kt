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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.ui.theme.OmniPalette

/**
 * Real LMSYS-style Blind Model Duel:
 * Sends one prompt to two distinct models in parallel via [ChatViewModel.startArenaDuel],
 * streams both answers live with real tok/s speedometers, and reveals identities on vote.
 */
@Composable
fun ArenaDuelScreen(
    state: ChatViewModel.UiState,
    onStartDuel: (String) -> Unit,
    onStopDuel: () -> Unit,
    onVote: (String) -> Unit,
) {
    var prompt by remember { mutableStateOf(state.arenaPrompt) }
    val hasStarted = state.arenaRunning || state.arenaResponseA.isNotBlank() || state.arenaResponseB.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚔️", fontSize = 22.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "LMSYS Arena Duel",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Live parallel blind comparison with real streaming speed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        state.arenaError?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = OmniPalette.danger.copy(alpha = 0.16f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(err, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Enter a prompt to duel two models") },
            placeholder = { Text("e.g. Explain quantum entanglement like I'm 12, or write a quick sort in Kotlin") },
            shape = RoundedCornerShape(14.dp),
            enabled = !state.arenaRunning,
            maxLines = 4,
        )

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onStartDuel(prompt) },
                enabled = prompt.isNotBlank() && !state.arenaRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OmniPalette.ok,
                    contentColor = Color.Black,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (state.arenaRunning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Dueling...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start Blind Duel", fontWeight = FontWeight.Bold)
                }
            }
            if (state.arenaRunning) {
                OutlinedButton(
                    onClick = onStopDuel,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (hasStarted) {
            Text(
                if (state.arenaRunning) "Streaming live · Identities hidden until you vote"
                else if (state.arenaVoted != null) "Identities Revealed"
                else "Duel complete · Vote for the better response",
                style = MaterialTheme.typography.labelMedium,
                color = OmniPalette.info,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            DuelModelCard(
                title = if (state.arenaVoted != null) "Model A: ${state.arenaModelAName}" else "Model A (Anonymous)",
                response = state.arenaResponseA.ifBlank { if (state.arenaRunning) "Waiting for first token..." else "(empty)" },
                speed = String.format("%.1f tok/s", state.arenaTpsA),
                isWinner = state.arenaVoted == "A",
                canVote = !state.arenaRunning && state.arenaVoted == null,
                voted = state.arenaVoted != null,
                onVote = { onVote("A") },
            )

            Spacer(Modifier.height(12.dp))

            DuelModelCard(
                title = if (state.arenaVoted != null) "Model B: ${state.arenaModelBName}" else "Model B (Anonymous)",
                response = state.arenaResponseB.ifBlank { if (state.arenaRunning) "Waiting for first token..." else "(empty)" },
                speed = String.format("%.1f tok/s", state.arenaTpsB),
                isWinner = state.arenaVoted == "B",
                canVote = !state.arenaRunning && state.arenaVoted == null,
                voted = state.arenaVoted != null,
                onVote = { onVote("B") },
            )

            if (state.arenaVoted != null) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = OmniPalette.ok.copy(alpha = 0.15f)),
                    border = BorderStroke(1.dp, OmniPalette.ok.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = OmniPalette.ok)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Vote recorded for Model ${state.arenaVoted}! Both model identities are now revealed.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DuelModelCard(
    title: String,
    response: String,
    speed: String,
    isWinner: Boolean,
    canVote: Boolean,
    voted: Boolean,
    onVote: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (isWinner) OmniPalette.ok else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isWinner) OmniPalette.ok.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isWinner) OmniPalette.ok else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = OmniPalette.info.copy(alpha = 0.15f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = OmniPalette.info, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(speed, fontSize = 10.sp, color = OmniPalette.info, fontWeight = FontWeight.Bold)
                    }
                }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                IconButton(
                    onClick = {
                        clip.setText(androidx.compose.ui.text.AnnotatedString(response))
                        android.widget.Toast.makeText(ctx, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy response", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                response,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(10.dp))

            if (canVote) {
                Button(
                    onClick = onVote,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("Vote: This answer is better 🏆", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (voted && isWinner) {
                Text("🏆 Voted Better", color = OmniPalette.ok, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

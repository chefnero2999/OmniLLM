package com.omni.llm.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.ui.theme.OmniPalette

/**
 * First launch. Three buttons, no fields, no consent wall, no account.
 * This is the entire onboarding.
 */
@Composable
fun WelcomeScreen(
    deviceSummary: String,
    onDownloadModel: () -> Unit,
    onEnableCloud: () -> Unit,
    onImport: () -> Unit,
    cloudEnabled: Boolean,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(OmniPalette.ok.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("LLM", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OmniPalette.ok)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("OmniLLM", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Local-first language models on your phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = OmniPalette.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = OmniPalette.ok)
                    Spacer(Modifier.width(8.dp))
                    Text("No account. No API key. Ever.", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "The model runs on this device. In local mode your prompts never leave the phone, " +
                        "and the app works with the network switched off.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    deviceSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = OmniPalette.info,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onDownloadModel,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Download a Model", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onImport,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Filled.Upload, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Import GGUF")
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onEnableCloud,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OmniPalette.info),
        ) {
            Icon(Icons.Filled.Cloud, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(if (cloudEnabled) "Cloud fallback ON (tap to disable)" else "Enable Cloud Fallback (optional)")
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Cloud fallback is off by default and only used when no local model is loaded. " +
                "It uses keyless public endpoints and never asks you for a key.",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

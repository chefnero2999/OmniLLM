package com.omni.llm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.omni.llm.data.ModelDownloadManager
import com.omni.llm.ui.theme.OmniPalette
import com.omni.llm.util.Formatters

/** Categories shown in this tab, in priority order, each with its one-line purpose. */
private val MODEL_GROUPS = listOf(
    Triple("everyday", "Everyday", "General chat: questions, writing, summaries"),
    Triple("coding", "Coding", "Writing and fixing code offline"),
    Triple("reasoning", "Reasoning", "Thinks before answering — slower, better at hard problems"),
    Triple(
        "uncensored",
        "Uncensored",
        "Refusal direction removed from the weights. Runs on the phone, so there is no server-side " +
            "filter to argue with. Turn on Unrestricted mode in Settings to put these first.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    state: ModelsViewModel.UiState,
    onDownload: (com.omni.llm.data.ModelEntry) -> Unit,
    onDelete: (com.omni.llm.data.ModelEntry) -> Unit,
    onLoad: (com.omni.llm.data.ModelEntry) -> Unit,
    onLoadImported: (java.io.File) -> Unit,
    onUnload: () -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onDownloadUrl: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    var urlDialog by remember { mutableStateOf(false) }
    var urlDraft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh manifest")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DeviceSummaryCard(state)
            }

            state.error?.let { error ->
                item { NoticeCard(error, isError = true, onDismiss = onDismiss) }
            }
            state.message?.let { message ->
                item { NoticeCard(message, isError = false, onDismiss = onDismiss) }
            }

            item {
                Text(
                    "Curated set · Q4_K_M only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Grouped by what the model is for, in the same order the cloud picker uses. A flat
            // list of ten GGUFs with names like "Qwen 2.5 Coder 3B" gives no clue which to pick.
            MODEL_GROUPS.forEach { (category, label, blurb) ->
                val group = state.items.filter { it.entry.category == category }
                if (group.isEmpty()) return@forEach
                item(key = "hdr-$category") {
                    Column(Modifier.padding(top = 10.dp, bottom = 2.dp)) {
                        Text(
                            label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = OmniPalette.info,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            blurb,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(group, key = { it.entry.id }) { item ->
                    ModelCard(
                        item = item,
                        loading = state.loadingModelId == item.entry.id,
                        loadProgress = state.loadProgress,
                        onDownload = { onDownload(item.entry) },
                        onLoad = { onLoad(item.entry) },
                        onUnload = onUnload,
                        onDelete = { onDelete(item.entry) },
                    )
                }
            }

            if (state.imported.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Imported GGUFs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.imported) { imported ->
                    ImportedRow(
                        file = imported.file,
                        isActive = imported.isActive,
                        onLoad = { onLoadImported(imported.file) },
                        onUnload = onUnload,
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import a .gguf from storage")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { urlDraft = ""; urlDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download any model by URL")
                }
            }

            item {
                Text(
                    "Catalog downloads are SHA-256 verified. Models you import or fetch by URL are not " +
                        "(there is no hash for an unknown file) — llama.cpp will tell you if the file is " +
                        "unusable. Nothing here is blocked by RAM: oversized models warn, then try.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (urlDialog) {
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text("Download by URL") },
            text = {
                Column {
                    Text(
                        "Paste a direct https link to a .gguf file. Hugging Face, a mirror, your own " +
                            "server — anything reachable. It is saved to the app's model folder.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        singleLine = true,
                        label = { Text("https://…/model.gguf") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onDownloadUrl(urlDraft); urlDialog = false }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { urlDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DeviceSummaryCard(state: ModelsViewModel.UiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OmniPalette.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = OmniPalette.info)
                Spacer(Modifier.width(8.dp))
                Text("This device", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(state.deviceSummary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Free model storage: ${state.freeStorage}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "A model is only offered when it fits in 55% of available RAM. That single rule " +
                    "prevents most OOM crashes.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelCard(
    item: ModelsViewModel.Item,
    loading: Boolean,
    loadProgress: Float?,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
) {
    val entry = item.entry
    val ready = item.download is ModelDownloadManager.State.Ready
    Card(
        colors = CardDefaults.cardColors(containerColor = OmniPalette.surface),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.isActive) Modifier.border(1.dp, OmniPalette.ok, RoundedCornerShape(12.dp))
                else Modifier
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isActive) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(OmniPalette.ok, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(entry.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Badge(entry.quantization, OmniPalette.info)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Badge(Formatters.bytes(entry.fileSizeBytes), OmniPalette.surfaceHigh)
                Spacer(Modifier.width(6.dp))
                Badge("${entry.minRamMb / 1024} GB+ RAM", OmniPalette.surfaceHigh)
                Spacer(Modifier.width(6.dp))
                Badge(Formatters.parameters(entry.paramCount), OmniPalette.surfaceHigh)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Advisory, never a block: the model can always be downloaded and loaded.
            if (item.gate.advisory && item.gate.reason != null && !ready) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = OmniPalette.warn.copy(alpha = 0.14f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        item.gate.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = OmniPalette.warn,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            when (val download = item.download) {
                is ModelDownloadManager.State.Downloading -> {
                    LinearProgressIndicator(
                        progress = { download.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = OmniPalette.info,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${Formatters.percent(download.progress)} · ${Formatters.bytes(download.bytes)} of " +
                            Formatters.bytes(if (download.total > 0) download.total else entry.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                is ModelDownloadManager.State.Verifying -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Verifying SHA-256…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is ModelDownloadManager.State.Failed -> {
                    Text(download.reason, color = OmniPalette.danger, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownload, enabled = !loading) { Text("Retry download") }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            item.isActive -> {
                                OutlinedButton(onClick = onUnload) { Text("Unload") }
                                TextButton(onClick = onDelete) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete model")
                                }
                            }
                            ready -> {
                                Button(onClick = onLoad, enabled = !loading) {
                                    if (loading) {
                                        CircularProgressIndicator(
                                            Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (loading) "Loading…" else "Load")
                                }
                                TextButton(onClick = onDelete) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete model")
                                }
                            }
                            else -> {
                                Button(
                                    onClick = onDownload,
                                    enabled = !loading,
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Download")
                                }
                            }
                        }
                    }
                    if (loading && loadProgress != null) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { loadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = OmniPalette.ok,
                        )
                        Text(
                            "Loading into memory: ${Formatters.percent(loadProgress)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportedRow(
    file: java.io.File,
    isActive: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = OmniPalette.surface), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isActive) {
                        Box(Modifier.size(9.dp).background(OmniPalette.ok, CircleShape))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(file.name, style = MaterialTheme.typography.titleMedium, fontSize = 15.sp)
                }
                Text(
                    Formatters.bytes(file.length()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActive) OutlinedButton(onClick = onUnload) { Text("Unload") }
            else Button(onClick = onLoad) { Text("Load") }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NoticeCard(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) OmniPalette.danger.copy(alpha = 0.14f)
            else OmniPalette.info.copy(alpha = 0.14f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

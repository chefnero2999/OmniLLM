package com.omni.llm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.data.ModelDownloadManager
import com.omni.llm.ui.theme.OmniPalette
import com.omni.llm.util.Formatters

private val VAULT_CATEGORIES = listOf(
    "all" to "All",
    "ready" to "On Disk",
    "everyday" to "Everyday",
    "coding" to "Coding",
    "reasoning" to "Reasoning",
    "uncensored" to "Uncensored",
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
    var selectedCategory by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    val filteredItems = remember(state.items, selectedCategory, searchQuery) {
        val byCat = when (selectedCategory) {
            "all" -> state.items
            "ready" -> state.items.filter { it.download is ModelDownloadManager.State.Ready || it.isActive }
            else -> state.items.filter { it.entry.category == selectedCategory }
        }
        val q = searchQuery.trim()
        if (q.isEmpty()) byCat else byCat.filter {
            it.entry.name.contains(q, true) || it.entry.description.contains(q, true)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                // Vault Telemetry Capsule
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
                        val hasActive = state.items.any { it.isActive } || state.imported.any { it.isActive }
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (hasActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "LOCAL MODEL VAULT",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                state.deviceSummary,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Free storage: ${state.freeStorage} · ${state.items.count { it.download is ModelDownloadManager.State.Ready }} installed",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { showSearch = !showSearch }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Search, contentDescription = "Filter vault", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh manifest", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                if (showSearch) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search local GGUF models...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Category Filter Strip
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VAULT_CATEGORIES.forEach { (id, label) ->
                        QuickModePill(
                            label = label,
                            active = selectedCategory == id,
                            tint = if (id == "uncensored") OmniPalette.copper else MaterialTheme.colorScheme.primary,
                            onClick = { selectedCategory = id },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { error ->
                item { NoticeCard(error, isError = true, onDismiss = onDismiss) }
            }
            state.message?.let { message ->
                item { NoticeCard(message, isError = false, onDismiss = onDismiss) }
            }

            // Compact Import / Custom URL Bar at Top
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import .gguf", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { urlDraft = ""; urlDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Fetch URL", fontSize = 12.sp)
                    }
                }
            }

            if (state.imported.isNotEmpty() && (selectedCategory == "all" || selectedCategory == "ready")) {
                item {
                    Text(
                        "IMPORTED GGUFS (${state.imported.size})",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
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

            if (filteredItems.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No models in this filter", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Switch to 'All' or import your own .gguf weights.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(filteredItems, key = { it.entry.id }) { item ->
                    CompactModelCard(
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
        }
    }

    if (urlDialog) {
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text("Download GGUF by URL") },
            text = {
                Column {
                    Text(
                        "Paste a direct https:// link to any .gguf model file.",
                        style = MaterialTheme.typography.bodySmall,
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
            confirmButton = { TextButton(onClick = { onDownloadUrl(urlDraft); urlDialog = false }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CompactModelCard(
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
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (item.isActive) 1.5.dp else 1.dp,
            color = if (item.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.isActive) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${entry.category.uppercase()} · ${Formatters.bytes(entry.fileSizeBytes)} · ${entry.quantization}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VaultBadge("${entry.minRamMb / 1024} GB+ RAM", MaterialTheme.colorScheme.surfaceVariant)
                    VaultBadge(Formatters.parameters(entry.paramCount), MaterialTheme.colorScheme.surfaceVariant)
                }
            }

            if (item.gate.advisory && item.gate.reason != null && !ready) {
                Spacer(Modifier.height(8.dp))
                Text("⚠ ${item.gate.reason}", fontSize = 11.sp, color = OmniPalette.copper)
            }

            Spacer(Modifier.height(10.dp))

            when (val download = item.download) {
                is ModelDownloadManager.State.Downloading -> {
                    LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${Formatters.percent(download.progress)} · ${Formatters.bytes(download.bytes)} / ${Formatters.bytes(if (download.total > 0) download.total else entry.fileSizeBytes)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
                is ModelDownloadManager.State.Verifying -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Verifying SHA-256…", fontSize = 12.sp)
                    }
                }
                is ModelDownloadManager.State.Failed -> {
                    Text(download.reason, color = OmniPalette.danger, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onDownload, enabled = !loading, shape = RoundedCornerShape(10.dp)) { Text("Retry") }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            item.isActive -> {
                                OutlinedButton(onClick = onUnload, shape = RoundedCornerShape(10.dp)) { Text("Unload") }
                                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = OmniPalette.danger, modifier = Modifier.size(18.dp))
                                }
                            }
                            ready -> {
                                Button(
                                    onClick = onLoad,
                                    enabled = !loading,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    if (loading) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(if (loading) "Loading…" else "Load Model", fontSize = 12.sp)
                                }
                                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = OmniPalette.danger, modifier = Modifier.size(18.dp))
                                }
                            }
                            else -> {
                                Button(onClick = onDownload, enabled = !loading, shape = RoundedCornerShape(10.dp)) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Download (${Formatters.bytes(entry.fileSizeBytes)})", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (loading && loadProgress != null) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { loadProgress }, modifier = Modifier.fillMaxWidth())
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
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(Formatters.bytes(file.length()), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isActive) OutlinedButton(onClick = onUnload) { Text("Unload") }
            else Button(onClick = onLoad) { Text("Load") }
        }
    }
}

@Composable
private fun VaultBadge(text: String, bg: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun NoticeCard(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) OmniPalette.danger.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

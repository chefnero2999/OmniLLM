package com.omni.llm.ui

import com.omni.llm.data.ProjectFile

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.ui.theme.OmniPalette

/**
 * Full access to everything a model produced.
 *
 * The rule this screen enforces: **if the AI wrote it, the user can see it, edit it, copy it, export
 * it or delete it.** No hidden state, no "trust me, it is in there somewhere". Every file the agent
 * creates lands in a project folder, and this is the window into that folder.
 *
 * Binary files are listed but not text-edited; images get a preview. Everything is read-only until
 * the user explicitly taps Edit, because silently rewriting generated code while inspecting it would
 * be the worst possible behaviour.
 */
@Composable
fun ProjectFilesScreen(
    slug: String,
    files: List<ProjectFile>,
    loading: Boolean,
    openFile: ProjectFile?,
    openContent: String,
    editing: Boolean,
    editDraft: String,
    confirmingDelete: String?,
    onOpen: (ProjectFile) -> Unit,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onAskDelete: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    // ---------------------------------------------------------------- file view / editor
    if (openFile != null) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back to files")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            openFile.path,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${openFile.sizeLabel} · ${openFile.lines} lines" +
                                if (openFile.isBinary) " · binary" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!openFile.isBinary) {
                        if (editing) {
                            TextButton(onClick = onCancelEdit) { Text("Cancel") }
                            TextButton(onClick = onSave) {
                                Text("Save", color = OmniPalette.ok, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            IconButton(onClick = onEdit) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = OmniPalette.info)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                when {
                    editing -> OutlinedTextField(
                        value = editDraft,
                        onValueChange = onDraft,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        ),
                    )
                    openFile.isBinary -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (openFile.path.endsWith(".jpg") || openFile.path.endsWith(".png"))
                                "Image — open the Preview pane to see it rendered."
                            else "Binary file (${openFile.sizeLabel}). Not shown as text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> SelectionContainer {
                        // Selectable and scrollable: the user can copy any part of the source.
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                        ) {
                            Text(
                                openContent,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        if (confirmingDelete != null) {
            AlertDialog(
                onDismissRequest = onDismissDelete,
                title = { Text("Delete ${confirmingDelete}?") },
                text = { Text("This removes the file from the project folder. It cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = { onConfirmDelete(confirmingDelete) }) {
                        Text("Delete", color = OmniPalette.danger)
                    }
                },
                dismissButton = { TextButton(onClick = onDismissDelete) { Text("Keep") } },
            )
        }
        return
    }

    // ---------------------------------------------------------------- file list
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text(slug, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${files.size} files · everything the model wrote, nothing hidden",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Filled.Download, contentDescription = "Export as zip", tint = OmniPalette.ok)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "This project folder is empty.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(files, key = { it.path }) { file ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { onOpen(file) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (file.isBinary) "▓" else "≡",
                                color = if (file.isBinary) OmniPalette.warn else OmniPalette.info,
                                fontSize = 13.sp,
                            )
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${file.sizeLabel}" + if (file.lines > 0) " · ${file.lines} lines" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { onAskDelete(file.path) },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete ${file.path}",
                                    tint = OmniPalette.danger,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}


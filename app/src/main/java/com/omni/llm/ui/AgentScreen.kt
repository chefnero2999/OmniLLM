package com.omni.llm.ui

import com.omni.llm.data.ProjectFile

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import com.omni.llm.agent.AgentEngine
import com.omni.llm.ui.theme.OmniPalette
import java.io.File

/**
 * The Build tab: prompt in, project out.
 *
 * Two panes, switched by a chip row: **Activity** (what the agent is doing, live) and **Preview**
 * (the actual result rendered by a real WebView). The preview is not a mock — it is the files on
 * disk, so "it works" means it works here.
 */
@Composable
fun AgentScreen(
    state: AgentViewModel.UiState,
    onPrompt: (String) -> Unit,
    onStack: (AgentEngine.Stack) -> Unit,
    onBuild: () -> Unit,
    onStop: () -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onRefreshPreview: () -> Unit,
    onDismissBanner: () -> Unit,
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onPickModel: (String) -> Unit,
    onImagePrompt: (String) -> Unit,
    onGenerateImage: () -> Unit,
    onDismissImageError: () -> Unit,
    onOpenFiles: (String) -> Unit,
    onCloseFiles: () -> Unit,
    onOpenFile: (ProjectFile) -> Unit,
    onCloseFile: () -> Unit,
    onBeginEdit: () -> Unit,
    onDraft: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onAskDelete: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
    onExport: () -> Unit,
    onDeleteAll: () -> Unit = {},
) {
    // 0 = activity, 1 = preview, 2 = images
    var pane by remember { mutableStateOf(0) }

    if (state.filesSlug != null) {
        ProjectFilesScreen(
            slug = state.filesSlug,
            files = state.files,
            loading = state.filesLoading,
            openFile = state.openFile,
            openContent = state.openContent,
            editing = state.editing,
            editDraft = state.editDraft,
            confirmingDelete = state.confirmingDelete,
            onOpen = onOpenFile,
            onClose = onCloseFile,
            onEdit = onBeginEdit,
            onDraft = onDraft,
            onSave = onSaveEdit,
            onCancelEdit = onCancelEdit,
            onAskDelete = onAskDelete,
            onDismissDelete = onDismissDelete,
            onConfirmDelete = onConfirmDelete,
            onExport = onExport,
            onBack = onCloseFiles,
        )
        return
    }

    if (state.pickerOpen) {
        ModelPickerSheet(
            title = "Model for builds",
            subtitle = "Only models verified to follow the tool protocol are selectable.",
            selectedKey = state.buildModel.threadKey,
            category = null,
            onCategory = {},
            onPick = { onPickModel(it.threadKey) },
            onClose = onClosePicker,
            agentOnly = true,
            unrestricted = state.settingsUnrestricted,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Build, contentDescription = null, tint = OmniPalette.ok)
            Spacer(Modifier.width(8.dp))
            Text(
                "Build",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            state.activeSlug?.let { slug ->
                AssistChip(
                    onClick = { onOpenFiles(slug) },
                    label = { Text("Files", fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    border = null,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (state.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = OmniPalette.info,
                )
                Spacer(Modifier.width(6.dp))
                Text("working", color = OmniPalette.info, fontSize = 12.sp)
            }
        }
        Text(
            "Describe an app or a website. A free model builds it file by file, and you can run it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        // Which model builds. Visible and changeable, never an invisible default.
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                .clickable(enabled = !state.running) { onOpenPicker() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Build, contentDescription = null, tint = OmniPalette.ok, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Builder: ${state.buildModel.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    state.buildModel.blurb,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (state.running) "busy" else "change",
                fontSize = 11.sp,
                color = OmniPalette.ok,
            )
        }
        Spacer(Modifier.height(10.dp))

        state.banner?.let { message ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    IconButton(onClick = onDismissBanner, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (pane == 2) {
            // Image mode: reuse the space for the image prompt instead of the build controls.
            Spacer(Modifier.height(4.dp))
        }

        // ------------------------------------------------------------- prompt
        OutlinedTextField(
            value = state.prompt,
            onValueChange = onPrompt,
            modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
            label = { Text("What should it build?") },
            placeholder = { Text("A tip calculator with a dark theme, or: a snake game with touch controls") },
            enabled = !state.running,
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentEngine.Stack.entries.forEach { stack ->
                FilterChip(
                    selected = state.stack == stack,
                    onClick = { onStack(stack) },
                    enabled = !state.running,
                    label = {
                        Text(
                            when (stack) {
                                AgentEngine.Stack.WEB -> "Website"
                                AgentEngine.Stack.HTML_GAME -> "Game"
                                AgentEngine.Stack.ANDROID -> "Android"
                            },
                            fontSize = 12.sp,
                        )
                    },
                )
            }
        }
        if (state.stack == AgentEngine.Stack.ANDROID) {
            Text(
                "Android projects are written as real Gradle source and exported as a zip. A phone " +
                    "cannot compile them, so there is no preview — build it in Android Studio.",
                style = MaterialTheme.typography.bodySmall,
                color = OmniPalette.warn,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onBuild,
                enabled = !state.running,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = OmniPalette.ok, contentColor = Color(0xFF04140A)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.activeSlug == null) "Build" else "Build again", fontWeight = FontWeight.SemiBold)
            }
            if (state.running) {
                OutlinedButton(onClick = onStop, shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ------------------------------------------------------------- panes
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Activity" to state.log.size,
                "Preview" to state.fileCount,
                "Images" to state.images.size,
            ).forEachIndexed { i, (label, count) ->
                AssistChip(
                    onClick = { pane = i },
                    label = { Text("$label" + if (i == 1 && count > 0) " · $count files" else "", fontSize = 12.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (pane == i) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                    ),
                    border = null,
                )
            }
            Spacer(Modifier.weight(1f))
            if (pane == 1) {
                IconButton(onClick = onRefreshPreview, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload preview", tint = OmniPalette.info, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (pane) {
                0 -> ActivityPane(state)
                1 -> PreviewPane(state, onRefreshPreview)
                else -> ImagePane(state, onImagePrompt, onGenerateImage, onDismissImageError)
            }
        }

        // ------------------------------------------------------------- projects
        if (state.projects.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Projects",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onDeleteAll,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text("Delete all", fontSize = 11.sp, color = OmniPalette.danger)
                }
            }
            LazyColumn(Modifier.heightIn(max = 132.dp)) {
                items(state.projects, key = { it.slug }) { project ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            project.slug,
                            Modifier.weight(1f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (project.slug == state.activeSlug) OmniPalette.ok
                            else MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            "${project.files}f",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onOpenProject(project.slug) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "Open", modifier = Modifier.size(17.dp), tint = OmniPalette.info)
                        }
                        IconButton(onClick = { onDeleteProject(project.slug) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(17.dp), tint = OmniPalette.danger)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActivityPane(state: AgentViewModel.UiState) {
    if (state.log.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing built yet.\nEvery file it writes shows up here as it happens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        if (state.running) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Working...",
                    style = MaterialTheme.typography.labelSmall,
                    color = OmniPalette.ok,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Step ${state.log.count { it.kind == AgentViewModel.Line.Kind.ACTION || it.kind == AgentViewModel.Line.Kind.THOUGHT }} of max 24",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.log) { line ->
            val colour = when (line.kind) {
                AgentViewModel.Line.Kind.THOUGHT -> MaterialTheme.colorScheme.onSurfaceVariant
                AgentViewModel.Line.Kind.ACTION -> OmniPalette.info
                AgentViewModel.Line.Kind.ERROR -> OmniPalette.danger
                AgentViewModel.Line.Kind.DONE -> OmniPalette.ok
                AgentViewModel.Line.Kind.WAIT -> OmniPalette.warn
            }
            Text(
                line.text,
                color = colour,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
}

/**
 * A real WebView pointed at the real files.
 *
 * `allowFileAccessFromFileURLs` is on because the generated site loads its own sibling files from
 * `file://`, which is blocked by default on modern Android — without it every generated page would
 * look blank and every no-JS fallback would hide the fact that the JS never ran.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewPane(state: AgentViewModel.UiState, onReload: () -> Unit) {
    val path = state.previewPath
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }

    if (path == null || !path.exists()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (state.activeStack == AgentEngine.Stack.ANDROID) {
                        "Android source has no on-device preview.\nOpen the project folder to export it."
                    } else {
                        "No index.html yet.\nThe preview appears once the first page is written."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // WebView cannot live in a lazy list safely, and it must be torn down when the user leaves the
    // tab, or its rendering surface keeps hold of memory the model needs.
    val webView = remember { mutableStateOf<WebView?>(null) }
    // AndroidView's update lambda runs on EVERY recomposition — and the log updates constantly
    // while the agent works. Without this gate the preview would reload ~20x per second and look
    // like a flickering broken page. Reload only when the nonce actually changes.
    val loadedNonce = remember { mutableStateOf(Int.MIN_VALUE) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webView.value?.let { wv ->
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.destroy()
            }
            webView.value = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        error?.let {
            Text(it, color = OmniPalette.danger, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
        }
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F14))
                .padding(0.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView.value = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            // Surfacing JS errors turns "it is blank" into a fixable message.
                            val text = msg?.message().orEmpty()
                            if (!text.isNullOrBlank()) error = "console: $text"
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            error = null
                        }
                    }
                    loadedNonce.value = state.previewNonce
                    loadUrl("file://${path.absolutePath}")
                }
            },
            update = { view ->
                if (loadedNonce.value != state.previewNonce) {
                    loadedNonce.value = state.previewNonce
                    view.loadUrl("file://${path.absolutePath}?v=${state.previewNonce}")
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                path.parentFile?.name ?: "",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val dir = path.parentFile
                    if (dir != null) ProjectExport.share(context, dir)
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text("Export zip", fontSize = 11.sp)
            }
            IconButton(onClick = onReload, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Reload", modifier = Modifier.size(18.dp), tint = OmniPalette.ok)
            }
        }
    }
}



/**
 * Keyless text-to-image.
 *
 * The image model is free and needs no account, but it is the most queue-limited thing in the app,
 * so the busy state is explicit and the error text says to retry rather than showing a dead spinner.
 * Note shown to the user: the free tier stamps a small watermark in the corner.
 */
@Composable
private fun ImagePane(
    state: AgentViewModel.UiState,
    onPrompt: (String) -> Unit,
    onGenerate: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sana · free, no account · adds a small watermark",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = state.imagePrompt,
            onValueChange = onPrompt,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.imageBusy,
            label = { Text("Describe a picture") },
            placeholder = { Text("a red fox in snow, phone wallpaper") },
            maxLines = 3,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onGenerate,
            enabled = !state.imageBusy && state.imagePrompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = OmniPalette.info,
                contentColor = Color(0xFF00131A),
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (state.imageBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = Color(0xFF00131A),
                )
                Spacer(Modifier.width(8.dp))
                Text("Drawing… (the free queue can take ~30 s)")
            } else {
                Text("Generate", fontWeight = FontWeight.SemiBold)
            }
        }

        state.imageError?.let { message ->
            Spacer(Modifier.height(6.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message,
                        Modifier.weight(1f),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    IconButton(onClick = onDismissError, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (state.images.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing generated yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.images) { image ->
                    Column {
                        androidx.compose.foundation.Image(
                            bitmap = remember(image.file) { decodeImage(image.file) },
                            contentDescription = image.prompt,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .background(Color(0xFF1C2530), RoundedCornerShape(12.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                        Text(
                            image.prompt,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Decodes a saved JPEG once per file. Returns an empty bitmap rather than crashing on bad bytes. */
private fun decodeImage(file: File): androidx.compose.ui.graphics.ImageBitmap {
    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
    return bitmap.asImageBitmap()
}

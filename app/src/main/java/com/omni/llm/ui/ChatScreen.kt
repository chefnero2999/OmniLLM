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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import android.content.Intent
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.ui.theme.OmniPalette
import com.omni.llm.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatViewModel.UiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onDismissBanner: () -> Unit,
    onEnableCloud: () -> Unit,
    onDismissCloudOffer: () -> Unit,
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onFilterCategory: (com.omni.llm.data.ModelCatalog.Category?) -> Unit,
    onPickModel: (String) -> Unit,
    onToggleContextSource: (String) -> Unit,
    onOpenContextSheet: () -> Unit,
    onCloseContextSheet: () -> Unit,
    onAskClear: () -> Unit,
    onDismissClear: () -> Unit,
    onConfirmClear: () -> Unit,
    onRetry: (Long) -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onCloseSearch: () -> Unit = {},
    onUpdateSearch: (String) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val onCopy: (String) -> Unit = { text ->
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
    }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // The picker and the context sheet are full-screen overlays rather than dialogs: on a phone a
    // 30-model list inside a dialog is cramped, and the category chips need room.
    if (state.pickerOpen) {
        ModelPickerSheet(
            title = "Pick a model",
            subtitle = "Each model keeps its own conversation. Nothing is overwritten.",
            selectedKey = state.activeModel?.threadKey,
            category = state.pickerCategory,
            onCategory = onFilterCategory,
            onPick = { onPickModel(it.threadKey) },
            onClose = onClosePicker,
            threadSummary = state.threadSummary,
            unrestricted = state.settingsUnrestricted,
        )
        return
    }

    if (state.contextSheetOpen) {
        ContextBridgeSheet(
            activeKey = state.activeModel?.threadKey.orEmpty(),
            selected = state.contextSources,
            candidates = state.readableThreads,
            onToggle = onToggleContextSource,
            onClose = onCloseContextSheet,
        )
        return
    }

    if (state.confirmingClear) {
        AlertDialog(
            onDismissRequest = onDismissClear,
            title = { Text("Delete this conversation?") },
            text = {
                Text(
                    "This deletes the chat for ${state.activeModel?.name ?: "this model"} only. " +
                        "Every other model's chat is untouched. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmClear) {
                    Text("Delete", color = OmniPalette.danger)
                }
            },
            dismissButton = { TextButton(onClick = onDismissClear) { Text("Keep") } },
        )
    }

    Scaffold(
        topBar = {
            if (state.searchOpen) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = onUpdateSearch,
                            placeholder = { Text("Search messages...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    },
                    actions = {
                        IconButton(onClick = onCloseSearch) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                TopAppBar(
                    title = {
                        Column(Modifier.clickable { onOpenPicker() }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    state.activeModel?.name ?: "Local model",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = "Switch model",
                                    tint = OmniPalette.ok,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                // This line is the promise made visible: this model, this chat.
                                "its own chat · ${state.messages.count { it.role != "system" }} messages" +
                                    if (state.contextSources.isNotEmpty())
                                        " · reading ${state.contextSources.size}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.contextSources.isEmpty())
                                    MaterialTheme.colorScheme.onSurfaceVariant else OmniPalette.info,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, contentDescription = "Search messages")
                        }
                        IconButton(onClick = {
                            val conversationText = state.messages
                                .filter { it.role != "system" }
                                .joinToString("\n\n") { "${if (it.role == "user") "User" else "Assistant"}: ${it.content}" }
                            if (conversationText.isNotBlank()) {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, conversationText)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Share Conversation")
                                context.startActivity(shareIntent)
                            }
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share conversation")
                        }
                        IconButton(onClick = onOpenContextSheet) {
                            Icon(
                                Icons.Filled.MenuBook,
                                contentDescription = "Read context from other chats",
                                tint = if (state.contextSources.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                else OmniPalette.info,
                            )
                        }
                        IconButton(onClick = onAskClear) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete this chat")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(Modifier.imePadding()) {
                ContextMeter(state)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask anything — stays on this device") },
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        shape = RoundedCornerShape(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.busy) {
                        FilledIconButton(onClick = onStop) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop generating")
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                val text = input
                                input = ""
                                onSend(text)
                            },
                            enabled = input.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            state.banner?.let { banner ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = OmniPalette.warn.copy(alpha = 0.16f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(banner, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismissBanner) { Text("✕") }
                    }
                }
            }

            if (state.offerCloudToggle) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = OmniPalette.info.copy(alpha = 0.16f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Chat now with free cloud models", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Open-weight models (GPT-OSS, DeepSeek, Gemma, Kimi) served without an " +
                                "account or API key. Claude / Gemini Pro / GPT-5 need a paid key and " +
                                "are not available here.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onEnableCloud) { Text("Enable") }
                            TextButton(onClick = onDismissCloudOffer) { Text("Not now") }
                        }
                    }
                }
            }

            val displayMessages = state.filteredMessages ?: state.messages
            if (displayMessages.isEmpty()) {
                if (state.filteredMessages != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No messages matching \"${state.searchQuery}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    EmptyState(state, onSelectStarter = { prompt -> onSend(prompt) })
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(displayMessages, key = { it.id }) { message ->
                        MessageBubble(message, onRetry, onStopGeneration, onCopy)
                    }
                    // One-tap redo of the whole last turn, for when the answer was fine in form
                    // but wrong in substance.
                    if (!state.busy && displayMessages.any { it.role == "assistant" } && state.filteredMessages == null) {
                        item {
                            TextButton(
                                onClick = onRegenerate,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Regenerate last answer", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    state: ChatViewModel.UiState,
    onSelectStarter: (String) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Living Ambient Aura & Logo (ChatGPT/Gemini inspired)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(OmniPalette.ok.copy(alpha = 0.35f), Color.Transparent),
                    ),
                    shape = RoundedCornerShape(36.dp),
                ),
        ) {
            Text("✨", fontSize = 32.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "OmniLLM Studio",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Private on-device inference & resilient keyless cloud intelligence.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        // Dynamic Starter Prompts (Pills)
        Text(
            "TRY ASKING",
            style = MaterialTheme.typography.labelSmall,
            color = OmniPalette.ok,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        val suggestions = listOf(
            "🚀 Build a touch-friendly space shooter game in HTML5",
            "🔍 Explain how neural network weights are quantized to GGUF",
            "💡 Debug a Kotlin Coroutine StateFlow recomposition issue",
            "✍️ Draft an executive pitch for an on-device AI app"
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            suggestions.forEach { prompt ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectStarter(prompt) },
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatViewModel.Message,
    onRetry: (Long) -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val isUser = message.role == "user"
    val bubbleColor = when {
        message.isError -> OmniPalette.danger.copy(alpha = 0.16f)
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> OmniPalette.surface
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onCopy(message.content) },
                ),
        ) {
            Column(Modifier.padding(12.dp)) {
                // Reasoning disclosure (Claude / Gemini thinking accordion)
                if (message.thinking.isNotBlank()) {
                    var thinkingExpanded by remember { mutableStateOf(false) }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clickable { thinkingExpanded = !thinkingExpanded },
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🧠", fontSize = 11.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Thought process",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OmniPalette.info,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text(
                                    if (thinkingExpanded) "Hide" else "Show",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (thinkingExpanded) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = message.thinking.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (message.content.isEmpty() && message.streaming) {
                    Text("…", style = MaterialTheme.typography.bodyLarge)
                } else {
                    FormattedText(
                        text = message.content,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Interactive Claude-style Artifact preview card if HTML/code is present
                val hasHtmlCode = remember(message.content) {
                    message.content.contains("```html", ignoreCase = true) ||
                        (message.content.contains("<!doctype html>", ignoreCase = true) && message.content.contains("</html>", ignoreCase = true))
                }
                if (hasHtmlCode && !isUser && !message.streaming) {
                    var showArtifactSheet by remember { mutableStateOf(false) }
                    val extractedCode = remember(message.content) {
                        if (message.content.contains("```html", ignoreCase = true)) {
                            message.content.substringAfter("```html").substringBefore("```").trim()
                        } else {
                            message.content.substringAfter("<!doctype html>").let { "<!doctype html>$it" }.substringBefore("</html>").let { "$it</html>" }.trim()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = OmniPalette.ok.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showArtifactSheet = true },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("⚡", fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Interactive Artifact Ready",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = OmniPalette.ok,
                                )
                                Text(
                                    "Tap to run live interactive preview",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("Preview →", style = MaterialTheme.typography.labelSmall, color = OmniPalette.ok)
                        }
                    }
                    if (showArtifactSheet) {
                        ArtifactPreviewDialog(
                            html = extractedCode,
                            onDismiss = { showArtifactSheet = false },
                        )
                    }
                }
                if (message.streaming) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("generating", style = MaterialTheme.typography.labelSmall)
                    }
                }
                // ---- per-reply actions, on every assistant bubble ----
                if (!isUser) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.tokensPerSec > 0.0) {
                            Text(
                                "%.1f tok/s".format(message.tokensPerSec),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = OmniPalette.ok,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        message.modelName?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (message.streaming) {
                            // Stop mid-generation, keeping the partial answer.
                            ActionIcon(Icons.Filled.Stop, "Stop generating", OmniPalette.danger, onStop)
                        } else {
                            ActionIcon(Icons.Filled.Refresh, "Retry this reply", OmniPalette.info) {
                                onRetry(message.id)
                            }
                            ActionIcon(Icons.Filled.ContentCopy, "Copy", MaterialTheme.colorScheme.onSurfaceVariant) {
                                onCopy(message.content)
                            }
                        }
                    }
                    if (message.stopped) {
                        Text(
                            "stopped",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = OmniPalette.warn,
                        )
                    }
                }
                message.footer?.let { footer ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        footer,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextMeter(state: ChatViewModel.UiState) {
    val fraction = state.contextFraction
    val color: Color = when {
        fraction > 0.9f -> OmniPalette.danger
        fraction > 0.7f -> OmniPalette.warn
        else -> OmniPalette.ok
    }
    Column(Modifier.fillMaxWidth()) {
        if (state.contextLimit > 0) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = color,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                buildString {
                    append(if (state.contextLimit > 0) "${state.contextUsed} / ${state.contextLimit} tokens"
                    else "no context yet")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Formatters.tokensPerSecond(state.lastTokensPerSec),
                style = MaterialTheme.typography.labelSmall,
                color = OmniPalette.ok,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun FormattedText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text, color) {
        buildAnnotatedString {
            var i = 0
            val len = text.length
            while (i < len) {
                if (text.startsWith("```", i)) {
                    val endBlock = text.indexOf("```", i + 3)
                    if (endBlock != -1) {
                        val content = text.substring(i + 3, endBlock).trimStart()
                        val code = if (content.contains("\n")) content.substringAfter("\n") else content
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color(0x33888888),
                                fontSize = 13.sp,
                            )
                        ) {
                            append(code.trimEnd())
                        }
                        i = endBlock + 3
                        continue
                    }
                }
                if (text[i] == '`') {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1 && !text.substring(i + 1, end).contains('\n')) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color(0x22888888),
                                fontSize = 13.sp,
                            )
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                        continue
                    }
                }
                if (text.startsWith("**", i)) {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                        continue
                    }
                }
                if (text[i] == '*' && (i == 0 || text[i - 1] != '*') && (i + 1 < len && text[i + 1] != '*')) {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && !text.substring(i + 1, end).contains('\n')) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                        continue
                    }
                }
                append(text[i])
                i++
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = modifier,
    )
}

/** Compact icon button for the reply action row. */
@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(26.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun ArtifactPreviewDialog(
    html: String,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✨", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Live Artifact Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A))
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                loadDataWithBaseURL("https://local.artifact", html, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

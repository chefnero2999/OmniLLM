package com.omni.llm.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.ui.theme.OmniPalette
import com.omni.llm.util.Formatters
import java.util.Locale

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
    onStartDuel: (String) -> Unit = {},
    onStopDuel: () -> Unit = {},
    onVoteArena: (String) -> Unit = {},
    onRunStudio: (String, String, Float, Float, Int) -> Unit = { _, _, _, _, _ -> },
    onStopStudio: () -> Unit = {},
    onToggleDeepWeb: () -> Unit = {},
    onToggleConcise: () -> Unit = {},
    onAttachUri: (Uri) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onEditAndResend: (Long, String) -> Unit = { _, _ -> },
) {
    var input by remember { mutableStateOf("") }
    // 0 = Obsidian Volt Workspace, 1 = Arena Duel, 2 = AI Studio
    var hubMode by remember { mutableStateOf(0) }
    var showVoiceOverlay by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var editingMsg by remember { mutableStateOf<ChatViewModel.Message?>(null) }

    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val onCopy: (String) -> Unit = { text ->
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
        android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    // Real Text-to-Speech engine for Read Aloud
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                instance?.language = Locale.getDefault()
                ttsReady = true
            }
        }
        instance
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                tts.stop()
                tts.shutdown()
            }
        }
    }
    val onSpeak: (String) -> Unit = { text ->
        if (ttsReady) {
            val clean = text.replace(Regex("```[\\s\\S]*?```"), "Code block.")
                .replace(Regex("[*_`#]"), " ")
                .take(3000)
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "omni-tts")
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onAttachUri) }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onAttachUri) }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    if (state.pickerOpen) {
        ModelPickerSheet(
            title = "Pick a model",
            subtitle = "Each model keeps its own Workspace timeline.",
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
            title = { Text("Clear this Workspace?") },
            text = {
                Text(
                    "Deletes the chat history for ${state.activeModel?.name ?: "this model"} only. " +
                        "Other models remain untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmClear) { Text("Delete", color = OmniPalette.danger) }
            },
            dismissButton = { TextButton(onClick = onDismissClear) { Text("Keep") } },
        )
    }

    editingMsg?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.content) }
        AlertDialog(
            onDismissRequest = { editingMsg = null },
            title = { Text("Edit & Resend") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        editingMsg = null
                        onEditAndResend(target.id, draft)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text("Resend") }
            },
            dismissButton = { TextButton(onClick = { editingMsg = null }) { Text("Cancel") } },
        )
    }

    val pulseTransition = rememberInfiniteTransition(label = "VoltPulse")
    val dotAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "DotAlpha",
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (state.searchOpen && hubMode == 0) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = onUpdateSearch,
                            placeholder = { Text("Search Workspace timeline...") },
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
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // Floating Telemetry Capsule (Obsidian Volt V3 Signature)
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(
                                        (if (state.busy) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary).copy(
                                            alpha = if (state.busy) dotAlpha else 1f
                                        )
                                    )
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { onOpenPicker() }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        state.activeModel?.name ?: "Local Engine",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = "Switch model",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                val modeTag = if (state.activeModel?.providerId == "local" || state.activeModel == null) "LOCAL" else "CLOUD"
                                val tpsTag = if (state.lastTokensPerSec > 0.0) " · ${String.format(Locale.US, "%.1f", state.lastTokensPerSec)} tok/s" else ""
                                Text(
                                    "$modeTag$tpsTag · ${state.messages.count { it.role != "system" }} msgs",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (hubMode == 0) {
                                IconButton(onClick = onOpenSearch, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Search, contentDescription = "Search", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = onOpenContextSheet, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = "Context Bridge",
                                        modifier = Modifier.size(18.dp),
                                        tint = if (state.contextSources.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                IconButton(onClick = onAskClear, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Hub Segmented Switcher
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QuickModePill("Timeline", active = hubMode == 0, tint = MaterialTheme.colorScheme.primary, onClick = { hubMode = 0 })
                        QuickModePill("⚔️ Arena Duel", active = hubMode == 1, tint = OmniPalette.copper, onClick = { hubMode = 1 })
                        QuickModePill("🎛️ Studio", active = hubMode == 2, tint = MaterialTheme.colorScheme.secondary, onClick = { hubMode = 2 })
                        if (hubMode == 0 && state.messages.isNotEmpty()) {
                            QuickModePill("+ New", active = false, tint = MaterialTheme.colorScheme.primary, onClick = onAskClear)
                            QuickModePill("📤 Export", active = false, tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = {
                                val conversationText = state.messages
                                    .filter { it.role != "system" }
                                    .joinToString("\n\n") { "${if (it.role == "user") "YOU" else "AI"}: ${it.content}" }
                                if (conversationText.isNotBlank()) {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, conversationText)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Export Workspace"))
                                }
                            })
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (hubMode == 0) {
                Column(
                    Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .imePadding()
                ) {
                    ContextMeter(state)

                    // Obsidian Volt Command Dock
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (state.deepWebEnabled || state.pendingAttachments.isNotEmpty())
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            // Pending Uploaded Attachments Tray
                            if (state.pendingAttachments.isNotEmpty()) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    state.pendingAttachments.forEach { att ->
                                        AttachmentTrayChip(
                                            attachment = att,
                                            onRemove = { onRemoveAttachment(att.id) },
                                        )
                                    }
                                }
                            }

                            // Multimodal & Mode Pills inside the Command Dock
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                QuickModePill(
                                    label = "🌐 Deep Web",
                                    active = state.deepWebEnabled,
                                    tint = OmniPalette.copper,
                                    onClick = onToggleDeepWeb,
                                )
                                QuickModePill(
                                    label = "📎 PDF / Code",
                                    active = false,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    onClick = { docPicker.launch(arrayOf("text/*", "application/pdf", "application/json", "*/*")) },
                                )
                                QuickModePill(
                                    label = "📷 Vision",
                                    active = false,
                                    tint = MaterialTheme.colorScheme.primary,
                                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                                )
                                QuickModePill(
                                    label = "⚡ Concise",
                                    active = state.conciseMode,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    onClick = onToggleConcise,
                                )
                                QuickModePill(
                                    label = "🎙️ Voice",
                                    active = false,
                                    tint = MaterialTheme.colorScheme.primary,
                                    onClick = { showVoiceOverlay = true },
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    Surface(
                                        onClick = { attachMenuOpen = true },
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        modifier = Modifier.size(38.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Add, contentDescription = "Attach", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = attachMenuOpen,
                                        onDismissRequest = { attachMenuOpen = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("📷 Upload Image (Vision AI)") },
                                            onClick = {
                                                attachMenuOpen = false
                                                imagePicker.launch(arrayOf("image/*"))
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("📄 Upload PDF / Document / Code") },
                                            onClick = {
                                                attachMenuOpen = false
                                                docPicker.launch(arrayOf("text/*", "application/pdf", "application/json", "*/*"))
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("🎙️ Live Voice Input") },
                                            onClick = {
                                                attachMenuOpen = false
                                                showVoiceOverlay = true
                                            },
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text(
                                            when {
                                                state.pendingAttachments.isNotEmpty() -> "Ask about your attached file(s)..."
                                                state.deepWebEnabled -> "Ask anything — live web search ON..."
                                                else -> "Command OmniLLM or attach files..."
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                        )
                                    },
                                    maxLines = 5,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                    ),
                                )

                                Spacer(Modifier.width(4.dp))
                                if (state.busy) {
                                    FilledIconButton(
                                        onClick = onStop,
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = OmniPalette.danger),
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Color.White)
                                    }
                                } else {
                                    val canSend = input.isNotBlank() || state.pendingAttachments.isNotEmpty()
                                    FilledIconButton(
                                        onClick = {
                                            val textToSend = input
                                            input = ""
                                            onSend(textToSend)
                                        },
                                        enabled = canSend,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (hubMode) {
                1 -> ArenaDuelScreen(
                    state = state,
                    onStartDuel = onStartDuel,
                    onStopDuel = onStopDuel,
                    onVote = onVoteArena,
                )
                2 -> AiStudioCanvasScreen(
                    state = state,
                    onRunInference = onRunStudio,
                    onStopInference = onStopStudio,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    state.banner?.let { banner ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = OmniPalette.copper.copy(alpha = 0.16f)),
                            border = BorderStroke(1.dp, OmniPalette.copper.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(banner, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = onDismissBanner, modifier = Modifier.size(28.dp)) { Text("✕") }
                            }
                        }
                    }

                    if (state.offerCloudToggle) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Unlock Cloud Models + Vision + Deep Web", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Keyless open-weight cloud fleet (DeepSeek, Qwen3-VL, GPT-OSS, Gemma) with image & file analysis — zero account needed.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = onEnableCloud,
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                                    ) { Text("Enable Cloud") }
                                    TextButton(onClick = onDismissCloudOffer) { Text("Later") }
                                }
                            }
                        }
                    }

                    val displayMessages = state.filteredMessages ?: state.messages
                    if (displayMessages.isEmpty()) {
                        if (state.filteredMessages != null) {
                            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("No matches for \"${state.searchQuery}\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            EmptyStateV3(state) { starter -> onSend(starter) }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(displayMessages, key = { it.id }) { message ->
                                TimelineSpineEntry(
                                    message = message,
                                    onRetry = onRetry,
                                    onStop = onStopGeneration,
                                    onCopy = onCopy,
                                    onSpeak = onSpeak,
                                    onEdit = { editingMsg = message },
                                    onOpenUrl = { url ->
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    },
                                )
                            }
                            if (!state.busy && displayMessages.any { it.role == "assistant" } && state.filteredMessages == null) {
                                item {
                                    TextButton(onClick = onRegenerate, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Regenerate last turn", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showVoiceOverlay) {
                LiveVoiceOverlay(
                    onDismiss = { showVoiceOverlay = false },
                    onSendQuery = { spoken ->
                        showVoiceOverlay = false
                        hubMode = 0
                        onSend(spoken)
                    }
                )
            }
        }
    }
}

@Composable
private fun AttachmentTrayChip(
    attachment: ChatViewModel.Attachment,
    onRemove: () -> Unit,
) {
    val thumb = remember(attachment.base64DataUrl) {
        attachment.base64DataUrl?.let { url ->
            runCatching {
                val b64 = url.substringAfter("base64,", "")
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (attachment.isImage) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(6.dp))
            } else {
                Text(if (attachment.isImage) "🖼️" else "📄", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
            }
            Column {
                Text(
                    attachment.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (attachment.isImage) "Vision Ready" else "${(attachment.sizeBytes / 1024).coerceAtLeast(1)} KB",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove attachment",
                modifier = Modifier.size(16.dp).clickable { onRemove() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyStateV3(
    state: ChatViewModel.UiState,
    onSelectStarter: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "COMMAND DECK READY",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            state.activeModel?.name ?: "OmniLLM Hybrid Engine",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Attach images, PDFs, or code below · Toggle Deep Web for cited live answers · Switch to Arena or Studio anytime.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        val starters = listOf(
            "🎮 Create a self-contained Neon Brick Breaker game in HTML5" to "Interactive HTML5 Game",
            "💻 Explain Android Compose StateFlow concurrency best practices" to "Architecture Deep Dive",
            "🧠 Compare Local Quantized GGUF vs Cloud Reasoning models" to "Hybrid AI Analysis",
        )
        starters.forEach { (prompt, subtitle) ->
            Surface(
                onClick = { onSelectStarter(prompt) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(width = 3.dp, height = 28.dp)
                            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(prompt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineSpineEntry(
    message: ChatViewModel.Message,
    onRetry: (Long) -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onEdit: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val isUser = message.role == "user"
    val railColor = when {
        message.isError -> OmniPalette.danger
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .combinedClickable(onClick = {}, onLongClick = { onCopy(message.content) }),
    ) {
        // Vertical Timeline Rail + Glowing Node
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(16.dp).fillMaxHeight(),
        ) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(railColor)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(railColor.copy(alpha = 0.4f))
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            // Spine Header Label + Actions
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isUser) "YOU" else (message.modelName ?: "OMNILLM").uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = railColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!isUser && message.tokensPerSec > 0.0) {
                    Text(
                        String.format(Locale.US, "%.1f tok/s", message.tokensPerSec),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (isUser) {
                    ActionIcon(Icons.Filled.ContentCopy, "Copy", MaterialTheme.colorScheme.onSurfaceVariant) { onCopy(message.content) }
                    ActionIcon(Icons.Filled.Edit, "Edit & Resend", MaterialTheme.colorScheme.primary, onEdit)
                } else if (message.streaming) {
                    ActionIcon(Icons.Filled.Stop, "Stop", OmniPalette.danger, onStop)
                } else {
                    ActionIcon(Icons.AutoMirrored.Filled.VolumeUp, "Read Aloud", MaterialTheme.colorScheme.primary) { onSpeak(message.content) }
                    ActionIcon(Icons.Filled.Refresh, "Retry", MaterialTheme.colorScheme.secondary) { onRetry(message.id) }
                    ActionIcon(Icons.Filled.ContentCopy, "Copy", MaterialTheme.colorScheme.onSurfaceVariant) { onCopy(message.content) }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Attached files / images on user turn
            if (message.attachments.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    message.attachments.forEach { att ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Text(
                                (if (att.isImage) "🖼️ " else "📄 ") + att.name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // Collapsible Thought Process
            if (message.thinking.isNotBlank()) {
                var expanded by remember { mutableStateOf(false) }
                Surface(
                    onClick = { expanded = !expanded },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🧠 Reasoning trace", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                            Text(if (expanded) "Hide" else "Show", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (expanded) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                message.thinking.trim(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (message.content.isEmpty() && message.streaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text("Streaming response...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Split markdown text & ```code``` blocks into dedicated Obsidian Code Decks
                RichTimelineBody(
                    content = message.content,
                    isError = message.isError,
                    streaming = message.streaming,
                    onCopyCode = onCopy,
                )
            }

            // Web Search Citations (Warm Copper pills)
            if (message.citations.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    message.citations.forEach { cite ->
                        Surface(
                            onClick = { onOpenUrl(cite.url) },
                            shape = RoundedCornerShape(8.dp),
                            color = OmniPalette.copper.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, OmniPalette.copper.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                "🌐 ${cite.title.take(28)}",
                                fontSize = 10.sp,
                                color = OmniPalette.copper,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            message.footer?.let { footer ->
                Spacer(Modifier.height(4.dp))
                Text(
                    footer,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private sealed interface Segment {
    data class Prose(val text: String) : Segment
    data class Code(val lang: String, val code: String) : Segment
}

@Composable
private fun RichTimelineBody(
    content: String,
    isError: Boolean,
    streaming: Boolean,
    onCopyCode: (String) -> Unit,
) {
    val segments = remember(content) {
        val list = mutableListOf<Segment>()
        var idx = 0
        while (idx < content.length) {
            val open = content.indexOf("```", idx)
            if (open == -1) {
                val tail = content.substring(idx).trim()
                if (tail.isNotEmpty()) list += Segment.Prose(tail)
                break
            }
            if (open > idx) {
                val before = content.substring(idx, open).trim()
                if (before.isNotEmpty()) list += Segment.Prose(before)
            }
            val close = content.indexOf("```", open + 3)
            if (close == -1) {
                val rest = content.substring(open + 3)
                val firstLine = rest.substringBefore("\n", "").trim()
                val body = if (rest.contains("\n")) rest.substringAfter("\n") else rest
                list += Segment.Code(firstLine.ifEmpty { "code" }, body)
                break
            } else {
                val inside = content.substring(open + 3, close)
                val firstLine = inside.substringBefore("\n", "").trim()
                val body = if (inside.contains("\n")) inside.substringAfter("\n") else inside
                list += Segment.Code(firstLine.ifEmpty { "code" }, body.trimEnd())
                idx = close + 3
            }
        }
        if (list.isEmpty()) list += Segment.Prose(content)
        list
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is Segment.Prose -> FormattedText(
                    text = seg.text,
                    color = if (isError) OmniPalette.danger else MaterialTheme.colorScheme.onBackground,
                )
                is Segment.Code -> {
                    var showArtifact by remember { mutableStateOf(false) }
                    val isHtml = seg.lang.equals("html", true) ||
                        seg.code.contains("<!doctype html", true) ||
                        seg.code.contains("<html", true)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0A0E16),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    seg.lang.uppercase(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (isHtml && !streaming) {
                                        Row(
                                            Modifier.clickable { showArtifact = true },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                            Text("RUN LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Row(
                                        Modifier.clickable { onCopyCode(seg.code) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("Copy", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Text(
                                seg.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                color = Color(0xFFDBE6FF),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp),
                            )
                        }
                    }
                    if (showArtifact) {
                        ArtifactPreviewDialog(html = seg.code, onDismiss = { showArtifact = false })
                    }
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
        fraction > 0.7f -> OmniPalette.copper
        else -> MaterialTheme.colorScheme.primary
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
    }
}

@Composable
private fun FormattedText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val inlineCodeColor = MaterialTheme.colorScheme.primary
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant
    val annotated = remember(text, color, inlineCodeColor, inlineCodeBg) {
        buildAnnotatedString {
            var i = 0
            val len = text.length
            while (i < len) {
                if (text[i] == '`') {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1 && !text.substring(i + 1, end).contains('\n')) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = inlineCodeBg,
                                color = inlineCodeColor,
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
        style = MaterialTheme.typography.bodyMedium,
        lineHeight = 21.sp,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(540.dp),
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
                        Text("⚡", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Live Artifact Runner",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
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
                        .background(Color(0xFF0A0E16))
                ) {
                    val webViewHolder = remember { mutableStateOf<android.webkit.WebView?>(null) }
                    DisposableEffect(Unit) {
                        onDispose {
                            webViewHolder.value?.let { wv ->
                                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                                wv.destroy()
                            }
                            webViewHolder.value = null
                        }
                    }
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                webViewHolder.value = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
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

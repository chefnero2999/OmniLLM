package com.omni.llm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omni.llm.ui.AgentScreen
import com.omni.llm.ui.AgentViewModel
import com.omni.llm.ui.ChatScreen
import com.omni.llm.ui.ChatViewModel
import com.omni.llm.ui.ModelsScreen
import com.omni.llm.ui.ModelsViewModel
import com.omni.llm.ui.ProjectExport
import com.omni.llm.ui.SettingsScreen
import com.omni.llm.ui.SettingsViewModel
import com.omni.llm.ui.WelcomeScreen
import com.omni.llm.ui.theme.OmniPalette
import com.omni.llm.ui.theme.OmniTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

class MainActivity : ComponentActivity() {

    // Android 13+ needs runtime consent for the foreground-service notification. Without it the
    // service still runs, but "OmniLLM is running locally" is invisible — which makes a working
    // engine look like nothing is happening.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* cosmetic */ }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        val container = (application as OmniApp).container
        setContent {
            val settings by container.settings.settings.collectAsState(
                initial = com.omni.llm.data.OmniSettings()
            )
            OmniTheme(
                themeMode = settings.themeMode,
                accentId = settings.accentColor,
                fontId = settings.fontFamily,
            ) {
                val modelsVm: ModelsViewModel = viewModel()
                val chatVm: ChatViewModel = viewModel()
                val settingsVm: SettingsViewModel = viewModel()
                val agentVm: AgentViewModel = viewModel()
                val scope = rememberCoroutineScope()

                // Connect to the inference process once so the GPU/backend report is available.
                LaunchedEffect(Unit) {
                    runCatching { container.llm.ensureConnected(timeoutMs = 4_000) }
                }

                val importer = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let { modelsVm.importModel(it) } }

                if (!settings.onboardingComplete) {
                    val profile = container.probe.probe()
                    WelcomeScreen(
                        deviceSummary = "${profile.model} · ${String.format("%.1f", profile.totalRamGb)} GB RAM · " +
                            "${profile.cores} cores",
                        onDownloadModel = {
                            scope.launch { container.settings.setOnboardingComplete(true) }
                        },
                        onImport = { importer.launch(arrayOf("*/*")) },
                        onEnableCloud = {
                            settingsVm.setCloudEnabled(!settings.cloudFallbackEnabled)
                            scope.launch { container.settings.setOnboardingComplete(true) }
                        },
                        cloudEnabled = settings.cloudFallbackEnabled,
                    )
                    return@OmniTheme
                }

                OmniShell(
                    modelsVm = modelsVm,
                    chatVm = chatVm,
                    settingsVm = settingsVm,
                    agentVm = agentVm,
                )
            }
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Workspace", Icons.AutoMirrored.Filled.Chat),
    Tab("Forge", Icons.Filled.Build),
    Tab("Vault", Icons.Filled.Storage),
    Tab("Core", Icons.Filled.Settings),
)

@Composable
private fun OmniShell(
    modelsVm: ModelsViewModel,
    chatVm: ChatViewModel,
    settingsVm: SettingsViewModel,
    agentVm: AgentViewModel,
) {
    var selected by remember { mutableStateOf(0) }
    val modelsState by modelsVm.state.collectAsState()
    val chatState by chatVm.state.collectAsState()
    val settingsState by settingsVm.state.collectAsState()
    val agentState by agentVm.state.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> ChatScreen(
                    state = chatState,
                    onSend = chatVm::send,
                    onStop = chatVm::stop,
                    onDismissBanner = chatVm::dismissBanner,
                    onEnableCloud = chatVm::enableCloudNow,
                    onDismissCloudOffer = chatVm::dismissCloudOffer,
                    onOpenPicker = chatVm::openPicker,
                    onClosePicker = chatVm::closePicker,
                    onFilterCategory = chatVm::filterCategory,
                    onPickModel = chatVm::openModel,
                    onToggleContextSource = chatVm::toggleContextSource,
                    onOpenContextSheet = chatVm::openContextSheet,
                    onCloseContextSheet = chatVm::closeContextSheet,
                    onAskClear = chatVm::askClear,
                    onDismissClear = chatVm::dismissClear,
                    onConfirmClear = chatVm::clearThread,
                    onRetry = chatVm::retry,
                    onStopGeneration = chatVm::stopGeneration,
                    onRegenerate = chatVm::regenerateLast,
                    onOpenSearch = chatVm::openSearch,
                    onCloseSearch = chatVm::closeSearch,
                    onUpdateSearch = chatVm::updateSearch,
                    onStartDuel = chatVm::startArenaDuel,
                    onStopDuel = chatVm::stopArenaDuel,
                    onVoteArena = chatVm::voteArena,
                    onRunStudio = chatVm::runStudioInference,
                    onStopStudio = chatVm::stopStudioInference,
                    onToggleDeepWeb = chatVm::toggleDeepWeb,
                    onToggleConcise = chatVm::toggleConciseMode,
                    onAttachUri = chatVm::attachUri,
                    onRemoveAttachment = chatVm::removeAttachment,
                    onEditAndResend = chatVm::editAndResend,
                )
                1 -> AgentScreen(
                    state = agentState,
                    onPrompt = agentVm::setPrompt,
                    onStack = agentVm::setStack,
                    onBuild = agentVm::build,
                    onStop = agentVm::stop,
                    onOpenProject = agentVm::openProject,
                    onDeleteProject = agentVm::deleteProject,
                    onRefreshPreview = agentVm::refreshPreview,
                    onDismissBanner = agentVm::dismissBanner,
                    onOpenPicker = agentVm::openPicker,
                    onClosePicker = agentVm::closePicker,
                    onPickModel = agentVm::pickBuildModel,
                    onImagePrompt = agentVm::setImagePrompt,
                    onSelectImageModel = agentVm::setImageModel,
                    onGenerateImage = agentVm::generateImage,
                    onDismissImageError = agentVm::dismissImageError,
                    onOpenFiles = agentVm::openFiles,
                    onCloseFiles = agentVm::closeFiles,
                    onOpenFile = agentVm::openFile,
                    onCloseFile = agentVm::closeFile,
                    onBeginEdit = agentVm::beginEdit,
                    onDraft = agentVm::updateDraft,
                    onSaveEdit = agentVm::saveEdit,
                    onCancelEdit = agentVm::cancelEdit,
                    onAskDelete = agentVm::askDelete,
                    onDismissDelete = agentVm::dismissDelete,
                    onConfirmDelete = agentVm::confirmDelete,
                    onExport = {
                        ProjectExport.share(
                            ctx,
                            java.io.File(ctx.filesDir, "projects/${agentState.filesSlug}"),
                        )
                    },
                    onDeleteAll = agentVm::deleteAllProjects,
                )
                2 -> ModelsScreen(
                    state = modelsState,
                    onDownload = modelsVm::download,
                    onDelete = modelsVm::delete,
                    onLoad = modelsVm::load,
                    onLoadImported = modelsVm::loadImported,
                    onUnload = modelsVm::unload,
                    onImport = modelsVm::importModel,
                    onDownloadUrl = modelsVm::downloadFromUrl,
                    onRefresh = modelsVm::refresh,
                    onDismiss = modelsVm::dismissMessages,
                )
                else -> SettingsScreen(
                    state = settingsState,
                    onContextLength = settingsVm::setContextLength,
                    onGpuLayers = settingsVm::setGpuLayers,
                    onThreads = settingsVm::setThreads,
                    onBatchSize = settingsVm::setBatchSize,
                    onMmap = settingsVm::setMmap,
                    onMlock = settingsVm::setMlock,
                    onFlashAttention = settingsVm::setFlashAttention,
                    onQ8Kv = settingsVm::setQ8Kv,
                    onSpeculativeEnabled = settingsVm::setSpeculativeEnabled,
                    onDraftModel = settingsVm::setDraftModel,
                    onDraftTokens = settingsVm::setDraftTokens,
                    onTemperature = settingsVm::setTemperature,
                    onTopP = settingsVm::setTopP,
                    onMaxTokens = settingsVm::setMaxTokens,
                    onSystemPrompt = settingsVm::setSystemPrompt,
                    onCloudEnabled = settingsVm::setCloudEnabled,
                    onAutoSwitchModels = settingsVm::setAutoSwitchModels,
                    onUnrestrictedMode = settingsVm::setUnrestrictedMode,
                    onThemeMode = settingsVm::setThemeMode,
                    onAccentColor = settingsVm::setAccentColor,
                    onFontFamily = settingsVm::setFontFamily,
                    onApplyDefaults = settingsVm::applyDeviceDefaults,
                    onPanic = settingsVm::panic,
                    onClearCrashLog = settingsVm::clearCrashLog,
                    onDismissNotice = settingsVm::dismissNotice,
                )
            }
        }
    }
}

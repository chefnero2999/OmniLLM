package com.omni.llm.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omni.llm.AppContainer
import com.omni.llm.OmniApp
import com.omni.llm.core.LlamaBridge
import com.omni.llm.data.EngineState
import com.omni.llm.data.ModelDownloadManager
import com.omni.llm.data.ModelEntry
import com.omni.llm.data.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class ModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as OmniApp).container

    data class Item(
        val entry: ModelEntry,
        val download: ModelDownloadManager.State,
        val gate: ModelRepository.Gate,
        val isActive: Boolean,
    )

    data class ImportedItem(val file: File, val isActive: Boolean)

    data class UiState(
        val items: List<Item> = emptyList(),
        val imported: List<ImportedItem> = emptyList(),
        val loading: Boolean = false,
        val loadProgress: Float? = null,
        val loadingModelId: String? = null,
        val message: String? = null,
        val error: String? = null,
        val deviceSummary: String = "",
        val freeStorage: String = "",
        val activeModelId: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.models.manifest.collect { manifest ->
                rebuild(manifest.models)
            }
        }
        viewModelScope.launch {
            container.downloads.states.collect { rebuild(container.models.entries()) }
        }
        viewModelScope.launch {
            container.models.imports.collect { rebuild(container.models.entries()) }
        }
        viewModelScope.launch {
            container.engine.loaded.collect { rebuild(container.models.entries()) }
        }
        viewModelScope.launch {
            container.settings.settings.collect { rebuild(container.models.entries()) }
        }
        viewModelScope.launch {
            container.llm.connection.collect {
                container.refreshProfile()
                rebuild(container.models.entries())
            }
        }
        refresh()
    }

    private fun rebuild(entries: List<ModelEntry>) {
        val profile = container.probe.probe()
        val activeId = container.engine.loaded.value?.modelId
        _state.value = _state.value.copy(
            items = entries.map { entry ->
                Item(
                    entry = entry,
                    download = container.models.stateFor(entry),
                    gate = container.models.gate(entry, profile),
                    isActive = activeId == entry.id,
                )
            },
            imported = container.models.imports.value.map {
                ImportedItem(it, activeId == it.name)
            },
            activeModelId = activeId,
            deviceSummary = "${profile.model} · ${String.format("%.1f", profile.totalRamGb)} GB RAM · " +
                "${profile.cores} cores · ${String.format("%.1f", profile.availRamGb)} GB free",
            freeStorage = com.omni.llm.util.Formatters.bytes(container.probe.freeModelStorageBytes()),
        )
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching { container.models.refreshManifest(refreshRemote = true) }
            rebuild(container.models.entries())
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun download(entry: ModelEntry) {
        viewModelScope.launch {
            val result = container.models.startDownload(entry)
            result.exceptionOrNull()?.let { error ->
                _state.value = _state.value.copy(error = error.message)
            }
        }
    }

    fun delete(entry: ModelEntry) {
        viewModelScope.launch {
            if (container.engine.loaded.value?.modelId == entry.id) unload()
            container.models.deleteModel(entry)
        }
    }

    fun dismissMessages() {
        _state.value = _state.value.copy(error = null, message = null)
    }

    /**
     * Load sequence, in the order that prevents crashes:
     *   1. clamp the user's settings to this device's caps;
     *   2. hand the config to the `:llm` process (which repeats the pre-flight check itself);
     *   3. on failure with GPU offload, retry once on CPU — that single retry is what turns a
     *      crash on a specific Adreno driver into a working app.
     */
    fun load(entry: ModelEntry) {
        viewModelScope.launch {
            val file = container.models.fileFor(entry)
            loadFile(
                file = file,
                modelId = entry.id,
                kvBytesPerToken = entry.kvBytesPerToken,
                displayName = entry.name,
            )
        }
    }

    fun loadImported(file: File) {
        viewModelScope.launch {
            // Imported models: assume Qwen/Llama-ish KV cost; the native side refines nothing here,
            // so use a slightly pessimistic estimate.
            loadFile(file, "imported:${file.name}", 128L * 1024, file.name)
        }
    }

    private suspend fun loadFile(
        file: File,
        modelId: String,
        kvBytesPerToken: Long,
        displayName: String,
    ) {
        val settings = container.settings.settings.first()
        val profile = container.probe.probe()
        val caps = container.probe.caps(profile)
        val gpuKnown = (container.llm.connection.value as? com.omni.llm.service.LlmClient.Connection.Ready)
            ?.gpuAvailable ?: false
        val enforced = container.settings.enforce(settings, caps, gpuKnown)

        _state.value = _state.value.copy(
            error = null,
            message = null,
            loadProgress = 0f,
            loadingModelId = modelId,
        )

        // Speculative decoding: only honoured when the user turned it on AND a distinct draft
        // model is downloaded. Any problem degrades to plain decoding, never to a failed load.
        val draft = resolveDraft(enforced.settings, modelId)
        var attempt = configFor(enforced.settings, draft)
        var result = attemptLoad(file, modelId, attempt, kvBytesPerToken) { fraction ->
            _state.value = _state.value.copy(loadProgress = fraction)
        }

        if (result.isFailure && attempt.gpuLayers > 0) {
            val firstError = result.exceptionOrNull()?.message.orEmpty()
            _state.value = _state.value.copy(
                loadProgress = 0f,
                message = "GPU load failed — retrying on CPU. ($firstError)",
            )
            attempt = attempt.copy(gpuLayers = 0, flashAttention = false)
            result = attemptLoad(file, modelId, attempt, kvBytesPerToken) { fraction ->
                _state.value = _state.value.copy(loadProgress = fraction)
            }
        }

        result.onSuccess { loaded ->
            container.engine.setLoaded(
                EngineState.Loaded(
                    modelId = modelId,
                    fileName = file.name,
                    description = loaded.description.ifBlank { displayName },
                    contextLength = loaded.context,
                    trainContext = loaded.trainContext,
                    gpuLayers = loaded.gpuLayers,
                    threads = loaded.threads,
                    mmap = loaded.mmap,
                    parameters = loaded.parameters,
                    loadedAtMillis = System.currentTimeMillis(),
                    speculative = loaded.draftLoaded,
                    draftModelId = if (loaded.draftLoaded) enforced.settings.draftModelId else null,
                    draftTokens = if (loaded.draftLoaded) enforced.settings.draftTokens else 0,
                )
            )
            container.settings.setActiveModel(modelId)
            val warnings = enforced.warnings
            _state.value = _state.value.copy(
                loadingModelId = null,
                loadProgress = null,
                message = buildString {
                    append("Loaded $displayName · ${loaded.context} ctx")
                    if (loaded.speculative && loaded.draftLoaded) append(" · draft model active")
                    else if (loaded.speculative) append(" · draft model requested but NOT loaded")
                    if (warnings.isNotEmpty()) append("\n" + warnings.joinToString("\n"))
                },
                error = null,
            )
        }.onFailure { failure ->
            _state.value = _state.value.copy(
                loadingModelId = null,
                loadProgress = null,
                error = failure.message ?: "Could not load the model.",
            )
        }
    }

    private suspend fun attemptLoad(
        file: File,
        modelId: String,
        config: LlamaBridge.LoadConfig,
        kvBytesPerToken: Long,
        onProgress: (Float) -> Unit,
    ): Result<com.omni.llm.service.LlmClient.LoadResult> = container.llm.load(
        modelFile = file,
        modelId = modelId,
        config = config,
        kvBytesPerToken = kvBytesPerToken,
        onProgress = onProgress,
    )

    /**
     * Resolves the draft model file for speculative decoding, or null when it should stay off.
     * Rejects self-pairing (draft == target), which would only burn memory and time.
     */
    private fun resolveDraft(
        settings: com.omni.llm.data.OmniSettings,
        targetModelId: String,
    ): File? {
        if (!settings.speculativeEnabled) return null
        val draftId = settings.draftModelId ?: return null
        if (draftId == targetModelId) return null
        val entry = container.models.entry(draftId) ?: return null
        val draftFile = container.models.fileFor(entry)
        if (!draftFile.isFile || draftFile.length() == 0L) return null
        return draftFile
    }

    private fun configFor(
        settings: com.omni.llm.data.OmniSettings,
        draft: File? = null,
    ): LlamaBridge.LoadConfig =
        LlamaBridge.LoadConfig(
            nCtx = settings.contextLength,
            nBatch = settings.batchSize,
            nUbatch = settings.batchSize,
            nThreads = settings.threads,
            gpuLayers = settings.gpuLayers,
            useMmap = settings.useMmap,
            useMlock = settings.useMlock,
            flashAttention = settings.flashAttention,
            q8KvCache = settings.q8KvCache,
            draftModelPath = draft?.absolutePath,
            draftGpuLayers = if (draft != null) settings.gpuLayers else 0,
            draftTokens = settings.draftTokens,
        )

    fun unload() {
        container.llm.unload()
        container.engine.clear()
        viewModelScope.launch { container.settings.setActiveModel(null) }
        _state.value = _state.value.copy(message = "Model unloaded — memory released.")
    }

    /** Download any GGUF from a user-supplied https URL. No catalog, no gate. */
    fun downloadFromUrl(url: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, message = null)
            val result = container.downloads.enqueueUrl(url)
            result.onSuccess {
                _state.value = _state.value.copy(
                    loading = false,
                    message = "Downloading from that URL. It will appear under Imported when finished.",
                )
            }.onFailure { failure ->
                _state.value = _state.value.copy(loading = false, error = failure.message)
            }
            rebuild(container.models.entries())
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, message = "Importing model…")
            val result = container.models.importGguf(uri)
            result.onSuccess { file ->
                _state.value = _state.value.copy(
                    loading = false,
                    message = "Imported ${file.name} (${com.omni.llm.util.Formatters.bytes(file.length())}). " +
                        "Tap Load to run it.",
                )
            }.onFailure { failure ->
                _state.value = _state.value.copy(loading = false, error = failure.message)
            }
            rebuild(container.models.entries())
        }
    }
}

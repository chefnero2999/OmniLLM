package com.omni.llm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omni.llm.AppContainer
import com.omni.llm.OmniApp
import com.omni.llm.core.LlamaBridge
import com.omni.llm.data.DeviceCapabilityProbe
import com.omni.llm.data.OmniSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as OmniApp).container

    // A nested data class is static: it cannot reference the outer `container` in a default
    // argument. These are resolved from the instance instead (see the `state` initialiser below).
    data class UiState(
        val settings: OmniSettings = OmniSettings(),
        val profile: DeviceCapabilityProbe.DeviceProfile,
        val caps: DeviceCapabilityProbe.Caps,
        val defaults: DeviceCapabilityProbe.TierDefaults,
        val kvEstimateBytes: Long = 0,
        val warnings: List<String> = emptyList(),
        val info: String? = null,
        val gpuAvailable: Boolean = false,
        val backendReport: String = "",
        val crashLog: String = "",
        /** Settings that only take effect on the next model load (context/threads/batch/mmap...). */
        val needsReload: Boolean = false,
        /** Downloaded models that could serve as a speculative draft. */
        val draftCandidates: List<com.omni.llm.data.ModelEntry> = emptyList(),
        /** Non-null when the current target/draft pair is unusable; shown verbatim. */
        val draftVerdict: String? = null,
        val draftChecking: Boolean = false,
        /** Keyless cloud models, verified available vs. paywalled, for the picker. */
        val cloudCatalog: List<com.omni.llm.data.KeylessCloudRouter.CatalogEntry> = emptyList(),
        val themeMode: String = "system",
    )

    private val _state = MutableStateFlow(
        run {
            val p = container.probe.probe()
            UiState(
                profile = p,
                caps = container.probe.caps(p),
                defaults = container.probe.defaults(p),
                cloudCatalog = container.cloud.catalog(),
            )
        }
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                recompute(settings)
            }
        }
        viewModelScope.launch {
            container.llm.connection.collect { conn ->
                if (conn is com.omni.llm.service.LlmClient.Connection.Ready) {
                    container.refreshProfile()
                    recompute(container.settings.settings.first())
                }
            }
        }
        viewModelScope.launch {
            // Downloaded set changes as files arrive; keep the draft picker in sync.
            container.downloads.states.collect { refreshDraftCandidates() }
        }
        refreshDiagnostics()
    }

    private fun recompute(settings: OmniSettings) {
        val profile = container.probe.probe()
        val caps = container.probe.caps(profile)
        val gpu = (container.llm.connection.value as? com.omni.llm.service.LlmClient.Connection.Ready)?.gpuAvailable
            ?: false
        val enforced = container.settings.enforce(settings, caps, gpu)
        val loaded = container.engine.loaded.value
        val kvBytes = if (loaded != null) {
            LlamaBridge.estimateKvBytes(settings.contextLength, settings.q8KvCache && settings.flashAttention)
        } else {
            // Generic estimate before any model is loaded: ~0.22 MB per 1k tokens for a ~1B Q4 model.
            settings.contextLength.toLong() * 225
        }
        // `it` here is the *loaded engine state*, so compare it against the settings — mixing the
        // two up makes the compiler look for speculativeEnabled on Loaded, which does not have it.
        val needsReload = loaded != null && (
            loaded.contextLength != settings.contextLength ||
                loaded.threads != settings.threads ||
                loaded.gpuLayers != settings.gpuLayers ||
                loaded.mmap != settings.useMmap ||
                loaded.speculative != settings.speculativeEnabled ||
                loaded.draftModelId != settings.draftModelId ||
                loaded.draftTokens != settings.draftTokens
            )
        _state.value = _state.value.copy(
            settings = settings,
            profile = profile,
            caps = caps,
            defaults = container.probe.defaults(profile),
            kvEstimateBytes = kvBytes,
            warnings = enforced.warnings,
            gpuAvailable = gpu,
            needsReload = needsReload,
            themeMode = settings.themeMode,
        )
        refreshDraftCandidates()
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val conn = container.llm.connection.value
            _state.value = _state.value.copy(
                crashLog = container.guard.crashLogTail(),
                backendReport = (conn as? com.omni.llm.service.LlmClient.Connection.Ready)?.backendReport
                    ?: "Connect to the engine (load a model) to read the backend list.",
                gpuAvailable = (conn as? com.omni.llm.service.LlmClient.Connection.Ready)?.gpuAvailable ?: false,
            )
        }
    }

    /**
     * Models that are actually on disk. The target model is excluded: a model cannot draft for
     * itself (it costs a second copy of the same weights and buys nothing).
     */
    private fun refreshDraftCandidates() {
        // Manifest metadata only: no file I/O on the main thread. ModelEntry.fileSizeBytes is the
        // same number the downloader verified, so it is trustworthy for the picker.
        val activeId = container.engine.loaded.value?.modelId
            ?: _state.value.settings.activeModelId
        val onDisk = container.models.entries()
            .filter { container.models.stateFor(it) == com.omni.llm.data.ModelDownloadManager.State.Ready }
        val candidates = onDisk.filter { it.id != activeId }
        _state.value = _state.value.copy(draftCandidates = candidates)
        validateDraftPair()
    }

    /**
     * Runs the same header-only compatibility check the engine uses, so an incompatible pair is
     * reported in Settings instead of silently doing nothing at load time.
     */
    private fun validateDraftPair() {
        val st = _state.value
        if (st.draftChecking) return
        if (!st.settings.speculativeEnabled) {
            if (st.draftVerdict != null) _state.value = st.copy(draftVerdict = null, draftChecking = false)
            return
        }
        val draftId = st.settings.draftModelId
        val activeId = container.engine.loaded.value?.modelId ?: st.settings.activeModelId
        val target = activeId?.let { id ->
            container.models.entry(id)?.let { container.models.fileFor(it) }
                ?.takeIf { it.isFile }
        }
        val draft = draftId?.let { id ->
            container.models.entry(id)?.let { container.models.fileFor(it) }
                ?.takeIf { it.isFile }
        }
        if (draft == null) {
            _state.value = st.copy(
                draftVerdict = "Pick a draft model — speculative decoding stays off until one is chosen.",
            )
            return
        }
        if (target == null) {
            // Nothing loaded yet: compatibility can only be judged at load time.
            _state.value = st.copy(draftVerdict = null)
            return
        }
        val availBytes = st.profile.availRamBytes
        val nCtx = st.settings.contextLength
        _state.value = st.copy(draftChecking = true)
        viewModelScope.launch {
            val verdict = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LlamaBridge.speculativeVerdict(target, draft, availBytes, nCtx)
            }
            _state.value = _state.value.copy(draftVerdict = verdict, draftChecking = false)
        }
    }

    fun setSpeculativeEnabled(value: Boolean) {
        update { it.copy(speculativeEnabled = value) }
        validateDraftPair()
    }

    fun setDraftModel(id: String?) {
        update { it.copy(draftModelId = id) }
        validateDraftPair()
    }

    fun setDraftTokens(value: Int) = update { it.copy(draftTokens = value.coerceIn(2, 8)) }

    // ---- setters ----------------------------------------------------------------------------

    fun setContextLength(value: Int) = update { it.copy(contextLength = value.coerceIn(512, _state.value.caps.maxContext)) }
    fun setGpuLayers(value: Int) = update { it.copy(gpuLayers = value.coerceIn(0, _state.value.caps.maxGpuLayers)) }
    fun setThreads(value: Int) = update { it.copy(threads = value.coerceIn(1, _state.value.caps.maxThreads), autoThreads = false) }
    fun setBatchSize(value: Int) = update { it.copy(batchSize = value) }
    fun setMmap(value: Boolean) = update { it.copy(useMmap = value) }
    fun setMlock(value: Boolean) = update { it.copy(useMlock = value) }
    fun setFlashAttention(value: Boolean) = update { it.copy(flashAttention = value) }
    fun setQ8Kv(value: Boolean) = update { it.copy(q8KvCache = value) }
    fun setTemperature(value: Float) = update { it.copy(temperature = value) }
    fun setTopP(value: Float) = update { it.copy(topP = value) }
    fun setMaxTokens(value: Int) = update { it.copy(maxTokens = value) }
    fun setSystemPrompt(value: String) = update { it.copy(systemPrompt = value) }
    fun setCloudModel(providerAndModel: String?) {
        // Encoded as "providerId|modelId" so one pref can pin both.
        val model = providerAndModel?.substringAfter('|', "")
        update { it.copy(cloudModel = model?.takeIf { m -> m.isNotBlank() }) }
    }

    fun setCloudEnabled(value: Boolean) {
        container.cloudGate.setEnabled(value)
        update { it.copy(cloudFallbackEnabled = value) }
    }

    fun dismissNotice() = update { it.copy(pendingCrashNotice = null) }

    /** "Reset to this device's defaults" — the non-destructive version of the panic button. */
    fun setAutoSwitchModels(value: Boolean) {
        viewModelScope.launch { container.settings.setAutoSwitchModels(value) }
    }

    fun setUnrestrictedMode(value: Boolean) {
        viewModelScope.launch { container.settings.setUnrestrictedMode(value) }
    }

    fun setThemeMode(value: String) {
        update { it.copy(themeMode = value) }
    }

    fun applyDeviceDefaults() {
        val d = _state.value.defaults
        update {
            it.copy(
                contextLength = d.contextLength,
                gpuLayers = d.gpuLayers,
                threads = d.threads,
                batchSize = d.batchSize,
                useMmap = d.useMmap,
                useMlock = d.useMlock,
                flashAttention = false,
                q8KvCache = false,
            )
        }
    }

    /**
     * Panic button. Cancels any running generation, unloads the model in the `:llm` process,
     * drops every aggressive setting, and clears the crash-loop history.
     */
    fun panic() {
        viewModelScope.launch {
            container.llm.cancel()
            container.llm.unload()
            container.engine.clear()
            container.settings.panicSafeDefaults()
            container.llm.resetConversation()
            _state.value = _state.value.copy(info = "Unloaded, KV cache cleared, CPU-only defaults restored.")
            refreshDiagnostics()
        }
    }

    fun clearCrashLog() {
        container.guard.clearCrashLog()
        refreshDiagnostics()
    }

    private fun update(transform: (OmniSettings) -> OmniSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    companion object {
        val CONTEXT_STEPS = (512..8192 step 512).toList()
        val BATCH_STEPS = listOf(128, 256, 512, 1024)
        fun maxSliderThreads(profile: DeviceCapabilityProbe.DeviceProfile) = max(1, minOf(8, profile.cores))
    }
}

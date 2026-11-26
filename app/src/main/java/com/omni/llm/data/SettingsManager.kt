package com.omni.llm.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "omni_settings")

/**
 * All user-visible state that affects inference. Persisted in DataStore (not SharedPreferences)
 * because the UI observes it: the sliders and the running model must never disagree.
 *
 * The service process never reads this file directly (DataStore is single-process). The app
 * process reads it and ships an explicit config bundle to the service on every load.
 */
class SettingsManager(private val context: Context) {

    private object Keys {
        val contextLength = intPreferencesKey("context_length")
        val gpuLayers = intPreferencesKey("gpu_layers")
        val threads = intPreferencesKey("threads")
        val batchSize = intPreferencesKey("batch_size")
        val useMmap = booleanPreferencesKey("use_mmap")
        val useMlock = booleanPreferencesKey("use_mlock")
        val flashAttention = booleanPreferencesKey("flash_attention")
        val q8Kv = booleanPreferencesKey("q8_kv")
        val temperature = floatPreferencesKey("temperature")
        val topP = floatPreferencesKey("top_p")
        val maxTokens = intPreferencesKey("max_tokens")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val cloudEnabled = booleanPreferencesKey("cloud_fallback_enabled")
        val cloudModel = stringPreferencesKey("cloud_model")
        val activeModelId = stringPreferencesKey("active_model_id")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val lastStableGpuLayers = intPreferencesKey("last_stable_gpu_layers")
        val autoThreads = booleanPreferencesKey("auto_threads")
        val pendingCrashNotice = stringPreferencesKey("pending_crash_notice")
        val speculativeEnabled = booleanPreferencesKey("speculative_enabled")
        val draftModelId = stringPreferencesKey("draft_model_id")
        val draftTokens = intPreferencesKey("draft_tokens")
        val initialized = booleanPreferencesKey("initialized")
        val autoSwitchModels = booleanPreferencesKey("auto_switch_models")
        val unrestrictedMode = booleanPreferencesKey("unrestricted_mode")
        val themeMode = stringPreferencesKey("theme_mode")
        val accentColor = stringPreferencesKey("accent_color")
        val fontFamily = stringPreferencesKey("font_family")
    }

    val settings: Flow<OmniSettings> = context.dataStore.data.map { p ->
        OmniSettings(
            contextLength = p[Keys.contextLength] ?: 2048,
            gpuLayers = p[Keys.gpuLayers] ?: 0,
            threads = p[Keys.threads] ?: 4,
            batchSize = p[Keys.batchSize] ?: 256,
            useMmap = p[Keys.useMmap] ?: true,
            useMlock = p[Keys.useMlock] ?: false,
            flashAttention = p[Keys.flashAttention] ?: false,
            q8KvCache = p[Keys.q8Kv] ?: false,
            temperature = p[Keys.temperature] ?: 0.7f,
            topP = p[Keys.topP] ?: 0.9f,
            maxTokens = p[Keys.maxTokens] ?: 512,
            systemPrompt = p[Keys.systemPrompt] ?: OmniSettings.DEFAULT_SYSTEM_PROMPT,
            cloudFallbackEnabled = p[Keys.cloudEnabled] ?: true,
            cloudModel = p[Keys.cloudModel],
            activeModelId = p[Keys.activeModelId],
            onboardingComplete = p[Keys.onboardingComplete] ?: false,
            lastStableGpuLayers = p[Keys.lastStableGpuLayers] ?: 0,
            autoThreads = p[Keys.autoThreads] ?: true,
            pendingCrashNotice = p[Keys.pendingCrashNotice],
            speculativeEnabled = p[Keys.speculativeEnabled] ?: false,
            draftModelId = p[Keys.draftModelId],
            draftTokens = p[Keys.draftTokens] ?: 4,
            autoSwitchModels = p[Keys.autoSwitchModels] ?: true,
            unrestrictedMode = p[Keys.unrestrictedMode] ?: true,
            themeMode = p[Keys.themeMode] ?: "system",
            accentColor = p[Keys.accentColor] ?: "volt",
            fontFamily = p[Keys.fontFamily] ?: "default",
        )
    }

    /**
     * Applies the Section 4.1 defaults exactly once. Called from [OmniApp] on every start; it is
     * a no-op after the first run so user choices are never stomped.
     */
    suspend fun initializeDefaults(performanceCores: Int, defaults: DeviceCapabilityProbe.TierDefaults) {
        val current = context.dataStore.data.first()
        if (current[Keys.initialized] == true) return
        context.dataStore.edit { p ->
            p[Keys.contextLength] = defaults.contextLength
            p[Keys.gpuLayers] = defaults.gpuLayers
            p[Keys.threads] = minOf(defaults.threads, performanceCores)
            p[Keys.batchSize] = defaults.batchSize
            p[Keys.useMmap] = defaults.useMmap
            p[Keys.useMlock] = defaults.useMlock
            p[Keys.flashAttention] = false
            p[Keys.maxTokens] = 512
            p[Keys.temperature] = 0.7f
            p[Keys.topP] = 0.9f
            p[Keys.autoThreads] = true
            p[Keys.cloudEnabled] = true
            p[Keys.unrestrictedMode] = true
            p[Keys.speculativeEnabled] = false
            p[Keys.draftTokens] = 4
            p[Keys.initialized] = true
        }
    }

    suspend fun update(transform: (OmniSettings) -> OmniSettings) {
        val current = settings.first()
        val next = transform(current)
        context.dataStore.edit { p ->
            p[Keys.contextLength] = next.contextLength
            p[Keys.gpuLayers] = next.gpuLayers
            p[Keys.threads] = next.threads
            p[Keys.batchSize] = next.batchSize
            p[Keys.useMmap] = next.useMmap
            p[Keys.useMlock] = next.useMlock
            p[Keys.flashAttention] = next.flashAttention
            p[Keys.q8Kv] = next.q8KvCache
            p[Keys.temperature] = next.temperature
            p[Keys.topP] = next.topP
            p[Keys.maxTokens] = next.maxTokens
            p[Keys.systemPrompt] = next.systemPrompt
            p[Keys.cloudEnabled] = next.cloudFallbackEnabled
            val cm = next.cloudModel
            if (cm != null) p[Keys.cloudModel] = cm else p.remove(Keys.cloudModel)
            next.activeModelId?.let { p[Keys.activeModelId] = it }
            p[Keys.speculativeEnabled] = next.speculativeEnabled
            p[Keys.draftTokens] = next.draftTokens
            // One decision, not set-then-maybe-remove: null clears the key entirely.
            val draftId = next.draftModelId
            if (draftId != null) p[Keys.draftModelId] = draftId else p.remove(Keys.draftModelId)
            p[Keys.onboardingComplete] = next.onboardingComplete
            p[Keys.lastStableGpuLayers] = next.lastStableGpuLayers
            p[Keys.autoThreads] = next.autoThreads
            val notice = next.pendingCrashNotice
            if (notice != null) p[Keys.pendingCrashNotice] = notice
            else p.remove(Keys.pendingCrashNotice)
            p[Keys.autoSwitchModels] = next.autoSwitchModels
            p[Keys.unrestrictedMode] = next.unrestrictedMode
            p[Keys.themeMode] = next.themeMode
            p[Keys.accentColor] = next.accentColor
            p[Keys.fontFamily] = next.fontFamily
        }
    }

    suspend fun setOnboardingComplete(value: Boolean) = update { it.copy(onboardingComplete = value) }

    /** The user's chosen cloud model, as `providerId|modelId`. */
    suspend fun setCloudModel(value: String?) = update { it.copy(cloudModel = value) }

    suspend fun setAutoSwitchModels(value: Boolean) = update { it.copy(autoSwitchModels = value) }

    suspend fun setUnrestrictedMode(value: Boolean) = update { it.copy(unrestrictedMode = value) }

    /**
     * The system prompt actually used, given the mode.
     *
     * Unrestricted mode swaps this app's own editorial instruction for a neutral one. It cannot and
     * does not touch the provider's safety training — see [OmniSettings.unrestrictedMode].
     */
    fun effectiveSystemPrompt(settings: OmniSettings): String {
        if (!settings.unrestrictedMode) return settings.systemPrompt
        // Only replace if the user has not written their own prompt.
        if (settings.systemPrompt != OmniSettings.DEFAULT_SYSTEM_PROMPT) return settings.systemPrompt
        return OmniSettings.UNRESTRICTED_SYSTEM_PROMPT
    }
    suspend fun setActiveModel(id: String?) {
        context.dataStore.edit { it[Keys.activeModelId] = id ?: "" }
    }
    suspend fun clearActiveModel() { context.dataStore.edit { it.remove(Keys.activeModelId) } }

    /** Panic button: unload-safe, CPU-only, smallest context, everything conservative. */
    suspend fun panicSafeDefaults() = update {
        it.copy(
            gpuLayers = 0,
            contextLength = 2048,
            threads = 4,
            batchSize = 128,
            speculativeEnabled = false,
            draftModelId = null,
            autoThreads = true,
            useMmap = true,
            useMlock = false,
            flashAttention = false,
            q8KvCache = false,
            lastStableGpuLayers = 0,
        )
    }

    /**
     * Enforces the hard caps at the point of use. Returns the settings that will actually be
     * handed to the native layer plus the messages the UI should show.
     */
    fun enforce(
        settings: OmniSettings,
        caps: DeviceCapabilityProbe.Caps,
        gpuAvailable: Boolean,
    ): Enforced {
        val warnings = mutableListOf<String>()
        var ctx = settings.contextLength.coerceIn(512, 200_000)
        if (ctx > 8_000) {
            warnings.add("Context is above 8,000 tokens (${ctx}) — high RAM consumption on local GGUF models.")
        }
        if (settings.maxTokens > 8_000) {
            warnings.add("Max answer length is above 8,000 tokens (${settings.maxTokens}) — may exceed provider limits or take longer.")
        }
        var layers = settings.gpuLayers
        if (!gpuAvailable && layers > 0) {
            warnings.add("GPU offload requested but this build has no Vulkan backend — running on CPU.")
            layers = 0
        }
        if (layers > caps.maxGpuLayers) {
            warnings.add("GPU layers capped at ${caps.maxGpuLayers} for safety on this device.")
            layers = caps.maxGpuLayers
        }
        var threads = settings.threads.coerceIn(1, caps.maxThreads)
        if (threads != settings.threads) {
            warnings.add("Threads capped at ${caps.maxThreads} (this device has ${caps.maxThreads} usable cores).")
        }
        var batch = settings.batchSize.coerceIn(128, caps.maxBatch)
        // llama.cpp compatibility: flash attention is forced off when layers are offloaded.
        var flash = settings.flashAttention
        if (layers > 0 && flash) {
            flash = false
            warnings.add("Flash attention disabled because GPU offload is on — required on Android.")
        }
        if (settings.q8KvCache && !flash) {
            warnings.add("Q8 KV cache requires flash attention; keeping the KV cache at f16.")
        }
        return Enforced(
            settings = settings.copy(
                contextLength = ctx,
                gpuLayers = layers,
                threads = threads,
                batchSize = batch,
                flashAttention = flash,
                q8KvCache = settings.q8KvCache && flash,
            ),
            warnings = warnings,
        )
    }

    data class Enforced(val settings: OmniSettings, val warnings: List<String>)
}

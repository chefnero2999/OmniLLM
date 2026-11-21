package com.omni.llm.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the `:llm` process currently has in memory, as far as the UI can know.
 *
 * Kept separate from [OmniSettings] because it is runtime truth, not user intent: settings say
 * "I want 20 GPU layers", this says "16 layers are loaded and the last generation did 12 tok/s".
 */
class EngineState {

    data class Loaded(
        val modelId: String,
        val fileName: String,
        val description: String,
        val contextLength: Int,
        val trainContext: Int,
        val gpuLayers: Int,
        val threads: Int,
        val mmap: Boolean,
        val parameters: Long,
        val loadedAtMillis: Long,
        val lastTokensPerSec: Double = 0.0,
        val lastPrefillMs: Long = 0,
        val lastDecodeMs: Long = 0,
        val lastGeneratedTokens: Int = 0,
        // Speculative decoding actually engaged for this load (not merely requested).
        val speculative: Boolean = false,
        val draftModelId: String? = null,
        val draftTokens: Int = 0,
    )

    private val _loaded = MutableStateFlow<Loaded?>(null)
    val loaded: StateFlow<Loaded?> = _loaded.asStateFlow()

    fun setLoaded(value: Loaded) {
        _loaded.value = value
    }

    fun clear() {
        _loaded.value = null
    }

    fun recordStats(tokensPerSec: Double, prefillMs: Long, decodeMs: Long, generated: Int) {
        _loaded.value = _loaded.value?.copy(
            lastTokensPerSec = tokensPerSec,
            lastPrefillMs = prefillMs,
            lastDecodeMs = decodeMs,
            lastGeneratedTokens = generated,
        )
    }
}

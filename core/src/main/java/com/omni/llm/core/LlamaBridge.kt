package com.omni.llm.core

import org.json.JSONObject
import java.io.File

/**
 * Thin Kotlin wrapper over the llama.cpp JNI bridge.
 *
 * Everything in here is meant to be called from a single worker thread inside the `:llm`
 * process. The class makes no attempt to be thread-safe beyond [cancel], which is safe to
 * call from any thread — that is what the Stop button uses.
 *
 * Anti-crash contract:
 *  - No call in this file throws [OutOfMemoryError] at the caller. Native OOM is reported as
 *    [Status.OOM] and translated into [InferenceException], which the service catches.
 *  - The native side never calls abort(): a bad decode or a corrupt model comes back as a
 *    status code, so the JVM stays alive and the UI can show a "Reload model" state.
 */
object LlamaBridge {

    /** Mirrors `omni::Status` in omni_engine.h. Do not renumber without updating both sides. */
    enum class Status(val code: Int) {
        OK(0),
        NOT_LOADED(1),
        ALREADY_LOADED(2),
        LOAD_FAILED(3),
        DECODE_FAILED(4),
        CONTEXT_FULL(5),
        CANCELLED(6),
        BAD_INPUT(7),
        OOM(8);

        companion object {
            fun of(code: Int): Status = entries.firstOrNull { it.code == code } ?: LOAD_FAILED
        }
    }

    class InferenceException(
        val status: Status,
        message: String,
        cause: Throwable? = null,
    ) : RuntimeException("[${status.name}] $message", cause)

    /** Runtime knobs that map 1:1 onto llama_context_params / llama_model_params. */
    data class LoadConfig(
        val nCtx: Int = 2048,
        val nBatch: Int = 512,
        val nUbatch: Int = 512,
        val nThreads: Int = 4,
        val gpuLayers: Int = 0,
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
        val flashAttention: Boolean = false,
        val q8KvCache: Boolean = false,
        // Speculative decoding. Leave draftModelPath null for plain decoding (the default).
        val draftModelPath: String? = null,
        val draftGpuLayers: Int = 0,
        val draftTokens: Int = 4,
    ) {
        init {
            require(nCtx >= 128) { "nCtx must be >= 128" }
            require(nUbatch <= nBatch) { "nUbatch ($nUbatch) must be <= nBatch ($nBatch)" }
            require(gpuLayers >= 0) { "gpuLayers must be >= 0" }
            require(nThreads in 1..16) { "nThreads must be in 1..16" }
            require(draftTokens in 2..8) { "draftTokens must be in 2..8" }
            require(draftGpuLayers >= 0) { "draftGpuLayers must be >= 0" }
        }
    }

    data class SamplingConfig(
        val maxTokens: Int = 512,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40,
        val minP: Float = 0.05f,
        val repeatPenalty: Float = 1.1f,
        val seed: Int = -1,
    )

    data class Stats(
        val promptTokens: Int,
        val evaluatedTokens: Int,
        val generatedTokens: Int,
        val prefillMs: Long,
        val decodeMs: Long,
        val tokensPerSec: Double,
        val contextFull: Boolean,
        val draftProposed: Int = 0,
        val draftAccepted: Int = 0,
    ) {
        /** Fraction of proposed draft tokens the target model accepted, or null when spec is off. */
        val draftAcceptanceRate: Double?
            get() = if (draftProposed <= 0) null else draftAccepted.toDouble() / draftProposed
        val prefixCacheReused: Int get() = (promptTokens - evaluatedTokens).coerceAtLeast(0)
    }

    data class ModelInfo(
        val description: String,
        val trainContext: Int,
        val context: Int,
        val parameters: Long,
        val sizeBytes: Long,
        val vocabSize: Int,
        val embeddingSize: Int,
        val headCount: Int,
        val layerCount: Int,
        val hasChatTemplate: Boolean,
    )

    fun interface TokenCallback {
        /** @return false to stop generation immediately. */
        fun onToken(piece: String, generatedCount: Int): Boolean
    }

    fun interface LoadCallback {
        /** @return false to abort the load. */
        fun onProgress(fraction: Float): Boolean
    }

    // ---- JNI declarations -------------------------------------------------------------------

    @JvmStatic
    external fun nativeLoad(
        path: String,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        nThreads: Int,
        gpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        flashAttention: Boolean,
        q8KvCache: Boolean,
        draftModelPath: String?,
        draftGpuLayers: Int,
        draftTokens: Int,
        loadCallback: LoadCallback?,
    ): Int

    @JvmStatic external fun nativeGenerate(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        seed: Int,
        tokenCallback: TokenCallback?,
    ): Int

    @JvmStatic external fun nativeCancel()

    @JvmStatic external fun nativeUnload()

    @JvmStatic external fun nativeIsLoaded(): Boolean

    @JvmStatic external fun nativeResetConversation()

    @JvmStatic external fun nativeSetThreads(nThreads: Int)

    @JvmStatic external fun nativeCountTokens(roles: Array<String>, contents: Array<String>): Int

    @JvmStatic external fun nativeLastError(): String

    @JvmStatic external fun nativeLastStats(): String

    /** GGUF header-only read: vocab/layer/embd counts + file size. Does not load weights. */
    @JvmStatic external fun nativePeekModel(path: String): String

    /** Empty string when the target/draft pair is usable; otherwise a one-line reason. */
    @JvmStatic external fun nativeSpeculativeVerdict(
        targetPath: String,
        draftPath: String,
        availableBytes: Long,
        nCtx: Int,
    ): String

    /** True when a draft model actually loaded and speculative decoding is active. */
    @JvmStatic external fun nativeDraftLoaded(): Boolean

    @JvmStatic external fun nativeModelInfo(): String

    @JvmStatic external fun nativeEstimateKvBytes(nCtx: Int, q8Kv: Boolean): Long

    @JvmStatic external fun nativeSaveKvState(path: String): Boolean

    @JvmStatic external fun nativeLoadKvState(path: String): Boolean

    @JvmStatic external fun nativeBackendInfo(): String

    @JvmStatic external fun nativeHasGpuBackend(): Boolean

    init {
        System.loadLibrary("omnillm")
    }

    // ---- Safe wrappers ----------------------------------------------------------------------

    val isLoaded: Boolean get() = try { nativeIsLoaded() } catch (_: Throwable) { false }

    fun backendInfo(): String = try { nativeBackendInfo() } catch (t: Throwable) { "unavailable: ${t.message}" }

    fun hasGpuBackend(): Boolean = try { nativeHasGpuBackend() } catch (_: Throwable) { false }

    /**
     * Loads a GGUF. Throws [InferenceException] with a human-readable message on failure —
     * including a specific hint for the two failure modes that actually happen on phones
     * (quant too large for RAM, and unsupported/corrupt file).
     */
    fun load(modelFile: File, config: LoadConfig, onProgress: LoadCallback? = null) {
        if (!modelFile.isFile) {
            throw InferenceException(Status.BAD_INPUT, "Model file not found: ${modelFile.absolutePath}")
        }
        val status = Status.of(
            nativeLoad(
                modelFile.absolutePath,
                config.nCtx, config.nBatch, config.nUbatch, config.nThreads, config.gpuLayers,
                config.useMmap, config.useMlock, config.flashAttention, config.q8KvCache,
                config.draftModelPath, config.draftGpuLayers, config.draftTokens,
                onProgress,
            )
        )
        if (status != Status.OK) {
            val detail = nativeLastError().ifBlank { "native load returned ${status.name}" }
            throw InferenceException(status, explain(status, detail))
        }
    }

    fun unload() {
        runCatching { nativeUnload() }
    }

    fun cancel() {
        runCatching { nativeCancel() }
    }

    fun resetConversation() {
        runCatching { nativeResetConversation() }
    }

    /** Live thread change (thermal throttling). No reload, no KV loss. */
    fun setThreads(nThreads: Int) {
        runCatching { nativeSetThreads(nThreads.coerceIn(1, 16)) }
    }

    fun generate(
        messages: List<ChatTurn>,
        sampling: SamplingConfig,
        onToken: TokenCallback?,
    ): Stats {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val roles = Array(messages.size) { messages[it].role }
        val contents = Array(messages.size) { messages[it].content }
        val status = Status.of(
            nativeGenerate(
                roles, contents,
                sampling.maxTokens, sampling.temperature, sampling.topP, sampling.topK,
                sampling.minP, sampling.repeatPenalty,
                if (sampling.seed < 0) 0xFFFFFFFF.toInt() else sampling.seed,
                onToken,
            )
        )
        val stats = lastStats()
        when (status) {
            Status.OK, Status.CONTEXT_FULL -> return stats
            Status.CANCELLED -> throw CancelledException(stats)
            else -> {
                val detail = nativeLastError().ifBlank { "native generate returned ${status.name}" }
                throw InferenceException(status, explain(status, detail))
            }
        }
    }

    /** Thrown when the user (or the thermal watchdog) stopped generation. Not an error. */
    class CancelledException(val stats: Stats) : RuntimeException("generation cancelled")

    fun lastStats(): Stats {
        val json = runCatching { JSONObject(nativeLastStats()) }.getOrNull()
        return Stats(
            promptTokens = json?.optInt("promptTokens") ?: 0,
            evaluatedTokens = json?.optInt("evaluatedTokens") ?: 0,
            generatedTokens = json?.optInt("generatedTokens") ?: 0,
            prefillMs = json?.optLong("prefillMs") ?: 0L,
            decodeMs = json?.optLong("decodeMs") ?: 0L,
            tokensPerSec = json?.optDouble("tokensPerSec") ?: 0.0,
            contextFull = json?.optBoolean("contextFull") ?: false,
            draftProposed = json?.optInt("draftProposed") ?: 0,
            draftAccepted = json?.optInt("draftAccepted") ?: 0,
        )
    }

    fun modelInfo(): ModelInfo? {
        val json = runCatching { JSONObject(nativeModelInfo()) }.getOrNull() ?: return null
        if (json.optString("description").isBlank() && json.optInt("nCtx") == 0) return null
        return ModelInfo(
            description = json.optString("description"),
            trainContext = json.optInt("nCtxTrain"),
            context = json.optInt("nCtx"),
            parameters = json.optLong("nParams"),
            sizeBytes = json.optLong("sizeBytes"),
            vocabSize = json.optInt("nVocab"),
            embeddingSize = json.optInt("nEmbd"),
            headCount = json.optInt("nHead"),
            layerCount = json.optInt("nLayer"),
            hasChatTemplate = json.optBoolean("hasChatTemplate"),
        )
    }

    /** KV-cache footprint of the currently loaded model at [nCtx], or a generic estimate. */
    fun estimateKvBytes(nCtx: Int, q8Kv: Boolean = false): Long =
        runCatching { nativeEstimateKvBytes(nCtx, q8Kv) }.getOrDefault(nCtx.toLong() * 225)

    fun saveKvState(file: File): Boolean = runCatching { nativeSaveKvState(file.absolutePath) }.getOrDefault(false)

    /**
     * Header-only info about a GGUF file: vocabulary size, layer/embedding counts, byte size.
     * Reads a few KB of metadata instead of the weights, so it is safe to call on a 2 GB model
     * from the UI thread's coroutine before deciding whether to load anything.
     */
    fun peekModel(file: File): PeekResult = runCatching {
        val json = JSONObject(nativePeekModel(file.absolutePath))
        PeekResult(
            ok = json.optBoolean("ok"),
            nVocab = json.optInt("nVocab"),
            nLayer = json.optInt("nLayer"),
            nEmbd = json.optInt("nEmbd"),
            fileBytes = json.optLong("fileBytes"),
            error = json.optString("error"),
        )
    }.getOrElse { PeekResult(ok = false, error = it.message ?: "peek failed") }

    /**
     * Checks whether a draft model can meaningfully accelerate this target on this device.
     * @return null when the pair is fine, otherwise a plain-English reason it is not.
     */
    fun speculativeVerdict(target: File, draft: File, availableBytes: Long, nCtx: Int): String? =
        runCatching { nativeSpeculativeVerdict(target.absolutePath, draft.absolutePath, availableBytes, nCtx) }
            .getOrNull()?.takeIf { it.isNotBlank() }

    fun isDraftLoaded(): Boolean = runCatching { nativeDraftLoaded() }.getOrDefault(false)

    data class PeekResult(
        val ok: Boolean,
        val nVocab: Int = 0,
        val nLayer: Int = 0,
        val nEmbd: Int = 0,
        val fileBytes: Long = 0L,
        val error: String = "",
    )

    fun loadKvState(file: File): Boolean = runCatching { nativeLoadKvState(file.absolutePath) }.getOrDefault(false)

    /**
     * Exact prompt size, in tokens, for the message list generate() would send.
     * Returns -1 when no model is loaded. Used for the "x / ctx" meter so it stops lying.
     */
    fun countTokens(messages: List<ChatTurn>): Int = runCatching {
        nativeCountTokens(
            Array(messages.size) { messages[it].role },
            Array(messages.size) { messages[it].content },
        )
    }.getOrDefault(-1)

    private fun explain(status: Status, detail: String): String = when (status) {
        Status.OOM ->
            "$detail — the runtime could not allocate the KV cache. Lower the context length or pick a smaller quant."
        Status.LOAD_FAILED ->
            "$detail — if this file came from a partial download it will be corrupt. Re-download it."
        Status.DECODE_FAILED ->
            "$detail — generation stopped. Turn off GPU offload or lower the context and try again."
        Status.CONTEXT_FULL ->
            "Conversation filled the context window. Start a new chat or raise the context length."
        else -> detail
    }
}

/** A single chat turn as the model sees it (role is the template role, not a UI label). */
data class ChatTurn(val role: String, val content: String)

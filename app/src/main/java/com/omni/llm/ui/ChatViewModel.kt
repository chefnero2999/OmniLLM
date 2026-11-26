package com.omni.llm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omni.llm.AppContainer
import com.omni.llm.OmniApp
import com.omni.llm.core.ChatTurn
import com.omni.llm.core.LlamaBridge
import com.omni.llm.data.ChatStore
import com.omni.llm.data.ModelCatalog
import com.omni.llm.data.OmniSettings
import com.omni.llm.service.LlmClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Chat pipeline. Local first, cloud only if the user opted in *and* there is no usable local
 * model (or the local load failed). Trimming happens here, not in the native layer, because it
 * needs to know what the user actually said.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as OmniApp).container
    private val threads = ChatStore(app)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class Attachment(
        val id: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val isImage: Boolean,
        val textContent: String? = null,
        val base64DataUrl: String? = null,
    )

    data class Citation(
        val title: String,
        val url: String,
    )

    data class Message(
        val id: Long,
        val role: String,            // "user" | "assistant" | "system"
        val content: String,
        val streaming: Boolean = false,
        val isError: Boolean = false,
        val footer: String? = null,  // tok/s, provider, context marker
        /** Streamed reasoning, shown collapsed. A thinking model is never a blank bubble. */
        val thinking: String = "",
        /** Live tokens/second for this reply, measured while it streams. */
        val tokensPerSec: Double = 0.0,
        /** Model that produced this particular message (it can change mid-thread on a switch). */
        val modelName: String? = null,
        /** True when the answer was cut short by the user's stop button. */
        val stopped: Boolean = false,
        val attachments: List<Attachment> = emptyList(),
        val citations: List<Citation> = emptyList(),
    )

    data class UiState(
        val messages: List<Message> = emptyList(),
        val busy: Boolean = false,
        val engineLabel: String = "No model loaded",
        val connectionLabel: String = "Inference process idle",
        val lastTokensPerSec: Double = 0.0,
        val contextUsed: Int = 0,
        val contextLimit: Int = 0,
        val banner: String? = null,
        /** Show the "use free cloud models" call-to-action above the message list. */
        val offerCloudToggle: Boolean = false,

        // ---- per-model threads -------------------------------------------------------------
        /** Which model's conversation is on screen. Every model has its own; none overlap. */
        val activeModel: ModelCatalog.FreeModel? = null,
        /** openPicker is true while the model picker sheet is up. */
        val pickerOpen: Boolean = false,
        /** Category filter inside the picker; null = show everything. */
        val pickerCategory: ModelCatalog.Category? = null,
        /** Thread sizes, so the picker can show "12 messages" per model at a glance. */
        val threadSummary: Map<String, ChatStore.Summary> = emptyMap(),
        /**
         * Thread keys of other cloud conversations whose context may be pulled in. Never includes
         * local models: the user's rule is specifically that *cloud* threads may read each other.
         */
        val readableThreads: List<ModelCatalog.FreeModel> = emptyList(),
        val contextSources: Set<String> = emptySet(),
        val confirmingClear: Boolean = false,
        val contextSheetOpen: Boolean = false,
        /** Mirrors the Settings toggle so the picker can rank unfiltered lanes first. */
        val settingsUnrestricted: Boolean = false,

        // ---- search -------------------------------------------------------------------------
        val searchQuery: String = "",
        val searchOpen: Boolean = false,
        /** null = no filter active; non-null = filtered message list to display. */
        val filteredMessages: List<Message>? = null,

        // ---- Multimodal Workspace Controls --------------------------------------------------
        val deepWebEnabled: Boolean = false,
        val conciseMode: Boolean = false,
        val pendingAttachments: List<Attachment> = emptyList(),

        // ---- Arena Duel (real parallel inference) -------------------------------------------
        val arenaPrompt: String = "",
        val arenaRunning: Boolean = false,
        val arenaModelAName: String = "Model A",
        val arenaResponseA: String = "",
        val arenaTpsA: Double = 0.0,
        val arenaModelBName: String = "Model B",
        val arenaResponseB: String = "",
        val arenaTpsB: Double = 0.0,
        val arenaVoted: String? = null,
        val arenaError: String? = null,

        // ---- AI Studio Canvas (real inference with custom params) ---------------------------
        val studioRunning: Boolean = false,
        val studioOutput: String = "",
        val studioStats: String = "",
        val studioError: String? = null,
    ) {
        val contextFraction: Float
            get() = if (contextLimit <= 0) 0f else (contextUsed.toFloat() / contextLimit).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val nextId = AtomicLong(1)
    private var generationJob: Job? = null
    private var arenaJob: Job? = null
    private var studioJob: Job? = null
    @Volatile private var streamStartedAt: Long = 0L
    @Volatile private var streamChars: Int = 0

    /**
     * The key of the thread currently on screen. Every read and write of [ChatStore] goes through
     * this, which is what makes "one chat space per model" true by construction rather than by
     * remembering to pass the right id at each call site.
     */
    private var activeKey: String = ModelCatalog.DEFAULT_CHAT_KEY

    /** Pseudo-provider id used by the "local model" entry in the picker. */
    private val LOCAL_PROVIDER = "local"

    init {
        _state.value = _state.value.copy(
            activeModel = ModelCatalog.find(activeKey),
            readableThreads = ModelCatalog.models.filter { it.usable && !it.image },
        )
        viewModelScope.launch {
            // Keep the picker's ranking in step with the Settings toggle.
            container.settings.settings.collect { st ->
                if (st.unrestrictedMode != _state.value.settingsUnrestricted) {
                    _state.value = _state.value.copy(settingsUnrestricted = st.unrestrictedMode)
                }
            }
        }
        viewModelScope.launch {
            // A saved preference wins, so reopening the app lands in the thread last used.
            val saved = runCatching { container.settings.settings.first().cloudModel }.getOrNull()
            activeKey = ModelCatalog.find(saved)?.threadKey ?: ModelCatalog.DEFAULT_CHAT_KEY
            loadThread(activeKey)
            refreshSummaries()
        }
        viewModelScope.launch {
            container.engine.loaded.collect { loaded ->
                _state.value = _state.value.copy(
                    engineLabel = if (loaded == null) "No model loaded"
                    else "${loaded.fileName} · ${loaded.contextLength} ctx · ${loaded.gpuLayers} GPU layers",
                    lastTokensPerSec = loaded?.lastTokensPerSec ?: _state.value.lastTokensPerSec,
                    contextLimit = loaded?.contextLength ?: 0,
                )
            }
        }
        viewModelScope.launch {
            container.llm.connection.collect { conn ->
                _state.value = _state.value.copy(
                    connectionLabel = when (conn) {
                        is LlmClient.Connection.Ready ->
                            "Engine ready · GPU ${if (conn.gpuAvailable) "available" else "not compiled"}"
                        is LlmClient.Connection.Lost -> "Engine stopped — reload the model"
                        LlmClient.Connection.Connecting -> "Starting engine…"
                        LlmClient.Connection.Disconnected -> "Inference process idle"
                    }
                )
            }
        }
        viewModelScope.launch {
            container.llm.warnings.collect { warning ->
                _state.value = _state.value.copy(banner = warning)
            }
        }
    }

    fun dismissBanner() {
        _state.value = _state.value.copy(banner = null)
    }

    fun toggleDeepWeb() {
        _state.update { it.copy(deepWebEnabled = !it.deepWebEnabled) }
    }

    fun toggleConciseMode() {
        _state.update { it.copy(conciseMode = !it.conciseMode) }
    }

    fun removeAttachment(id: String) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == id }) }
    }

    fun attachUri(uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            val mime = cr.getType(uri) ?: "application/octet-stream"
            var name = "attachment"
            var sizeBytes = 0L
            runCatching {
                cr.query(uri, null, null, null, null)?.use { c ->
                    val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                        if (sizeIdx >= 0) sizeBytes = c.getLong(sizeIdx)
                    }
                }
            }

            val isImage = mime.startsWith("image/", ignoreCase = true) ||
                name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                name.endsWith(".jpeg", true) || name.endsWith(".webp", true)

            if (isImage) {
                val dataUrl = runCatching {
                    cr.openInputStream(uri)?.use { stream ->
                        val orig = android.graphics.BitmapFactory.decodeStream(stream) ?: return@use null
                        val maxDim = 1024
                        val scale = minOf(1f, maxDim.toFloat() / maxOf(orig.width, orig.height).coerceAtLeast(1))
                        val scaled = if (scale < 1f) {
                            android.graphics.Bitmap.createScaledBitmap(
                                orig,
                                (orig.width * scale).toInt().coerceAtLeast(1),
                                (orig.height * scale).toInt().coerceAtLeast(1),
                                true,
                            )
                        } else orig
                        val baos = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, baos)
                        if (scaled !== orig) scaled.recycle()
                        orig.recycle()
                        val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                        "data:image/jpeg;base64,$b64"
                    }
                }.getOrNull()
                if (dataUrl == null) {
                    _state.update { it.copy(banner = "Could not decode image: $name") }
                    return@launch
                }
                val att = Attachment(
                    id = "att-${System.nanoTime()}",
                    name = name,
                    mimeType = mime,
                    sizeBytes = sizeBytes,
                    isImage = true,
                    base64DataUrl = dataUrl,
                )
                _state.update { it.copy(pendingAttachments = (it.pendingAttachments + att).take(4)) }
            } else {
                val extracted = runCatching {
                    cr.openInputStream(uri)?.use { stream ->
                        val raw = stream.readBytes()
                        if (sizeBytes == 0L) sizeBytes = raw.size.toLong()
                        if (mime.equals("application/pdf", true) || name.endsWith(".pdf", true)) {
                            // Lightweight on-device PDF literal text extractor (BT...ET Tj/TJ operators)
                            extractReadablePdfStrings(raw)
                        } else {
                            String(raw.take(48_000).toByteArray(), Charsets.UTF_8)
                        }
                    }
                }.getOrNull().orEmpty()

                if (extracted.isBlank()) {
                    _state.update { it.copy(banner = "Could not extract readable text from $name.") }
                    return@launch
                }
                val att = Attachment(
                    id = "att-${System.nanoTime()}",
                    name = name,
                    mimeType = mime,
                    sizeBytes = sizeBytes,
                    isImage = false,
                    textContent = extracted.take(24_000),
                )
                _state.update { it.copy(pendingAttachments = (it.pendingAttachments + att).take(4)) }
            }
        }
    }

    private fun extractReadablePdfStrings(bytes: ByteArray): String {
        val rawStr = String(bytes, Charsets.ISO_8859_1)
        val regex = Regex("""\(([^()\\]{3,200})\)""")
        val chunks = regex.findAll(rawStr)
            .map { it.groupValues[1].replace(Regex("[^\\x20-\\x7E\\n]"), " ").trim() }
            .filter { chunk -> chunk.any { it.isLetterOrDigit() } && !chunk.startsWith("PDF-") }
            .distinct()
            .take(400)
            .toList()
        return if (chunks.isNotEmpty()) chunks.joinToString(" ")
        else "[Binary PDF attached (${bytes.size / 1024} KB) — plain-text streams were compressed/scanned]"
    }

    fun editAndResend(id: Long, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        val messages = _state.value.messages
        val idx = messages.indexOfFirst { it.id == id && it.role == "user" }
        if (idx < 0) return
        _state.update { it.copy(messages = messages.take(idx)) }
        send(trimmed)
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val attached = _state.value.pendingAttachments
        if ((trimmed.isEmpty() && attached.isEmpty()) || _state.value.busy) return

        val docBlocks = attached.filter { !it.isImage && !it.textContent.isNullOrBlank() }
            .joinToString("\n\n") { "[Attached file: ${it.name}]\n```\n${it.textContent}\n```" }
        val userContent = buildString {
            if (trimmed.isNotEmpty()) append(trimmed)
            if (docBlocks.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(docBlocks)
            }
            if (isEmpty() && attached.any { it.isImage }) {
                append("Analyze the attached image(s) in detail.")
            }
        }
        val imageUrls = attached.mapNotNull { it.base64DataUrl }

        val userMsg = Message(
            id = nextId.getAndIncrement(),
            role = "user",
            content = if (trimmed.isNotEmpty()) trimmed else "Analyze attached file(s)",
            attachments = attached,
        )
        val assistantId = nextId.getAndIncrement()
        resetStreamMeter()
        val curState = _state.value
        _state.update {
            it.copy(
                messages = it.messages + userMsg +
                    Message(
                        assistantId, "assistant", "",
                        streaming = true,
                        modelName = it.activeModel?.name,
                    ),
                busy = true,
                contextUsed = 0,
                pendingAttachments = emptyList(),
            )
        }

        generationJob = viewModelScope.launch {
            val settings = container.settings.settings.first()
            val loaded = container.engine.loaded.value
            val cloudEnabled = container.cloudGate.allow() || settings.cloudFallbackEnabled
            val active = _state.value.activeModel

            val wantsCloud = (active != null && active.providerId != LOCAL_PROVIDER) || imageUrls.isNotEmpty() || curState.deepWebEnabled

            if (wantsCloud) {
                streamCloud(
                    assistantId = assistantId,
                    settings = settings,
                    promptForSearch = trimmed.ifBlank { userContent },
                    imageDataUrls = imageUrls,
                    overrideUserTurn = userContent,
                    useDeepWeb = curState.deepWebEnabled,
                    concise = curState.conciseMode,
                )
            } else if (loaded == null) {
                if (cloudEnabled) {
                    streamCloud(
                        assistantId = assistantId,
                        settings = settings,
                        promptForSearch = trimmed.ifBlank { userContent },
                        imageDataUrls = imageUrls,
                        overrideUserTurn = userContent,
                        useDeepWeb = curState.deepWebEnabled,
                        concise = curState.conciseMode,
                    )
                } else {
                    finishWithError(
                        assistantId,
                        "No local model is loaded yet.\n\n" +
                            "Either download a model in the Vault tab (runs fully offline), or tap " +
                            "Enable to unlock free keyless cloud models + uploads + Deep Web.",
                    )
                    _state.update { it.copy(offerCloudToggle = true) }
                }
            } else {
                streamLocal(assistantId, settings, loaded.contextLength)
            }
        }
    }

    private suspend fun streamLocal(assistantId: Long, settings: OmniSettings, contextLimit: Int) {
        // Unrestricted mode must apply to local models too — that is the one place it can be
        // genuinely complete, because nothing leaves the device to be filtered.
        val effective = settings.copy(systemPrompt = container.settings.effectiveSystemPrompt(settings))
        val turns = buildTurns(effective, contextLimit)
        val sampling = LlamaBridge.SamplingConfig(
            maxTokens = settings.maxTokens,
            temperature = settings.temperature,
            topP = settings.topP,
            seed = -1,
        )
        var generated = 0
        // Exact prompt size from the tokeniser, not a character-count guess. Falls back to the
        // old estimate only if the engine cannot answer (e.g. it died).
        val exactPromptTokens = container.llm.countTokens(turns).takeIf { it > 0 }
            ?: turns.sumOf { estimateTokens(it.content) }
        try {
            container.llm.generate(turns, sampling).collect { event ->
                when (event) {
                    is LlmClient.LlmEvent.Token -> {
                        generated = event.count + 1
                        appendToMessage(assistantId, event.piece)
                        // Real count: the prompt is fixed once generation starts, so the meter only
                        // moves by the tokens actually produced.
                        _state.value = _state.value.copy(contextUsed = exactPromptTokens + generated)
                    }
                    is LlmClient.LlmEvent.Done -> {
                        container.engine.recordStats(
                            event.tokensPerSec, event.prefillMs, event.decodeMs, event.generatedTokens,
                        )
                        val footer = buildString {
                            append(if (event.cancelled) "stopped · " else "")
                            append("${event.generatedTokens} tok · ${"%.1f".format(event.tokensPerSec)} tok/s")
                            if (event.evaluatedTokens > 0 && event.promptTokens > event.evaluatedTokens) {
                                append(" · cache saved ${event.promptTokens - event.evaluatedTokens} tok")
                            }
                            // Speculative decoding accounting: shown only when it actually ran, and
                            // stated plainly so a low acceptance rate is visible rather than hidden.
                            if (event.draftProposed > 0) {
                                val pct = event.draftAccepted * 100 / event.draftProposed
                                append(" · draft ${event.draftAccepted}/${event.draftProposed} ($pct%)")
                            }
                        }
                        finalizeMessage(assistantId, footer)
                        // Persist the KV state so the next cold start skips the prompt prefill.
                        runCatching {
                            container.llm.saveKvState(
                                com.omni.llm.data.ModelPaths.kvCacheFile(getApplication())
                            )
                        }
                        // A completed generation proves these settings run on this device, so
                        // record them as the "last stable" values the crash breaker falls back to.
                        val gpuLayersNow = container.engine.loaded.value?.gpuLayers ?: 0
                        runCatching {
                            container.settings.update { s ->
                                if (s.lastStableGpuLayers != gpuLayersNow) s.copy(lastStableGpuLayers = gpuLayersNow) else s
                            }
                        }
                        _state.value = _state.value.copy(
                            busy = false,
                            lastTokensPerSec = event.tokensPerSec,
                            contextUsed = event.promptTokens + event.generatedTokens,
                            contextLimit = contextLimit,
                            banner = if (event.contextFull)
                                "Context window full — start a new chat to keep going." else _state.value.banner,
                        )
                        persist()
                    }
                    is LlmClient.LlmEvent.Error -> finishWithError(assistantId, event.message)
                }
            }
            if (_state.value.busy) {
                // Flow ended without a Done (e.g. the process died mid-stream).
                finishWithError(assistantId, "The engine stopped mid-answer. Reload the model and retry.")
            }
        } catch (t: Throwable) {
            finishWithError(assistantId, t.message ?: "Generation failed.")
        }
    }

    private suspend fun streamCloud(
        assistantId: Long,
        settings: OmniSettings,
        promptForSearch: String = "",
        imageDataUrls: List<String> = emptyList(),
        overrideUserTurn: String? = null,
        useDeepWeb: Boolean = false,
        concise: Boolean = false,
    ) {
        val active = _state.value.activeModel
        var webBlock = ""
        if (useDeepWeb && promptForSearch.isNotBlank()) {
            val hits = container.cloud.webSearch(promptForSearch)
            if (hits.isNotEmpty()) {
                val citations = hits.map { Citation(it.title, it.url) }
                _state.update { cur ->
                    cur.copy(messages = cur.messages.map { m ->
                        if (m.id == assistantId) m.copy(citations = citations) else m
                    })
                }
                webBlock = "LIVE WEB SEARCH RESULTS (use these to ground your answer with citations):\n" +
                    hits.mapIndexed { idx, r -> "[${idx + 1}] ${r.title} (${r.url})\n${r.snippet}" }
                        .joinToString("\n\n")
            }
        }

        // Carried-over context is assembled from the OTHER threads the user ticked. Read-only.
        val bridge = buildString {
            if (webBlock.isNotBlank()) appendLine(webBlock).appendLine()
            if (concise) appendLine("STYLE: Respond concisely and directly.").appendLine()
            for (key in _state.value.contextSources) {
                val digest = threads.contextFrom(key)
                if (digest.isNotBlank()) appendLine(digest).appendLine()
            }
        }
        val effective = settings.copy(
            systemPrompt = container.settings.effectiveSystemPrompt(settings),
            maxTokens = maxOf(settings.maxTokens, 2048)
        )
        val cloudCtxLimit = maxOf(settings.contextLength, 8192)
        val rawTurns = buildTurns(effective, contextLimit = cloudCtxLimit, carriedContext = bridge)
        val turns = if (!overrideUserTurn.isNullOrBlank()) {
            val lastUser = rawTurns.indexOfLast { it.role == "user" }
            if (lastUser >= 0) rawTurns.mapIndexed { i, t -> if (i == lastUser) t.copy(content = overrideUserTurn) else t }
            else rawTurns
        } else rawTurns

        // Auto-select vision model when images are attached and active model lacks vision
        val targetPreferred = if (imageDataUrls.isNotEmpty() && active?.vision != true) {
            "ovh|qwen3-vl-30b-a3b"
        } else {
            active?.providerId?.let { "${it}|${active.modelId}" } ?: settings.cloudModel
        }

        val result = container.cloud.complete(
            preferredModel = targetPreferred,
            messages = turns,
            maxTokens = maxOf(effective.maxTokens, active?.minOutputTokens ?: 0, 2048),
            temperature = settings.temperature,
            onReasoning = { piece -> appendToReasoning(assistantId, piece) },
            imageDataUrls = imageDataUrls,
        ) { delta ->
            appendToMessage(assistantId, delta)
        }
        when (result) {
            is com.omni.llm.data.KeylessCloudRouter.Result.Success -> {
                val label = "cloud · ${result.providerId}/${result.model}"
                finalizeMessage(assistantId, label)
                _state.update { it.copy(busy = false) }
                persist()
            }
            is com.omni.llm.data.KeylessCloudRouter.Result.Failure -> {
                if (settings.autoSwitchModels) {
                    val next = nextCloudModel()
                    if (next != null) {
                        _state.update {
                            it.copy(
                                banner = "${active?.name ?: "This model"} is out of quota — switched " +
                                    "to ${next.name}. Your conversation continues here.",
                                activeModel = next,
                            )
                        }
                        activeKey = next.threadKey
                        val retryTurns = buildTurns(
                            effective.copy(systemPrompt = container.settings.effectiveSystemPrompt(settings)),
                            contextLimit = cloudCtxLimit,
                            carriedContext = bridge,
                        )
                        val second = container.cloud.complete(
                            preferredModel = "${next.providerId}|${next.modelId}",
                            messages = retryTurns,
                            maxTokens = maxOf(settings.maxTokens, next.minOutputTokens, 2048),
                            temperature = settings.temperature,
                            onReasoning = { piece -> appendToReasoning(assistantId, piece) },
                            imageDataUrls = imageDataUrls,
                        ) { delta -> appendToMessage(assistantId, delta) }
                        when (second) {
                            is com.omni.llm.data.KeylessCloudRouter.Result.Success -> {
                                finalizeMessage(assistantId, "cloud · ${second.providerId}/${second.model}")
                                _state.update { it.copy(busy = false) }
                                persist()
                            }
                            is com.omni.llm.data.KeylessCloudRouter.Result.Failure ->
                                finishWithError(assistantId, second.message)
                        }
                        return
                    }
                }
                finishWithError(assistantId, result.message)
            }
        }
    }

    /** Enables the keyless cloud path and retries, from the chat call-to-action. */
    fun enableCloudNow() {
        viewModelScope.launch {
            container.cloudGate.setEnabled(true)
            container.settings.update { it.copy(cloudFallbackEnabled = true) }
            _state.value = _state.value.copy(
                offerCloudToggle = false,
                banner = "Free cloud models enabled. No account involved.",
            )
        }
    }

    fun dismissCloudOffer() {
        _state.value = _state.value.copy(offerCloudToggle = false)
    }

    fun stop() {
        generationJob?.cancel()
        generationJob = null
        container.llm.cancel()
        _state.value = _state.value.copy(busy = false)
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun appendToReasoning(id: Long, piece: String) {
        _state.update { cur ->
            cur.copy(messages = cur.messages.map { m ->
                if (m.id == id) m.copy(thinking = m.thinking + piece) else m
            })
        }
    }

    private fun appendToMessage(id: Long, piece: String) {
        if (streamStartedAt == 0L) streamStartedAt = System.currentTimeMillis()
        streamChars += piece.length
        val elapsed = (System.currentTimeMillis() - streamStartedAt).coerceAtLeast(1L) / 1000.0
        val tps = if (elapsed > 0.4) (streamChars / 3.6) / elapsed else 0.0
        _state.update { cur ->
            cur.copy(
                lastTokensPerSec = if (tps > 0.0) tps else cur.lastTokensPerSec,
                messages = cur.messages.map {
                    if (it.id == id) it.copy(content = it.content + piece, tokensPerSec = tps) else it
                }
            )
        }
    }

    private fun resetStreamMeter() {
        streamStartedAt = 0L
        streamChars = 0
    }

    private fun finalizeMessage(id: Long, footer: String?) {
        _state.update { cur ->
            cur.copy(messages = cur.messages.map {
                if (it.id == id) it.copy(streaming = false, footer = footer) else it
            })
        }
    }

    private fun finishWithError(id: Long, message: String) {
        _state.update { cur ->
            cur.copy(
                busy = false,
                messages = cur.messages.map {
                    if (it.id == id) it.copy(content = message, streaming = false, isError = true) else it
                },
            )
        }
        persist()
    }

    /**
     * Keeps the system prompt plus the most recent turns that fit in ~70 % of the context.
     * The remaining 30 % is headroom for the answer itself — overfilling here is what causes
     * "context full" errors in the middle of a reply.
     */
    private fun buildTurns(
        settings: OmniSettings,
        contextLimit: Int,
        carriedContext: String = "",
    ): List<ChatTurn> {
        val budget = max(256, (contextLimit * 0.7).toInt())
        val turns = mutableListOf<ChatTurn>()
        var used = estimateTokens(settings.systemPrompt)
        turns.add(ChatTurn("system", settings.systemPrompt))
        if (carriedContext.isNotBlank()) {
            used += estimateTokens(carriedContext)
            turns.add(ChatTurn("system", carriedContext))
        }

        val history = _state.value.messages.filter {
            !it.isError && it.content.isNotBlank() && !it.streaming
        }
        val kept = ArrayDeque<ChatTurn>()
        for (m in history.asReversed()) {
            val tokens = estimateTokens(m.content)
            if (used + tokens > budget) break
            used += tokens
            kept.addFirst(ChatTurn(if (m.role == "user") "user" else "assistant", m.content))
        }
        turns.addAll(kept)
        return turns
    }

    /** Cheap token estimate: ~3.6 chars/token for English. Good enough to trim, no tokeniser call. */
    private fun estimateTokens(text: String): Int = (text.length / 3.6).toInt() + 8

    // ------------------------------------------------------------------ threads

    /** Persist the visible conversation into the thread for [activeKey] and nowhere else. */
    private fun persist() {
        val snapshot = _state.value.messages
            .filter { !it.isError && it.content.isNotBlank() }
            .map { ChatStore.Msg(role = it.role, content = it.content) }
        val key = activeKey
        viewModelScope.launch {
            threads.save(
                ChatStore.Thread(
                    key = key,
                    title = threads.titleFor(snapshot),
                    messages = snapshot,
                )
            )
            refreshSummaries()
        }
    }

    private suspend fun loadThread(key: String) {
        val thread = threads.load(key)
        val model = ModelCatalog.find(key)
        _state.value = _state.value.copy(
            messages = thread.messages.mapIndexed { index, m ->
                Message(id = index.toLong(), role = m.role, content = m.content)
            },
            activeModel = model,
            busy = false,
            banner = null,
        )
        nextId.set(thread.messages.size.toLong() + 1)
    }

    private suspend fun refreshSummaries() {
        val keys = ModelCatalog.models.map { it.threadKey }
        _state.value = _state.value.copy(threadSummary = threads.summaries(keys))
    }

    /** Switch to a model's own conversation. Nothing from the previous thread is carried in. */
    /**
     * Next model to try when the current one dies. Ordered by the catalogue's own ranking, and it
     * skips models already marked as tried in this turn, so a switch loop cannot ping-pong.
     */
    private fun nextCloudModel(): ModelCatalog.FreeModel? {
        val current = _state.value.activeModel
        return ModelCatalog.models
            .filter { it.usable && !it.image && it.threadKey != current?.threadKey }
            .sortedByDescending { it.agentReady }
            .firstOrNull()
    }

    fun openModel(key: String) {
        val model = ModelCatalog.find(key) ?: return
        if (model.image) {
            _state.value = _state.value.copy(banner = "That model draws pictures — open Build to use it.")
            return
        }
        if (!model.usable) {
            _state.value = _state.value.copy(
                banner = "${model.name} needs a paid API key. This app has no signup flow, so it " +
                    "cannot be used — pick one of the free models instead.",
            )
            return
        }
        if (_state.value.busy) {
            _state.value = _state.value.copy(banner = "Wait for the current answer to finish, or stop it.")
            return
        }
        activeKey = model.threadKey
        _state.value = _state.value.copy(pickerOpen = false, pickerCategory = null, contextSources = emptySet())
        viewModelScope.launch {
            loadThread(activeKey)
            // Remember the choice so reopening the app lands where the user left off.
            container.settings.setCloudModel("${model.providerId}|${model.modelId}")
        }
    }

    fun openPicker() {
        _state.value = _state.value.copy(pickerOpen = true)
        viewModelScope.launch { refreshSummaries() }
    }

    fun closePicker() {
        _state.value = _state.value.copy(pickerOpen = false, pickerCategory = null)
    }

    fun filterCategory(category: ModelCatalog.Category?) {
        _state.value = _state.value.copy(pickerCategory = category)
    }

    /** Toggle a donor thread whose context should be attached to the next message. */
    fun toggleContextSource(threadKey: String) {
        if (threadKey == activeKey) return
        val current = _state.value.contextSources
        _state.value = _state.value.copy(
            contextSources = if (threadKey in current) current - threadKey else current + threadKey,
        )
    }

    /**
     * Stop generation immediately, keeping what already arrived.
     *
     * Cancelling the job is the whole mechanism; the partial text stays in the message and is
     * marked `stopped` so the UI can offer Retry instead of pretending it finished.
     */
    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        val last = _state.value.messages.lastOrNull()
        _state.value = _state.value.copy(
            busy = false,
            messages = _state.value.messages.map { m ->
                if (m.streaming) m.copy(streaming = false, stopped = true) else m
            },
        )
        if (last != null) persist()
        viewModelScope.launch { runCatching { container.llm.cancel() } }
    }

    /**
     * Retry a reply: drop it and regenerate from the preceding user message.
     * [messageId] identifies which bubble to redo, so an older answer can be retried too.
     */
    fun retry(messageId: Long) {
        if (_state.value.busy) return
        val messages = _state.value.messages
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        // Find the user message this reply belongs to.
        val userIndex = (index - 1 downTo 0).firstOrNull { messages[it].role == "user" } ?: return
        val prompt = messages[userIndex].content
        // Remove everything from that user message onward and re-ask.
        _state.value = _state.value.copy(messages = messages.take(userIndex))
        send(prompt)
    }

    /** Regenerate the last answer without re-typing the question. */
    fun regenerateLast() {
        val lastAssistant = _state.value.messages.lastOrNull { it.role == "assistant" } ?: return
        retry(lastAssistant.id)
    }

    fun openContextSheet() {
        _state.value = _state.value.copy(contextSheetOpen = true)
    }

    fun closeContextSheet() {
        _state.value = _state.value.copy(contextSheetOpen = false)
    }

    fun askClear() {
        _state.value = _state.value.copy(confirmingClear = true)
    }

    fun dismissClear() {
        _state.value = _state.value.copy(confirmingClear = false)
    }

    /** Only ever clears the thread on screen. Deleting is the user's call, always. */
    fun clearThread() {
        val key = activeKey
        _state.value = _state.value.copy(
            messages = emptyList(), confirmingClear = false, busy = false,
            contextUsed = 0, banner = null, filteredMessages = null,
        )
        nextId.set(1)
        // Reset the native KV state too, otherwise the local model keeps answering as if the
        // deleted turns were still there.
        runCatching { container.llm.resetConversation() }
        viewModelScope.launch {
            threads.delete(key)
            refreshSummaries()
        }
    }

    // ---- search -----------------------------------------------------------------------------

    fun openSearch() {
        _state.value = _state.value.copy(searchOpen = true, searchQuery = "", filteredMessages = null)
    }

    fun closeSearch() {
        _state.value = _state.value.copy(searchOpen = false, searchQuery = "", filteredMessages = null)
    }

    fun updateSearch(query: String) {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            null
        } else {
            _state.value.messages.filter { it.content.contains(trimmed, ignoreCase = true) }
        }
        _state.update { it.copy(searchQuery = query, filteredMessages = filtered) }
    }

    // ---- Real LMSYS Arena Duel --------------------------------------------------------------

    fun startArenaDuel(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        arenaJob?.cancel()

        val candidates = ModelCatalog.models
            .filter { it.usable && it.providerId != "local" }
            .shuffled()
        val modelA = candidates.getOrNull(0)
        val modelB = candidates.getOrNull(1) ?: modelA
        if (modelA == null || modelB == null) {
            _state.update { it.copy(arenaError = "No cloud models available for duel.") }
            return
        }

        _state.update {
            it.copy(
                arenaPrompt = trimmed,
                arenaRunning = true,
                arenaModelAName = "${modelA.name} (${modelA.providerId.uppercase()})",
                arenaResponseA = "",
                arenaTpsA = 0.0,
                arenaModelBName = "${modelB.name} (${modelB.providerId.uppercase()})",
                arenaResponseB = "",
                arenaTpsB = 0.0,
                arenaVoted = null,
                arenaError = null,
            )
        }

        arenaJob = viewModelScope.launch {
            val turns = listOf(ChatTurn("user", trimmed))
            val t0 = System.currentTimeMillis()
            val defA = async {
                var charsA = 0
                val res = container.cloud.complete(
                    preferredModel = "${modelA.providerId}|${modelA.modelId}",
                    messages = turns,
                    maxTokens = 1536,
                    temperature = 0.7f,
                ) { delta ->
                    charsA += delta.length
                    val elapsedSec = ((System.currentTimeMillis() - t0).coerceAtLeast(100)) / 1000.0
                    val estTokens = charsA / 4.0
                    _state.update { s ->
                        s.copy(
                            arenaResponseA = s.arenaResponseA + delta,
                            arenaTpsA = estTokens / elapsedSec,
                        )
                    }
                }
                if (res is com.omni.llm.data.KeylessCloudRouter.Result.Failure) {
                    _state.update { s ->
                        if (s.arenaResponseA.isBlank()) s.copy(arenaResponseA = "⚠ ${res.message}") else s
                    }
                }
            }
            val defB = async {
                var charsB = 0
                val res = container.cloud.complete(
                    preferredModel = "${modelB.providerId}|${modelB.modelId}",
                    messages = turns,
                    maxTokens = 1536,
                    temperature = 0.7f,
                ) { delta ->
                    charsB += delta.length
                    val elapsedSec = ((System.currentTimeMillis() - t0).coerceAtLeast(100)) / 1000.0
                    val estTokens = charsB / 4.0
                    _state.update { s ->
                        s.copy(
                            arenaResponseB = s.arenaResponseB + delta,
                            arenaTpsB = estTokens / elapsedSec,
                        )
                    }
                }
                if (res is com.omni.llm.data.KeylessCloudRouter.Result.Failure) {
                    _state.update { s ->
                        if (s.arenaResponseB.isBlank()) s.copy(arenaResponseB = "⚠ ${res.message}") else s
                    }
                }
            }
            defA.await()
            defB.await()
            _state.update { it.copy(arenaRunning = false) }
        }
    }

    fun stopArenaDuel() {
        arenaJob?.cancel()
        arenaJob = null
        _state.update { it.copy(arenaRunning = false) }
    }

    fun voteArena(winner: String) {
        _state.update { it.copy(arenaVoted = winner) }
    }

    // ---- Real AI Studio Canvas --------------------------------------------------------------

    fun runStudioInference(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
    ) {
        val trimmedUser = userPrompt.trim()
        if (trimmedUser.isEmpty()) return
        studioJob?.cancel()
        _state.update {
            it.copy(
                studioRunning = true,
                studioOutput = "",
                studioStats = "Streaming...",
                studioError = null,
            )
        }
        studioJob = viewModelScope.launch {
            val turns = buildList {
                if (systemPrompt.isNotBlank()) add(ChatTurn("system", systemPrompt.trim()))
                add(ChatTurn("user", trimmedUser))
            }
            val t0 = System.currentTimeMillis()
            var chars = 0
            val active = _state.value.activeModel
            val preferred = active?.takeIf { it.providerId != "local" }?.let { "${it.providerId}|${it.modelId}" }
                ?: container.settings.settings.first().cloudModel
            val res = container.cloud.complete(
                preferredModel = preferred,
                messages = turns,
                maxTokens = maxTokens.coerceIn(128, 8192),
                temperature = temperature.coerceIn(0f, 2f),
            ) { delta ->
                chars += delta.length
                val elapsedSec = ((System.currentTimeMillis() - t0).coerceAtLeast(100)) / 1000.0
                val tps = (chars / 4.0) / elapsedSec
                _state.update { s ->
                    s.copy(
                        studioOutput = s.studioOutput + delta,
                        studioStats = String.format("%.2fs · ~%.1f tok/s (T=%.2f, topP=%.2f)", elapsedSec, tps, temperature, topP),
                    )
                }
            }
            when (res) {
                is com.omni.llm.data.KeylessCloudRouter.Result.Success -> {
                    val elapsedSec = ((System.currentTimeMillis() - t0).coerceAtLeast(100)) / 1000.0
                    val tps = (chars / 4.0) / elapsedSec
                    _state.update {
                        it.copy(
                            studioRunning = false,
                            studioStats = String.format("%s/%s · %.2fs · ~%.1f tok/s", res.providerId, res.model, elapsedSec, tps),
                        )
                    }
                }
                is com.omni.llm.data.KeylessCloudRouter.Result.Failure -> {
                    _state.update {
                        it.copy(
                            studioRunning = false,
                            studioError = res.message,
                            studioStats = "Failed",
                        )
                    }
                }
            }
        }
    }

    fun stopStudioInference() {
        studioJob?.cancel()
        studioJob = null
        _state.update { it.copy(studioRunning = false) }
    }
}

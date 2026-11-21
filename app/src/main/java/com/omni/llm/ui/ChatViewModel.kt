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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    ) {
        val contextFraction: Float
            get() = if (contextLimit <= 0) 0f else (contextUsed.toFloat() / contextLimit).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val nextId = AtomicLong(1)
    private var generationJob: Job? = null
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

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return

        val userMsg = Message(nextId.getAndIncrement(), "user", trimmed)
        val assistantId = nextId.getAndIncrement()
        resetStreamMeter()
        _state.value = _state.value.copy(
            messages = _state.value.messages + userMsg +
                Message(
                    assistantId, "assistant", "",
                    streaming = true,
                    modelName = _state.value.activeModel?.name,
                ),
            busy = true,
            contextUsed = 0,
        )

        generationJob = viewModelScope.launch {
            val settings = container.settings.settings.first()
            val loaded = container.engine.loaded.value
            val cloudEnabled = container.cloudGate.allow() || settings.cloudFallbackEnabled
            val active = _state.value.activeModel

            // A cloud model was explicitly chosen for this thread, so honour it even if a local
            // model happens to be loaded. The local path is only used when the active thread *is*
            // the local one.
            val wantsCloud = active != null && active.providerId != LOCAL_PROVIDER

            if (wantsCloud) {
                streamCloud(assistantId, settings)
            } else if (loaded == null) {
                if (cloudEnabled) {
                    streamCloud(assistantId, settings)
                } else {
                    // Make the cloud path one tap away instead of buried in Settings: the user is
                    // mid-conversation and just wants an answer.
                    finishWithError(
                        assistantId,
                        "No local model is loaded yet.\n\n" +
                            "Either download a model in the Models tab (runs fully offline), or tap " +
                            "the cloud button below to enable free keyless models now — no account, " +
                            "no API key.",
                    )
                    _state.value = _state.value.copy(offerCloudToggle = true)
                }
                return@launch
            }

            streamLocal(assistantId, settings, loaded?.contextLength ?: settings.contextLength)
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

    private suspend fun streamCloud(assistantId: Long, settings: OmniSettings) {
        val active = _state.value.activeModel
        // Carried-over context is assembled from the OTHER threads the user ticked. Read-only.
        val bridge = buildString {
            for (key in _state.value.contextSources) {
                val digest = threads.contextFrom(key)
                if (digest.isNotBlank()) appendLine(digest).appendLine()
            }
        }
        val effective = settings.copy(systemPrompt = container.settings.effectiveSystemPrompt(settings))
        val turns = buildTurns(effective, contextLimit = 8192, carriedContext = bridge)
        val result = container.cloud.complete(
            // Explicit model for this thread, not the global setting: each model owns its chat.
            preferredModel = active?.providerId?.let { "${it}|${active.modelId}" }
                ?: settings.cloudModel,
            messages = turns,
            // Never let the user's answer-length cap starve a reasoning model: below its measured
            // floor it returns its thinking and no answer at all.
            maxTokens = maxOf(settings.maxTokens, active?.minOutputTokens ?: 0),
            temperature = settings.temperature,
            // Reasoning is shown as its own dimmed stream so a thinking model never looks blank.
            onReasoning = { piece -> appendToReasoning(assistantId, piece) },
        ) { delta ->
            appendToMessage(assistantId, delta)
        }
        when (result) {
            is com.omni.llm.data.KeylessCloudRouter.Result.Success -> {
                val label = "cloud · ${result.providerId}/${result.model}"
                finalizeMessage(assistantId, label)
                _state.value = _state.value.copy(busy = false)
                persist()
            }
            is com.omni.llm.data.KeylessCloudRouter.Result.Failure -> {
                if (settings.autoSwitchModels) {
                    val next = nextCloudModel()
                    if (next != null) {
                        // Keep the thread and the partial answer: the new model continues the
                        // same conversation rather than starting a new one.
                        _state.value = _state.value.copy(
                            banner = "${active?.name ?: "This model"} is out of quota — switched " +
                                "to ${next.name}. Your conversation continues here.",
                            activeModel = next,
                        )
                        activeKey = next.threadKey
                        val retryTurns = buildTurns(
                            effective.copy(systemPrompt = container.settings.effectiveSystemPrompt(settings)),
                            contextLimit = 8192,
                            carriedContext = bridge,
                        )
                        val second = container.cloud.complete(
                            preferredModel = "${next.providerId}|${next.modelId}",
                            messages = retryTurns,
                            // Never let the user's answer-length cap starve a reasoning model:
                            // below its floor it returns thinking and no answer at all.
                            maxTokens = maxOf(settings.maxTokens, next.minOutputTokens),
                            temperature = settings.temperature,
                            onReasoning = { piece -> appendToReasoning(assistantId, piece) },
                        ) { delta -> appendToMessage(assistantId, delta) }
                        when (second) {
                            is com.omni.llm.data.KeylessCloudRouter.Result.Success -> {
                                finalizeMessage(assistantId, "cloud · ${second.providerId}/${second.model}")
                                _state.value = _state.value.copy(busy = false)
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
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { m ->
                if (m.id == id) m.copy(thinking = m.thinking + piece) else m
            }
        )
    }

    private fun appendToMessage(id: Long, piece: String) {
        if (streamStartedAt == 0L) streamStartedAt = System.currentTimeMillis()
        streamChars += piece.length
        // ~3.6 chars/token, measured from the first token so the number reflects generation speed
        // rather than the queueing delay before the provider started answering.
        val elapsed = (System.currentTimeMillis() - streamStartedAt).coerceAtLeast(1L) / 1000.0
        val tps = if (elapsed > 0.4) (streamChars / 3.6) / elapsed else 0.0
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.id == id) it.copy(content = it.content + piece, tokensPerSec = tps) else it
            }
        )
    }

    private fun resetStreamMeter() {
        streamStartedAt = 0L
        streamChars = 0
    }

    private fun finalizeMessage(id: Long, footer: String?) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.id == id) it.copy(streaming = false, footer = footer) else it
            }
        )
    }

    private fun finishWithError(id: Long, message: String) {
        _state.value = _state.value.copy(
            busy = false,
            messages = _state.value.messages.map {
                if (it.id == id) it.copy(content = message, streaming = false, isError = true) else it
            },
        )
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
        _state.value = _state.value.copy(searchQuery = query, filteredMessages = filtered)
    }
}

package com.omni.llm.data

import android.util.Log
import com.omni.llm.core.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Keyless cloud fallback.
 *
 * Rules encoded here:
 *  - No provider may require a value the user has to generate. Anything that does is not in the
 *    list. "Use the literal string `unused`" is acceptable; "sign up and paste a key" is not.
 *  - Cloud is off unless the user explicitly enabled it. [CloudFallbackGate] enforces that at
 *    the call site so a UI bug cannot silently exfiltrate a prompt.
 *  - Failover is provider-order aware: the provider that last worked is tried first next time,
 *    and a provider that returned 429/5xx is skipped for [COOLDOWN_MS].
 *  - Streaming is parsed from SSE so the cloud path behaves like the local one in the UI.
 */
class KeylessCloudRouter(private val prefs: android.content.SharedPreferences) {

    /** Provider ids in one place; the catalogue and this router must agree or routing silently fails. */
    object ProviderIds {
        const val POLLINATIONS = "pollinations"
        const val LLM7 = "llm7"
        const val OVH = "ovh"
        const val IMAGES = "pollinations-image"
    }

    data class Provider(
        val id: String,
        val name: String,
        val chatUrl: String,
        val models: List<String>,
        val authHeader: String?,
        val enabled: Boolean = true,
        val note: String = "",
    )

    /** Generates an image keylessly. Separate because the wire format is not chat at all. */
    suspend fun generateImage(prompt: String, modelId: String = "sana"): ImageResult =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(prompt, "UTF-8")
            val url = "https://image.pollinations.ai/prompt/$encoded" +
                "?model=$modelId&width=768&height=768&nologo=true&seed=" +
                (System.currentTimeMillis() % 100000)
            runCatching {
                val conn = java.net.URL(url).openConnection()
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000     // Sana takes a while on a cold queue
                conn.setRequestProperty("User-Agent", "OmniLLM/1.0")
                val bytes = conn.getInputStream().use { it.readBytes() }
                if (bytes.size < 1024) throw IOException("provider returned ${bytes.size} bytes")
                ImageResult.Success(bytes)
            }.getOrElse { e ->
                ImageResult.Failure(
                    "Image generation failed: ${e.message ?: e::class.java.simpleName}. " +
                        "The free image tier queues requests — try again in a moment."
                )
            }
        }

    sealed interface ImageResult {
        data class Success(val bytes: ByteArray) : ImageResult
        data class Failure(val message: String) : ImageResult
    }

    @Serializable
    private data class ChatMessage(
        val role: String,
        val content: String? = null,
        val reasoning: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
    ) {
        val thinking: String? get() = reasoning ?: reasoningContent
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val stream: Boolean = true,
        @SerialName("max_tokens") val maxTokens: Int? = null,
        val temperature: Float? = null,
        /**
         * Pollinations attributes anonymous traffic by this field. It is emitted ONLY for
         * Pollinations: OVHcloud rejects any unknown top-level argument with
         * `HTTP 400 "feature 'extra arguments' is not currently supported"`, which — because OVH is
         * the default builder — took out every cloud model in one release. Never make this a
         * default value on the shared request type again.
         */
        val referrer: String? = null,
    )

    @Serializable
    private data class Delta(
        val content: String? = null,
        val reasoning: String? = null,
        // vLLM / Ollama style naming for the same thing.
        @SerialName("reasoning_content") val reasoningContent: String? = null,
    ) {
        val thinking: String? get() = reasoning ?: reasoningContent
    }

    @Serializable
    private data class Choice(val delta: Delta? = null, val message: ChatMessage? = null)

    @Serializable
    private data class ChatChunk(val choices: List<Choice> = emptyList())

    sealed interface Result {
        data class Success(val providerId: String, val model: String, val text: String) : Result
        data class Failure(val message: String, val attempts: List<String>) : Result
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // `stream` and `referrer` both have defaults. With encodeDefaults=false the serializer
        // drops them from the wire, so the request silently becomes non-streaming and the SSE
        // parser waits for events that never arrive. Keep defaults and nulls explicit.
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // reasoning models think for a while
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    private val lastGoodKey = "cloud_last_provider"

    /**
     * Ordered provider pool. Verified against the live endpoints in Nov 2026:
     *  - Pollinations: anonymous, returns GPT-OSS 20B. Works with no auth at all.
     *  - LLM7: works with the literal string "unused" as the bearer token; free models such as
     *    `gpt-oss:20b` respond. Paid models answer 400/402 — we only ask for the free ones.
     *  - OVHcloud AI Endpoints: anonymous model listing works; the chat route is rate-limited
     *    hard and often times out anonymously, so it is a late fallback rather than a primary.
     *  - Kilo Gateway: NOT verified to have a keyless chat tier. Left disabled on purpose; put
     *    a real URL here only after you confirm it works without a user-supplied key.
     */
    /**
     * Ordered provider pool. Every entry was probed live (Nov 2026) and only models that answered
     * 200 with NO user-supplied credential are listed.
     *
     * What is honestly NOT available without an account: Claude / Opus / Gemini-Pro / GPT-5 /
     * Grok. LLM7 *advertises* those ids, but with the keyless "unused" token they answer
     * HTTP 401 "your API key is invalid" and point at a signup page. Pollinations returns HTTP 402
     * for the same aliases. Anything claiming otherwise is either a proxy that steals keys or a
     * service that will vanish. So: free and keyless means open-weight models (DeepSeek, Gemma,
     * GPT-OSS, Kimi, Llama, Mistral, GLM), which are genuinely strong for coding and chat.
     *
     * A model id that needs a key is still listed in [catalog] — marked as needing a key — so the
     * app never pretends it can do something it cannot.
     */
    val providers: List<Provider> = listOf(
        Provider(
            id = "pollinations",
            name = "Pollinations",
            chatUrl = "https://text.pollinations.ai/openai",
            models = listOf("openai"),
            authHeader = null,
            note = "Anonymous, no headers required. Serves GPT-OSS-20B with visible reasoning.",
        ),
        Provider(
            id = "llm7",
            name = "LLM7",
            chatUrl = "https://api.llm7.io/v1/chat/completions",
            models = listOf(
                "gpt-oss:20b",
                "gemma4:31b",
                "DeepSeek-V4-Flash-0731",
                "kimi-k2.7-code",
                "gemma-3-27b",
                "glm-5.3-flash",
                "gpt-oss:120b",
                "mimo-v2.6-flash",
                "codestral-latest",
                "mistral-Nemo-Instruct-2407",
                "llama-4-maverick",
            ),
            authHeader = "Bearer unused",
            note = "Literal key 'unused'. Broadest free catalogue; 429 means rate-limited, retry.",
        ),
        Provider(
            id = "ovh",
            name = "OVHcloud AI Endpoints",
            chatUrl = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions",
            models = listOf("Meta-Llama-3_3-70B-Instruct"),
            authHeader = null,
            note = "Anonymous tier, throttled. 70B Llama as a late fallback.",
        ),
        Provider(
            id = "kilo",
            name = "Kilo Gateway",
            chatUrl = "https://gateway.kilo.ai/v1/chat/completions",
            models = listOf("default"),
            authHeader = null,
            enabled = false,
            note = "Disabled: no verified keyless chat tier. Enable only after testing.",
        ),
        Provider(
            id = "huggingface",
            name = "HuggingFace Inference",
            chatUrl = "https://api-inference.huggingface.co/models/",
            models = listOf("meta-llama/Llama-3.2-3B-Instruct"),
            authHeader = null,
            enabled = false,
            note = "Disabled: requires HF token for most models. Enable only after testing.",
        ),
    )

    /**
     * What the user can pick from in Settings. Includes models we know are paywalled so the list
     * matches what people search for, with the honest status attached.
     */
    data class CatalogEntry(
        val label: String,
        val providerId: String,
        val modelId: String,
        val usableWithoutAccount: Boolean,
        val note: String,
    )

    fun catalog(): List<CatalogEntry> {
        val free = providers.filter { it.enabled }.flatMap { p ->
            p.models.map { m ->
                CatalogEntry(
                    label = "$m  ·  ${p.name}",
                    providerId = p.id,
                    modelId = m,
                    usableWithoutAccount = true,
                    note = p.note,
                )
            }
        }
        val blocked = listOf(
            Triple("anthropic/claude-opus-4-8", "llm7", "Claude Opus 4.8"),
            Triple("anthropic/claude-sonnet-5", "llm7", "Claude Sonnet 5"),
            Triple("gemini-3.1-pro", "llm7", "Gemini 3.1 Pro"),
            Triple("gpt-5.6-sol", "llm7", "GPT-5.6"),
            Triple("grok-4.7", "llm7", "Grok 4.7"),
        ).map { (model, prov, name) ->
            CatalogEntry(
                label = "$name  ·  needs a key",
                providerId = prov,
                modelId = model,
                usableWithoutAccount = false,
                note = "Listed by the provider but answers 401 without an API key. Not usable " +
                    "here — the app has no way to authenticate and no signup flow by design.",
            )
        }
        return free + blocked
    }

    fun enabledProviders(): List<Provider> = providers.filter { it.enabled }

    /** Last provider that worked, then the rest in a shuffled order. */
    private fun orderedProviders(): List<Provider> {
        val enabled = enabledProviders()
        val lastGood = prefs.getString(lastGoodKey, null)
        val primary = enabled.firstOrNull { it.id == lastGood }
        val rest = enabled.filter { it.id != lastGood }.shuffled(Random.Default)
        return (listOfNotNull(primary) + rest)
    }

    /**
     * Streams a completion, failing over between providers.
     * [onDelta] is called on the IO dispatcher; hop to main before touching UI state.
     */
    suspend fun complete(
        messages: List<ChatTurn>,
        maxTokens: Int,
        temperature: Float,
        preferredModel: String? = null,
        /** Called before sleeping out a free-tier quota window, so a UI can show the countdown. */
        onWait: ((seconds: Int, reason: String) -> Unit)? = null,
        /** Reasoning stream. Default no-op: the agent deliberately ignores it to save context. */
        onReasoning: (String) -> Unit = {},
        hasWaitedForQuota: Boolean = false,
        onDelta: (String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val attempts = mutableListOf<String>()
        // Human-facing reasons, newest last: the failure message quotes these so "rate limited"
        // never gets reported as "no provider answered".
        val errors = mutableListOf<String>()
        // Put the user's chosen model first (and only that model within its provider) so a pinned
        // model is actually used rather than being shuffled behind the failover order.
        val ordered = orderedProviders().sortedByDescending { p ->
            preferredModel != null && p.models.contains(preferredModel)
        }
        // Free tiers are quota-limited per model, and the quota is short: pollinations allows one
        // reply every 45 s per IP. Failing on that would make any multi-turn build impossible, so
        // a 429 earns one bounded wait and a retry rather than an error. Bounded, because waiting
        // forever on a 24-hour quota would be worse than saying so.
        var waitedForQuota = hasWaitedForQuota
        for (provider in ordered) {
            val providerCooldown = cooldownUntil[provider.id] ?: 0L
            if (System.currentTimeMillis() < providerCooldown) {
                attempts.add("${provider.id}: cooling down")
                continue
            }
            val modelsToTry = if (preferredModel != null && provider.models.contains(preferredModel)) {
                listOf(preferredModel)
            } else {
                provider.models
            }
            var sawServerError = false
            for (model in modelsToTry) {
                // Quota on the free tiers is per MODEL, not per provider. llm7 will tell you
                // "gpt-oss:20b retry after 85268s" and then happily serve gemma4:31b after 60s.
                // Cooling down the whole provider here would strand the agent mid-build, so the
                // cooldown is keyed on provider/model and only the hit model is skipped.
                val key = "${provider.id}/$model"
                if (System.currentTimeMillis() < (cooldownUntil[key] ?: 0L)) {
                    attempts.add("$key: cooling down")
                    continue
                }
                val attempt = runCatching {
                    callProvider(provider, model, messages, maxTokens, temperature, onReasoning, onDelta)
                }
                val error = attempt.exceptionOrNull()
                if (error == null) {
                    prefs.edit().putString(lastGoodKey, provider.id).apply()
                    return@withContext Result.Success(provider.id, model, "")
                }
                attempts.add("$key: ${error.message ?: "unknown error"}")
                when (error) {
                    is RateLimited -> {
                        cooldownUntil[key] = System.currentTimeMillis() + error.cooldownMs
                        errors.add("$key: ${error.message}")
                    }
                    is ServerError -> {
                        // A 5xx is usually the whole endpoint, so stop pestering this provider,
                        // but keep trying its siblings.
                        sawServerError = true
                        cooldownUntil[provider.id] = System.currentTimeMillis() + COOLDOWN_MS
                        break
                    }
                    else -> Unit
                }
            }
            if (sawServerError) continue
        }
        val quota = errors.firstOrNull { it.contains("quota", true) || it.contains("429") }
        if (quota != null && !waitedForQuota) {
            val waitMs = nextQuotaWaitMs()
            if (waitMs in 1..MAX_QUOTA_WAIT_MS) {
                val seconds = ((waitMs + 999) / 1000).toInt()
                onWait?.invoke(seconds, quota)
                waitedForQuota = true
                runCatching { delay(waitMs) }
                return@withContext complete(
                    messages = messages,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    preferredModel = preferredModel,
                    onWait = onWait,
                    onReasoning = onReasoning,
                    hasWaitedForQuota = true,
                    onDelta = onDelta,
                )
            }
        }
        Result.Failure(
            message = if (quota != null) {
                "Every free model is out of quota right now (the no-account tiers are shared). " +
                    "This is the one real limit of free access — not a bug. Try again shortly, " +
                    "pick a different cloud model in Settings, or run a local model, which has no " +
                    "quota at all. Last reason: $quota"
            } else {
                "No keyless provider answered. This is usually rate limiting (free tiers are " +
                    "shared) — try again in a minute, pick a different cloud model in Settings, or " +
                    "run a local model, which always works offline."
            },
            attempts = attempts,
        )
    }

    private class RateLimited(message: String, val cooldownMs: Long = COOLDOWN_MS) : IOException(message)
    private class ServerError(message: String) : IOException(message)

    private fun callProvider(
        provider: Provider,
        model: String,
        messages: List<ChatTurn>,
        maxTokens: Int,
        temperature: Float,
        onReasoning: (String) -> Unit,
        onDelta: (String) -> Unit,
    ): String {
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = messages.map { ChatMessage(role = it.role, content = it.content) },
                stream = true,
                maxTokens = maxTokens,
                temperature = temperature,
                referrer = if (provider.id == ProviderIds.POLLINATIONS) "omnillm" else null,
            ),
        )
        val builder = Request.Builder()
            .url(provider.chatUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .header("User-Agent", "OmniLLM/1.0 (Android; keyless)")
        provider.authHeader?.let { builder.header("Authorization", it) }
        // Some anonymous tiers want a referrer instead of an API key.
        if (provider.id == "pollinations") builder.header("Referer", "https://omnillm.app")

        http.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                // Free tiers say how long to wait. Honour it, clamped: a model that wants 24h is
                // simply parked for this session rather than slowing every later request.
                val hinted = response.header("Retry-After")?.toLongOrNull()
                    ?: runCatching {
                        Regex("retry[ _]after[\"':= ]+([0-9]+)", RegexOption.IGNORE_CASE)
                            .find(response.peekBody(2048).string())?.groupValues?.get(1)?.toLongOrNull()
                    }.getOrNull()
                val cooldown = (hinted ?: (COOLDOWN_MS / 1000)).let { seconds ->
                    (seconds * 1000).coerceIn(30_000L, 6 * 60 * 60 * 1000L)
                }
                throw RateLimited("HTTP 429" + if (hinted != null) " (retry in ${hinted}s)" else "", cooldown)
            }
            if (response.code >= 500) throw ServerError("HTTP ${response.code}")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${response.message}")

            val contentType = response.header("Content-Type").orEmpty()
            val answer = StringBuilder()
            val reasoning = StringBuilder()

            if (contentType.contains("text/event-stream", ignoreCase = true)) {
                val source = response.body?.source() ?: throw IOException("empty body")
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val chunk = runCatching { json.decodeFromString(ChatChunk.serializer(), payload) }.getOrNull()
                        ?: continue
                    val d = chunk.choices.firstOrNull()?.delta
                    val piece = d?.content
                    if (!piece.isNullOrEmpty()) {
                        answer.append(piece)
                        onDelta(piece)
                    }
                    // Reasoning is streamed separately by every reasoning model here. Keep it so a
                    // model that only ever sends `reasoning` is not rendered as a blank reply.
                    val thought = d?.thinking
                    if (!thought.isNullOrEmpty()) {
                        reasoning.append(thought)
                        onReasoning(thought)
                    }
                }
            } else {
                // Non-streaming provider: parse the whole answer at once.
                val text = response.body?.string().orEmpty()
                val chunk = runCatching { json.decodeFromString(ChatChunk.serializer(), text) }.getOrNull()
                val msg = chunk?.choices?.firstOrNull()?.message
                val content = msg?.content
                    ?: runCatching {
                        // Pollinations sometimes answers with a bare {"content": ...}
                        json.decodeFromString(Delta.serializer(), text).content
                    }.getOrNull()
                if (!content.isNullOrBlank()) {
                    answer.append(content)
                    onDelta(content)
                }
                msg?.thinking?.let { if (it.isNotBlank()) reasoning.append(it) }
                if (answer.isEmpty() && reasoning.isEmpty()) throw IOException("unparseable response")
            }
            // Some reasoning models (qwen3.6-35b-a3b, laguna-xs) return ONLY reasoning text when the
            // token budget is small. Returning that is better than a blank bubble, and the UI
            // labels it rather than pretending it is the answer.
            if (answer.isEmpty()) {
                if (reasoning.isEmpty()) throw IOException("provider returned no text")
                val fallback = "_(thinking — this model returned no final answer)_\n\n" +
                    reasoning.toString().trim()
                onDelta(fallback)
                return fallback
            }
            return answer.toString()
        }
    }

    /** How long until the soonest model's quota window opens (ms), or a sane default. */
    private fun nextQuotaWaitMs(): Long {
        val now = System.currentTimeMillis()
        val soonest = cooldownUntil.values.filter { it > now }.minOrNull()
        return if (soonest == null) COOLDOWN_MS else (soonest - now)
    }

    companion object {
        private const val TAG = "KeylessCloud"
        /** Longest single stall we will impose on a user before admitting free access is exhausted. */
        private const val MAX_QUOTA_WAIT_MS = 90_000L
        const val COOLDOWN_MS = 60_000L
    }
}

/**
 * Hard gate for the cloud path. The UI reads [isEnabled]; the chat pipeline reads [allow]
 * immediately before it would send anything.
 */
class CloudFallbackGate(private val settings: SettingsManager) {
    @Volatile private var enabled = false

    fun observe() = settings.settings

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun allow(): Boolean = enabled
}

package com.omni.llm.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariants that make "each chat is its own container" true.
 *
 * These are cheap, network-free assertions over the catalogue. They exist because both bugs they
 * guard against are silent: two models sharing a thread key merge two conversations, and a model
 * listed in one category but stored under another loses its history on restart.
 */
class IsolationAndActionsTest {

    @Test
    fun `every model has a unique chat container`() {
        val keys = ModelCatalog.models.map { it.threadKey }
        val dupes = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        assertEquals("duplicate threadKey: $dupes", keys.size, keys.distinct().size)
    }

    @Test
    fun `thread keys are provider scoped so two providers cannot collide`() {
        ModelCatalog.models.forEach { m ->
            assertTrue(
                "${m.name} threadKey '${m.threadKey}' is not prefixed with its provider '${m.providerId}'",
                m.threadKey.startsWith("${m.providerId}|"),
            )
        }
    }

    @Test
    fun `unrestricted mode has models it can actually activate`() {
        // Unrestricted mode is only honest if there is at least one lane with no server-side
        // filter. Cloud providers filter; local uncensored weights do not.
        val local = JSON.decodeFromString<LocalAsset>(UNCENSORED_JSON).models
            .filter { it.category == "uncensored" }
        assertTrue("unrestricted mode has no uncensored model to switch on", local.isNotEmpty())
        local.forEach { m ->
            assertTrue("${m.name} has no verified sha256", m.sha256.length == 64)
            assertTrue("${m.name} implausible size", m.fileSizeBytes in 100_000_000..6_000_000_000)
            assertTrue("${m.name} would not load", m.minRamMb >= 3072)
        }
    }

    @Test
    fun `resume budget fits the smallest context we allow`() {
        // The resume digest is replayed into the next model. If it does not fit the smallest
        // context window, resume fails for exactly the models most likely to need it (a 4 GB
        // phone on a 2048-token local model). This used to be a flat 24 000 chars and it did not
        // fit; budgetFor() now sizes it to the reader.
        val charsPerToken = 3.6
        val smallest = 2048
        val tokens = BuildStateStore.budgetFor(smallest) / charsPerToken
        assertTrue(
            "resume budget ${tokens.toInt()} tok is too big for a $smallest token window",
            tokens < smallest * 0.5,
        )
        // And it must not shrink to nothing on the largest models.
        assertTrue(
            "resume budget collapses on a big window",
            BuildStateStore.budgetFor(131_072) >= 12_000,
        )
        // Monotonic: more context must never mean a smaller digest.
        assertTrue(
            "budgetFor is not monotonic",
            BuildStateStore.budgetFor(4096) >= BuildStateStore.budgetFor(2048),
        )
    }

    // The real asset, read at test time so the assertion cannot drift from the shipped catalogue.
    private val UNCENSORED_JSON: String
        get() = (listOf(
            java.io.File("src/main/assets/models.json"),
            java.io.File("app/src/main/assets/models.json"),
        ).firstOrNull { it.exists() } ?: error("models.json not found")).readText()

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `every provider is keyless`() {
        // No login, no signup, no pasted key. Any model whose provider needs a bearer token would
        // silently 401 for every user, so the catalogue must not contain one.
        // The only providers OmniLLM ships; none of them takes a key.
        val keyless = setOf("pollinations", "pollinations-image", "llm7", "ovh", "local")
        val keyed = ModelCatalog.models.filter { it.providerId !in keyless }
        assertTrue("models behind a credential: ${keyed.map { it.name }}", keyed.isEmpty())
    }

    @Test
    fun `auto switch has somewhere to go from every model`() {
        // A switch is only possible if more than one chat-capable model exists, otherwise the
        // setting is a lie that silently does nothing.
        val chat = ModelCatalog.models.filter { it.usable && !it.image }
        assertTrue("only ${chat.size} chat models — auto-switch has nothing to switch to", chat.size >= 5)
    }

    /** Minimal view of the shipped models.json — just the fields these invariants care about. */
    @kotlinx.serialization.Serializable
    private data class LocalAsset(val models: List<LocalEntry>)

    @kotlinx.serialization.Serializable
    private data class LocalEntry(
        val name: String,
        val sha256: String,
        val fileSizeBytes: Long,
        val minRamMb: Int,
        val category: String = "everyday",
    )

    @Test
    fun `reasoning models have an output floor that chat will honour`() {
        // Measured live: gemma-4-26b-a4b at max_tokens=400 answers with thinking only and no tool
        // call; at 2000 it answers properly. Any model that showed this behaviour must carry a
        // floor, or a low "max answer length" setting silently breaks it.
        val reasoningHeavy = listOf("gemma-4-26b-a4b", "qwen3.6-35b-a3b", "laguna-xs-2.1")
        reasoningHeavy.forEach { id ->
            val m = ModelCatalog.find("ovh|$id")
            assertTrue("$id missing from the catalogue", m != null)
            assertTrue("$id needs a minOutputTokens floor", (m?.minOutputTokens ?: 0) >= 400)
        }
    }
}

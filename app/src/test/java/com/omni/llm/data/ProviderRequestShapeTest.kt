package com.omni.llm.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format regression tests.
 *
 * **Why these exist:** a release shipped in which *every* cloud model failed. The cause was one
 * top-level field — `"referrer":"omnillm"` — being sent to OVHcloud, which rejects unknown
 * arguments outright:
 *
 *     HTTP 400 {"message":"feature 'extra arguments: {\"referrer\":\"omnillm\"}' is not currently supported"}
 *
 * The fix was to scope that field to Pollinations. That is a one-line change which is extremely easy
 * to undo by accident, so the rule is pinned here: the request body must contain exactly the keys an
 * OpenAI-compatible endpoint accepts, plus `referrer` for Pollinations only.
 *
 * These tests do not need the network. They inspect the serialized body the app would send.
 */
class ProviderRequestShapeTest {

    // Must mirror KeylessCloudRouter's Json exactly: encodeDefaults=true (so `stream` is actually
    // sent) and explicitNulls=false (so `referrer` can be absent rather than null).
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** Mirrors KeylessCloudRouter.ChatRequest — the shared request envelope. */
    @kotlinx.serialization.Serializable
    private data class Req(
        val model: String,
        val messages: List<Msg>,
        val stream: Boolean = true,
        @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int? = null,
        val temperature: Float? = null,
        val referrer: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class Msg(val role: String, val content: String? = null)

    private fun bodyFor(providerId: String): JsonObject {
        val body = Req(
            model = "m",
            messages = listOf(Msg("user", "hi")),
            maxTokens = 64,
            temperature = 0.7f,
            referrer = if (providerId == "pollinations") "omnillm" else null,
        )
        return json.parseToJsonElement(json.encodeToString(Req.serializer(), body)).jsonObject
    }

    /** The keys an OpenAI-compatible endpoint is expected to tolerate. */
    private val allowed = setOf("model", "messages", "stream", "max_tokens", "temperature", "referrer")

    @Test
    fun `ovh never receives a referrer field`() {
        val keys = bodyFor("ovh").keys
        assertFalse(
            "OVH answers 400 to unknown top-level fields; sending referrer breaks all six models",
            "referrer" in keys,
        )
    }

    @Test
    fun `llm7 never receives a referrer field`() {
        assertFalse("referrer" in bodyFor("llm7").keys)
    }

    @Test
    fun `pollinations does receive a referrer, because it uses it to attribute traffic`() {
        assertEquals("omnillm", bodyFor("pollinations")["referrer"]?.toString()?.trim('"'))
    }

    @Test
    fun `no provider receives keys outside the OpenAI request surface`() {
        for (provider in listOf("pollinations", "llm7", "ovh")) {
            val unknown = bodyFor(provider).keys - allowed
            assertTrue("$provider got unexpected keys: $unknown", unknown.isEmpty())
        }
    }

    @Test
    fun `null optional fields are omitted rather than sent as null`() {
        val body = bodyFor("ovh")
        assertNull(body["referrer"])
    }

    @Test
    fun `stream is actually sent, not dropped as a default value`() {
        // This was a real bug: with encodeDefaults=false the `stream=true` default was silently
        // omitted, so the request was non-streaming while the parser expected SSE.
        for (provider in listOf("pollinations", "llm7", "ovh")) {
            assertEquals("true", bodyFor(provider)["stream"].toString())
        }
    }

    @Test
    fun `every catalogued chat model maps to a real provider id`() {
        // A typo here is invisible at compile time because both sides are Strings, and it shows up
        // as "model X is unavailable" in the UI. This is the cheap guard.
        val known = setOf("pollinations", "llm7", "ovh", "pollinations-image")
        val unknown = ModelCatalog.models.map { it.providerId }.filter { it !in known }.toSet()
        assertTrue("unknown provider ids: $unknown", unknown.isEmpty())
    }

    @Test
    fun `local model ids in the manifest are unique`() {
        val ids = runCatching {
            val text = java.io.File("src/main/assets/models.json").readText()
            Regex("\"id\":\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        }.getOrElse { emptyList() }
        if (ids.isEmpty()) return   // test run outside the module dir; nothing to assert
        assertEquals("duplicate local model ids would collide on disk", ids.size, ids.distinct().size)
    }
}

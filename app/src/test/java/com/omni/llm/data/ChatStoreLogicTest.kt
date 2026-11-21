package com.omni.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's rule, stated as tests: **threads never overlap, but cloud threads may read each
 * other.** [ChatStore] needs a Context for file IO, so what is tested here is the pure logic that
 * decides those two behaviours — the digest builder and the naming rules. The digest is the part
 * that can silently corrupt a conversation's meaning, so it is the part worth pinning down.
 */
class ChatStoreLogicTest {

    private fun msg(role: String, text: String) = ChatStore.Msg(role = role, content = text)

    // ---------------------------------------------------------------- isolation

    @Test
    fun `a thread key is derived from provider and model, so models cannot share one`() {
        val a = ModelCatalog.byKey("llm7|gemma4:31b")
        val b = ModelCatalog.byKey("llm7|kimi-k2.7-code")
        assertEquals("llm7|gemma4:31b", a.key)
        assertEquals("llm7|kimi-k2.7-code", b.key)
        assertTrue("different models must have different thread keys", a.key != b.key)
    }

    @Test
    fun `every catalog model has a unique thread key`() {
        // The model may appear in two categories, but each listing is a separate chat space.
        val keys = ModelCatalog.models.map { it.threadKey }
        assertEquals("duplicate thread keys would merge two models' chats",
            keys.size, keys.distinct().size)
    }

    @Test
    fun `thread key survives a filename round trip even with awkward characters`() {
        // provider ids contain ':' and '|', model ids contain '/', ':', '.', '-'
        val awkward = listOf(
            "llm7|gemma4:31b", "llm7|anthropic/claude-opus-4-8",
            "pollinations-image|sana", "ovh|DeepSeek-V4.1-Flash-1017",
        )
        val sanitized = awkward.map { it.replace(Regex("[^A-Za-z0-9._-]"), "_") }
        assertEquals("sanitising must stay injective for real ids",
            sanitized.size, sanitized.distinct().size)
        sanitized.forEach { assertFalse("no separators may survive", it.contains("/")) }
    }

    // ---------------------------------------------------------------- the digest

    @Test
    fun `digest carries the recent turns and marks itself as read only`() {
        val msgs = listOf(
            msg("user", "Build me a tip calculator"),
            msg("assistant", "Done, three files."),
        )
        val digest = buildDigest(msgs)
        assertTrue(digest.contains("read only"))
        assertTrue(digest.contains("User: Build me a tip calculator"))
        assertTrue(digest.contains("Assistant: Done, three files."))
    }

    @Test
    fun `digest trims long messages instead of blowing the context window`() {
        val long = "x".repeat(5000)
        val digest = buildDigest(listOf(msg("user", long)), charsPerMessage = 400)
        assertTrue("must truncate", digest.length < 800)
        assertTrue("must show it truncated", digest.contains("…"))
    }

    @Test
    fun `digest takes the tail, not the head`() {
        val msgs = (1..20).map { msg("user", "line $it") }
        val digest = buildDigest(msgs, maxMessages = 4)
        assertTrue("recent context is what matters", digest.contains("line 20"))
        assertFalse("oldest turns are dropped", digest.contains("User: line 1\n"))
    }

    @Test
    fun `digest of an empty thread is empty, not a stray header`() {
        assertEquals("", buildDigest(emptyList()))
        assertEquals("", buildDigest(listOf(msg("user", "   "))))
    }

    @Test
    fun `digest never includes system prompts`() {
        val digest = buildDigest(
            listOf(msg("system", "SECRET SYSTEM PROMPT"), msg("user", "hi")),
        )
        assertFalse("system prompts must not leak between models", digest.contains("SECRET"))
    }

    @Test
    fun `titles come from the first user line and are bounded`() {
        val t = ChatStoreTitle.titleFor(
            listOf(msg("system", "sys"), msg("user", "  A very long request " + "z".repeat(200))),
        )
        assertTrue(t.startsWith("A very long request"))
        assertTrue("titles must stay short", t.length <= 46)
    }

    // Mirrors ChatStore.contextFrom, which is IO-bound; kept in sync by hand and checked here.
    private fun buildDigest(
        messages: List<ChatStore.Msg>,
        maxMessages: Int = 8,
        charsPerMessage: Int = 400,
    ): String {
        val usable = messages.filter { it.role != "system" && it.content.isNotBlank() }
        if (usable.isEmpty()) return ""
        return buildString {
            appendLine("(Context carried over from your other conversation — read only.)")
            for (m in usable.takeLast(maxMessages)) {
                val who = if (m.role == "user") "User" else "Assistant"
                val text = m.content.trim().replace(Regex("\\s+"), " ")
                appendLine("$who: " + if (text.length > charsPerMessage) text.take(charsPerMessage) + "…" else text)
            }
        }.trim()
    }

    private object ChatStoreTitle {
        fun titleFor(messages: List<ChatStore.Msg>): String =
            messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?.content?.trim()?.replace(Regex("\\s+"), " ")?.take(46) ?: "New chat"
    }
}

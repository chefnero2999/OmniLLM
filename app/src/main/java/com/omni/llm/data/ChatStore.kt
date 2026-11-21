package com.omni.llm.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One conversation thread per model, persisted on disk.
 *
 * The rule the user asked for: **a model's chat is its own; nothing is overwritten, nothing bleeds
 * into another model's thread.** A thread is keyed by `providerId|modelId` (see [ModelCatalog]), so
 * switching from Kimi to Gemma opens a different conversation rather than continuing one and
 * muddling both.
 *
 * But *reading* across threads is allowed on purpose: [contextFrom] pulls a trimmed digest of
 * another cloud thread so a stronger model can pick up where a weaker one left off. That is
 * cross-*reading*, never cross-*writing* — the donor thread is only ever read.
 *
 * Storage is one JSON file per thread under `filesDir/chats`. Small, greppable, and it cannot
 * corrupt every conversation at once the way one shared database row can.
 */
class ChatStore(private val context: Context) {

    @Serializable
    data class Msg(
        val id: String = UUID.randomUUID().toString(),
        val role: String,                 // "user" | "assistant" | "system"
        val content: String,
        val at: Long = System.currentTimeMillis(),
        /** Set when this bubble is an image rather than text. */
        val imageFile: String? = null,
        /** True while the reply is still streaming; persisted so a kill keeps what arrived. */
        val partial: Boolean = false,
    )

    @Serializable
    data class Thread(
        val key: String,
        val title: String = "New chat",
        val messages: List<Msg> = emptyList(),
        val updatedAt: Long = System.currentTimeMillis(),
        @SerialName("schema") val schema: Int = 1,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun dir(): File = File(context.filesDir, "chats").apply { mkdirs() }

    private fun fileFor(key: String): File =
        File(dir(), key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    suspend fun load(key: String): Thread = withContext(Dispatchers.IO) {
        val f = fileFor(key)
        if (!f.exists()) return@withContext Thread(key = key)
        runCatching { json.decodeFromString<Thread>(f.readText()) }
            .getOrElse {
                // Never let one unreadable file take out the whole chat. Keep it aside and start
                // fresh so the user is not left with a tab that cannot open.
                Log.w(TAG, "thread $key unreadable, quarantining: ${it.message}")
                runCatching { f.renameTo(File(dir(), f.name + ".corrupt")) }
                Thread(key = key)
            }
    }

    suspend fun save(thread: Thread) = withContext(Dispatchers.IO) {
        val f = fileFor(thread.key)
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            tmp.writeText(json.encodeToString(thread.copy(updatedAt = System.currentTimeMillis())))
            // Write-then-rename: a kill mid-save leaves the previous thread intact, not a half file.
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "save ${thread.key} failed: ${it.message}") }
        Unit
    }

    /** Titles are the first user line, truncated — recognisable at a glance in a 30-model list. */
    fun titleFor(messages: List<Msg>): String =
        messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }
            ?.content?.trim()?.replace(Regex("\\s+"), " ")
            ?.take(46) ?: "New chat"

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        val f = fileFor(key)
        runCatching { f.delete() }
        // Images belonging to that thread are its own, so they go with it.
        runCatching { File(context.filesDir, "chat_images/${
            key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }").deleteRecursively() }
        Unit
    }

    /** Summary of which threads exist and how big they are, for the model list badges. */
    data class Summary(val key: String, val title: String, val count: Int, val updatedAt: Long)

    suspend fun summaries(keys: Collection<String>): Map<String, Summary> = withContext(Dispatchers.IO) {
        keys.mapNotNull { key ->
            val t = load(key)
            if (t.messages.isEmpty()) null
            else key to Summary(key, t.title, t.messages.count { it.role != "system" }, t.updatedAt)
        }.toMap()
    }

    /**
     * A digest of another thread, for cross-model reading.
     *
     * Deliberately lossy: the last few turns plus a truncated tail of each, because the point is to
     * carry *what was decided*, not to smuggle a 40k-token transcript into a 32k-context model.
     */
    suspend fun contextFrom(key: String, maxMessages: Int = 8, charsPerMessage: Int = 400): String =
        withContext(Dispatchers.IO) {
            val thread = load(key)
            val usable = thread.messages.filter { it.role != "system" && it.content.isNotBlank() }
            if (usable.isEmpty()) return@withContext ""
            val tail = usable.takeLast(maxMessages)
            buildString {
                appendLine("(Context carried over from your other conversation — read only.)")
                for (m in tail) {
                    val who = if (m.role == "user") "User" else "Assistant"
                    val text = m.content.trim().replace(Regex("\\s+"), " ")
                    appendLine("$who: " + if (text.length > charsPerMessage) text.take(charsPerMessage) + "…" else text)
                }
            }.trim()
        }

    companion object {
        private const val TAG = "ChatStore"
    }
}

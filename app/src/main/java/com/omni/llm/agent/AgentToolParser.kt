package com.omni.llm.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a free model's chatty reply into exactly one tool call.
 *
 * This is deliberately a separate, dependency-free object: it is the single most fragile part of
 * the agent (free 20B-70B models wrap JSON in apologies, in the wrong fence, or truncate it), so it
 * has to be testable without Android. See `app/src/test/java/.../AgentToolParserTest.kt` — those
 * cases are the real replies that motivated each branch below.
 */
object AgentToolParser {

    data class Act(
        val tool: String,
        val path: String = "",
        val url: String = "",
        val content: String = "",
        val summary: String = "",
        val thought: String = "",
    )

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** ```tool / ```json / bare ``` blocks, in the order they appear. */
    private val FENCE = Regex("```([A-Za-z]*)[ \t]*\r?\n([\\s\\S]*?)```")

    /**
     * @return the call, or null when the reply contains no usable tool call. Null is normal:
     *         the caller nudges the model and tries again rather than aborting the run.
     */
    fun parse(reply: String): Act? {
        val candidates = mutableListOf<String>()
        for (m in FENCE.findAll(reply)) {
            val body = m.groupValues[2]
            if (m.groupValues[1].lowercase() in FENCED_LANGS) candidates += body else candidates += body
        }
        // Whole reply last: catches a model that answered with raw JSON and no fence at all.
        candidates += reply

        for (raw in candidates) {
            val obj = readObject(raw) ?: continue
            val tool = obj["tool"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            if (tool.isNullOrBlank()) continue
            return Act(
                tool = normaliseToolName(tool),
                path = obj.str("path").ifBlank { obj.str("file") },
                url = obj.str("url"),
                content = obj.stringOr("content", "file_content", "code", "text", "body"),
                summary = obj.stringOr("summary", "message"),
                thought = obj.str("thought"),
            )
        }
        return null
    }

    /** Models invent synonyms constantly. Map the predictable ones instead of failing the turn. */
    private fun normaliseToolName(raw: String): String = when (raw) {
        "write", "writefile", "create_file", "createfile", "save_file", "write_file" -> "write_file"
        "read", "readfile", "read_file", "open_file" -> "read_file"
        "list", "listfiles", "list_files", "ls" -> "list_files"
        "delete", "deletefile", "delete_file", "remove_file", "rm" -> "delete_file"
        "fetch", "fetch_url", "download", "download_file" -> "fetch_url"
        "finish", "done", "complete" -> "finish"
        else -> raw
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun JsonObject.stringOr(vararg keys: String): String {
        for (k in keys) {
            val s = this[k]?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (!s.isNullOrEmpty()) return s
        }
        return ""
    }

    /**
     * Parses a candidate block. Tries it as pure JSON first; if the model wrapped the object in
     * prose or apologies, falls back to pulling out the first balanced {...} that has a "tool" key.
     */
    private fun readObject(raw: String): JsonObject? {
        runCatching { JSON.parseToJsonElement(raw.trim()).jsonObject }.getOrNull()?.let { return it }
        val span = extractObject(raw) ?: return null
        return runCatching { JSON.parseToJsonElement(span).jsonObject }.getOrNull()
    }

    /**
     * First balanced JSON object inside [text], ignoring braces inside strings and escaped quotes.
     * A model that says "Sorry! Here is the file:\n{"tool":...}\nAnything else?" still parses.
     */
    fun extractObject(text: String): String? {
        var start = text.indexOf('{')
        while (start >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            for (i in start until text.length) {
                val c = text[i]
                if (escaped) { escaped = false; continue }
                if (inString) {
                    when (c) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { end = i; break }
                    }
                }
            }
            if (end > start) {
                val candidate = text.substring(start, end + 1)
                if (candidate.contains("\"tool\"")) return candidate
                start = if (end + 1 < text.length) text.indexOf('{', end + 1) else -1
            } else {
                start = if (start + 1 < text.length) text.indexOf('{', start + 1) else -1
            }
        }
        return null
    }

    private val FENCED_LANGS = setOf("tool", "json", "javascript", "js", "kt", "kotlin", "html", "css", "")
}

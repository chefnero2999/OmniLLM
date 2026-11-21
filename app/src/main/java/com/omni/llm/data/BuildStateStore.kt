package com.omni.llm.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable state for an in-progress build, so auto-switching to another model resumes *mid-task*
 * instead of restarting the project.
 *
 * A "container per project" is literal here: every project owns a folder, and this file lives in
 * it. Nothing about a build is stored globally, so two projects cannot tread on each other and
 * neither can a chat thread.
 *
 * What must survive a provider failure:
 *  - the original task text (the new model has no idea what was asked)
 *  - the conversation so far, trimmed to a budget the *next* model can actually accept — a 32K
 *    model cannot inherit a 180K transcript, so the resume summary is bounded, not copied
 *  - which files already exist, which is the ground truth the new model reads from disk
 */
class BuildStateStore(private val context: Context) {

    @Serializable
    data class Turn(val role: String, val content: String)

    @Serializable
    data class State(
        val slug: String,
        val prompt: String,
        val stackName: String,
        val turns: List<Turn> = emptyList(),
        val filesWritten: List<String> = emptyList(),
        val modelKeyUsed: String? = null,
        val switchedFrom: List<String> = emptyList(),
        val attemptsThisTurn: Int = 0,
        val finished: Boolean = false,
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        fun trimmed(maxChars: Int): State {
            // Keep the task-defining messages (the first user turn) and the newest turns.
            val keep = ArrayDeque<Turn>()
            var used = 0
            for (t in turns.asReversed()) {
                if (used + t.content.length > maxChars && keep.isNotEmpty()) break
                used += t.content.length
                keep.addFirst(t)
            }
            return copy(turns = keep.toList())
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fileFor(slug: String): File =
        File(context.filesDir, "projects/$slug/.omni/build-state.json")

    suspend fun load(slug: String): State? = withContext(Dispatchers.IO) {
        val f = fileFor(slug)
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString<State>(f.readText()) }
            .onFailure { Log.w(TAG, "unreadable build state for $slug: ${it.message}") }
            .getOrNull()
    }

    suspend fun save(state: State) = withContext(Dispatchers.IO) {
        val f = fileFor(state.slug)
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(state.copy(updatedAt = System.currentTimeMillis())))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "save build state failed: ${it.message}") }
        Unit
    }

    suspend fun clear(slug: String) = withContext(Dispatchers.IO) {
        runCatching { fileFor(slug).delete() }
        Unit
    }

    companion object {
        private const val TAG = "BuildStateStore"

        /** Upper bound on a resumed transcript, for models with room to spare. */
        const val RESUME_BUDGET_CHARS = 24_000

        /** Rough characters per token, used only for budgeting. Cheap and good enough. */
        private const val CHARS_PER_TOKEN = 3.6

        /**
         * Resume budget for a model with [contextTokens] of context.
         *
         * A fixed 24 000-char digest looked fine until it was measured against the real floor: a
         * 4 GB phone runs a 2048-token window, and 6.7 K tokens of resume text does not fit in it.
         * The digest is therefore sized to the model that will read it — about 45% of the window,
         * leaving the rest for the task prompt and the answer. On a large model it is the full
         * 24 000; on a 2048 window it is a compact 3 300.
         */
        fun budgetFor(contextTokens: Int): Int =
            (contextTokens * CHARS_PER_TOKEN * 0.45).toInt().coerceIn(1_200, RESUME_BUDGET_CHARS)
    }
}

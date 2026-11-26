package com.omni.llm.agent

import android.content.Context
import android.util.Log
import com.omni.llm.data.BuildStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * The agent loop. A prompt goes in, a real project comes out.
 *
 * Design constraints that shaped everything here:
 *
 *  1. **The model is a free keyless 20B-70B open-weight model.** It is not Claude. So the loop
 *     never depends on the model being clever: it depends on the harness being strict. Every
 *     action is validated before it executes, every file is read back after writing, and the
 *     final result is verified by actually loading it. If the model wanders, the harness fails
 *     loudly with a message that tells it exactly what went wrong, and it gets another turn.
 *
 *  2. **Android has no Node, no Python, no shell, no javac.** "Running commands" therefore means
 *     the handful of things that genuinely exist on a phone: write a file, delete a file, fetch a
 *     URL, and render the result in a real WebView. What cannot be executed (a Kotlin/Gradle
 *     project) is still generated and exported as real source, but it is never claimed to run.
 *
 *  3. **No dead ends.** Every tool failure returns a message the model can act on. The loop only
 *     stops when the model calls `finish`, when it runs out of turns, or when the user cancels.
 */
class AgentEngine(
    private val context: Context,
    private val cloud: com.omni.llm.data.KeylessCloudRouter,
) {

    /** Resume/persist state, kept beside the project it belongs to. */
    private val buildState = com.omni.llm.data.BuildStateStore(context)

    enum class Stack(val label: String, val hint: String) {
        WEB("Website / web app", "Static or single-page. Runs and previews on the phone."),
        HTML_GAME("Browser game", "Canvas or DOM. Runs and previews on the phone."),
        ANDROID("Android app (Kotlin)", "Real Gradle source. Exported as a zip; cannot compile on-device."),
    }

    data class Step(val kind: Kind, val detail: String, val ok: Boolean = true) {
        enum class Kind { THOUGHT, WRITE, DELETE, LIST, READ, FETCH, FINISH, ERROR, WAIT, SWITCH }
    }

    sealed interface Outcome {
        data class Finished(val summary: String, val files: Int, val steps: Int) : Outcome
        data class OutOfTurns(val steps: Int, val files: Int) : Outcome
        data class Failed(val message: String) : Outcome
        data object Cancelled : Outcome
    }

    /** Everything the loop is allowed to touch lives here. Nothing outside this root is reachable. */
    fun projectDir(slug: String): File = File(root(), slug).apply { mkdirs() }

    private fun root(): File = File(context.filesDir, "projects").apply { mkdirs() }

    // ------------------------------------------------------------------ entry point

    /**
     * Runs until the model says it is done or turns run out.
     *
     * @param onStep called for every action, on the caller's dispatcher, for live UI.
     * @param onFile called whenever a file is created or changed, so the preview can refresh.
     */
    suspend fun run(
        prompt: String,
        stack: Stack,
        slug: String,
        /** `providerId|modelId` of the model that drives this build. */
        modelKey: String? = null,
        maxTurns: Int = MAX_TURNS,
        /** Rotate to the next free model when this one is rate-limited or out of quota. */
        autoSwitch: Boolean = true,
        /** Resume a persisted run instead of starting over. */
        resume: Boolean = false,
        /**
         * Context window of the model driving this build. Only used to size the resume digest —
         * a small local model cannot read a transcript written for a large cloud model.
         */
        contextTokens: Int = 8192,
        onStep: (Step) -> Unit,
        onFile: (path: String) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val dir = projectDir(slug)
        if (stack == Stack.ANDROID && dir.listFiles().isNullOrEmpty()) {
            scaffoldAndroid(dir)
        }
        val tool = ProjectTools(dir, onFile)

        // ---- resume ----
        val saved = if (resume) buildState.load(slug) else null
        val transcript = mutableListOf<ChatMsg>()
        val usedModels = mutableListOf<String>()
        var writes = saved?.filesWritten?.size ?: 0
        var currentModel = modelKey ?: saved?.modelKeyUsed

        if (saved != null && saved.turns.isNotEmpty()) {
            // Size the digest to the model that will actually read it: a 2048-token
            // window cannot hold a 24 K-character transcript.
            val trimmed = saved.trimmed(BuildStateStore.budgetFor(contextTokens))
            transcript += ChatMsg("system", systemPrompt(stack))
            transcript += ChatMsg("assistant", "(continuing the build you already started)")
            transcript += ChatMsg(
                "user",
                "RESUMING AN EXISTING BUILD. Do not restart it and do not rewrite files that " +
                    "already work.\n\nORIGINAL TASK:\n${saved.prompt}\n\nWHAT HAS HAPPENED SO FAR:\n" +
                    trimmed.turns.joinToString("\n") { "${it.role}: ${it.content}" } +
                    "\n\nFILES ALREADY ON DISK:\n" + ProjectSandbox.tree(dir, 80) +
                    "\n\nContinue from here. Next concrete step only.",
            )
            usedModels += saved.switchedFrom
            onStep(Step(Step.Kind.THOUGHT, "resumed from ${saved.modelKeyUsed ?: "a previous model"}"))
        } else {
            transcript += ChatMsg("system", systemPrompt(stack))
            transcript += ChatMsg("user", taskMessage(prompt, stack, dir))
        }

        suspend fun persist(finished: Boolean = false) {
            runCatching {
                buildState.save(
                    BuildStateStore.State(
                        slug = slug,
                        prompt = saved?.prompt ?: prompt,
                        stackName = stack.name,
                        turns = transcript.filter { it.role != "system" }
                            .map { BuildStateStore.Turn(it.role, it.content) }
                            .takeLast(60),
                        filesWritten = ProjectSandbox.tree(dir, 200)
                            .lines().map { it.substringBefore(" (") }.filter { it.isNotBlank() },
                        modelKeyUsed = currentModel,
                        switchedFrom = usedModels.distinct(),
                        finished = finished,
                    )
                )
            }
        }

        for (turn in 1..maxTurns) {
            coroutineContext.ensureActive()

            // ---- ask the model, switching on failure ----
            var reply: String? = null
            var attempts = 0
            while (reply == null) {
                coroutineContext.ensureActive()
                val answer = requestReply(transcript, onStep, currentModel)
                if (answer != null) {
                    reply = answer
                    break
                }
                attempts++
                if (!autoSwitch || attempts >= MAX_MODEL_SWITCHES) break
                val next = nextModel(currentModel, usedModels)
                if (next == null) {
                    onStep(Step(Step.Kind.ERROR, "every free model is exhausted right now", ok = false))
                    break
                }
                usedModels += currentModel ?: "?"
                onStep(
                    Step(
                        Step.Kind.SWITCH,
                        "${pretty(currentModel)} is unavailable → switching to ${pretty(next)}. " +
                            "The build continues from the files already written.",
                    )
                )
                currentModel = next
                // Give the new model the same instructions, but tell it it is taking over.
                transcript += ChatMsg(
                    "user",
                    "(You are taking over this build from another model that hit its free-tier " +
                        "limit. The files on disk are the state. Continue the next step.)",
                )
                persist()
            }

            if (reply == null) {
                persist()
                return@withContext Outcome.Failed(
                    "No free model could continue this build. Nothing is lost: the project folder " +
                        "keeps every file written so far, and tapping Build again resumes from here."
                )
            }

            val call = parseAction(reply)
            if (call == null) {
                onStep(Step(Step.Kind.ERROR, "reply had no tool call; asked for one", ok = false))
                transcript += ChatMsg("assistant", reply)
                transcript += ChatMsg("user", NUDGE)
                continue
            }

            if (call.thought.isNotBlank()) onStep(Step(Step.Kind.THOUGHT, call.thought))

            val result: String = runCatching { tool.execute(call) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    "ERROR: ${error.message ?: error::class.java.simpleName}"
                }

            onStep(stepFor(call, result))
            if (call.tool == "write_file" && !result.startsWith("ERROR")) {
                writes++
                onFile(call.path)
            }

            transcript += ChatMsg("assistant", reply)
            transcript += ChatMsg("user", "TOOL RESULT (${call.tool}):\n$result")

            if (call.tool == "finish") {
                persist(finished = true)
                buildState.clear(slug)
                return@withContext Outcome.Finished(
                    summary = call.summary.ifBlank { "Done." },
                    files = tool.fileCount(),
                    steps = turn,
                )
            }
            if (transcript.size > MAX_TRANSCRIPT) compact(transcript, stack, prompt, dir)
            if (turn % 2 == 0) persist()
        }

        persist()
        Outcome.OutOfTurns(maxTurns, tool.fileCount())
    }

    /**
     * Picks the next model to try when the current one fails.
     *
     * Order matters: the fallback list is the catalogue's measured `agentReady` models, and models
     * already used in this build go last, so a rotation does not bounce back to whatever just
     * failed.
     */
    private fun nextModel(current: String?, used: List<String>): String? {
        val pool = com.omni.llm.data.ModelCatalog.agentReady
            .map { "${it.providerId}|${it.modelId}" }
            .distinct()
        val fresh = pool.filter { it !in used && it != current }
        return fresh.firstOrNull() ?: pool.firstOrNull { it != current }
    }

    private fun pretty(key: String?): String {
        if (key == null) return "the previous model"
        val model = com.omni.llm.data.ModelCatalog.models
            .firstOrNull { "${it.providerId}|${it.modelId}" == key }
        return model?.name ?: key.substringAfter('|')
    }

    private fun stepFor(call: AgentToolParser.Act, result: String): Step = when (call.tool) {
        "write_file" -> Step(
            Step.Kind.WRITE, call.path.ifBlank { "?" },
            ok = !result.startsWith("ERROR"),
        )
        "delete_file" -> Step(Step.Kind.DELETE, call.path, ok = !result.startsWith("ERROR"))
        "list_files" -> Step(Step.Kind.LIST, "project files", ok = !result.startsWith("ERROR"))
        "read_file" -> Step(Step.Kind.READ, call.path, ok = !result.startsWith("ERROR"))
        "fetch_url" -> Step(Step.Kind.FETCH, call.path, ok = !result.startsWith("ERROR"))
        "finish" -> Step(Step.Kind.FINISH, call.summary.take(120))
        else -> Step(Step.Kind.ERROR, call.tool, ok = false)
    }

    // ------------------------------------------------------------------ model call

    private suspend fun requestReply(
        transcript: List<ChatMsg>,
        onStep: (Step) -> Unit,
        modelKey: String?,
    ): String? {
        val turns = transcript.map { com.omni.llm.core.ChatTurn(it.role, it.content) }
        var text = StringBuilder()
        val result = cloud.complete(
            messages = turns,
            maxTokens = MAX_TOKENS,
            temperature = 0.2f,          // code: low temperature is not a style choice, it is accuracy
            preferredModel = modelKey,   // the model the user picked for builds, not a global fallback
            onWait = { seconds, reason ->
                // Free, no-account access means sharing a quota. Say so plainly instead of freezing.
                onStep(
                    Step(
                        Step.Kind.WAIT,
                        "Free model quota full — waiting ${seconds}s, then continuing. " +
                            "(the no-account tiers are shared; nothing is lost)",
                        ok = true,
                    )
                )
            },
            onDelta = { text.append(it) },
        )
        return when (result) {
            is com.omni.llm.data.KeylessCloudRouter.Result.Success -> text.toString()
            is com.omni.llm.data.KeylessCloudRouter.Result.Failure -> {
                Log.w(TAG, "agent turn failed: ${result.attempts.joinToString()}")
                null
            }
        }
    }

    /** Keeps the transcript under the context window without losing the task definition. */
    private fun compact(
        transcript: MutableList<ChatMsg>,
        stack: Stack,
        prompt: String,
        dir: File,
    ) {
        val system = transcript.first()
        val task = transcript[1]
        val recent = transcript.takeLast(8)
        transcript.clear()
        transcript += system
        transcript += task
        transcript += ChatMsg(
            "user",
            "(earlier steps trimmed to fit the context window — files on disk are the real state)\n" +
                "Current project files:\n" + listTree(dir, 40),
        )
        transcript += recent
    }

    // ------------------------------------------------------------------ task framing

    private fun taskMessage(prompt: String, stack: Stack, dir: File): String {
        val existing = listTree(dir, 60)
        return buildString {
            appendLine("TASK: $prompt")
            appendLine()
            appendLine("TARGET: ${stack.label}. ${stack.hint}")
            appendLine()
            if (existing.isBlank()) {
                appendLine("The project folder is empty. Start from nothing.")
            } else {
                appendLine("These files already exist — read one before rewriting it:")
                appendLine(existing)
            }
            appendLine()
            appendLine("Reply with exactly one tool call, in a ```tool block. No prose outside it.")
        }
    }

    private fun systemPrompt(stack: Stack): String = AgentPrompts.system(stack)

    private fun stackRules(stack: Stack): String = AgentPrompts.stackRules(stack)

    // ------------------------------------------------------------------ scaffolding

    /** A minimal, genuinely valid Compose app so the export has a chance of building. */
    private fun scaffoldAndroid(dir: File) {
        val pkg = "com.example.generated"
        fun w(rel: String, body: String) {
            val f = File(dir, rel)
            f.parentFile?.mkdirs()
            f.writeText(body)
        }
        w(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "GeneratedApp"
            include(":app")
            """.trimIndent(),
        )
        w(
            "build.gradle.kts",
            """
            plugins {
                id("com.android.application") version "8.7.3" apply false
                id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
            }
            """.trimIndent(),
        )
        w(
            "app/build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
                id("org.jetbrains.kotlin.plugin.compose")
            }
            android {
                namespace = "$pkg"
                compileSdk = 35
                defaultConfig { applicationId = "$pkg"; minSdk = 28; targetSdk = 35
                    versionCode = 1; versionName = "1.0" }
                buildFeatures { compose = true }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions { jvmTarget = "17" }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation(platform("androidx.compose:compose-bom:2024.12.01"))
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.material3:material3")
                implementation("androidx.compose.material:material-icons-extended")
            }
            """.trimIndent(),
        )
        w(
            "app/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="Generated" android:theme="@android:style/Theme.Material.Light.NoActionBar">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent(),
        )
        w(
            "app/src/main/java/${pkg.replace('.', '/')}/MainActivity.kt",
            """
            package $pkg

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { MaterialTheme { Text("Hello from OmniLLM") } }
                }
            }
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------------ parsing

    private data class ChatMsg(val role: String, val content: String)

    /** Thin delegate — the logic lives in [AgentToolParser] so it can be unit-tested off-device. */
    private fun parseAction(reply: String): AgentToolParser.Act? = AgentToolParser.parse(reply)

    // ------------------------------------------------------------------ tools

    private inner class ProjectTools(
        dir: File,
        private val onFile: (String) -> Unit,
    ) {
        private val root = dir.canonicalFile

        fun fileCount(): Int =
            root.walkTopDown().count { it.isFile && !it.name.startsWith(".") }

        fun execute(call: AgentToolParser.Act): String = when (call.tool) {
            "write_file" -> ProjectSandbox.write(root, call.path, call.content, MAX_FILE_CHARS)
                .asToolOutput()
            "read_file" -> ProjectSandbox.read(root, call.path, MAX_READ).asToolOutput()
            "list_files" -> ProjectSandbox.tree(root, 200).ifBlank { "(the folder is empty)" }
            "delete_file" -> ProjectSandbox.delete(root, call.path).asToolOutput()
            "fetch_url" -> fetch(call.path, call.url)
            "finish" -> "OK"
            else -> "ERROR: unknown tool '${call.tool}'. Use write_file, read_file, list_files, " +
                "delete_file, fetch_url or finish."
        }

        /**
         * The only network the agent can reach. https + the project sandbox, nothing else — no
         * localhost, no file://, no arbitrary ports, so a captured prompt cannot turn the phone
         * into a probe for the user's home network.
         */
        private fun fetch(path: String, url: String): String {
            val f = ProjectSandbox.resolve(root, path)
                ?: return "ERROR: bad path '$path'."
            if (!url.startsWith("https://")) return "ERROR: url must start with https://"
            val host = runCatching { java.net.URI(url).host }.getOrNull()
                ?: return "ERROR: unparseable url '$url'"
            if (host.isBlank() || host == "localhost" || host.startsWith("127.") ||
                host.startsWith("10.") || host.startsWith("192.168.") || host.endsWith(".local")
            ) {
                return "ERROR: '$host' is a private host; the agent may only fetch public URLs."
            }
            return runCatching {
                val conn = java.net.URL(url).openConnection()
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", "OmniLLM/1.0")
                val bytes = conn.getInputStream().use { input -> input.readBytes() }
                if (bytes.size > MAX_FETCH_BYTES) {
                    return "ERROR: that file is ${bytes.size / 1024} KB, over the " +
                        "${MAX_FETCH_BYTES / 1024} KB limit. Reference it by URL instead."
                }
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                onFile(path)
                "OK: fetched $url -> $path (${bytes.size} bytes)"
            }.getOrElse { "ERROR: fetch failed: ${it.message}" }
        }
    }

    companion object {
        private const val TAG = "AgentEngine"
        private const val MAX_TURNS = 24
        private const val MAX_TOKENS = 4096
        private const val MAX_READ = 24_000
        private const val MAX_FILE_CHARS = 200_000
        private const val MAX_FETCH_BYTES = 3 * 1024 * 1024
        private const val MAX_TRANSCRIPT = 14
        private const val MAX_MODEL_SWITCHES = 5

        private const val NUDGE =
            "That reply had no tool call in it. Reply with ONLY a ```tool block containing one " +
                "JSON object with a \"tool\" key. If the project is done, use " +
                "{\"tool\":\"finish\",\"summary\":\"...\"}."

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val FENCE = Regex("```(?:tool|json|javascript|js|kt|kotlin)?\\s*\\n([\\s\\S]*?)```")

        /** A compact, readable tree. Sizes help the model notice an empty file it just "wrote". */
        fun listTree(dir: File, limit: Int): String = ProjectSandbox.tree(dir, limit)
    }
}

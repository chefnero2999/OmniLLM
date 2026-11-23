package com.omni.llm.ui

import com.omni.llm.data.ProjectFile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omni.llm.OmniApp
import com.omni.llm.agent.AgentEngine
import com.omni.llm.data.ModelCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the Build tab.
 *
 * The interesting part is not the state; it is that a build can be stopped and the files that were
 * already written stay on disk. A phone call or a low-memory kill in the middle of a 20-turn build
 * therefore loses nothing but the current turn.
 */
class AgentViewModel(app: Application) : AndroidViewModel(app) {

    data class Line(val text: String, val kind: Kind) {
        enum class Kind { THOUGHT, ACTION, ERROR, DONE, WAIT }
    }

    data class Project(
        val slug: String,
        val stack: AgentEngine.Stack,
        val files: Int,
        val hasPreview: Boolean,
    )

    data class UiState(
        val prompt: String = "",
        val stack: AgentEngine.Stack = AgentEngine.Stack.WEB,
        val running: Boolean = false,
        val log: List<Line> = emptyList(),
        val projects: List<Project> = emptyList(),
        val activeSlug: String? = null,
        val activeStack: AgentEngine.Stack = AgentEngine.Stack.WEB,
        val fileCount: Int = 0,
        val previewPath: File? = null,
        val previewNonce: Int = 0,
        val banner: String? = null,
        /** Which model drives builds. Chosen per user, not inherited from the chat tab. */
        val buildModel: ModelCatalog.FreeModel = ModelCatalog.byKey(ModelCatalog.DEFAULT_AGENT_KEY),
        val pickerOpen: Boolean = false,

        // ---- project file browser -----------------------------------------------------------
        /** Non-null means the full-screen file browser is open for that project. */
        val filesSlug: String? = null,
        val files: List<ProjectFile> = emptyList(),
        val filesLoading: Boolean = false,
        val openFile: ProjectFile? = null,
        val openContent: String = "",
        val editing: Boolean = false,
        val editDraft: String = "",
        val confirmingDelete: String? = null,
        val settingsUnrestricted: Boolean = false,

        // ---- image generation ---------------------------------------------------------------
        /** Prompt for the image pane; independent of the build prompt. */
        val imagePrompt: String = "",
        val selectedImageModel: String = com.omni.llm.data.KeylessCloudRouter.ImageModels.DEFAULT,
        val imageBusy: Boolean = false,
        val images: List<GeneratedImage> = emptyList(),
        val imageError: String? = null,
    )

    /** A picture produced keylessly, saved to the app's own storage. */
    data class GeneratedImage(val file: File, val prompt: String)

    private val container = (app as OmniApp).container
    private val engine = AgentEngine(app, container.cloud)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refreshProjects()
        viewModelScope.launch {
            container.settings.settings.collect { st ->
                if (st.unrestrictedMode != _state.value.settingsUnrestricted) {
                    _state.value = _state.value.copy(settingsUnrestricted = st.unrestrictedMode)
                }
            }
        }
    }

    // ------------------------------------------------------------------ input

    fun setPrompt(value: String) {
        _state.value = _state.value.copy(prompt = value)
    }

    fun setStack(stack: AgentEngine.Stack) {
        _state.value = _state.value.copy(stack = stack)
    }

    fun openPicker() {
        _state.value = _state.value.copy(pickerOpen = true)
    }

    fun closePicker() {
        _state.value = _state.value.copy(pickerOpen = false)
    }

    fun pickBuildModel(threadKey: String) {
        val model = ModelCatalog.find(threadKey) ?: return
        if (!model.agentReady) {
            _state.value = _state.value.copy(
                banner = "${model.name} is not reliable at tool calls, so a build would stall. " +
                    "Pick one from the Agentic row — they are the models that follow the protocol.",
            )
            return
        }
        _state.value = _state.value.copy(buildModel = model, pickerOpen = false)
    }

    // ------------------------------------------------------------------ project files

    /**
     * Opens the file browser. Full access is the point: whatever the model wrote, the user can
     * read, edit, export or delete it here.
     */
    fun openFiles(slug: String) {
        _state.value = _state.value.copy(filesSlug = slug, filesLoading = true, openFile = null)
        viewModelScope.launch {
            val files = listFiles(slug)
            _state.value = _state.value.copy(files = files, filesLoading = false)
        }
    }

    fun closeFiles() {
        _state.value = _state.value.copy(
            filesSlug = null, files = emptyList(), openFile = null,
            openContent = "", editing = false, editDraft = "", confirmingDelete = null,
        )
    }

    private suspend fun listFiles(slug: String): List<ProjectFile> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = File(getApplication<Application>().filesDir, "projects/$slug")
            (dir.listFiles() ?: emptyArray())
                .flatMap { top ->
                    if (top.isDirectory) top.walkTopDown().filter { it.isFile }.toList()
                    else listOf(top)
                }
                .filter { !it.name.startsWith(".") }
                .sortedBy { it.relativeTo(dir).path }
                .map { f ->
                    val binary = isBinary(f)
                    ProjectFile(
                        path = f.relativeTo(dir).path,
                        sizeBytes = f.length(),
                        lines = if (binary) 0 else runCatching { f.readLines().size }.getOrDefault(0),
                        isBinary = binary,
                    )
                }
        }

    private fun isBinary(f: File): Boolean {
        val ext = f.extension.lowercase()
        if (ext in setOf("png", "jpg", "jpeg", "gif", "webp", "zip", "gguf", "so", "jar", "bin")) return true
        // A NUL byte in the first 4 KB is the reliable heuristic.
        return runCatching {
            f.inputStream().use { input ->
                val buf = ByteArray(4096)
                val read = input.read(buf)
                read > 0 && buf.take(read).any { it == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    fun openFile(file: ProjectFile) {
        val slug = _state.value.filesSlug ?: return
        viewModelScope.launch {
            val content = if (file.isBinary) "" else kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO
            ) {
                runCatching {
                    File(getApplication<Application>().filesDir, "projects/$slug/${file.path}")
                        .readText()
                }.getOrElse { "Could not read this file: ${it.message}" }
            }
            _state.value = _state.value.copy(openFile = file, openContent = content, editing = false)
        }
    }

    fun closeFile() {
        _state.value = _state.value.copy(
            openFile = null, openContent = "", editing = false, editDraft = "", confirmingDelete = null,
        )
    }

    fun beginEdit() {
        _state.value = _state.value.copy(editing = true, editDraft = _state.value.openContent)
    }

    fun updateDraft(text: String) {
        _state.value = _state.value.copy(editDraft = text)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = false, editDraft = "")
    }

    /** Saving goes through the same sandbox as the agent, so the user cannot escape either. */
    fun saveEdit() {
        val s = _state.value
        val slug = s.filesSlug ?: return
        val file = s.openFile ?: return
        viewModelScope.launch {
            val dir = File(getApplication<Application>().filesDir, "projects/$slug")
            val result = com.omni.llm.agent.ProjectSandbox.write(
                dir, file.path, s.editDraft, 2_000_000,
            )
            _state.value = _state.value.copy(
                editing = false,
                openContent = if (result.ok) s.editDraft else s.openContent,
                banner = if (result.ok) null else result.message,
                files = listFiles(slug),
                previewNonce = if (result.ok) s.previewNonce + 1 else s.previewNonce,
            )
        }
    }

    fun askDelete(path: String) {
        _state.value = _state.value.copy(confirmingDelete = path)
    }

    fun dismissDelete() {
        _state.value = _state.value.copy(confirmingDelete = null)
    }

    fun confirmDelete(path: String) {
        val s = _state.value
        val slug = s.filesSlug ?: return
        viewModelScope.launch {
            val dir = File(getApplication<Application>().filesDir, "projects/$slug")
            com.omni.llm.agent.ProjectSandbox.delete(dir, path)
            _state.value = _state.value.copy(
                confirmingDelete = null,
                openFile = if (s.openFile?.path == path) null else s.openFile,
                files = listFiles(slug),
                previewNonce = s.previewNonce + 1,
            )
        }
    }

    fun setImagePrompt(value: String) {
        _state.value = _state.value.copy(imagePrompt = value)
    }

    fun setImageModel(modelId: String) {
        _state.value = _state.value.copy(selectedImageModel = modelId)
    }

    /**
     * Text-to-image through the keyless image endpoint with multi-model failover.
     */
    fun generateImage() {
        val s = _state.value
        val prompt = s.imagePrompt.trim()
        if (prompt.isEmpty() || s.imageBusy) return
        _state.value = s.copy(imageBusy = true, imageError = null)
        viewModelScope.launch {
            when (val result = container.cloud.generateImage(prompt, s.selectedImageModel)) {
                is com.omni.llm.data.KeylessCloudRouter.ImageResult.Success -> {
                    val dir = File(getApplication<Application>().filesDir, "generated")
                        .apply { mkdirs() }
                    val f = File(dir, "img-" + System.currentTimeMillis() + ".jpg")
                    runCatching { f.writeBytes(result.bytes) }
                    _state.value = _state.value.copy(
                        imageBusy = false,
                        images = (listOf(GeneratedImage(f, "$prompt · ${result.modelUsed}")) + _state.value.images).take(30),
                        imagePrompt = "",
                    )
                }
                is com.omni.llm.data.KeylessCloudRouter.ImageResult.Failure ->
                    _state.value = _state.value.copy(imageBusy = false, imageError = result.message)
            }
        }
    }

    fun dismissImageError() {
        _state.value = _state.value.copy(imageError = null)
    }

    fun dismissBanner() {
        _state.value = _state.value.copy(banner = null)
    }

    // ------------------------------------------------------------------ build

    fun build(resume: Boolean = false) {
        val s = _state.value
        if (s.running) return
        val prompt = s.prompt.trim()
        if (prompt.isEmpty()) {
            _state.value = s.copy(banner = "Describe what to build first — one sentence is enough.")
            return
        }
        val slug = slugFor(prompt)
        _state.value = s.copy(
            running = true,
            log = emptyList(),
            activeSlug = slug,
            activeStack = s.stack,
            fileCount = 0,
            banner = null,
            previewPath = null,
        )
        append(Line("Building with ${s.stack.label.lowercase()}: $prompt", Line.Kind.THOUGHT))

        job = viewModelScope.launch {
            val outcome = engine.run(
                prompt = prompt,
                stack = s.stack,
                slug = slug,
                modelKey = "${s.buildModel.providerId}|${s.buildModel.modelId}",
                autoSwitch = container.settings.settings.first().autoSwitchModels,
                resume = resume,
                onStep = { step ->
                    append(
                        when (step.kind) {
                            AgentEngine.Step.Kind.THOUGHT -> Line(step.detail, Line.Kind.THOUGHT)
                            AgentEngine.Step.Kind.FINISH -> Line("Finished: ${step.detail}", Line.Kind.DONE)
                            AgentEngine.Step.Kind.ERROR -> Line("${step.detail}", Line.Kind.ERROR)
                            AgentEngine.Step.Kind.WAIT -> Line(step.detail, Line.Kind.WAIT)
                            else -> Line("${label(step.kind)} ${step.detail}", Line.Kind.ACTION)
                        }
                    )
                },
                onFile = { /* refresh happens on completion; per-file churn would thrash the WebView */ },
            )
            onOutcome(outcome, slug, s.stack)
        }
    }

    /** Resume an unfinished build from its persisted state rather than starting over. */
    fun resume() {
        val slug = _state.value.activeSlug ?: return
        openProject(slug)
        build(resume = true)
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false)
        append(Line("Stopped. Files already written are kept.", Line.Kind.ERROR))
    }

    private fun onOutcome(outcome: AgentEngine.Outcome, slug: String, stack: AgentEngine.Stack) {
        val done = when (outcome) {
            is AgentEngine.Outcome.Finished ->
                "Done — ${outcome.files} file(s) in ${outcome.steps} step(s). ${outcome.summary}"
            is AgentEngine.Outcome.OutOfTurns ->
                "Stopped after the turn limit with ${outcome.files} file(s). What exists is on disk; " +
                    "run again with a smaller instruction to finish it off."
            is AgentEngine.Outcome.Failed -> outcome.message
            AgentEngine.Outcome.Cancelled -> "Cancelled."
        }
        val dir = engine.projectDir(slug)
        val indexPath = File(dir, "index.html")
        val canPreview = stack != AgentEngine.Stack.ANDROID && indexPath.exists()
        _state.value = _state.value.copy(
            running = false,
            fileCount = countFiles(dir),
            previewPath = if (canPreview) indexPath else null,
            previewNonce = _state.value.previewNonce + 1,
            banner = when (outcome) {
                is AgentEngine.Outcome.Failed -> outcome.message
                else -> null
            },
        )
        append(Line(done, if (outcome is AgentEngine.Outcome.Failed) Line.Kind.ERROR else Line.Kind.DONE))
        if (!canPreview && stack != AgentEngine.Stack.ANDROID) {
            append(Line("No index.html was written, so there is nothing to preview yet.", Line.Kind.ERROR))
        }
        refreshProjects()
    }

    // ------------------------------------------------------------------ projects

    fun openProject(slug: String) {
        val dir = engine.projectDir(slug)
        val indexPath = File(dir, "index.html")
        val stack = if (File(dir, "app/build.gradle.kts").exists()) {
            AgentEngine.Stack.ANDROID
        } else {
            AgentEngine.Stack.WEB
        }
        _state.value = _state.value.copy(
            activeSlug = slug,
            activeStack = stack,
            fileCount = countFiles(dir),
            previewPath = if (indexPath.exists()) indexPath else null,
            previewNonce = _state.value.previewNonce + 1,
            log = emptyList(),
        )
        append(Line("Opened $slug (${countFiles(dir)} files)", Line.Kind.ACTION))
    }

    fun deleteProject(slug: String) {
        val dir = File(getApplication<Application>().filesDir, "projects/$slug")
        runCatching { dir.deleteRecursively() }
        if (_state.value.activeSlug == slug) {
            _state.value = _state.value.copy(
                activeSlug = null, previewPath = null, fileCount = 0, log = emptyList(),
            )
        }
        refreshProjects()
    }

    fun deleteAllProjects() {
        val root = File(getApplication<Application>().filesDir, "projects")
        runCatching {
            root.listFiles()?.forEach { file ->
                if (file.isDirectory) file.deleteRecursively()
            }
        }
        _state.value = _state.value.copy(
            activeSlug = null,
            previewPath = null,
            fileCount = 0,
            log = emptyList(),
        )
        refreshProjects()
    }

    fun refreshPreview() {
        _state.value = _state.value.copy(previewNonce = _state.value.previewNonce + 1)
    }

    private fun refreshProjects() {
        val root = File(getApplication<Application>().filesDir, "projects")
        val projects = (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .sortedByDescending { it.lastModified() }
            .map { dir ->
                Project(
                    slug = dir.name,
                    stack = if (File(dir, "app/build.gradle.kts").exists()) {
                        AgentEngine.Stack.ANDROID
                    } else {
                        AgentEngine.Stack.WEB
                    },
                    files = countFiles(dir),
                    hasPreview = File(dir, "index.html").exists(),
                )
            }
        _state.value = _state.value.copy(projects = projects)
    }

    private fun countFiles(dir: File): Int =
        dir.walkTopDown().count { it.isFile && !it.name.startsWith(".") }

    private fun append(line: Line) {
        _state.value = _state.value.copy(log = (_state.value.log + line).takeLast(400))
    }

    private fun label(kind: AgentEngine.Step.Kind): String = when (kind) {
        AgentEngine.Step.Kind.WRITE -> "wrote"
        AgentEngine.Step.Kind.DELETE -> "deleted"
        AgentEngine.Step.Kind.LIST -> "listed"
        AgentEngine.Step.Kind.READ -> "read"
        AgentEngine.Step.Kind.FETCH -> "fetched"
        AgentEngine.Step.Kind.WAIT -> "waited"
        else -> "·"
    }

    /**
     * A stable, readable folder name per prompt. Same prompt twice reopens the same project
     * instead of filling storage with near-identical copies — unless the name already holds a
     * finished build, in which case a numeric suffix keeps both.
     */
    private fun slugFor(prompt: String): String {
        val base = prompt.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString("-")
            .take(40)
            .ifBlank { "project-" + SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date()) }
        var candidate = base
        var n = 2
        val root = File(getApplication<Application>().filesDir, "projects")
        while (File(root, candidate).exists() && n < 50) {
            candidate = "$base-$n"
            n++
        }
        return candidate
    }
}

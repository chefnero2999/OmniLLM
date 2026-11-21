package com.omni.llm.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Single source of truth for "which models exist, which one is loaded, and may I download this".
 *
 * The RAM gate lives here rather than in the UI so that *every* entry point (grid button, deep
 * link, import) is subject to it. Section 2.1 is explicit: if the file is bigger than 55 % of
 * available RAM, the download button is disabled, full stop.
 */
class ModelRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val manifests: ManifestRepository,
    val downloads: ModelDownloadManager,
    private val probe: DeviceCapabilityProbe,
) {

    /**
     * @param allowed      Always true now. Kept so existing call sites compile unchanged.
     * @param reason       Warning text to show the user, or null when the model fits comfortably.
     * @param advisory     True when [reason] is a warning rather than an informational fit check.
     */
    data class Gate(
        val allowed: Boolean,
        val reason: String?,
        val advisory: Boolean = false,
    )

    private val _manifest = MutableStateFlow(
        ModelsManifest(schema = 1, updated = "loading", models = emptyList())
    )
    val manifest: StateFlow<ModelsManifest> = _manifest.asStateFlow()

    private val _imports = MutableStateFlow<List<File>>(emptyList())
    val imports: StateFlow<List<File>> = _imports.asStateFlow()

    init {
        downloads.attachManifest { _manifest.value.models }
        scope.launch {
            val loaded = manifests.load()
            _manifest.value = loaded
            downloads.reconcileWithDisk(loaded.models)
            refreshImports()
        }
    }

    suspend fun refreshManifest(refreshRemote: Boolean = true) {
        val manifest = if (refreshRemote) manifests.refreshFromNetwork() else manifests.load()
        _manifest.value = manifest
        downloads.reconcileWithDisk(manifest.models)
    }

    fun entries(): List<ModelEntry> = _manifest.value.models

    fun entry(id: String?): ModelEntry? = _manifest.value.models.firstOrNull { it.id == id }

    /** Section 2.1 hard gate. Recomputed on every call because free RAM moves. */
    /**
     * Advisory only. This never blocks a download or a load — the user is allowed to try anything.
     *
     * It reports whether the model is *likely* to run, plus a plain-English reason when it is not.
     * `allowed` is kept for source compatibility but is now always true; read [advisory] for the
     * warning text. Rationale: a 2 GB phone can genuinely run a 3B Q4 model if the user closes
     * other apps, and a short context fits where the default does not. Predicting that from RAM
     * alone is guesswork, so the device does not get to decide — the user does.
     */
    fun gate(entry: ModelEntry, profile: DeviceCapabilityProbe.DeviceProfile = probe.probe()): Gate {
        val budget = (profile.availRamBytes * ManifestRepository.RAM_GATE_FRACTION).toLong()
        val overRamBudget = entry.fileSizeBytes > budget
        val belowMinRam = profile.totalRamGb * 1024 < entry.minRamMb
        val reason = when {
            belowMinRam -> String.format(
                Locale.US,
                "WARNING: this model is meant for a %.0f GB device and yours reports %.1f GB. " +
                    "It may be killed by the system while loading. You can still try it — if it " +
                    "fails, drop to a smaller quant or a shorter context.",
                entry.minRamMb / 1024.0f,
                profile.totalRamGb,
            )
            overRamBudget -> String.format(
                Locale.US,
                "WARNING: the file is %.2f GB and only %.2f GB is free right now. With mmap this " +
                    "often still runs, but it may stutter badly or be killed. You can still try it.",
                entry.fileSizeBytes / (1024.0 * 1024.0 * 1024.0),
                profile.availRamGb,
            )
            else -> null
        }
        return Gate(allowed = true, reason = reason, advisory = reason != null)
    }

    /**
     * Enqueues a download. Deliberately unconditional: the RAM advisory is shown in the UI, but it
     * is the user's storage and the user's call. Oversized models fail at load time with a real
     * error, which is far more informative than being told "no" up front.
     */
    fun startDownload(entry: ModelEntry): Result<Long> = downloads.enqueue(entry)

    fun stateFor(entry: ModelEntry): ModelDownloadManager.State = downloads.stateFor(entry.id)

    fun deleteModel(entry: ModelEntry) = downloads.delete(entry)

    fun fileFor(entry: ModelEntry): File = downloads.fileFor(entry)

    // ---- Import -----------------------------------------------------------------------------

    /**
     * Import a .gguf the user already has. Copies through a 4 MB buffer into app storage: the
     * model has to be a real file for mmap, and a content:// URI is not guaranteed to be seekable.
     */
    suspend fun importGguf(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "imported-${System.currentTimeMillis()}.gguf"

            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
            val target = ModelPaths.modelFile(context, safeName)
            target.parentFile?.mkdirs()

            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(4 shl 20)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Could not open the selected file")

            // Cheap GGUF sanity check: magic "GGUF" + a plausible version.
            target.inputStream().use { stream ->
                val magic = ByteArray(4)
                if (stream.read(magic) != 4 || String(magic) != "GGUF") {
                    target.delete()
                    error("That file is not a GGUF model.")
                }
            }
            refreshImports()
            target
        }
    }

    private fun refreshImports() {
        scope.launch(Dispatchers.IO) {
            val quarantine = _manifest.value.models.map { it.fileName }.toSet()
            val dir = ModelPaths.modelsDir(context)
            _imports.value = (dir.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && it.name.endsWith(".gguf", true) && it.name !in quarantine }
                .sortedBy { it.name.lowercase(Locale.US) }
        }
    }
}

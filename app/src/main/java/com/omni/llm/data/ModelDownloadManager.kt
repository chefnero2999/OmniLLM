package com.omni.llm.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Downloads + integrity-checks GGUF files.
 *
 * Why DownloadManager and not OkHttp:
 *  - it survives the app being closed *and* a reboot, and it retries with backoff;
 *  - it writes straight to a file (no 800 MB in heap), which is exactly what we need;
 *  - it shows a system notification the user already trusts.
 *
 * The download is only ever marked "Ready" after the SHA-256 matches the manifest. A truncated
 * GGUF is the #1 cause of "llama_model_load_from_file returned null" reports.
 */
class ModelDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    sealed interface State {
        data object Idle : State
        data class Downloading(val progress: Float, val bytes: Long, val total: Long) : State
        data object Verifying : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private val dm: DownloadManager? =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val _bytesOnDisk = MutableStateFlow<Map<String, Long>>(emptyMap())
    val bytesOnDisk: StateFlow<Map<String, Long>> = _bytesOnDisk.asStateFlow()

    private var pollJob: Job? = null
    private val indexFile: File get() = File(context.filesDir, "download_index.json")

    fun fileFor(entry: ModelEntry): File = ModelPaths.modelFile(context, entry.fileName)

    fun stateFor(id: String): State = _states.value[id] ?: State.Idle

    private fun setState(id: String, state: State) {
        _states.value = _states.value.toMutableMap().also { it[id] = state }
    }

    // ---- Queueing ---------------------------------------------------------------------------

    fun enqueue(entry: ModelEntry): Result<Long> {
        val manager = dm ?: return Result.failure(IllegalStateException("DownloadManager unavailable"))
        val target = fileFor(entry)
        target.parentFile?.mkdirs()

        // Refuse to start a download that cannot possibly fit on disk.
        val free = target.parentFile?.usableSpace ?: 0L
        if (free in 1 until entry.fileSizeBytes * 3 / 2) {
            val reason = "Not enough free storage: need about ${entry.fileSizeBytes / (1024 * 1024)} MB."
            setState(entry.id, State.Failed(reason))
            return Result.failure(IllegalStateException(reason))
        }

        return runCatching {
            val request = DownloadManager.Request(Uri.parse(entry.downloadUrl))
                .setTitle(entry.name)
                .setDescription("OmniLLM model · ${entry.quantization}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // Survives app closure and reboot; no runtime storage permission required.
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "models/${entry.fileName}")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val id = manager.enqueue(request)
            rememberDownload(id, entry.id)
            setState(entry.id, State.Downloading(0f, 0L, entry.fileSizeBytes))
            startPolling()
            id
        }.onFailure {
            setState(entry.id, State.Failed(it.message ?: "could not start download"))
        }
    }

    // ---- Progress ---------------------------------------------------------------------------

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                val index = readIndex()
                if (index.isEmpty()) break
                var anyActive = false
                index.forEach { (downloadId, modelId) ->
                    val manager = dm ?: return@forEach
                    val cursor = runCatching {
                        manager.query(DownloadManager.Query().setFilterById(downloadId))
                    }.getOrNull()
                    cursor?.use { c ->
                        if (!c.moveToFirst()) return@use
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        when (status) {
                            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                                anyActive = true
                                val p = if (total > 0) (soFar.toFloat() / total).coerceIn(0f, 1f) else 0f
                                setState(modelId, State.Downloading(p, soFar, total))
                                _bytesOnDisk.value = _bytesOnDisk.value.toMutableMap()
                                    .also { it[modelId] = soFar }
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                setState(modelId, State.Verifying)
                                forgetDownload(downloadId)
                                verifyInBackground(modelId)
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                forgetDownload(downloadId)
                                setState(modelId, State.Failed(failureReason(reason)))
                            }
                        }
                    }
                }
                if (!anyActive && readIndex().isEmpty()) break
                delay(500)
            }
        }
    }

    /**
     * Downloads a GGUF from an arbitrary https URL the user typed. No catalog entry, no hash to
     * verify (we have no manifest hash for an unknown file), no RAM advisory.
     *
     * Why this exists: the curated catalog is a convenience, not a gate. "Any model" means any
     * model — a newer quant, a fine-tune, a friend's export. llama.cpp is the authority on whether
     * a GGUF is valid; it will reject a bad one with a real message at load time.
     *
     * Safety that remains: https only, must end in .gguf (or the Content-Type must be octet-stream),
     * and it downloads into the app's own models directory so it is visible to the importer.
     */
    fun enqueueUrl(rawUrl: String): Result<Long> {
        val manager = dm ?: return Result.failure(IllegalStateException("DownloadManager unavailable"))
        val url = rawUrl.trim()
        if (url.isEmpty()) return Result.failure(IllegalArgumentException("Enter a URL"))

        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("That is not a valid URL"))
        if (uri.scheme?.lowercase() != "https") {
            return Result.failure(IllegalArgumentException("Only https:// URLs are allowed"))
        }
        val name = (uri.lastPathSegment ?: "model.gguf")
            .substringAfterLast('/')
            .substringBefore('?')
        if (!name.lowercase().endsWith(".gguf")) {
            return Result.failure(
                IllegalArgumentException("The URL must point at a .gguf file (got '$name')")
            )
        }
        val id = "url:$url"
        val request = DownloadManager.Request(uri)
            .setTitle(name)
            .setDescription("OmniLLM · custom model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "models/$name")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        return runCatching {
            val downloadId = manager.enqueue(request)
            rememberDownload(downloadId, id)
            setState(id, State.Downloading(0f, 0L, 0L))
            startPolling()
            downloadId
        }.onFailure { setState(id, State.Failed(it.message ?: "could not start download")) }
    }

    /** Called by [DownloadCompleteReceiver] when the system finishes a download. */
    fun onDownloadComplete(downloadId: Long) {
        val modelId = readIndex()[downloadId] ?: return
        forgetDownload(downloadId)
        setState(modelId, State.Verifying)
        verifyInBackground(modelId)
    }

    private fun verifyInBackground(modelId: String) {
        scope.launch {
            val entry = pendingEntry(modelId)
            if (entry == null) {
                setState(modelId, State.Failed("Model is no longer in the manifest."))
                return@launch
            }
            val ok = verifyFile(entry)
            if (ok) {
                setState(modelId, State.Ready)
                // Keep the verified GGUF in place; the KV cache is per-model and lives elsewhere.
            } else {
                setState(modelId, State.Failed("Checksum mismatch — the file is corrupt. Download again."))
            }
        }
    }

    /**
     * Streams the file through SHA-256 in 1 MB chunks. Never reads the whole file into memory:
     * a 2.3 GB model would otherwise be an instant OutOfMemoryError on this class's thread.
     */
    suspend fun verifyFile(entry: ModelEntry): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(entry)
        if (!file.isFile) {
            setState(entry.id, State.Failed("Downloaded file is missing."))
            return@withContext false
        }
        if (file.length() != entry.fileSizeBytes) {
            Log.w(TAG, "size mismatch for ${entry.id}: ${file.length()} vs ${entry.fileSizeBytes}")
            file.delete()
            setState(entry.id, State.Failed("Size mismatch — the file is truncated. Download again."))
            return@withContext false
        }
        setState(entry.id, State.Verifying)
        val digest = MessageDigest.getInstance("SHA-256")
        runCatching {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }.onFailure {
            setState(entry.id, State.Failed(it.message ?: "could not read the downloaded file"))
            return@withContext false
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val matches = actual.equals(entry.sha256, ignoreCase = true)
        if (!matches) {
            Log.e(TAG, "sha256 mismatch for ${entry.id}: got $actual")
            file.delete()
            setState(entry.id, State.Failed("Checksum mismatch — rejected the corrupt file. Try again."))
        }
        matches
    }

    fun delete(entry: ModelEntry) {
        runCatching { fileFor(entry).delete() }
        setState(entry.id, State.Idle)
    }

    /** Marks files that exist and size-match as Ready on app start (offline cold start). */
    fun reconcileWithDisk(entries: List<ModelEntry>) {
        scope.launch(Dispatchers.IO) {
            entries.forEach { entry ->
                val f = fileFor(entry)
                if (f.isFile && stateFor(entry.id) == State.Idle) {
                    _bytesOnDisk.value = _bytesOnDisk.value.toMutableMap().also { it[entry.id] = f.length() }
                    if (f.length() == entry.fileSizeBytes) {
                        // Full verification runs lazily; a size match is a fast, safe first pass.
                        setState(entry.id, State.Ready)
                    }
                }
            }
        }
    }

    // ---- Bookkeeping ------------------------------------------------------------------------

    private var manifestProvider: (() -> List<ModelEntry>)? = null
    fun attachManifest(provider: () -> List<ModelEntry>) {
        manifestProvider = provider
    }
    private fun pendingEntry(modelId: String): ModelEntry? =
        manifestProvider?.invoke()?.firstOrNull { it.id == modelId }

    private fun rememberDownload(downloadId: Long, modelId: String) {
        val json = JSONObject(readIndex().mapKeys { it.key.toString() }).put(downloadId.toString(), modelId)
        runCatching { indexFile.writeText(json.toString()) }
    }

    private fun forgetDownload(downloadId: Long) {
        val map = readIndex().toMutableMap()
        map.remove(downloadId)
        val json = JSONObject(map.mapKeys { it.key.toString() })
        runCatching { indexFile.writeText(json.toString()) }
    }

    private fun readIndex(): Map<Long, String> = runCatching {
        if (!indexFile.exists()) return emptyMap()
        val json = JSONObject(indexFile.readText())
        json.keys().asSequence().mapNotNull { key ->
            key.toLongOrNull()?.let { it to json.optString(key) }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun failureReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Out of storage space."
        DownloadManager.ERROR_FILE_ERROR -> "Could not write the file."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
            "Network error while downloading. Check your connection and retry."
        DownloadManager.ERROR_CANNOT_RESUME -> "Download could not be resumed. Retry from scratch."
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "The host redirected too many times."
        else -> "Download failed (reason $reason)."
    }

    companion object {
        private const val TAG = "ModelDownloads"
    }
}

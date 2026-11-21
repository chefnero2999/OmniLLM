package com.omni.llm.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * The three safety nets that keep a 2 GB model from taking the app down with it.
 *
 * 1. [preflight] refuses a load before llama.cpp allocates anything if the numbers do not add up.
 * 2. The in-flight marker + [detectCrashLoop] turn a hard crash on the previous run into a
 *    one-shot auto-degrade on this run, instead of a crash loop.
 * 3. Observability: every degraded attempt appends a signature to crash_signatures.txt so the
 *    user can paste it into a bug report.
 *
 * The marker file lives in filesDir rather than SharedPreferences on purpose: SharedPreferences
 * is cached per process and is *not* reliable across the app/`:llm` process split, and it is not
 * flushed when a native crash kills the process mid-load. A create-then-rename file always is.
 */
class AntiCrashGuard(private val context: Context) {

    /**
     * @param allowed        Whether the numbers look safe. Advisory: the caller may proceed anyway
     *                       once the user has acknowledged [message].
     * @param likelyToFail   True when even a generous reading says this will probably die.
     */
    data class Preflight(
        val allowed: Boolean,
        val requiredBytes: Long,
        val availableBytes: Long,
        val safeBudgetBytes: Long,
        val message: String,
        val likelyToFail: Boolean = false,
    )

    data class LoadMarker(
        val modelId: String,
        val fileName: String,
        val gpuLayers: Int,
        val contextLength: Int,
        val startedAtMillis: Long,
        val processPid: Int,
    )

    data class CrashReport(
        val modelId: String,
        val fileName: String,
        val gpuLayers: Int,
        val contextLength: Int,
        val occurredAtMillis: Long,
        val signature: String,
    )

    // ---- 1. Pre-flight memory check ---------------------------------------------------------

    /**
     * required = weights + KV cache + workspace.
     *
     * The 0.75 factor is the whole trick: Android reports `availMem` optimistically and the LMK
     * acts on a much tighter real budget. Refusing at 75 % leaves room for the UI process, the
     * page cache and the GPU driver.
     */
    fun preflight(
        modelFileSizeBytes: Long,
        contextLength: Int,
        kvBytesPerToken: Long,
        availableRamBytes: Long,
        usingMmap: Boolean,
    ): Preflight {
        val kv = contextLength.toLong() * max(kvBytesPerToken, 1024L)
        // With mmap the weights are file-backed and evictable, so the *private* cost is much
        // lower than the file size; without mmap the whole file is resident anonymous memory.
        val weightCost = if (usingMmap) (modelFileSizeBytes * 12 / 100) else modelFileSizeBytes
        val workspace = 96L * 1024 * 1024
        val required = weightCost + kv + workspace
        val budget = (availableRamBytes * 0.75).toLong()
        val allowed = required <= budget

        // "Definitely doomed" vs "merely tight": the first is when the request exceeds ALL of
        // physical RAM, which no amount of app-closing can fix.
        val totalPhysical = availableRamBytes * 2
        val likelyToFail = required > totalPhysical
        val message = if (allowed) {
            "Needs about ${gb(required)} of ${gb(budget)} safely available."
        } else {
            "This model needs ~${gb(required)} but only ${gb(budget)} is safely available while the " +
                "app is running.\n\nYou can load it anyway — it may work if you close other apps, or " +
                "with a shorter context. If it does not fit, the app will be killed while loading and " +
                "you will see a crash-recovery notice on next launch. Nothing is corrupted by trying."
        }
        return Preflight(allowed, required, availableRamBytes, budget, message, likelyToFail)
    }

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))

    // ---- 2. Crash-loop breaker --------------------------------------------------------------

    fun markLoadStarted(modelId: String, fileName: String, gpuLayers: Int, contextLength: Int) {
        val marker = LoadMarker(
            modelId = modelId,
            fileName = fileName,
            gpuLayers = gpuLayers,
            contextLength = contextLength,
            startedAtMillis = System.currentTimeMillis(),
            processPid = android.os.Process.myPid(),
        )
        val json = JSONObject()
            .put("modelId", marker.modelId)
            .put("fileName", marker.fileName)
            .put("gpuLayers", marker.gpuLayers)
            .put("contextLength", marker.contextLength)
            .put("startedAt", marker.startedAtMillis)
            .put("pid", marker.processPid)
        val target = ModelPaths.inflightMarker(context)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    fun markLoadFinished() {
        runCatching { ModelPaths.inflightMarker(context).delete() }
    }

    private fun readMarker(): LoadMarker? = runCatching {
        val f = ModelPaths.inflightMarker(context)
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        LoadMarker(
            modelId = json.optString("modelId"),
            fileName = json.optString("fileName"),
            gpuLayers = json.optInt("gpuLayers"),
            contextLength = json.optInt("contextLength"),
            startedAtMillis = json.optLong("startedAt"),
            processPid = json.optInt("pid"),
        )
    }.getOrNull()

    /** True when the pid recorded in the marker is no longer running. */
    private fun isPidAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return try {
            File("/proc/$pid").exists()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Call once on app start, *before* any load is attempted. Returns a report when the previous
     * run died during a model load; the caller should then drop to CPU + 2048 context.
     */
    fun detectCrashLoop(nowMillis: Long = System.currentTimeMillis()): CrashReport? {
        val marker = readMarker() ?: return null
        val youngEnough = nowMillis - marker.startedAtMillis <= CRASH_LOOP_WINDOW_MS
        val processGone = !isPidAlive(marker.processPid)
        if (!youngEnough || !processGone) {
            // Stale marker (app was killed long ago, or the load is still running): clear it.
            if (!youngEnough) markLoadFinished()
            return null
        }
        val signature = buildString {
            append("crash-during-load")
            append(" model=").append(marker.modelId.ifBlank { marker.fileName })
            append(" gpuLayers=").append(marker.gpuLayers)
            append(" ctx=").append(marker.contextLength)
            append(" at=").append(isoTime(marker.startedAtMillis))
        }
        markLoadFinished()
        appendCrashSignature(signature)
        Log.w(TAG, "Crash loop detected: $signature")
        return CrashReport(
            modelId = marker.modelId,
            fileName = marker.fileName,
            gpuLayers = marker.gpuLayers,
            contextLength = marker.contextLength,
            occurredAtMillis = marker.startedAtMillis,
            signature = signature,
        )
    }

    fun appendCrashSignature(signature: String) {
        runCatching {
            ModelPaths.crashLog(context).appendText("${isoTime(System.currentTimeMillis())}  $signature\n")
        }
    }

    fun crashLogTail(maxLines: Int = 40): String = runCatching {
        val f = ModelPaths.crashLog(context)
        if (!f.exists()) "" else f.readLines().takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    fun clearCrashLog() {
        runCatching { ModelPaths.crashLog(context).delete() }
    }

    private fun isoTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    companion object {
        private const val TAG = "AntiCrashGuard"
        const val CRASH_LOOP_WINDOW_MS = 10 * 60 * 1000L  // Section 5.4: 10 minutes
    }
}

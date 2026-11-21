package com.omni.llm.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Reads what this phone can actually do and derives the Section 4.1 defaults from it.
 *
 * The single most important number here is `availRamBytes`: `totalMem` is a marketing number,
 * the LMK only cares about what is free right now. Every hard cap in the app is derived from
 * available memory, never from total memory.
 */
class DeviceCapabilityProbe(private val context: Context) {

    data class DeviceProfile(
        val totalRamBytes: Long,
        val availRamBytes: Long,
        val isLowRamDevice: Boolean,
        val cores: Int,
        val performanceCores: Int,
        val sdkInt: Int,
        val model: String,
        val hasVulkan: Boolean?,
        val gpuReport: String?,
        val thermalStatus: Int,
    ) {
        val totalRamGb: Double get() = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        val availRamGb: Double get() = availRamBytes / (1024.0 * 1024.0 * 1024.0)
        val ramTierGb: Int
            get() = when {
                totalRamGb <= 4.5 -> 4
                totalRamGb <= 6.5 -> 6
                totalRamGb <= 8.5 -> 8
                else -> 12
            }
    }

    /** The defaults table from Section 4.1. These are caps, not suggestions. */
    data class TierDefaults(
        val contextLength: Int,
        val gpuLayers: Int,
        val threads: Int,
        val maxContext: Int,
        val maxGpuLayers: Int,
        val batchSize: Int,
        val useMmap: Boolean,
        val useMlock: Boolean,
    )

    fun probe(gpu: GpuStatus? = null): DeviceProfile {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return DeviceProfile(
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            isLowRamDevice = am?.isLowRamDevice ?: false,
            cores = cores,
            performanceCores = estimatePerformanceCores(cores),
            sdkInt = Build.VERSION.SDK_INT,
            model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            hasVulkan = gpu?.hasVulkan,
            gpuReport = gpu?.report,
            thermalStatus = thermalStatus(),
        )
    }

    data class GpuStatus(val hasVulkan: Boolean, val report: String)

    /**
     * Big.LITTLE layouts: using every core on a 4+4 phone makes the little cores the bottleneck
     * and burns battery for no throughput. Cap at the number of big cores, never below 4.
     */
    fun estimatePerformanceCores(cores: Int): Int = when {
        cores <= 4 -> cores
        cores <= 6 -> 4
        cores <= 8 -> min(6, cores - 2)
        else -> min(8, cores - 2)
    }

    fun defaults(profile: DeviceProfile): TierDefaults {
        val t = DeviceDefaults.forTier(profile.ramTierGb, profile.hasVulkan == true)
        return TierDefaults(
            contextLength = t.contextLength,
            gpuLayers = t.gpuLayers,
            threads = t.threads,
            maxContext = t.maxContext,
            maxGpuLayers = t.maxGpuLayers,
            batchSize = t.batchSize,
            useMmap = t.useMmap,
            useMlock = t.useMlock,
        )
    }

    /** Hard caps enforced at load time, whatever the sliders say. */
    fun caps(profile: DeviceProfile): Caps {
        val c = DeviceDefaults.capsFor(profile.ramTierGb, profile.hasVulkan == true, profile.cores)
        return Caps(c.maxContext, c.maxGpuLayers, c.maxThreads, c.maxBatch)
    }

    data class Caps(
        val maxContext: Int,
        val maxGpuLayers: Int,
        val maxThreads: Int,
        val maxBatch: Int,
    )

    fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val pm = context.getSystemService(PowerManager::class.java) ?: return 0
        return try {
            pm.currentThermalStatus
        } catch (_: Throwable) {
            0
        }
    }

    fun thermalLabel(status: Int): String = when {
        status >= PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown imminent"
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        status >= PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        status >= PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        status >= PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        else -> "Nominal"
    }

    /** Free space on the volume that holds downloaded models. */
    fun freeModelStorageBytes(): Long {
        val dir = ModelPaths.modelsDir(context)
        return try {
            dir.mkdirs()
            dir.usableSpace
        } catch (_: Throwable) {
            0L
        }
    }
}

object ModelPaths {
    /** External app-specific storage: survives app restarts, no runtime permission needed. */
    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "models")

    fun modelFile(context: Context, fileName: String): File = File(modelsDir(context), fileName)

    fun partialFile(context: Context, fileName: String): File = File(modelsDir(context), "$fileName.part")

    fun stateDir(context: Context): File = File(context.filesDir, "llm_state").also { it.mkdirs() }

    fun kvCacheFile(context: Context): File = File(stateDir(context), "kv_state.bin")

    fun inflightMarker(context: Context): File = File(context.filesDir, "load_inflight.json")

    fun crashLog(context: Context): File = File(context.filesDir, "crash_signatures.txt")
}

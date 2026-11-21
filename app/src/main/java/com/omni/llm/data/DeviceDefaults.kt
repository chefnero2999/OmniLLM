package com.omni.llm.data

/**
 * The device-tier arithmetic, split out of [DeviceCapabilityProbe] so it can be tested without an
 * Android runtime.
 *
 * This logic used to live inside a class that imports `android.*`, which meant it could only be
 * exercised on a real phone — and the defaults it produced (GPU offload on, batch 512) crashed
 * phones. Pure functions are testable functions.
 */
object DeviceDefaults {

    /**
     * Conservative, crash-averse defaults.
     *
     *  - **gpuLayers = 0.** Vulkan offload is the single most common cause of a hard crash (bad
     *    driver, exhausted shared memory). It stays off until someone asks for it.
     *  - **batchSize 128–256, never 512.** Halves peak commit charge during prefill.
     *  - **mlock off.** Pinning a whole model in RAM guarantees the low-memory killer fires.
     *  - **flash attention and Q8 KV stay off.** Both are correctness-risky across backends.
     */
    fun forTier(ramTierGb: Int, gpuCapable: Boolean): Tier = when (ramTierGb) {
        4 -> Tier(2048, 0, 4, 2048, if (gpuCapable) 6 else 0, 128, true, false)
        6 -> Tier(2048, 0, 4, 4096, if (gpuCapable) 10 else 0, 256, true, false)
        8 -> Tier(4096, 0, 4, 4096, if (gpuCapable) 16 else 0, 256, true, false)
        else -> Tier(4096, 0, 6, 8192, if (gpuCapable) 24 else 0, 256, true, false)
    }

    data class Tier(
        val contextLength: Int,
        val gpuLayers: Int,
        val threads: Int,
        val maxContext: Int,
        val maxGpuLayers: Int,
        val batchSize: Int,
        val useMmap: Boolean,
        val useMlock: Boolean,
    )

    /** Rough KV-cache cost in MB for a context, at Q4-class weights with 32 layers. */
    fun kvCacheMb(contextLength: Int): Int = (contextLength * 0.13).toInt()

    /** Hard caps enforced at load time, whatever the sliders say. */
    fun capsFor(ramTierGb: Int, gpuCapable: Boolean, cores: Int): Caps {
        val d = forTier(ramTierGb, gpuCapable)
        return Caps(
            maxContext = d.maxContext,
            maxGpuLayers = d.maxGpuLayers,
            maxThreads = minOf(8, maxOf(1, cores)),
            maxBatch = 1024,
        )
    }

    data class Caps(
        val maxContext: Int,
        val maxGpuLayers: Int,
        val maxThreads: Int,
        val maxBatch: Int,
    )
}

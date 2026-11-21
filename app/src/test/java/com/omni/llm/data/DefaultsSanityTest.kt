package com.omni.llm.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the claim "the defaults do not crash".
 *
 * Every value here was chosen after a real failure: GPU offload on by default crashed on Adreno,
 * batch 512 doubled peak memory during prefill, and cloud disabled on first launch meant nothing
 * worked until the user found a toggle. These assertions keep those defaults from creeping back.
 */
class DefaultsSanityTest {

    private val fresh = OmniSettings()

    @Test
    fun `gpu offload is opt-in`() {
        assertTrue("gpuLayers default must be 0", fresh.gpuLayers == 0)
    }

    @Test
    fun `batch size is conservative`() {
        assertTrue("batchSize ${fresh.batchSize} is too large for a 4 GB phone", fresh.batchSize <= 256)
    }

    @Test
    fun `memory pinning is off`() {
        assertTrue("mlock must stay off by default", !fresh.useMlock)
        assertTrue("mmap must stay on by default", fresh.useMmap)
    }

    @Test
    fun `risky accelerations are off`() {
        assertTrue("flash attention is backend-risky", !fresh.flashAttention)
        assertTrue("Q8 KV cache is backend-risky", !fresh.q8KvCache)
        assertTrue("speculative decoding must be opt-in", !fresh.speculativeEnabled)
    }

    @Test
    fun `a fresh install has a working route to a model`() {
        // Cloud on, and the chat default points at a model that exists. Otherwise first launch is
        // a dead screen and the user has to go hunting through Settings.
        assertTrue("cloud must start enabled", fresh.cloudFallbackEnabled)
        assertTrue(
            "default chat model must resolve",
            ModelCatalog.find(ModelCatalog.DEFAULT_CHAT_KEY) != null,
        )
        assertTrue(
            "default agent model must resolve",
            ModelCatalog.find(ModelCatalog.DEFAULT_AGENT_KEY) != null,
        )
        assertTrue(
            "default agent model must actually be agent-ready",
            ModelCatalog.find(ModelCatalog.DEFAULT_AGENT_KEY)?.agentReady == true,
        )
    }

    @Test
    fun `context fits the smallest device the app supports`() {
        assertTrue("default context ${fresh.contextLength} too large for a 4 GB phone", fresh.contextLength <= 4096)
        assertTrue("default context must be usable", fresh.contextLength >= 2048)
    }

    @Test
    fun `sampling defaults are sane`() {
        assertTrue("temperature out of range", fresh.temperature in 0f..2f)
        assertTrue("topP out of range", fresh.topP > 0f && fresh.topP <= 1f)
        assertTrue("maxTokens too small to be useful", fresh.maxTokens >= 256)
    }

    @Test
    fun `every device tier gets loadable defaults`() {
        // Walk all tiers the probe can report and check the arithmetic actually fits in RAM.
        for (ramGb in listOf(4, 6, 8, 12)) {
            for (vulkan in listOf(true, false)) {
                val d = DeviceDefaults.forTier(ramGb, vulkan)
                assertTrue("tier $ramGb: gpu layers must be opt-in", d.gpuLayers == 0)
                assertTrue("tier $ramGb: batch too large", d.batchSize <= 256)
                assertTrue("tier $ramGb: no threads", d.threads >= 1)
                assertTrue("tier $ramGb: context below minimum", d.contextLength >= 2048)
                assertTrue("tier $ramGb: context above cap", d.contextLength <= d.maxContext)
                assertTrue("tier $ramGb: mlock must stay off", !d.useMlock)
                // KV cache must leave room in RAM for the model itself.
                val kvMb = DeviceDefaults.kvCacheMb(d.contextLength)
                assertTrue("tier $ramGb: KV cache ${kvMb}MB eats the device", kvMb < ramGb * 1024 / 3)
            }
        }
    }
}

package com.omni.llm.data

data class OmniSettings(
    val contextLength: Int = 2048,
    val gpuLayers: Int = 0,
    val threads: Int = 4,
    val batchSize: Int = 256,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val flashAttention: Boolean = false,
    val q8KvCache: Boolean = false,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 512,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /**
     * On by default. This app's whole premise is keyless cloud models; starting with cloud disabled
     * meant a fresh install had no route at all until the user found the toggle, and every send
     * failed with "no model loaded". A working default is the safe default.
     */
    val cloudFallbackEnabled: Boolean = true,
    /** Pinned cloud model id; null = let the router choose. */
    val cloudModel: String? = null,
    val activeModelId: String? = null,
    val onboardingComplete: Boolean = false,
    val lastStableGpuLayers: Int = 0,
    val autoThreads: Boolean = true,
    // Speculative decoding, opt-in. draftModelId points at a downloaded model used as the draft.
    val speculativeEnabled: Boolean = false,
    val draftModelId: String? = null,
    val draftTokens: Int = 4,
    /** Set by the crash-loop breaker; the UI shows a banner until dismissed. */
    val pendingCrashNotice: String? = null,

    /**
     * Rotate to another free model when the current one is rate-limited, out of quota or broken.
     * On by default: on free tiers a single model WILL run out, and a dead end mid-answer is worse
     * than a switch.
     */
    val autoSwitchModels: Boolean = true,

    /**
     * Unrestricted mode. This app ships no model of its own and cannot strip a provider's safety
     * training, so this does exactly two honest things: (1) removes this app's own editorial
     * restraint from the default system prompt, and (2) puts models that are *known* to be
     * uncensored at the front of the picker. It does not, and cannot, disable a provider's
     * server-side filter.
     */
    val unrestrictedMode: Boolean = true,
    val themeMode: String = "system", // "dark" | "light" | "system"
    val accentColor: String = "volt", // "volt" | "cobalt" | "copper" | "emerald" | "rose"
    val fontFamily: String = "default", // "default" | "sans" | "mono" | "serif"
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are OmniLLM, a concise on-device assistant. Answer directly. " +
                "If you are unsure, say so instead of inventing facts."

        /**
         * Used only when unrestricted mode is on. This removes *this app's* hedging instruction.
         * Whether the model itself complies is entirely down to the provider's own policy — no
         * client-side flag can change another company's server-side filters, and any app claiming
         * otherwise is lying about what it does.
         */
        const val UNRESTRICTED_SYSTEM_PROMPT =
            "You are OmniLLM, a concise assistant running on the user's own device or a model the " +
                "user chose. Answer the question that was asked, directly and completely, in " +
                "whatever level of detail is appropriate to the request. Do not add unsolicited " +
                "warnings, moral commentary or refusals that were not asked for. If you genuinely " +
                "cannot do something, say what you can do instead."
    }
}

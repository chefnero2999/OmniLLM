package com.omni.llm.data

/**
 * The free-model catalogue: who is usable without an account, what each one is good at, and a
 * one-line description so a picker is not a wall of opaque ids.
 *
 * Every `Verdict.FREE` entry below was probed live in Nov 2026 with **no credential at all** (or
 * the literal string "unused" where the provider demands a header). Entries that need a key are
 * listed too, because hiding them would misrepresent what "free" means here — but they are marked
 * and never selected silently.
 *
 * The tiering is per provider and it is real:
 *  - **Pollinations** — 1 reply / 45 s / IP. Serves GPT-OSS-20B only, but it is the most reliable.
 *  - **LLM7 `turbo` tier** — 11 open-weight models, all free, rolling ~38 s-per-model rate limit.
 *    (Its `pro` tier advertises Claude/Gemini/GPT and answers **401** without a paid key.)
 *  - **OVHcloud AI Endpoints** — 6+ zero-price models anonymous, throttled and occasionally slow.
 */
object ModelCatalog {

    enum class Category(val label: String, val blurb: String) {
        EVERYDAY("Everyday", "General chat: questions, summaries, writing, translation"),
        CODING("Coding", "Writing and editing code"),
        REASONING("Reasoning", "Thinking models for hard problems, maths and planning"),
        AGENTIC("Agentic / tools", "Reliable at tool calls, long context, multi-step work"),
        VISION("Vision", "Reads images you attach or generate"),
        IMAGE("Image generation", "Text to picture"),
        LOCKED("Needs an account", "Advertised but paywalled — shown, never faked"),

        /**
         * Local weights with the refusal direction ablated.
         *
         * This category exists because it is the only honest place for "no filters": a cloud
         * provider applies its own moderation before the text ever reaches us, and no client-side
         * setting can change that. These run entirely on the phone, so there is nothing between the
         * weights and the user.
         */
        UNCENSORED("Uncensored", "Local, unfiltered models — nothing leaves the phone"),
    }

    /** How a model answered when actually asked, not what a marketing page claims. */
    enum class Verdict { FREE, LIMITED, NEEDS_KEY }

    data class FreeModel(
        /** `providerId|modelId` — the routing identity sent to the provider. */
        val key: String,
        val providerId: String,
        val modelId: String,
        val name: String,
        val blurb: String,
        val category: Category,
        val contextK: Int? = null,
        val reasoning: Boolean = false,
        val vision: Boolean = false,
        val tools: Boolean = false,
        val verdict: Verdict = Verdict.FREE,
        val image: Boolean = false,
        /** True for providers whose quota is generous enough to drive a multi-turn agent build. */
        val agentReady: Boolean = false,
        /**
         * Minimum output budget this model needs before it stops emitting tool calls.
         *
         * Measured, not guessed: gemma-4-26b-a4b at `max_tokens = 400` spends the whole budget on
         * its reasoning pass and returns an empty `content` with no tool call at all; at 2000 it
         * answers correctly in 9 s. Builds get 4096, so this is a floor for CHAT, where the user's
         * "max answer length" setting can otherwise silently break tool use.
         */
        val minOutputTokens: Int = 0,
        /**
         * Identity of this entry's chat thread. Normally the same as [key], but a model listed in
         * two categories (Kimi under both Coding and Agentic) gets `key#agentic` here — otherwise
         * two visually separate entries would silently share one conversation, which is exactly
         * the "chats overlapping" the user forbade.
         */
        val threadKey: String = key,
    ) {
        val usable: Boolean get() = verdict != Verdict.NEEDS_KEY
        val displayContext: String? get() = contextK?.let { if (it >= 1000) "${it / 1000}M" else "${it}K" }
    }

    const val POLLINATIONS = "pollinations"
    const val LLM7 = "llm7"
    const val OVH = "ovh"
    const val IMAGES = "pollinations-image"

    private fun llm7(id: String, name: String, blurb: String, cat: Category, ctx: Int?,
                     reasoning: Boolean = false, tools: Boolean = true, vision: Boolean = false,
                     agentReady: Boolean = false, verdict: Verdict = Verdict.FREE) =
        FreeModel(
            key = "$LLM7|$id", providerId = LLM7, modelId = id, name = name, blurb = blurb,
            category = cat, contextK = ctx, reasoning = reasoning, tools = tools, vision = vision,
            verdict = verdict, agentReady = agentReady,
        )

    private fun ovh(id: String, name: String, blurb: String, cat: Category, ctx: Int?,
                    reasoning: Boolean = false, tools: Boolean = true, vision: Boolean = false,
                    agentReady: Boolean = false, minOutputTokens: Int = 0) =
        FreeModel(
            key = "$OVH|$id", providerId = OVH, modelId = id, name = name, blurb = blurb,
            category = cat, contextK = ctx, reasoning = reasoning, tools = tools, vision = vision,
            agentReady = agentReady, minOutputTokens = minOutputTokens,
        )

    /**
     * Ordered so the first entry is the best default for that category. The picker groups by
     * [Category], so order inside a category is a recommendation, not an accident.
     */
    val models: List<FreeModel> = listOf(

        // ---------------------------------------------------------------- everyday
        llm7("gemma4:31b", "Gemma 4 31B",
            "Google's mid-size all-rounder. Dependable, quick, good at plain questions.",
            Category.EVERYDAY, 262, agentReady = true),
        llm7("gemma-3-27b", "Gemma 3 27B",
            "Light and fast. Best for short answers, summaries and rewrites.",
            Category.EVERYDAY, 262, tools = false),
        llm7("glm-5.3-flash", "GLM 5.3 Flash",
            "Zhipu's fast model. Balanced speed and quality, 1M context.",
            Category.EVERYDAY, 1048),
        llm7("mimo-v2.6-flash", "MiMo v2.6 Flash",
            "Small and surprisingly capable for its size. Very long context.",
            Category.EVERYDAY, 1000, agentReady = true),
        llm7("minimax-m2.7", "MiniMax M2.7",
            "Chatty and creative. Good for brainstorming and drafts.",
            Category.EVERYDAY, 180),
        ovh("nemotron3-nano-30b", "Nemotron 3 Nano 30B",
            "NVIDIA's 30B. Fast, solid instruction following, 262K context.",
            Category.EVERYDAY, 262),
        ovh("nemotron3-omni-30b", "Nemotron 3 Omni 30B",
            "Multimodal variant of Nano. Handles images and text in one model.",
            Category.EVERYDAY, 131, vision = true, agentReady = true),
        ovh("laguna-xs-2.1", "Laguna XS 2.1",
            "Tiny reasoning model. Quickest of the OVH set when it is not busy.",
            Category.EVERYDAY, 131, reasoning = true, minOutputTokens = 600),

        // ---------------------------------------------------------------- coding
        llm7("kimi-k2.7-code", "Kimi K2.7 Code",
            "Moonshot's coding specialist. Best free model here at long, multi-file edits.",
            Category.CODING, 256, reasoning = true, agentReady = true),
        llm7("codestral-latest", "Codestral",
            "Mistral's code model. Precise, terse diffs and completions.",
            Category.CODING, 32, tools = false),
        FreeModel(
            key = "$LLM7|gemma4:31b", providerId = LLM7, modelId = "gemma4:31b",
            name = "Gemma 4 31B (code use)",
            blurb = "Not code-trained, but quietly competent at small scripts and fixes.",
            category = Category.CODING, contextK = 262,
            threadKey = "$LLM7|gemma4:31b#code",
        ),


        // ---------------------------------------------------------------- reasoning
        llm7("gpt-oss:120b", "GPT-OSS 120B",
            "OpenAI's open-weight flagship. The strongest reasoning you can get with no account.",
            Category.REASONING, 131, reasoning = true, agentReady = true),
        llm7("gpt-oss:20b", "GPT-OSS 20B",
            "Same family, 20B. Shows its thinking; good enough for most hard questions.",
            Category.REASONING, 128, reasoning = true, agentReady = true),
        llm7("DeepSeek-V4-Flash-0731", "DeepSeek V4 Flash",
            "Fast reasoner with a 400K window. Strong at maths and step-by-step logic.",
            Category.REASONING, 400, reasoning = true, agentReady = true),
        ovh("DeepSeek-V4.1-Flash-1017", "DeepSeek V4.1 Flash",
            "Newer DeepSeek with a 1M context. Use it when the problem is long.",
            Category.REASONING, 1048, reasoning = true, agentReady = true),
        ovh("qwen3.6-35b-a3b", "Qwen 3.6 35B",
            "Mixture-of-experts, ~3B active. Thinks hard, answers fast, great all-rounder.",
            Category.REASONING, 262, reasoning = true, agentReady = true, minOutputTokens = 600),
        ovh("gemma-4-26b-a4b", "Gemma 4 26B",
            "Sparse Gemma. Cheaper per token than it looks, good at careful answers.",
            Category.REASONING, 262, agentReady = true, minOutputTokens = 1500),

        // ---------------------------------------------------------------- agentic
        FreeModel(
            key = "$POLLINATIONS|openai", providerId = POLLINATIONS, modelId = "openai",
            name = "GPT-OSS 20B (Pollinations)",
            blurb = "The most reliable free endpoint. Use it to drive builds when others run dry.",
            category = Category.AGENTIC, contextK = 128, reasoning = true, tools = true,
            agentReady = true,
        ),
        FreeModel(
            key = "$LLM7|gpt-oss:20b", providerId = LLM7, modelId = "gpt-oss:20b",
            name = "GPT-OSS 20B (agentic)",
            blurb = "Tool calling verified. Best balance of speed and protocol compliance.",
            category = Category.AGENTIC, contextK = 128, reasoning = true, agentReady = true,
            threadKey = "$LLM7|gpt-oss:20b#agentic",
        ),
        FreeModel(
            key = "$LLM7|kimi-k2.7-code", providerId = LLM7, modelId = "kimi-k2.7-code",
            name = "Kimi K2.7 Code (agentic)",
            blurb = "Long-context coder that keeps its place across many tool calls.",
            category = Category.AGENTIC, contextK = 256, reasoning = true, agentReady = true,
            threadKey = "$LLM7|kimi-k2.7-code#agentic",
        ),
        FreeModel(
            key = "$LLM7|DeepSeek-V4-Flash-0731", providerId = LLM7, modelId = "DeepSeek-V4-Flash-0731",
            name = "DeepSeek V4 Flash (agentic)",
            blurb = "Big context and tool support. Good for build plans with many files.",
            category = Category.AGENTIC, contextK = 400, reasoning = true, agentReady = true,
            threadKey = "$LLM7|DeepSeek-V4-Flash-0731#agentic",
        ),

        // Measured Aug/Nov 2026: these five answered the real build prompt with a valid
        // write_file on the first turn, in 3.6-19 s, on a provider with no per-model quota.
        FreeModel(
            key = "$OVH|DeepSeek-V4.1-Flash-1017", providerId = OVH, modelId = "DeepSeek-V4.1-Flash-1017",
            name = "DeepSeek V4.1 Flash (agentic)",
            blurb = "Fastest verified builder here (4.8 s to a valid first file) and a 1M context.",
            category = Category.AGENTIC, contextK = 1048, reasoning = true, agentReady = true,
            threadKey = "$OVH|DeepSeek-V4.1-Flash-1017#agentic",
        ),
        FreeModel(
            key = "$OVH|gemma-4-26b-a4b", providerId = OVH, modelId = "gemma-4-26b-a4b",
            name = "Gemma 4 26B (agentic)",
            blurb = "Sparse 26B. Verified tool caller, 262K context, no per-model daily cap.",
            category = Category.AGENTIC, contextK = 262, agentReady = true,
            threadKey = "$OVH|gemma-4-26b-a4b#agentic",
        ),
        FreeModel(
            key = "$OVH|qwen3.6-35b-a3b", providerId = OVH, modelId = "qwen3.6-35b-a3b",
            name = "Qwen 3.6 35B (agentic)",
            blurb = "Thinks before each step. Best for builds with fiddly logic.",
            category = Category.AGENTIC, contextK = 262, reasoning = true, agentReady = true,
            threadKey = "$OVH|qwen3.6-35b-a3b#agentic",
        ),

        // ---------------------------------------------------------------- vision
        ovh("qwen3-vl-30b-a3b", "Qwen 3 VL 30B",
            "Reads images: screenshots, photos, diagrams. Describe or ask about an attachment.",
            Category.VISION, 32, vision = true, agentReady = true),
        llm7("deepseek-v4-flash-0731-fast", "DeepSeek V4 Flash Fast",
            "Faster DeepSeek endpoint. Needs a key — listed so the gap is visible.",
            Category.VISION, 1000, reasoning = true, verdict = Verdict.NEEDS_KEY),

        // ---------------------------------------------------------------- image
        FreeModel(
            key = "$IMAGES|sana", providerId = IMAGES, modelId = "sana",
            name = "Sana",
            blurb = "Text to image, no account. Fast and free; adds a small watermark.",
            category = Category.IMAGE, image = true,
        ),

        // ---------------------------------------------------------------- uncensored (local only)
        FreeModel(
            key = "local|llama-3.2-1b-abliterated",
            providerId = "local",
            modelId = "llama-3.2-1b-abliterated",
            name = "Llama 3.2 1B Abliterated",
            blurb = "Refusal direction removed. Tiny and fast. Runs entirely on the phone — nothing leaves the device.",
            category = Category.UNCENSORED,
            contextK = 128,
            verdict = Verdict.FREE,
        ),
        FreeModel(
            key = "local|qwen-2.5-1.5b-abliterated",
            providerId = "local",
            modelId = "qwen-2.5-1.5b-abliterated",
            name = "Qwen 2.5 1.5B Abliterated",
            blurb = "Refusal direction removed. Apache-2.0. Stronger than Llama 1B for most tasks.",
            category = Category.UNCENSORED,
            contextK = 128,
            verdict = Verdict.FREE,
        ),
        FreeModel(
            key = "local|llama-3.2-3b-abliterated",
            providerId = "local",
            modelId = "llama-3.2-3b-abliterated",
            name = "Llama 3.2 3B Abliterated",
            blurb = "Refusal direction removed. 3B class — usable on 4 GB devices.",
            category = Category.UNCENSORED,
            contextK = 128,
            verdict = Verdict.FREE,
        ),
        FreeModel(
            key = "local|dolphin-3.0-llama-3.2-3b",
            providerId = "local",
            modelId = "dolphin-3.0-llama-3.2-3b",
            name = "Dolphin 3.0 Llama 3.2 3B",
            blurb = "Fine-tuned for instruction following without refusals. Local-only.",
            category = Category.UNCENSORED,
            contextK = 128,
            verdict = Verdict.FREE,
        ),

        // ---------------------------------------------------------------- locked
        locked("anthropic/claude-opus-4-8", "Claude Opus 4.8"),
        locked("anthropic/claude-sonnet-5", "Claude Sonnet 5"),
        locked("gemini-3.1-pro", "Gemini 3.1 Pro"),
        locked("gemini-4-argon", "Gemini 4 Argon"),
        locked("gpt-6.2-sol", "GPT-6.2 Sol"),
        locked("grok-4.7", "Grok 4.7"),
    )

    private fun locked(modelId: String, name: String) = FreeModel(
        key = "$LLM7|$modelId", providerId = LLM7, modelId = modelId, name = name,
        blurb = "Advertised by the provider but answers 401 without a paid key. Shown so the " +
            "gap is honest; this app has no signup and cannot use it.",
        category = Category.LOCKED, verdict = Verdict.NEEDS_KEY,
    )

    /** Grouped for the picker. Empty categories are dropped. */
    fun byCategory(): List<Pair<Category, List<FreeModel>>> =
        Category.entries
            .map { c -> c to models.filter { it.category == c } }
            .filter { it.second.isNotEmpty() }

    fun find(key: String?): FreeModel? =
        models.firstOrNull { it.threadKey == key } ?: models.firstOrNull { it.key == key }

    fun byKey(key: String?): FreeModel =
        find(key) ?: models.first { it.key == DEFAULT_CHAT_KEY }

    /** The default brain for chat: capable, free, and verified working. */
    const val DEFAULT_CHAT_KEY = "llm7|gemma4:31b"

    /** The default brain for builds: best measured tool-call compliance. */
    const val DEFAULT_AGENT_KEY = "ovh|DeepSeek-V4.1-Flash-1017"

    val agentReady: List<FreeModel> get() = models.filter { it.agentReady }

    val imageModels: List<FreeModel> get() = models.filter { it.image }
}

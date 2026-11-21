// OmniLLM — native inference core.
//
// This file is deliberately free of any JNI / Android dependency so that it can be
// compiled and fuzzed on a host machine (see tools/host_test.cpp). The JNI layer in
// jni_bridge.cpp is a thin translation shim over this class.
//
// Design notes (why it looks like this):
//  * All llama.cpp handles live behind one object; ownership is explicit and every
//    failure path frees what it allocated. A half-loaded model is worse than no model.
//  * Nothing here ever calls exit()/abort(). llama.cpp returns error codes for the
//    recoverable cases (decode != 0, model load == nullptr) and the abort callback
//    gives us a cooperative cancel path. The Kotlin side turns these into exceptions.
//  * Token pieces are UTF-8-split-safe: a multi-byte codepoint is only emitted once all
//    of its bytes have arrived, otherwise the UI would render garbage per token.
//  * Prefix caching: we keep the tokenised prefix of the last evaluated prompt and only
//    evaluate the suffix that changed. This is the difference between a 2s and a 200ms
//    first token on turn N of a conversation.

#ifndef OMNI_ENGINE_H
#define OMNI_ENGINE_H

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace omni {

enum class Status {
    OK = 0,
    NOT_LOADED = 1,
    ALREADY_LOADED = 2,
    LOAD_FAILED = 3,
    DECODE_FAILED = 4,
    CONTEXT_FULL = 5,
    CANCELLED = 6,
    BAD_INPUT = 7,
    OOM = 8,
};

const char * statusName(Status s);

struct EngineParams {
    int32_t n_ctx = 2048;         // KV cache size in tokens
    int32_t n_batch = 512;        // logical batch (prompt chunking)
    int32_t n_ubatch = 512;       // physical batch; llama.cpp wants n_ubatch <= n_batch
    int32_t n_threads = 4;        // generation threads
    int32_t n_threads_batch = 0;  // 0 -> same as n_threads
    int32_t gpu_layers = 0;       // layers offloaded to Vulkan/GPU backend
    bool use_mmap = true;         // zero-copy weights; the single most important OOM guard
    bool use_mlock = false;       // pin weights in RAM (battery cost, latency win)
    bool flash_attn = false;      // forced off by the Kotlin layer when gpu_layers > 0
    // Speculative decoding: a small draft model proposes tokens, the target model verifies them in
    // one batched pass. Only worth it when the draft is much cheaper than the target AND the device
    // can hold both. Empty path = disabled.
    std::string draft_model_path;
    int32_t draft_gpu_layers = 0;
    int32_t draft_tokens = 4;     // candidates proposed per verification pass (2-8 is sane)
    bool q8_kv = false;           // quantise KV cache (needs flash attention)
    float rope_freq_base = 0.f;   // 0 -> from model
    float rope_freq_scale = 0.f;  // 0 -> from model
    bool verbose = false;
};

struct SamplingParams {
    int32_t max_tokens = 512;
    float temp = 0.7f;
    float top_p = 0.9f;
    int32_t top_k = 40;
    float min_p = 0.05f;
    float repeat_penalty = 1.1f;
    int32_t repeat_last_n = 64;
    uint32_t seed = 0xFFFFFFFFu;  // LLAMA_DEFAULT_SEED
};

struct ChatMsg {
    std::string role;
    std::string content;
};

struct Stats {
    int32_t prompt_tokens = 0;
    // Speculative decoding accounting: how many draft tokens were proposed vs accepted.
    int32_t draft_proposed = 0;
    int32_t draft_accepted = 0;
    int32_t prompt_tokens_evaluated = 0;  // after prefix-cache reuse this can be much lower
    int32_t generated_tokens = 0;
    int64_t prefill_ms = 0;
    int64_t decode_ms = 0;
    double tokens_per_sec = 0.0;
    bool context_full = false;
};

struct ModelInfo {
    std::string description;
    std::string chat_template;  // empty when the GGUF has none
    int32_t n_ctx_train = 0;
    int32_t n_params = 0;
    uint64_t model_size_bytes = 0;
    int32_t n_vocab = 0;
    int32_t n_embd = 0;
    int32_t n_head = 0;
    int32_t n_layer = 0;
};

// Called for every decoded token. Return false to cancel generation immediately.
using TokenCallback = std::function<bool(const std::string & piece, int32_t n_generated)>;
// Called with 0..1 during model load. Return false to abort the load.
using ProgressCallback = std::function<bool(float progress)>;

class Engine {
public:
    Engine() = default;
    ~Engine();

    Engine(const Engine &) = delete;
    Engine & operator=(const Engine &) = delete;

    // Loads weights + creates the context. Not thread-safe against generate().
    Status loadModel(const std::string & path, const EngineParams & params,
                     const ProgressCallback & progress, std::string & err);

    // Frees the context and the model. Safe to call when not loaded.
    void unload();

    bool isLoaded() const { return model_ != nullptr && ctx_ != nullptr; }
    // True when a draft model is loaded and speculative decoding will actually be attempted.
    bool draftLoaded() const { return draft_model_ != nullptr && draft_ctx_ != nullptr; }

    // Formats `messages` with the model's own chat template when one is embedded in the
    // GGUF, else falls back to a plain ChatML-ish transcript. Empty string on failure.
    std::string applyChatTemplate(const std::vector<ChatMsg> & messages, bool add_assistant) const;

    // Runs prefill (with prefix-cache reuse) then streams tokens. Blocking; call from a
    // worker thread. on_token may be empty. `cancel` is checked between tokens AND wired
    // into llama.cpp via the abort callback, so a Stop tap interrupts the current decode
    // instead of waiting for it to finish.
    Status generate(const std::vector<ChatMsg> & messages, const SamplingParams & sp,
                    const TokenCallback & on_token, Stats & stats, std::string & err);

    // Atomically request cancellation of an in-flight generate().
    void requestCancel() { cancel_.store(true, std::memory_order_relaxed); }
    void clearCancel() { cancel_.store(false, std::memory_order_relaxed); }

    // Persist / restore the KV state of sequence 0 *together with the token list it represents*.
    // Saving the KV blob alone is useless: the prefix cache compares tokens, so without the token
    // list every restored cache looks like a mismatch and gets thrown away.
    // Returns false on any mismatch (different model, different n_ctx, truncated file).
    bool saveKvState(const std::string & path) const;
    bool loadKvState(const std::string & path);

    // Stable identity of the loaded model, used to reject a KV cache from another model.
    uint64_t modelFingerprint() const;

    // Tokenises the exact prompt generate() would build, without evaluating it. Lets the UI show
    // a real "x / ctx" number instead of guessing tokens from character counts.
    int32_t countPromptTokens(const std::vector<ChatMsg> & messages) const;

    ModelInfo info() const;

    // Human-readable list of ggml backends compiled in + devices detected (Vulkan/CPU).
    static std::string backendInfo();
    static bool hasGpuBackend();

    // Change the thread count without a reload (thermal throttling path).
    void setThreads(int32_t n_threads);

    // Reset the conversation: drop KV entries and the cached prefix. The system prompt is
    // re-evaluated on the next generate().
    void resetConversation();

    int32_t nCtx() const { return n_ctx_; }

private:
    Status prefill(const std::vector<llama_token> & tokens, Stats & stats, std::string & err);

    // Speculative path: drafts with `draft_` then verifies with the target model.
    // Falls back to plain generate() when the draft model is absent or unusable.
    Status generateSpeculative(const std::vector<llama_token> & prompt_tokens,
                               const SamplingParams & sp, const TokenCallback & on_token,
                               Stats & stats, std::string & err);
    Status loadDraftModel(const std::string & path, const EngineParams & params, std::string & err);
    // Drops the KV state + cached prefix after a failed/aborted decode.
    void poisonPrefixCache();
    void rebuildSampler(const SamplingParams & sp);

    // Speculative decoding state (only populated when a draft model is configured).
    llama_model * draft_model_ = nullptr;
    llama_context * draft_ctx_ = nullptr;
    const llama_vocab * draft_vocab_ = nullptr;
    std::string draft_path_;

    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    const llama_vocab * vocab_ = nullptr;
    llama_batch_ext * batch_ = nullptr;
    llama_sampler * smpl_ = nullptr;
    llama_memory_t memory_ = nullptr;

    EngineParams params_{};
    int32_t n_ctx_ = 0;
    std::vector<llama_token> cached_tokens_;  // tokens currently materialised in the KV cache
    std::atomic<bool> cancel_{false};
    mutable std::mutex mu_;
};

// Splits a raw byte stream into valid UTF-8 chunks so the UI never sees half a codepoint.
class Utf8Splitter {
public:
    // Appends bytes; returns the longest prefix that ends on a codepoint boundary.
    std::string push(const char * data, size_t len);
    std::string flush();

private:
    std::string pending_;
};

// Rough KV-cache footprint estimate, used by the Kotlin pre-flight check as a cross-check.
// Cheap GGUF header read: vocabulary size, layer count, embedding width and file size, without
// loading any weights. Speculative decoding needs to know whether a draft model is even compatible
// with the target before either is mmapped into memory.
struct ModelPeek {
    bool ok = false;
    int32_t n_vocab = 0;
    int32_t n_layer = 0;
    int32_t n_embd = 0;
    uint64_t file_bytes = 0;
    std::string error;
};

ModelPeek peekModelFile(const std::string & path);

// Human-readable reason a target/draft pair would be rejected, or empty string if the pair is fine.
// Mirrors speculativePairIsSane() but works from header peeks so it can run at picker time.
std::string speculativePairVerdict(const ModelPeek & target, const ModelPeek & draft,
                                   int64_t available_bytes, int32_t n_ctx);

int64_t estimateKvBytes(int32_t n_ctx, const ModelInfo & info, bool q8_kv);

// True when a draft/target pair is worth attempting: same vocabulary, draft at least 2x smaller,
// and enough headroom for both. Guards the feature against turning into a crash.
bool speculativePairIsSane(const ModelInfo & target, const ModelInfo & draft, int64_t available_bytes,
                          int32_t n_ctx);

// Route llama.cpp's own logging into `sink` (level follows ggml_log_level).
void setLogSink(std::function<void(int level, const char * text)> sink);

}  // namespace omni

#endif  // OMNI_ENGINE_H

// OmniLLM — native inference core (implementation). See omni_engine.h for design notes.

#include "omni_engine.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>

#include "ggml-backend.h"
#include "gguf.h"

namespace omni {

namespace {

// llama.cpp logs a lot. On device we forward to logcat through a single hook so the
// Kotlin side can show the last native line in the crash report.
std::function<void(int level, const char * text)> g_log_sink;
std::mutex g_log_mu;

void log_bridge(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    std::function<void(int, const char *)> sink;
    {
        std::lock_guard<std::mutex> lk(g_log_mu);
        sink = g_log_sink;
    }
    if (sink) sink(static_cast<int>(level), text);
}

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

}  // namespace

void setLogSink(std::function<void(int, const char *)> sink) {
    {
        std::lock_guard<std::mutex> lk(g_log_mu);
        g_log_sink = std::move(sink);
    }
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(log_bridge, nullptr);
    });
}

const char * statusName(Status s) {
    switch (s) {
        case Status::OK: return "OK";
        case Status::NOT_LOADED: return "NOT_LOADED";
        case Status::ALREADY_LOADED: return "ALREADY_LOADED";
        case Status::LOAD_FAILED: return "LOAD_FAILED";
        case Status::DECODE_FAILED: return "DECODE_FAILED";
        case Status::CONTEXT_FULL: return "CONTEXT_FULL";
        case Status::CANCELLED: return "CANCELLED";
        case Status::BAD_INPUT: return "BAD_INPUT";
        case Status::OOM: return "OOM";
    }
    return "UNKNOWN";
}

// ---------------------------------------------------------------------------------------
// Utf8Splitter
// ---------------------------------------------------------------------------------------

std::string Utf8Splitter::push(const char * data, size_t len) {
    pending_.append(data, len);
    size_t cut = pending_.size();
    // Walk back over trailing continuation bytes (10xxxxxx) and decide whether the lead
    // byte's sequence is complete. Anything incomplete stays buffered for the next call.
    size_t i = pending_.size();
    size_t back = 0;
    while (i > 0) {
        unsigned char c = static_cast<unsigned char>(pending_[i - 1]);
        if ((c & 0xC0) == 0x80) {  // continuation
            ++back;
            --i;
            continue;
        }
        int need = 0;
        if ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else need = 1;  // invalid lead, emit as-is
        if (static_cast<size_t>(need) > back + 1) {
            cut = pending_.size() - (back + 1);  // hold the partial codepoint back
        }
        break;
    }
    if (cut == pending_.size()) {
        std::string out;
        out.swap(pending_);
        return out;
    }
    std::string out = pending_.substr(0, cut);
    pending_.erase(0, cut);
    return out;
}

std::string Utf8Splitter::flush() {
    std::string out;
    out.swap(pending_);
    return out;
}

// ---------------------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------------------

Engine::~Engine() { unload(); }

static void batch_set_tokens(llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos pos0) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        if (idx < 0) return;
        const llama_pos pos = pos0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    if (n > 0) llama_batch_ext_set_output_logits(batch, n - 1, true);
}

Status Engine::loadModel(const std::string & path, const EngineParams & params,
                         const ProgressCallback & progress, std::string & err) {
    std::lock_guard<std::mutex> lk(mu_);

    if (isLoaded()) {
        err = "a model is already loaded; unload() first";
        return Status::ALREADY_LOADED;
    }

    params_ = params;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = params.gpu_layers;
    // mmap/mlcok live in load_mode in current llama.cpp. mmap is the OOM-killer defence:
    // weights are faulted in from disk and evictable, instead of a private anonymous copy.
    if (params.use_mlock) mp.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
    else if (params.use_mmap) mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    else mp.load_mode = LLAMA_LOAD_MODE_NONE;

    struct ProgCtx { const ProgressCallback * cb; };
    ProgCtx pctx{&progress};
    if (progress) {
        // The progress callback doubles as a load-abort hook: returning false stops the load.
        mp.progress_callback = [](float p, void * ud) -> bool {
            auto * c = static_cast<ProgCtx *>(ud);
            if (!c || !c->cb || !*c->cb) return true;
            const float clamped = p < 0.f ? 0.f : (p > 1.f ? 1.f : p);
            return (*c->cb)(clamped);
        };
        mp.progress_callback_user_data = &pctx;
    }
    model_ = llama_model_load_from_file(path.c_str(), mp);

    if (!model_) {
        err = "llama_model_load_from_file failed (corrupt GGUF, unsupported quant, or out of memory)";
        return Status::LOAD_FAILED;
    }

    vocab_ = llama_model_get_vocab(model_);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(params.n_ctx > 0 ? params.n_ctx : 2048);
    cp.n_batch = static_cast<uint32_t>(std::max(32, params.n_batch));
    cp.n_ubatch = static_cast<uint32_t>(std::max(32, std::min(params.n_batch, params.n_ubatch > 0 ? params.n_ubatch : params.n_batch)));
    cp.n_threads = params.n_threads > 0 ? params.n_threads : 4;
    cp.n_threads_batch = params.n_threads_batch > 0 ? params.n_threads_batch : cp.n_threads;
    // Flash attention + quantised KV: halves KV footprint, but the Android Vulkan path is
    // not reliable with FA yet -> the Kotlin layer forces this off when gpu_layers > 0.
    cp.flash_attn_type = params.flash_attn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    if (params.q8_kv && params.flash_attn) {
        cp.type_k = GGML_TYPE_Q8_0;
        cp.type_v = GGML_TYPE_Q8_0;
    }
    cp.rope_freq_base = params.rope_freq_base;
    cp.rope_freq_scale = params.rope_freq_scale;
    cp.no_perf = true;

    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) {
        err = "llama_init_from_model failed (KV cache allocation likely exceeded available RAM)";
        llama_model_free(model_);
        model_ = nullptr;
        vocab_ = nullptr;
        return Status::OOM;
    }

    n_ctx_ = static_cast<int32_t>(llama_n_ctx(ctx_));
    memory_ = llama_get_memory(ctx_);
    batch_ = llama_batch_ext_init(ctx_);
    // Cooperative abort: llama.cpp polls this during decode, so Stop is immediate.
    llama_set_abort_callback(ctx_, [](void * data) -> bool {
        return static_cast<std::atomic<bool> *>(data)->load(std::memory_order_relaxed);
    }, &cancel_);

    cached_tokens_.clear();
    cancel_.store(false, std::memory_order_relaxed);
    rebuildSampler(SamplingParams{});

    // Speculative decoding is opt-in and must never be the reason a load fails: if the draft model
    // cannot be loaded we log it, disable the path, and carry on with the target model alone.
    if (!params.draft_model_path.empty()) {
        std::string draft_err;
        const Status ds = loadDraftModel(params.draft_model_path, params, draft_err);
        if (ds != Status::OK) {
            err = (err.empty() ? "" : err + "; ") + "draft model disabled: " + draft_err;
        }
    }

    return Status::OK;
}

void Engine::unload() {
    std::lock_guard<std::mutex> lk(mu_);
    if (smpl_) { llama_sampler_free(smpl_); smpl_ = nullptr; }
    if (batch_) { llama_batch_ext_free(batch_); batch_ = nullptr; }
    if (draft_ctx_) { llama_free(draft_ctx_); draft_ctx_ = nullptr; }
    if (draft_model_) { llama_model_free(draft_model_); draft_model_ = nullptr; draft_vocab_ = nullptr; }
    if (ctx_) { llama_free(ctx_); ctx_ = nullptr; }
    if (model_) { llama_model_free(model_); model_ = nullptr; }
    vocab_ = nullptr;
    memory_ = nullptr;
    cached_tokens_.clear();
    n_ctx_ = 0;
}

void Engine::setThreads(int32_t n_threads) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!ctx_) return;
    const int32_t n = std::max(1, std::min(n_threads, 16));
    params_.n_threads = n;
    params_.n_threads_batch = n;
    llama_set_n_threads(ctx_, n, n);
}

void Engine::resetConversation() {
    std::lock_guard<std::mutex> lk(mu_);
    if (memory_) llama_memory_clear(memory_, true);
    cached_tokens_.clear();
}

void Engine::rebuildSampler(const SamplingParams & sp) {
    if (smpl_) { llama_sampler_free(smpl_); smpl_ = nullptr; }
    auto chain = llama_sampler_chain_default_params();
    smpl_ = llama_sampler_chain_init(chain);
    if (sp.repeat_penalty != 1.0f && sp.repeat_last_n > 0) {
        llama_sampler_chain_add(smpl_, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab_), sp.repeat_last_n, sp.repeat_penalty, 0.0f, 0.0f));
    }
    if (sp.temp <= 0.01f) {
        llama_sampler_chain_add(smpl_, llama_sampler_init_greedy());
    } else {
        if (sp.top_k > 0) llama_sampler_chain_add(smpl_, llama_sampler_init_top_k(sp.top_k));
        if (sp.top_p < 1.0f) llama_sampler_chain_add(smpl_, llama_sampler_init_top_p(sp.top_p, 1));
        if (sp.min_p > 0.0f) llama_sampler_chain_add(smpl_, llama_sampler_init_min_p(sp.min_p, 1));
        llama_sampler_chain_add(smpl_, llama_sampler_init_temp(sp.temp));
        llama_sampler_chain_add(smpl_, llama_sampler_init_dist(sp.seed));
    }
}

std::string Engine::applyChatTemplate(const std::vector<ChatMsg> & messages, bool add_assistant) const {
    if (!model_) return {};
    const char * tmpl = llama_model_chat_template(model_, nullptr);
    if (!tmpl) {
        // No template in the GGUF: build a ChatML transcript. Works for the Qwen/Phi family;
        // for anything else the user sees slightly worse formatting, never a crash.
        std::string out;
        for (const auto & m : messages) {
            out += "<|im_start|>" + m.role + "\n" + m.content + "<|im_end|>\n";
        }
        if (add_assistant) out += "<|im_start|>assistant\n";
        return out;
    }

    std::vector<llama_chat_message> msgs;
    msgs.reserve(messages.size());
    for (const auto & m : messages) msgs.push_back({m.role.c_str(), m.content.c_str()});

    int32_t need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, nullptr, 0);
    if (need <= 0) return {};
    std::vector<char> buf(static_cast<size_t>(need) + 1, 0);
    int32_t written = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), static_cast<int32_t>(buf.size()));
    if (written < 0) return {};
    return std::string(buf.data(), static_cast<size_t>(written));
}

void Engine::poisonPrefixCache() {
    cached_tokens_.clear();
    if (memory_) llama_memory_clear(memory_, true);
}

Status Engine::prefill(const std::vector<llama_token> & tokens, Stats & stats, std::string & err) {
    llama_pos n_cached = 0;
    if (memory_) {
        const llama_pos p = llama_memory_seq_pos_max(memory_, 0);
        n_cached = p < 0 ? 0 : p + 1;
    }

    // Longest common prefix between what is in the KV cache and the prompt we want.
    size_t common = 0;
    const size_t limit = std::min<size_t>(static_cast<size_t>(n_cached), tokens.size());
    while (common < limit && common < cached_tokens_.size() && cached_tokens_[common] == tokens[common]) {
        ++common;
    }
    if (common < static_cast<size_t>(n_cached) && memory_) {
        // Drop the divergent tail; positions must stay contiguous from 0.
        llama_memory_seq_rm(memory_, 0, static_cast<llama_pos>(common), -1);
        n_cached = static_cast<llama_pos>(common);
    }
    cached_tokens_.resize(common);

    if (static_cast<size_t>(n_cached) + (tokens.size() - common) > static_cast<size_t>(n_ctx_)) {
        err = "prompt does not fit in the context window";
        stats.context_full = true;
        return Status::CONTEXT_FULL;
    }

    stats.prompt_tokens = static_cast<int32_t>(tokens.size());
    stats.prompt_tokens_evaluated = static_cast<int32_t>(tokens.size() - common);

    const int64_t t0 = now_ms();
    size_t i = common;
    while (i < tokens.size()) {
        if (cancel_.load(std::memory_order_relaxed)) return Status::CANCELLED;
        const int32_t chunk = static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(params_.n_batch), tokens.size() - i));
        batch_set_tokens(batch_, tokens.data() + i, chunk, static_cast<llama_pos>(i));
        const int32_t rc = llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_);
        if (rc != 0) {
            poisonPrefixCache();
            if (cancel_.load(std::memory_order_relaxed)) return Status::CANCELLED;
            err = "llama_decode failed during prefill (rc=" + std::to_string(rc) + ")";
            return Status::DECODE_FAILED;
        }
        for (int32_t k = 0; k < chunk; ++k) cached_tokens_.push_back(tokens[i + k]);
        i += static_cast<size_t>(chunk);
    }
    if (tokens.empty()) {
        err = "empty prompt";
        return Status::BAD_INPUT;
    }
    // Logits for the last token must come from a batch that actually ran. If everything was
    // cached, re-evaluate the final token so llama_sampler_sample(-1) has fresh logits.
    if (common == tokens.size() && !tokens.empty()) {
        const llama_token last = tokens.back();
        llama_memory_seq_rm(memory_, 0, static_cast<llama_pos>(tokens.size() - 1), -1);
        cached_tokens_.pop_back();
        batch_set_tokens(batch_, &last, 1, static_cast<llama_pos>(tokens.size() - 1));
        const int32_t rc = llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_);
        if (rc != 0) {
            poisonPrefixCache();
            if (cancel_.load(std::memory_order_relaxed)) return Status::CANCELLED;
            err = "llama_decode failed re-evaluating the final prompt token (rc=" + std::to_string(rc) + ")";
            return Status::DECODE_FAILED;
        }
        cached_tokens_.push_back(last);
        stats.prompt_tokens_evaluated += 1;
    }
    stats.prefill_ms = now_ms() - t0;
    return Status::OK;
}

Status Engine::generate(const std::vector<ChatMsg> & messages, const SamplingParams & sp,
                        const TokenCallback & on_token, Stats & stats, std::string & err) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!isLoaded()) {
        err = "no model loaded";
        return Status::NOT_LOADED;
    }
    if (messages.empty()) {
        err = "empty message list";
        return Status::BAD_INPUT;
    }

    cancel_.store(false, std::memory_order_relaxed);
    rebuildSampler(sp);

    const std::string prompt = applyChatTemplate(messages, true);
    if (prompt.empty()) {
        err = "chat template produced an empty prompt";
        return Status::BAD_INPUT;
    }

    const bool is_first = cached_tokens_.empty();
    int32_t n_tok = -llama_tokenize(vocab_, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                    nullptr, 0, is_first, true);
    if (n_tok <= 0) {
        err = "tokenization failed";
        return Status::BAD_INPUT;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_tok));
    if (llama_tokenize(vocab_, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                       n_tok, is_first, true) < 0) {
        err = "tokenization failed (buffer too small)";
        return Status::BAD_INPUT;
    }

    if (draft_model_ && draft_ctx_) {
        return generateSpeculative(tokens, sp, on_token, stats, err);
    }

    Status st = prefill(tokens, stats, err);
    if (st != Status::OK) return st;

    Utf8Splitter splitter;
    stats.generated_tokens = 0;
    const int64_t t0 = now_ms();
    st = Status::OK;

    while (stats.generated_tokens < sp.max_tokens) {
        if (cancel_.load(std::memory_order_relaxed)) { st = Status::CANCELLED; break; }

        const llama_token id = llama_sampler_sample(smpl_, ctx_, -1);
        if (llama_vocab_is_eog(vocab_, id)) break;

        char buf[512];
        int32_t n = llama_token_to_piece(vocab_, id, buf, sizeof(buf), 0, false);
        if (n < 0) {  // piece longer than buf: retry with the reported size
            const int32_t bigger = -n;
            if (bigger > 0 && bigger < 1 << 20) {
                std::vector<char> tmp(static_cast<size_t>(bigger) + 1);
                n = llama_token_to_piece(vocab_, id, tmp.data(), bigger, 0, false);
                if (n > 0) {
                    std::string out = splitter.push(tmp.data(), static_cast<size_t>(n));
                    if (!out.empty() && on_token && !on_token(out, stats.generated_tokens)) { st = Status::CANCELLED; break; }
                }
            }
        } else if (n > 0) {
            std::string out = splitter.push(buf, static_cast<size_t>(n));
            if (!out.empty() && on_token && !on_token(out, stats.generated_tokens)) { st = Status::CANCELLED; break; }
        }

        stats.generated_tokens++;
        cached_tokens_.push_back(id);

        // No room left for another step -> stop cleanly instead of letting llama.cpp
        // return the "no KV slot" error (rc=1) mid-generation.
        const llama_pos used = llama_memory_seq_pos_max(memory_, 0) + 1;
        if (used + 1 > n_ctx_) {
            stats.context_full = true;
            cached_tokens_.pop_back();  // this token never made it into the KV cache
            break;
        }
        batch_set_tokens(batch_, &id, 1, used);
        const int32_t rc = llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_);
        if (rc != 0) {
            // llama.cpp removes KV entries for the aborted remainder, so the cached prefix is
            // stale even for a user-requested Stop. Invalidate before returning either way.
            poisonPrefixCache();
            if (cancel_.load(std::memory_order_relaxed)) { st = Status::CANCELLED; break; }
            err = "llama_decode failed while streaming (rc=" + std::to_string(rc) + ")";
            st = Status::DECODE_FAILED;
            break;
        }
    }

    const std::string tail = splitter.flush();
    if (!tail.empty() && on_token && st == Status::OK) on_token(tail, stats.generated_tokens);

    stats.decode_ms = now_ms() - t0;
    if (stats.decode_ms > 0) {
        stats.tokens_per_sec = (stats.generated_tokens * 1000.0) / static_cast<double>(stats.decode_ms);
    }
    return st;
}

uint64_t Engine::modelFingerprint() const {
    if (!model_) return 0;
    // FNV-1a over the things that would make a KV cache invalid for a different model.
    uint64_t h = 1469598103934665603ULL;
    auto mix = [&h](const void * data, size_t len) {
        const auto * p = static_cast<const unsigned char *>(data);
        for (size_t i = 0; i < len; ++i) { h ^= p[i]; h *= 1099511628211ULL; }
    };
    const auto mi = info();
    mix(mi.description.data(), mi.description.size());
    mix(&mi.n_layer, sizeof(mi.n_layer));
    mix(&mi.n_embd, sizeof(mi.n_embd));
    mix(&mi.n_vocab, sizeof(mi.n_vocab));
    mix(&mi.model_size_bytes, sizeof(mi.model_size_bytes));
    return h;
}

namespace {
constexpr char kKvMagic[8] = {'O', 'M', 'N', 'I', 'K', 'V', '0', '1'};
constexpr uint32_t kKvVersion = 1;
}  // namespace

bool Engine::saveKvState(const std::string & path) const {
    if (!ctx_ || cached_tokens_.empty()) return false;

    const size_t kv_size = llama_state_seq_get_size(ctx_, 0);
    if (kv_size == 0) return false;

    std::vector<uint8_t> kv(kv_size);
    const size_t written = llama_state_seq_get_data(ctx_, kv.data(), kv.size(), 0);
    if (written == 0) return false;

    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    if (!f) return false;

    const auto write_raw = [&f](const void * p, size_t n) {
        f.write(static_cast<const char *>(p), static_cast<std::streamsize>(n));
    };
    const uint32_t n_ctx = static_cast<uint32_t>(n_ctx_);
    const uint64_t fingerprint = modelFingerprint();
    const uint32_t n_tokens = static_cast<uint32_t>(cached_tokens_.size());
    const uint64_t kv_len = static_cast<uint64_t>(written);

    write_raw(kKvMagic, sizeof(kKvMagic));
    write_raw(&kKvVersion, sizeof(kKvVersion));
    write_raw(&n_ctx, sizeof(n_ctx));
    write_raw(&fingerprint, sizeof(fingerprint));
    write_raw(&n_tokens, sizeof(n_tokens));
    write_raw(cached_tokens_.data(), cached_tokens_.size() * sizeof(llama_token));
    write_raw(&kv_len, sizeof(kv_len));
    write_raw(kv.data(), static_cast<size_t>(written));
    f.flush();
    return f.good();
}

bool Engine::loadKvState(const std::string & path) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!ctx_) return false;

    std::ifstream f(path, std::ios::binary);
    if (!f) return false;

    const auto read_raw = [&f](void * p, size_t n) -> bool {
        f.read(static_cast<char *>(p), static_cast<std::streamsize>(n));
        return static_cast<size_t>(f.gcount()) == n;
    };

    char magic[sizeof(kKvMagic)];
    uint32_t version = 0, n_ctx = 0, n_tokens = 0;
    uint64_t fingerprint = 0, kv_len = 0;

    if (!read_raw(magic, sizeof(magic))) return false;
    if (std::memcmp(magic, kKvMagic, sizeof(kKvMagic)) != 0) return false;
    if (!read_raw(&version, sizeof(version)) || version != kKvVersion) return false;
    if (!read_raw(&n_ctx, sizeof(n_ctx)) || n_ctx != static_cast<uint32_t>(n_ctx_)) return false;
    if (!read_raw(&fingerprint, sizeof(fingerprint)) || fingerprint != modelFingerprint()) return false;
    if (!read_raw(&n_tokens, sizeof(n_tokens))) return false;
    if (n_tokens == 0 || n_tokens > static_cast<uint32_t>(n_ctx_)) return false;

    std::vector<llama_token> tokens(n_tokens);
    if (!read_raw(tokens.data(), tokens.size() * sizeof(llama_token))) return false;
    if (!read_raw(&kv_len, sizeof(kv_len)) || kv_len == 0 || kv_len > (1ULL << 32)) return false;

    std::vector<uint8_t> kv(static_cast<size_t>(kv_len));
    if (!read_raw(kv.data(), kv.size())) return false;

    const size_t restored = llama_state_seq_set_data(ctx_, kv.data(), kv.size(), 0);
    if (restored == 0) return false;

    // Only claim the cache once the KV state is actually in place, and only as far as the
    // restored positions go. A lie here would make the next prefill attend to garbage.
    const llama_pos pos_max = llama_memory_seq_pos_max(memory_, 0);
    const size_t positions = pos_max < 0 ? 0 : static_cast<size_t>(pos_max) + 1;
    const size_t usable = std::min({tokens.size(), positions, static_cast<size_t>(n_ctx_)});
    cached_tokens_.assign(tokens.begin(), tokens.begin() + static_cast<long>(usable));
    return usable > 0;
}

Status Engine::loadDraftModel(const std::string & path, const EngineParams & params, std::string & err) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = params.draft_gpu_layers;
    mp.load_mode = params.use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    draft_model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!draft_model_) {
        err = "could not load draft model " + path;
        return Status::LOAD_FAILED;
    }
    draft_vocab_ = llama_model_get_vocab(draft_model_);

    // The draft and target must share a vocabulary, otherwise draft token ids mean nothing to the
    // target model and verification is guaranteed to reject everything (or worse, accept garbage).
    const int32_t target_vocab = vocab_ ? llama_vocab_n_tokens(vocab_) : 0;
    if (!draft_vocab_ || llama_vocab_n_tokens(draft_vocab_) != target_vocab) {
        llama_model_free(draft_model_);
        draft_model_ = nullptr;
        draft_vocab_ = nullptr;
        err = "draft model vocabulary does not match the target model";
        return Status::BAD_INPUT;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(params.n_ctx);
    cp.n_batch = static_cast<uint32_t>(std::max(32, params.n_batch));
    cp.n_ubatch = cp.n_batch;
    cp.n_threads = params.n_threads;
    cp.n_threads_batch = params.n_threads;
    cp.no_perf = true;
    draft_ctx_ = llama_init_from_model(draft_model_, cp);
    if (!draft_ctx_) {
        llama_model_free(draft_model_);
        draft_model_ = nullptr;
        draft_vocab_ = nullptr;
        err = "draft context allocation failed (not enough RAM for a second KV cache)";
        return Status::OOM;
    }
    draft_path_ = path;
    return Status::OK;
}

bool speculativePairIsSane(const ModelInfo & target, const ModelInfo & draft, int64_t available_bytes,
                           int32_t n_ctx) {
    if (target.n_vocab <= 0 || draft.n_vocab <= 0) return false;
    if (target.n_vocab != draft.n_vocab) return false;
    // A draft that is not meaningfully smaller than the target just doubles the cost.
    if (draft.model_size_bytes * 2 > target.model_size_bytes) return false;
    const int64_t need = static_cast<int64_t>(target.model_size_bytes) +
                         static_cast<int64_t>(draft.model_size_bytes) +
                         estimateKvBytes(n_ctx, target, false) +
                         estimateKvBytes(n_ctx, draft, false) +
                         (128LL << 20);
    return need <= (available_bytes * 3) / 4;
}

Status Engine::generateSpeculative(const std::vector<llama_token> & prompt_tokens,
                                   const SamplingParams & sp, const TokenCallback & on_token,
                                   Stats & stats, std::string & err) {
    if (!draft_model_ || !draft_ctx_) { err = "draft model not available"; return Status::NOT_LOADED; }

    // The target model must already hold the prompt: its last-token logits are what predicts the
    // first candidate, so we rely on prefill having just run and must not lose those logits.
    Status st = prefill(prompt_tokens, stats, err);
    if (st != Status::OK) return st;
    if (!smpl_) { err = "no sampler"; return Status::BAD_INPUT; }

    llama_sampler * draft_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(draft_smpl, llama_sampler_init_top_k(sp.top_k));
    llama_sampler_chain_add(draft_smpl, llama_sampler_init_temp(sp.temp));
    llama_sampler_chain_add(draft_smpl, llama_sampler_init_dist(sp.seed));
    llama_batch_ext * dbatch = llama_batch_ext_init(draft_ctx_);
    llama_memory_t dmem = llama_get_memory(draft_ctx_);
    // Draft context mirrors the prompt once; after that it is rolled back incrementally, never
    // rebuilt, because a full rebuild per step turns speculative decoding into an O(n^2) crawl.
    const auto feedDraftRange = [&](const std::vector<llama_token> & toks, int32_t from, int32_t to,
                                    llama_pos pos0) -> bool {
        int32_t i = from;
        while (i < to) {
            const int32_t chunk = std::min(params_.n_batch, to - i);
            batch_set_tokens(dbatch, toks.data() + i, chunk, pos0 + (i - from));
            if (llama_process(draft_ctx_, LLAMA_PROCESS_TYPE_DECODE, dbatch) != 0) return false;
            i += chunk;
        }
        return true;
    };
    if (!feedDraftRange(prompt_tokens, 0, static_cast<int32_t>(prompt_tokens.size()), 0)) {
        llama_batch_ext_free(dbatch); llama_sampler_free(draft_smpl);
        err = "draft model failed to process the prompt";
        return Status::DECODE_FAILED;
    }

    Utf8Splitter splitter;
    stats.generated_tokens = 0; stats.draft_proposed = 0; stats.draft_accepted = 0;
    const int64_t t0_ms = now_ms();
    const int32_t n_draft = std::max(1, std::min(params_.draft_tokens, 8));
    st = Status::OK;

    while (stats.generated_tokens < sp.max_tokens) {
        if (cancel_.load(std::memory_order_relaxed)) { st = Status::CANCELLED; break; }

        const llama_pos base = llama_memory_seq_pos_max(memory_, 0) + 1;
        if (base + n_draft + 1 >= n_ctx_) { stats.context_full = true; break; }

        // ---- 1. draft proposes n_draft tokens, starting from its current position ----
        std::vector<llama_token> cand;
        cand.reserve(n_draft);
        llama_token d = llama_sampler_sample(draft_smpl, draft_ctx_, -1);
        for (int32_t k = 0; k < n_draft; ++k) {
            if (llama_vocab_is_eog(draft_vocab_, d)) break;
            cand.push_back(d);
            batch_set_tokens(dbatch, &d, 1, base + k);
            if (llama_process(draft_ctx_, LLAMA_PROCESS_TYPE_DECODE, dbatch) != 0) break;
            d = llama_sampler_sample(draft_smpl, draft_ctx_, -1);
        }
        stats.draft_proposed += static_cast<int32_t>(cand.size());
        if (cand.empty()) {
            // An empty proposal means the draft sampled an end-of-generation token, i.e. the answer
            // is finished. That is a normal stop, not a failure.
            st = Status::OK;
            break;
        }

        // ---- 2. target verifies the whole run in ONE batched decode ----
        // Off-by-one that matters: logits at position p predict the token at p+1. So the first
        // candidate is checked against the logits the target ALREADY holds (from its previous
        // decode), and candidate i+1 against the logits produced at candidate i's position.
        const llama_token t_first = llama_sampler_sample(smpl_, ctx_, -1);
        if (t_first != cand[0]) {
            // Immediate divergence: emit the target's own token and move on.
            if (llama_vocab_is_eog(vocab_, t_first)) break;
            char buf[512];
            const int32_t n = llama_token_to_piece(vocab_, t_first, buf, sizeof(buf), 0, false);
            std::string out = n > 0 ? splitter.push(buf, static_cast<size_t>(n)) : std::string();
            batch_set_tokens(batch_, &t_first, 1, base);
            if (llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_) != 0) {
                poisonPrefixCache(); st = Status::DECODE_FAILED; err = "target decode failed"; break;
            }
            cached_tokens_.push_back(t_first);
            // Roll the draft back to the committed position and replay the same token.
            llama_memory_seq_rm(dmem, 0, base, -1);
            std::vector<llama_token> one{t_first};
            if (!feedDraftRange(one, 0, 1, base)) { st = Status::DECODE_FAILED; err = "draft resync failed"; break; }
            stats.generated_tokens += 1;
            if (on_token && !out.empty() && !on_token(out, stats.generated_tokens)) { st = Status::CANCELLED; break; }
            continue;
        }

        llama_batch_ext_clear(batch_);
        for (size_t i = 0; i < cand.size(); ++i) {
            const int32_t idx = llama_batch_ext_add_token(batch_, 0, cand[i]);
            const llama_pos pos = base + static_cast<llama_pos>(i);
            llama_batch_ext_set_pos(batch_, idx, &pos);
            // Every position needs logits, otherwise llama_sampler_sample() trips
            // GGML_ASSERT(logits != nullptr) — a native abort, which this app must never produce.
            llama_batch_ext_set_output_logits(batch_, idx, true);
        }
        if (llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_) != 0) {
            poisonPrefixCache(); st = Status::DECODE_FAILED; err = "target rejected the verification batch"; break;
        }

        int32_t accepted = 1;  // cand[0] matched t_first
        llama_token correction = LLAMA_TOKEN_NULL;
        for (size_t i = 1; i < cand.size(); ++i) {
            const llama_token t_i = llama_sampler_sample(smpl_, ctx_, static_cast<int32_t>(i) - 1);
            if (t_i != cand[i]) { correction = t_i; break; }
            accepted++;
        }
        // If every candidate matched, the logits at the last position give a free extra token.
        const bool full_match = (correction == LLAMA_TOKEN_NULL) && (accepted == static_cast<int32_t>(cand.size()));
        llama_token bonus = LLAMA_TOKEN_NULL;
        if (full_match) {
            const llama_token t_next = llama_sampler_sample(smpl_, ctx_, static_cast<int32_t>(cand.size()) - 1);
            if (!llama_vocab_is_eog(vocab_, t_next)) bonus = t_next;
        }
        stats.draft_accepted += accepted;

        std::string out_chunk;
        std::vector<llama_token> commit;
        for (int32_t i = 0; i < accepted; ++i) {
            char buf[512];
            const int32_t n = llama_token_to_piece(vocab_, cand[i], buf, sizeof(buf), 0, false);
            if (n > 0) out_chunk += splitter.push(buf, static_cast<size_t>(n));
            commit.push_back(cand[i]);
        }
        for (llama_token extra : {correction, bonus}) {
            if (extra == LLAMA_TOKEN_NULL || llama_vocab_is_eog(vocab_, extra)) continue;
            char buf[512];
            const int32_t n = llama_token_to_piece(vocab_, extra, buf, sizeof(buf), 0, false);
            if (n > 0) out_chunk += splitter.push(buf, static_cast<size_t>(n));
            commit.push_back(extra);
        }
        if (commit.empty()) break;

        // ---- 3. align both KV caches with what was actually committed ----
        const int32_t total = static_cast<int32_t>(commit.size());
        // Target: drop candidate positions we did not commit, then re-evaluate any extra token.
        if (total <= accepted) {
            llama_memory_seq_rm(memory_, 0, base + total, -1);
        } else {
            llama_memory_seq_rm(memory_, 0, base + accepted, -1);
            const int32_t extra = total - accepted;
            batch_set_tokens(batch_, commit.data() + accepted, extra, base + accepted);
            if (llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_) != 0) {
                poisonPrefixCache(); st = Status::DECODE_FAILED; err = "target replay failed"; break;
            }
        }
        cached_tokens_.insert(cached_tokens_.end(), commit.begin(), commit.end());

        // Draft: it already holds cand[0..accepted-1] at positions base..base+accepted-1 from the
        // drafting pass, so only drop the uncommitted tail and feed whatever came after it.
        // Replaying the accepted run from scratch (the obvious implementation) doubles draft work
        // and is the difference between a speedup and a slowdown.
        llama_memory_seq_rm(dmem, 0, base + accepted, -1);
        if (total > accepted) {
            if (!feedDraftRange(commit, accepted, total, base + accepted)) {
                st = Status::DECODE_FAILED; err = "draft resync failed"; break;
            }
        }

        stats.generated_tokens += total;
        if (on_token && !out_chunk.empty() && !on_token(out_chunk, stats.generated_tokens)) {
            st = Status::CANCELLED; break;
        }
    }

    llama_batch_ext_free(dbatch);
    llama_sampler_free(draft_smpl);
    const std::string tail = splitter.flush();
    if (!tail.empty() && on_token && st == Status::OK) on_token(tail, stats.generated_tokens);
    stats.decode_ms = now_ms() - t0_ms;
    if (stats.decode_ms > 0) {
        stats.tokens_per_sec = (stats.generated_tokens * 1000.0) / static_cast<double>(stats.decode_ms);
    }
    return st;
}

int32_t Engine::countPromptTokens(const std::vector<ChatMsg> & messages) const {
    if (!model_ || !vocab_ || messages.empty()) return -1;
    const std::string prompt = applyChatTemplate(messages, true);
    if (prompt.empty()) return -1;
    const bool is_first = cached_tokens_.empty();
    const int32_t n = -llama_tokenize(vocab_, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                      nullptr, 0, is_first, true);
    return n > 0 ? n : -1;
}

ModelInfo Engine::info() const {
    ModelInfo mi;
    if (!model_) return mi;
    char desc[256] = {0};
    if (llama_model_desc(model_, desc, sizeof(desc)) > 0) mi.description = desc;
    const char * tmpl = llama_model_chat_template(model_, nullptr);
    if (tmpl) mi.chat_template = tmpl;
    mi.n_ctx_train = llama_model_n_ctx_train(model_);
    mi.n_params = static_cast<int32_t>(llama_model_n_params(model_));
    mi.model_size_bytes = llama_model_size(model_);
    mi.n_vocab = vocab_ ? llama_vocab_n_tokens(vocab_) : 0;
    mi.n_embd = llama_model_n_embd(model_);
    mi.n_head = llama_model_n_head(model_);
    mi.n_layer = llama_model_n_layer(model_);
    return mi;
}

std::string Engine::backendInfo() {
    std::ostringstream os;
    const size_t n = ggml_backend_dev_count();
    os << "backends=" << n;
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (!d) continue;
        const char * name = ggml_backend_dev_name(d);
        const char * desc = ggml_backend_dev_description(d);
        os << " | " << (name ? name : "?") << ":" << (desc ? desc : "?")
           << ":type=" << static_cast<int>(ggml_backend_dev_type(d));
    }
    os << " | gpu_offload=" << (llama_supports_gpu_offload() ? 1 : 0);
    return os.str();
}

bool Engine::hasGpuBackend() {
    const size_t n = ggml_backend_dev_count();
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (!d) continue;
        const auto t = ggml_backend_dev_type(d);
        if (t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) return true;
    }
    return false;
}

int64_t estimateKvBytes(int32_t n_ctx, const ModelInfo & info, bool q8_kv) {
    if (n_ctx <= 0 || info.n_layer <= 0 || info.n_embd <= 0) {
        // Conservative default: 0.22 MB per 1k context for a ~1B model at f16 KV.
        return static_cast<int64_t>(n_ctx) * 225;
    }
    // K and V, n_layer each, n_embd wide, 2 bytes per element at f16 (1 byte for Q8_0).
    const int64_t per_elem = q8_kv ? 1 : 2;
    return static_cast<int64_t>(n_ctx) * info.n_layer * info.n_embd * 2 * per_elem;
}


// ---------------------------------------------------------------------------------------------
// GGUF header peek + draft-pair validation
// ---------------------------------------------------------------------------------------------

namespace {

int32_t ggufArchU32(const gguf_context * ctx, const char * arch, const char * suffix) {
    const std::string key = std::string(arch) + "." + suffix;
    const int64_t id = gguf_find_key(ctx, key.c_str());
    if (id < 0 || gguf_get_kv_type(ctx, id) != GGUF_TYPE_UINT32) return 0;
    return static_cast<int32_t>(gguf_get_val_u32(ctx, id));
}

// vocab size is stored per-architecture; fall back to the token-embedding tensor shape, which is
// universal and always present.
int32_t ggufVocabSize(const gguf_context * ctx, const char * arch) {
    const int64_t id = gguf_find_key(ctx, (std::string(arch) + ".vocab_size").c_str());
    if (id >= 0 && gguf_get_kv_type(ctx, id) == GGUF_TYPE_UINT32) {
        return static_cast<int32_t>(gguf_get_val_u32(ctx, id));
    }
    const int64_t t = gguf_find_tensor(ctx, "token_embd.weight");
    if (t >= 0) {
        const int64_t * ne = gguf_get_tensor_ne(ctx, t);
        if (ne) return static_cast<int32_t>(ne[1]);  // [n_embd, n_vocab]
    }
    return 0;
}

}  // namespace

ModelPeek peekModelFile(const std::string & path) {
    ModelPeek out;
    if (path.empty()) { out.error = "empty path"; return out; }

    FILE * f = fopen(path.c_str(), "rb");
    if (!f) { out.error = "cannot open " + path; return out; }
    if (fseek(f, 0, SEEK_END) == 0) {
        const long sz = ftell(f);
        if (sz > 0) out.file_bytes = static_cast<uint64_t>(sz);
    }
    fclose(f);

    gguf_init_params gp;
    gp.no_alloc = true;   // header only: no tensor data is read or allocated
    gp.ctx = nullptr;

    gguf_context * ctx = gguf_init_from_file(path.c_str(), gp);
    if (!ctx) { out.error = "not a GGUF file (or too new / corrupt): " + path; return out; }

    const int64_t aid = gguf_find_key(ctx, "general.architecture");
    if (aid < 0 || gguf_get_kv_type(ctx, aid) != GGUF_TYPE_STRING) {
        gguf_free(ctx);
        out.error = "GGUF has no general.architecture key";
        return out;
    }
    const std::string arch = gguf_get_val_str(ctx, aid);

    out.n_vocab = ggufVocabSize(ctx, arch.c_str());
    out.n_layer = ggufArchU32(ctx, arch.c_str(), "block_count");
    out.n_embd = ggufArchU32(ctx, arch.c_str(), "embedding_length");
    out.ok = true;
    gguf_free(ctx);

    if (out.n_vocab <= 0) { out.ok = false; out.error = "could not determine vocabulary size"; }
    return out;
}

std::string speculativePairVerdict(const ModelPeek & target, const ModelPeek & draft,
                                   int64_t available_bytes, int32_t n_ctx) {
    if (!target.ok) return "Target model unreadable: " + target.error;
    if (!draft.ok) return "Draft model unreadable: " + draft.error;
    if (target.file_bytes == 0 || draft.file_bytes == 0) return "Could not measure file sizes";

    if (target.n_vocab != draft.n_vocab) {
        return "Vocabulary mismatch: target has " + std::to_string(target.n_vocab) +
               " tokens, draft has " + std::to_string(draft.n_vocab) +
               ". The two models must share a tokenizer and vocabulary.";
    }
    if (draft.file_bytes * 2 > target.file_bytes) {
        return "Draft model is too large relative to the target (draft must be at most half the "
               "target size). A big draft costs more to run than it saves.";
    }
    if (target.n_layer > 0 && draft.n_layer > 0 && draft.n_layer > target.n_layer) {
        return "Draft model has more layers than the target, so it cannot be cheaper to run.";
    }

    // Both models plus two KV caches plus working room must fit in 75% of available RAM.
    const int64_t kv = estimateKvBytes(n_ctx, ModelInfo{}, false);
    const int64_t need = static_cast<int64_t>(target.file_bytes + draft.file_bytes) + 2 * kv +
                         (128LL << 20);
    const int64_t budget = static_cast<int64_t>(static_cast<double>(available_bytes) * 0.75);
    if (available_bytes > 0 && need > budget) {
        const double need_gb = static_cast<double>(need) / (1024.0 * 1024.0 * 1024.0);
        const double avail_gb = static_cast<double>(available_bytes) / (1024.0 * 1024.0 * 1024.0);
        char buf[256];
        snprintf(buf, sizeof(buf),
                 "Not enough RAM: both models need about %.2f GB, free memory is %.2f GB "
                 "(using %.0f%% of it).", need_gb, avail_gb, 75.0);
        return std::string(buf);
    }
    return {};
}

}  // namespace omni

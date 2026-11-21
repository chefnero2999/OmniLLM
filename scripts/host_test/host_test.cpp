// Host harness for the OmniLLM engine. Compiles the *same* omni_engine.cpp that ships in the APK,
// but without JNI, so it can be run on a laptop or CI box.
//
// What it proves, in order:
//   1. the model loads and the GGUF metadata is readable;
//   2. generation streams real tokens and reports tokens/sec;
//   3. the prefix cache actually reuses the conversation (turn 2 evaluates far fewer tokens);
//   4. cancel() aborts a running decode instead of waiting for it to finish;
//   5. unload() releases everything without a leak report from your sanitiser of choice.
//
// Build: see CMakeLists.txt in this directory. Run: ./omni_host_test model.gguf [prompt]

#include "omni_engine.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

static void onLog(int level, const char * text) {
    if (level >= 3) fprintf(stderr, "[llama] %s", text);
}

int main(int argc, char ** argv) {
    if (argc < 2) {
        printf("usage: %s model.gguf [prompt]\n", argv[0]);
        return 2;
    }

    omni::setLogSink(onLog);
    llama_backend_init();

    omni::Engine engine;
    omni::EngineParams params;
    params.n_ctx = 1024;
    params.n_threads = 4;
    params.n_batch = 256;
    params.n_ubatch = 256;
    params.gpu_layers = 0;
    params.use_mmap = true;
    params.use_mlock = false;
    params.flash_attn = false;

    std::string err;
    auto status = engine.loadModel(
        argv[1], params,
        [](float f) {
            printf("\rload %3.0f%%", f * 100);
            fflush(stdout);
            return true;
        },
        err);

    printf("\nload status=%s err=%s\n", omni::statusName(status), err.c_str());
    if (status != omni::Status::OK) return 1;

    const auto info = engine.info();
    printf("model=%s\n", info.description.c_str());
    printf("ctx_train=%d params=%d size=%llu vocab=%d embd=%d heads=%d layers=%d\n",
           info.n_ctx_train, info.n_params,
           (unsigned long long) info.model_size_bytes, info.n_vocab,
           info.n_embd, info.n_head, info.n_layer);
    printf("chat_template=%.60s\n", info.chat_template.c_str());
    printf("backends=%s\n", omni::Engine::backendInfo().c_str());
    printf("kv@1024=%.1f MB\n", omni::estimateKvBytes(1024, info, /*q8_kv=*/false) / 1048576.0);

    const char * user = argc > 2 ? argv[2]
                                 : "In one short sentence, what does memory-mapping a model file do?";
    std::vector<omni::ChatMsg> messages = {
        {"system", "You are a terse assistant."},
        {"user", user},
    };

    omni::SamplingParams sampling;
    sampling.max_tokens = 80;
    sampling.temp = 0.3f;

    // ---- turn 1 -------------------------------------------------------------------------
    omni::Stats stats;
    std::string answer;
    status = engine.generate(messages, sampling, [&](const std::string & piece, int) {
        answer += piece;
        printf("%s", piece.c_str());
        fflush(stdout);
        return true;
    }, stats, err);

    printf("\n--- turn1 status=%s err=%s\n", omni::statusName(status), err.c_str());
    printf("prompt=%d evaluated=%d generated=%d prefill=%lldms decode=%lldms tok/s=%.2f ctx_full=%d\n",
           stats.prompt_tokens, stats.prompt_tokens_evaluated, stats.generated_tokens,
           (long long) stats.prefill_ms, (long long) stats.decode_ms,
           stats.tokens_per_sec, (int) stats.context_full);

    // ---- turn 2: the prefix cache should skip most of the prompt -------------------------
    messages.push_back({"assistant", answer});
    messages.push_back({"user", "And in one word: faster or slower?"});
    omni::Stats stats2;
    status = engine.generate(messages, sampling, [](const std::string & piece, int) {
        printf("%s", piece.c_str());
        fflush(stdout);
        return true;
    }, stats2, err);
    printf("\n--- turn2 status=%s prompt=%d evaluated=%d reused=%d prefill=%lldms\n",
           omni::statusName(status), stats2.prompt_tokens, stats2.prompt_tokens_evaluated,
           stats2.prompt_tokens - stats2.prompt_tokens_evaluated, (long long) stats2.prefill_ms);

    // ---- cancel: Stop must interrupt the decode, not queue behind it ---------------------
    omni::Stats stats3;
    std::atomic<bool> started{false};
    std::thread canceller([&] {
        while (!started.load()) { std::this_thread::yield(); }
        std::this_thread::sleep_for(std::chrono::milliseconds(120));
        engine.requestCancel();
    });
    omni::SamplingParams longForm;
    longForm.max_tokens = 400;
    longForm.temp = 0.9f;
    status = engine.generate({{"user", "Write a very long essay about the sea."}}, longForm,
                             [&](const std::string &, int) {
                                 started.store(true);
                                 return true;
                             },
                             stats3, err);
    canceller.join();
    printf("cancel status=%s generated_before_stop=%d (expect CANCELLED)\n",
           omni::statusName(status), stats3.generated_tokens);

    engine.unload();
    llama_backend_free();
    printf("done\n");
    return 0;
}

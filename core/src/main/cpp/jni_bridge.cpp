// OmniLLM — JNI boundary.
//
// Rules this file follows (each one exists because of a real Android failure mode):
//  1. Nothing here calls abort()/exit(). Every failure becomes a status code the Kotlin
//     layer translates into an exception on a background thread, never on the UI thread.
//  2. No C++ exception is allowed to escape into the JVM. A throw that unwinds past a JNI
//     frame is undefined behaviour and shows up as a SIGSEGV / "JNI DETECTED ERROR".
//  3. Kotlin callbacks are checked for exceptions; a throwing callback cancels generation
//     instead of corrupting state.
//  4. One engine instance per process. The :llm service is the only thing that calls in.

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <new>
#include <string>
#include <vector>

#include "omni_engine.h"

#ifdef __ANDROID__
#include <android/log.h>
#define OMNI_TAG "OmniNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, OMNI_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, OMNI_TAG, __VA_ARGS__)
#else
#define LOGI(...) ::printf(__VA_ARGS__)
#define LOGE(...) ::fprintf(stderr, __VA_ARGS__)
#endif

namespace {

omni::Engine g_engine;

// The service runs every native call on one worker thread, so a plain global is enough and
// avoids allocating a JNI object just to read back an error string or the last stats.
std::string g_last_error;
omni::Stats g_last_stats;

void setError(const std::string & msg) { g_last_error = msg; }

// Escapes a string for the tiny JSON objects we hand back to Kotlin.
std::string jsonEscape(const std::string & in) {
    std::string out;
    out.reserve(in.size() + 16);
    for (char c : in) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

jstring toJString(JNIEnv * env, const std::string & s) {
    return env->NewStringUTF(s.c_str());
}

// Returns true when a Kotlin callback threw; the exception is cleared so we can unwind safely.
bool consumeCallbackThrow(JNIEnv * env, const char * where) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("OmniNative: Kotlin callback threw in %s (cleared)", where);
        return true;
    }
    return false;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    omni::setLogSink([](int level, const char * text) {
        // Route llama.cpp chatter to logcat at a low priority; errors higher.
        if (level >= 3) LOGE("%s", text);
        else LOGI("%s", text);
    });
    llama_backend_init();
    LOGI("OmniNative loaded (llama.cpp backend initialised)");
    (void) vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
    g_engine.unload();
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeBackendInfo(JNIEnv * env, jclass) {
    return toJString(env, omni::Engine::backendInfo());
}

JNIEXPORT jboolean JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeHasGpuBackend(JNIEnv *, jclass) {
    return omni::Engine::hasGpuBackend() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeIsLoaded(JNIEnv *, jclass) {
    return g_engine.isLoaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeLastError(JNIEnv * env, jclass) {
    return toJString(env, g_last_error);
}

JNIEXPORT jint JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeLoad(JNIEnv * env, jclass, jstring jpath,
                                             jint n_ctx, jint n_batch, jint n_ubatch,
                                             jint n_threads, jint n_gpu_layers,
                                             jboolean use_mmap, jboolean use_mlock,
                                             jboolean flash_attn, jboolean q8_kv,
                                             jstring jdraft_path, jint draft_gpu_layers,
                                             jint draft_tokens,
                                             jobject load_callback) {
    if (jpath == nullptr) {
        setError("null model path");
        return static_cast<jint>(omni::Status::BAD_INPUT);
    }
    const char * path_chars = env->GetStringUTFChars(jpath, nullptr);
    if (!path_chars) {
        setError("could not read model path");
        return static_cast<jint>(omni::Status::BAD_INPUT);
    }
    const std::string path(path_chars);
    env->ReleaseStringUTFChars(jpath, path_chars);

    omni::EngineParams p;
    p.n_ctx = n_ctx;
    p.n_batch = n_batch;
    p.n_ubatch = n_ubatch;
    p.n_threads = n_threads;
    p.n_threads_batch = n_threads;
    p.gpu_layers = n_gpu_layers;
    p.use_mmap = use_mmap == JNI_TRUE;
    p.use_mlock = use_mlock == JNI_TRUE;
    p.flash_attn = flash_attn == JNI_TRUE;
    p.q8_kv = q8_kv == JNI_TRUE;

    // Speculative decoding. An empty/null path means "off"; the engine then behaves exactly as
    // before. draft_tokens is clamped because a sane window is 2-8 candidates per verify pass.
    if (jdraft_path) {
        const char * dp = env->GetStringUTFChars(jdraft_path, nullptr);
        if (dp) {
            p.draft_model_path = dp;
            env->ReleaseStringUTFChars(jdraft_path, dp);
        }
    }
    if (!p.draft_model_path.empty()) {
        p.draft_gpu_layers = draft_gpu_layers;
        p.draft_tokens = draft_tokens < 2 ? 2 : (draft_tokens > 8 ? 8 : draft_tokens);
    }

    jmethodID on_progress = nullptr;
    if (load_callback) {
        jclass cb_cls = env->GetObjectClass(load_callback);
        on_progress = env->GetMethodID(cb_cls, "onProgress", "(F)Z");
        env->DeleteLocalRef(cb_cls);
        if (!on_progress) {
            setError("load callback has no onProgress(float): Boolean");
            return static_cast<jint>(omni::Status::BAD_INPUT);
        }
    }

    omni::ProgressCallback progress;
    std::atomic<bool> abort_load{false};
    if (on_progress) {
        progress = [&](float fraction) -> bool {
            if (abort_load.load(std::memory_order_relaxed)) return false;
            const jboolean keep = env->CallBooleanMethod(load_callback, on_progress, static_cast<jfloat>(fraction));
            if (consumeCallbackThrow(env, "onProgress")) return false;
            return keep == JNI_TRUE;
        };
    }

    std::string err;
    omni::Status st = omni::Status::LOAD_FAILED;
    try {
        st = g_engine.loadModel(path, p, progress, err);
    } catch (const std::bad_alloc &) {
        err = "native allocation failed while loading (std::bad_alloc)";
        st = omni::Status::OOM;
    } catch (const std::exception & e) {
        err = std::string("unexpected native exception: ") + e.what();
        st = omni::Status::LOAD_FAILED;
    } catch (...) {
        err = "unexpected non-standard native exception";
        st = omni::Status::LOAD_FAILED;
    }

    setError(err);
    if (st != omni::Status::OK) g_engine.unload();
    return static_cast<jint>(st);
}

JNIEXPORT void JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeUnload(JNIEnv *, jclass) {
    g_engine.unload();
}

JNIEXPORT void JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeCancel(JNIEnv *, jclass) {
    g_engine.requestCancel();
}

JNIEXPORT void JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeSetThreads(JNIEnv *, jclass, jint n_threads) {
    g_engine.setThreads(n_threads);
}

JNIEXPORT void JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeResetConversation(JNIEnv *, jclass) {
    g_engine.resetConversation();
}

JNIEXPORT jint JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeCountTokens(JNIEnv * env, jclass,
                                                     jobjectArray roles, jobjectArray contents) {
    if (!roles || !contents || env->GetArrayLength(roles) != env->GetArrayLength(contents)) return -1;
    const jsize n = env->GetArrayLength(roles);
    std::vector<omni::ChatMsg> messages;
    messages.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto jr = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto jc = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        if (!jr || !jc) { if (jr) env->DeleteLocalRef(jr); if (jc) env->DeleteLocalRef(jc); return -1; }
        const char * r = env->GetStringUTFChars(jr, nullptr);
        const char * c = env->GetStringUTFChars(jc, nullptr);
        messages.push_back({r ? r : "", c ? c : ""});
        if (r) env->ReleaseStringUTFChars(jr, r);
        if (c) env->ReleaseStringUTFChars(jc, c);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    return static_cast<jint>(g_engine.countPromptTokens(messages));
}

JNIEXPORT jstring JNICALL
Java_com_omni_llm_core_LlamaBridge_nativePeekModel(JNIEnv * env, jclass, jstring jpath) {
    if (!jpath) return toJString(env, "{\"ok\":false,\"error\":\"null path\"}");
    const char * p = env->GetStringUTFChars(jpath, nullptr);
    const std::string path = p ? p : "";
    if (p) env->ReleaseStringUTFChars(jpath, p);

    const omni::ModelPeek peek = omni::peekModelFile(path);
    char buf[512];
    snprintf(buf, sizeof(buf),
             "{\"ok\":%s,\"nVocab\":%d,\"nLayer\":%d,\"nEmbd\":%d,\"fileBytes\":%llu,"
             "\"error\":\"%s\"}",
             peek.ok ? "true" : "false", peek.n_vocab, peek.n_layer, peek.n_embd,
             static_cast<unsigned long long>(peek.file_bytes),
             jsonEscape(peek.error).c_str());
    return toJString(env, std::string(buf));
}

// Returns an empty string when the pair is usable, otherwise a one-line reason to show the user.
JNIEXPORT jstring JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeSpeculativeVerdict(JNIEnv * env, jclass,
                                                           jstring jtarget, jstring jdraft,
                                                           jlong available_bytes, jint n_ctx) {
    if (!jtarget || !jdraft) return toJString(env, "missing model path");
    const char * tp = env->GetStringUTFChars(jtarget, nullptr);
    const char * dp = env->GetStringUTFChars(jdraft, nullptr);
    const std::string target = tp ? tp : "";
    const std::string draft = dp ? dp : "";
    if (tp) env->ReleaseStringUTFChars(jtarget, tp);
    if (dp) env->ReleaseStringUTFChars(jdraft, dp);

    const omni::ModelPeek t = omni::peekModelFile(target);
    const omni::ModelPeek d = omni::peekModelFile(draft);
    return toJString(env, omni::speculativePairVerdict(t, d,
                                                      static_cast<int64_t>(available_bytes), n_ctx));
}

JNIEXPORT jboolean JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeDraftLoaded(JNIEnv *, jclass) {
    return g_engine.draftLoaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeGenerate(JNIEnv * env, jclass,
                                                 jobjectArray roles, jobjectArray contents,
                                                 jint max_tokens, jfloat temp, jfloat top_p,
                                                 jint top_k, jfloat min_p, jfloat repeat_penalty,
                                                 jint seed, jobject token_callback) {
    if (!roles || !contents || env->GetArrayLength(roles) != env->GetArrayLength(contents)) {
        setError("roles/contents arrays must be non-null and the same length");
        return static_cast<jint>(omni::Status::BAD_INPUT);
    }

    const jsize n = env->GetArrayLength(roles);
    std::vector<omni::ChatMsg> messages;
    messages.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto jr = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto jc = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        if (!jr || !jc) {
            if (jr) env->DeleteLocalRef(jr);
            if (jc) env->DeleteLocalRef(jc);
            setError("null role/content entry");
            return static_cast<jint>(omni::Status::BAD_INPUT);
        }
        const char * r = env->GetStringUTFChars(jr, nullptr);
        const char * c = env->GetStringUTFChars(jc, nullptr);
        messages.push_back({r ? r : "", c ? c : ""});
        if (r) env->ReleaseStringUTFChars(jr, r);
        if (c) env->ReleaseStringUTFChars(jc, c);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }

    jmethodID on_token = nullptr;
    if (token_callback) {
        jclass cb_cls = env->GetObjectClass(token_callback);
        on_token = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;I)Z");
        env->DeleteLocalRef(cb_cls);
        if (!on_token) {
            setError("token callback has no onToken(String, Int): Boolean");
            return static_cast<jint>(omni::Status::BAD_INPUT);
        }
    }

    omni::TokenCallback cb;
    if (on_token) {
        cb = [&](const std::string & piece, int32_t count) -> bool {
            jstring js = env->NewStringUTF(piece.c_str());
            const jboolean keep = env->CallBooleanMethod(token_callback, on_token, js, static_cast<jint>(count));
            env->DeleteLocalRef(js);
            if (consumeCallbackThrow(env, "onToken")) return false;
            return keep == JNI_TRUE;
        };
    }

    omni::SamplingParams sp;
    sp.max_tokens = max_tokens;
    sp.temp = temp;
    sp.top_p = top_p;
    sp.top_k = top_k;
    sp.min_p = min_p;
    sp.repeat_penalty = repeat_penalty;
    sp.seed = static_cast<uint32_t>(seed);

    omni::Stats stats;
    std::string err;
    omni::Status st = omni::Status::DECODE_FAILED;
    try {
        st = g_engine.generate(messages, sp, cb, stats, err);
    } catch (const std::bad_alloc &) {
        err = "native allocation failed during generation";
        st = omni::Status::OOM;
    } catch (const std::exception & e) {
        err = std::string("unexpected native exception: ") + e.what();
        st = omni::Status::DECODE_FAILED;
    } catch (...) {
        err = "unexpected non-standard native exception";
        st = omni::Status::DECODE_FAILED;
    }

    setError(err);

    // Hand the stats back through a thread-local so the caller can read them right after
    // without another JNI object allocation in the hot path.
    g_last_stats = stats;
    return static_cast<jint>(st);
}

JNIEXPORT jstring JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeLastStats(JNIEnv * env, jclass) {
    const auto & s = g_last_stats;
    char buf[640];
    snprintf(buf, sizeof(buf),
             "{\"promptTokens\":%d,\"evaluatedTokens\":%d,\"generatedTokens\":%d,"
             "\"prefillMs\":%lld,\"decodeMs\":%lld,\"tokensPerSec\":%.3f,\"contextFull\":%s,"
             "\"draftProposed\":%d,\"draftAccepted\":%d}",
             s.prompt_tokens, s.prompt_tokens_evaluated, s.generated_tokens,
             static_cast<long long>(s.prefill_ms), static_cast<long long>(s.decode_ms),
             s.tokens_per_sec, s.context_full ? "true" : "false",
             s.draft_proposed, s.draft_accepted);
    return toJString(env, std::string(buf));
}

JNIEXPORT jstring JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeModelInfo(JNIEnv * env, jclass) {
    const auto info = g_engine.info();
    char buf[512];
    snprintf(buf, sizeof(buf),
             "{\"description\":\"%s\",\"nCtxTrain\":%d,\"nCtx\":%d,\"nParams\":%d,"
             "\"sizeBytes\":%llu,\"nVocab\":%d,\"nEmbd\":%d,\"nHead\":%d,\"nLayer\":%d,"
             "\"hasChatTemplate\":%s}",
             jsonEscape(info.description).c_str(), info.n_ctx_train, g_engine.nCtx(),
             info.n_params, static_cast<unsigned long long>(info.model_size_bytes), info.n_vocab,
             info.n_embd, info.n_head, info.n_layer,
             info.chat_template.empty() ? "false" : "true");
    return toJString(env, std::string(buf));
}

JNIEXPORT jlong JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeEstimateKvBytes(JNIEnv *, jclass, jint n_ctx, jboolean q8_kv) {
    return static_cast<jlong>(omni::estimateKvBytes(n_ctx, g_engine.info(), q8_kv == JNI_TRUE));
}

JNIEXPORT jboolean JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeSaveKvState(JNIEnv * env, jclass, jstring jpath) {
    if (!jpath) return JNI_FALSE;
    const char * p = env->GetStringUTFChars(jpath, nullptr);
    if (!p) return JNI_FALSE;
    const bool ok = g_engine.saveKvState(std::string(p));
    env->ReleaseStringUTFChars(jpath, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_omni_llm_core_LlamaBridge_nativeLoadKvState(JNIEnv * env, jclass, jstring jpath) {
    if (!jpath) return JNI_FALSE;
    const char * p = env->GetStringUTFChars(jpath, nullptr);
    if (!p) return JNI_FALSE;
    const bool ok = g_engine.loadKvState(std::string(p));
    env->ReleaseStringUTFChars(jpath, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

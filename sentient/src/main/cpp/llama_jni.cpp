// GMind's on-device answers: a small JNI over the pinned llama.cpp. CPU only; no network,
// no files written, no logging of prompts or output. One generate() at a time per handle
// (LlamaNative serializes calls).
#include <jni.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

struct Session {
    llama_model *model;
    llama_context *ctx;
    const llama_vocab *vocab;
    int n_ctx;
};

constexpr jint kBadInput = -3, kTooLong = -1, kDecodeFailed = -2;

std::once_flag backend_once;

void quiet(ggml_log_level, const char *, void *) {}

// Length of the longest prefix of `s` that ends on a complete UTF-8 character, so Java never
// receives half a character (tokens can split multi-byte characters such as "ç" or emoji).
size_t complete_utf8(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte: keep looking for the lead
        size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        return back >= need ? n : n - back;
    }
    return n;  // not valid UTF-8; pass it through rather than stall
}

// Returns false when Java asked to stop or threw.
bool emit(JNIEnv *env, jobject sink, jmethodID accept, const std::string &bytes) {
    jbyteArray chunk = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (chunk == nullptr) return false;
    env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
    jboolean go = env->CallBooleanMethod(sink, accept, chunk);
    env->DeleteLocalRef(chunk);
    return !env->ExceptionCheck() && go == JNI_TRUE;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_br_gabriel_sentient_LlamaNative_load(JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    if (jpath == nullptr || n_ctx < 256 || n_threads < 1) return 0;
    std::call_once(backend_once, [] {
        llama_log_set(quiet, nullptr);
        llama_backend_init();
    });
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return 0;
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    auto *session = new Session{model, ctx, llama_model_get_vocab(model), static_cast<int>(llama_n_ctx(ctx))};
    return reinterpret_cast<jlong>(session);
}

// Streams the continuation of `prompt` (UTF-8, special tokens parsed) to sink.accept(byte[]),
// in complete UTF-8 chunks, until end of generation, max_tokens, or accept() returns false.
// Returns the number of tokens generated, or kTooLong / kDecodeFailed / kBadInput.
extern "C" JNIEXPORT jint JNICALL
Java_br_gabriel_sentient_LlamaNative_generate(JNIEnv *env, jclass, jlong handle, jbyteArray jprompt,
                                               jint max_tokens, jfloat temperature, jint seed, jobject sink) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || jprompt == nullptr || sink == nullptr || max_tokens <= 0) return kBadInput;
    jclass type = env->GetObjectClass(sink);
    jmethodID accept = env->GetMethodID(type, "accept", "([B)Z");
    if (accept == nullptr) return kBadInput;
    jsize length = env->GetArrayLength(jprompt);
    std::string prompt(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(jprompt, 0, length, reinterpret_cast<jbyte *>(&prompt[0]));

    llama_memory_clear(llama_get_memory(s->ctx), true);
    int needed = -llama_tokenize(s->vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (needed <= 0) return kBadInput;
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    if (llama_tokenize(s->vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(), needed, true, true) < 0)
        return kBadInput;
    if (needed + max_tokens > s->n_ctx) return kTooLong;
    int batch = static_cast<int>(llama_n_batch(s->ctx));
    for (int i = 0; i < needed; i += batch) {
        int count = std::min(batch, needed - i);
        if (llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, count)) != 0) return kDecodeFailed;
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    std::string pending;
    jint generated = 0;
    bool go = true;
    while (go && generated < max_tokens) {
        llama_token token = llama_sampler_sample(sampler, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, token)) break;
        generated++;
        char piece[256];
        int n = llama_token_to_piece(s->vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) {
            pending.append(piece, static_cast<size_t>(n));
            size_t ready = complete_utf8(pending);
            if (ready > 0) {
                go = emit(env, sink, accept, pending.substr(0, ready));
                pending.erase(0, ready);
            }
        }
        if (go && llama_decode(s->ctx, llama_batch_get_one(&token, 1)) != 0) {
            generated = kDecodeFailed;
            break;
        }
    }
    if (go && generated >= 0 && !pending.empty()) emit(env, sink, accept, pending);
    llama_sampler_free(sampler);
    return generated;
}

extern "C" JNIEXPORT void JNICALL
Java_br_gabriel_sentient_LlamaNative_close(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

#include <jni.h>
#include <whisper.h>
#include "whisper_safety.h"
#include <algorithm>
#include <atomic>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

namespace {
using nottheomi::Wiped;
struct Engine {
    whisper_context *ctx = nullptr;
    std::string vad_path; // Bundled Silero model; empty disables VAD.
    std::atomic<bool> cancelled{false};
    std::mutex inference;
    ~Engine() { if (ctx) whisper_free(ctx); }
};
// Opaque monotonically allocated IDs are not raw pointers. Invalid/stale handles
// cannot dereference arbitrary memory; cancellation and close retain ownership.
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Engine>> registry;
jlong next_handle = 1;
std::once_flag quiet_once;
void quiet(ggml_log_level, const char *, void *) {} // No paths/audio/transcripts.

void fail(JNIEnv *env, const char *message) noexcept {
    if (env->ExceptionCheck()) return; // Preserve JVM allocation/array exceptions.
    jclass exception = env->FindClass("java/io/IOException");
    if (exception) {
        env->ThrowNew(exception, message);
        env->DeleteLocalRef(exception);
    }
}
std::shared_ptr<Engine> lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto entry = registry.find(handle);
    return entry == registry.end() ? nullptr : entry->second;
}
bool cancelled(void *data) {
    return static_cast<Engine *>(data)->cancelled.load(std::memory_order_acquire);
}
bool begin_encoder(whisper_context *, whisper_state *, void *data) {
    return !cancelled(data);
}
jstring empty(JNIEnv *env) { return env->NewString(nullptr, 0); }

// Java path -> UTF-8. False (with a pending exception) when missing or malformed.
bool utf8_path(JNIEnv *env, jstring name, std::string &out) {
    if (!name) { fail(env, "Missing Whisper model path"); return false; }
    const jsize size = env->GetStringLength(name);
    if (size == 0 || size > 4096) { fail(env, "Invalid Whisper model path"); return false; }
    Wiped<std::vector<uint16_t>> chars;
    chars.value.resize(size);
    env->GetStringRegion(name, 0, size, reinterpret_cast<jchar *>(chars.value.data()));
    if (env->ExceptionCheck()) return false;
    out = nottheomi::path_utf8(chars.value.data(), chars.value.size());
    return true;
}

// Only these languages; anything else is a caller error, not a model choice.
const char *language_code(JNIEnv *env, jstring language) {
    if (!language) return nullptr;
    const jsize size = env->GetStringLength(language);
    if (size < 2 || size > 4) return nullptr;
    jchar chars[4];
    env->GetStringRegion(language, 0, size, chars);
    if (env->ExceptionCheck()) return nullptr;
    const std::u16string value(reinterpret_cast<const char16_t *>(chars), size);
    if (value == u"pt") return "pt";
    if (value == u"en") return "en";
    if (value == u"auto") return "auto";
    return nullptr;
}

jlong open_file(JNIEnv *env, jstring name, jstring vad) {
    Wiped<std::string> path;
    if (!utf8_path(env, name, path.value)) return 0;
    std::string vad_path;
    if (vad && !utf8_path(env, vad, vad_path)) return 0;
    std::call_once(quiet_once, [] { whisper_log_set(quiet, nullptr); ggml_log_set(quiet, nullptr); });
    auto state = std::make_shared<Engine>();
    state->vad_path = std::move(vad_path);
    auto options = whisper_context_default_params();
    options.use_gpu = false;
    options.flash_attn = false;
    state->ctx = whisper_init_from_file_with_params(path.value.c_str(), options);
    if (!state->ctx) { fail(env, "Whisper model initialization failed"); return 0; }
    std::lock_guard<std::mutex> lock(registry_mutex);
    if (next_handle == std::numeric_limits<jlong>::max()) {
        fail(env, "Whisper handle capacity exceeded"); return 0;
    }
    const jlong handle = next_handle++;
    registry.emplace(handle, std::move(state));
    return handle;
}

jstring transcribe(JNIEnv *env, jlong handle, jshortArray samples, jint threads, jstring language) {
    auto state = lookup(handle);
    if (!state || !samples) { fail(env, "Invalid Whisper input boundary"); return nullptr; }
    const char *requested = language_code(env, language);
    if (!requested) { fail(env, "Unsupported Whisper language"); return nullptr; }
    const jsize count = env->GetArrayLength(samples);
    if (env->ExceptionCheck()) return nullptr;
    if (count > nottheomi::kMaxSamples) {
        fail(env, "Whisper input exceeds 30 seconds"); return nullptr;
    }
    std::lock_guard<std::mutex> lock(state->inference);
    if (cancelled(state.get()) || count == 0) return empty(env);
    // Never pin a Java array across inference. Caller retains and wipes its own
    // short[]; all native raw PCM copies are overwritten on every exit path.
    Wiped<std::vector<jshort>> pcm;
    Wiped<std::vector<float>> audio;
    pcm.value.resize(count);
    audio.value.resize(std::max<int>(count, nottheomi::kSampleRate), 0.0f);
    env->GetShortArrayRegion(samples, 0, count, pcm.value.data());
    if (env->ExceptionCheck()) return nullptr;
    double energy = 0;
    for (jsize i = 0; i < count; ++i) {
        const float value = pcm.value[i] / 32768.0f;
        audio.value[i] = value;
        energy += static_cast<double>(value) * value;
    }
    nottheomi::wipe(pcm.value);
    // 100ms minimum and RMS floor suppress digital silence/near-zero noise.
    // Not a claim that silence filtering eliminates all model hallucinations.
    if (count < 1600 || energy / count < 0.00000064 || cancelled(state.get())) return empty(env);
    auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = std::clamp<int>(threads, 1, 4);
    // A fixed language (Portuguese by default) is far more accurate than detecting
    // it per window on noisy wearable audio; "auto" stays available for mixed speech.
    // English-only (.en) weights keep the fixed English path.
    p.language = whisper_is_multilingual(state->ctx) ? requested : "en";
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.n_max_text_ctx = 0;
    p.no_timestamps = true;
    p.single_segment = false;
    p.print_realtime = p.print_progress = p.print_timestamps = p.print_special = false;
    p.debug_mode = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.temperature = p.temperature_inc = 0.0f;
    p.greedy.best_of = 1;
    p.no_speech_thold = 0.6f;
    p.logprob_thold = -1.0f;
    p.entropy_thold = 2.4f;
    // Full model audio context (not experimental shortened-context decoding).
    p.audio_ctx = 0;
    // Silero VAD (bundled, offline): Whisper decodes only the speech in the window,
    // which stops it skipping sentences in noise and skips silence quickly.
    p.vad = !state->vad_path.empty();
    p.vad_model_path = p.vad ? state->vad_path.c_str() : nullptr;
    p.abort_callback = cancelled;
    p.abort_callback_user_data = state.get();
    p.encoder_begin_callback = begin_encoder;
    p.encoder_begin_callback_user_data = state.get();
    const int result = whisper_full(state->ctx, p, audio.value.data(),
                                   static_cast<int>(audio.value.size()));
    nottheomi::wipe(audio.value);
    if (cancelled(state.get())) return empty(env); // Never publish a cancelled partial.
    if (result != 0) { fail(env, "Whisper inference failed"); return nullptr; }
    Wiped<std::string> text;
    const int segments = whisper_full_n_segments(state->ctx);
    for (int i = 0; i < segments; ++i) {
        // No extra no-speech filter: in wearable noise it dropped most real speech.
        // Whisper's own rule (no-speech AND low confidence) and VAD handle silence.
        const char *part = whisper_full_get_segment_text(state->ctx, i);
        if (!part) { fail(env, "Invalid Whisper text result"); return nullptr; }
        const size_t remaining = nottheomi::kMaxTextBytes - text.value.size();
        size_t length = 0;
        while (length <= remaining && part[length] != '\0') ++length;
        if (length > remaining) { fail(env, "Whisper text exceeds output limit"); return nullptr; }
        text.value.append(part, length);
    }
    if (cancelled(state.get())) return empty(env);
    Wiped<std::vector<uint16_t>> utf16;
    utf16.value = nottheomi::text_utf16(text.value);
    return env->NewString(reinterpret_cast<const jchar *>(utf16.value.data()),
                          static_cast<jsize>(utf16.value.size()));
}
} // namespace

// C++ exceptions must never unwind into the JVM. Errors deliberately contain no
// model paths, transcripts, audio or implementation exception messages.
extern "C" JNIEXPORT jlong JNICALL
Java_app_nottheomi_ai_WhisperNative_openFile(JNIEnv *env, jclass, jstring name, jstring vad) {
    try { return open_file(env, name, vad); }
    catch (const std::bad_alloc &) { fail(env, "Whisper memory allocation failed"); }
    catch (...) { fail(env, "Whisper model initialization failed"); }
    return 0;
}
extern "C" JNIEXPORT jstring JNICALL
Java_app_nottheomi_ai_WhisperNative_transcribe(JNIEnv *env, jclass, jlong handle,
                                            jshortArray samples, jint threads, jstring language) {
    try { return transcribe(env, handle, samples, threads, language); }
    catch (const std::bad_alloc &) { fail(env, "Whisper memory allocation failed"); }
    catch (...) { fail(env, "Whisper inference failed"); }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_app_nottheomi_ai_WhisperNative_cancel(JNIEnv *env, jclass, jlong handle) {
    try { if (auto state = lookup(handle)) state->cancelled.store(true, std::memory_order_release); }
    catch (...) { fail(env, "Whisper cancellation failed"); }
}
extern "C" JNIEXPORT void JNICALL
Java_app_nottheomi_ai_WhisperNative_close(JNIEnv *env, jclass, jlong handle) {
    try {
        std::shared_ptr<Engine> state;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            const auto entry = registry.find(handle);
            if (entry == registry.end()) return; // Idempotent close, including zero.
            state = entry->second;
            state->cancelled.store(true, std::memory_order_release);
            registry.erase(entry);
        }
        // Current inference owns a shared reference, so even a close/cancel race
        // cannot free its context underneath it. Wait for the active call.
        std::lock_guard<std::mutex> lock(state->inference);
    } catch (...) { fail(env, "Whisper close failed"); }
}

#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <utility>
#include <algorithm>
#include <cstdint>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "youraicqa_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// API compatibility: each wrapper picks the function name that exists in the bundled llama.h (SFINAE).
// If neither name exists, check llama.h and add a third variant.
namespace compat {

template <typename... Args>
auto load_model_dispatch(int, Args&&... args)
    -> decltype(llama_model_load_from_file(std::forward<Args>(args)...)) {
    return llama_model_load_from_file(std::forward<Args>(args)...);
}
template <typename... Args>
auto load_model_dispatch(long, Args&&... args)
    -> decltype(llama_load_model_from_file(std::forward<Args>(args)...)) {
    return llama_load_model_from_file(std::forward<Args>(args)...);
}
template <typename... Args>
auto load_model(Args&&... args) {
    return load_model_dispatch(0, std::forward<Args>(args)...);
}

template <typename... Args>
auto init_context_dispatch(int, Args&&... args)
    -> decltype(llama_init_from_model(std::forward<Args>(args)...)) {
    return llama_init_from_model(std::forward<Args>(args)...);
}
template <typename... Args>
auto init_context_dispatch(long, Args&&... args)
    -> decltype(llama_new_context_with_model(std::forward<Args>(args)...)) {
    return llama_new_context_with_model(std::forward<Args>(args)...);
}
template <typename... Args>
auto init_context(Args&&... args) {
    return init_context_dispatch(0, std::forward<Args>(args)...);
}

template <typename V, typename M>
auto is_eog_dispatch(int, V vocab, M /*model*/, llama_token tok)
    -> decltype(llama_vocab_is_eog(vocab, tok)) {
    return llama_vocab_is_eog(vocab, tok);
}
template <typename V, typename M>
auto is_eog_dispatch(long, V /*vocab*/, M model, llama_token tok)
    -> decltype(llama_token_is_eog(model, tok)) {
    return llama_token_is_eog(model, tok);
}
// Old API takes llama_model*, new one llama_vocab*; pass both and let each overload pick.
template <typename V, typename M>
auto is_eog(V vocab, M model, llama_token tok) {
    return is_eog_dispatch(0, vocab, model, tok);
}

template <typename V, typename M>
auto n_vocab_dispatch(int, V vocab, M /*model*/)
    -> decltype(llama_vocab_n_tokens(vocab)) {
    return llama_vocab_n_tokens(vocab);
}
template <typename V, typename M>
auto n_vocab_dispatch(long, V /*vocab*/, M model)
    -> decltype(llama_n_vocab(model)) {
    return llama_n_vocab(model);
}
// Vocab size is the first arg of llama_sampler_init_penalties; same vocab/model split as is_eog.
template <typename V, typename M>
auto n_vocab(V vocab, M model) {
    return n_vocab_dispatch(0, vocab, model);
}

// Clear the KV cache: llama_memory_clear in new llama.cpp, llama_kv_cache_clear in old.
template <typename C>
auto clear_kv_dispatch(int, C ctx) -> decltype(llama_memory_clear(llama_get_memory(ctx), true), void()) {
    llama_memory_clear(llama_get_memory(ctx), true);
}
template <typename C>
auto clear_kv_dispatch(long, C ctx) -> decltype(llama_kv_cache_clear(ctx), void()) {
    llama_kv_cache_clear(ctx);
}
template <typename C>
void clear_kv(C ctx) { clear_kv_dispatch(0, ctx); }

} // namespace compat

struct LlamaSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::atomic<bool> cancelRequested{false};
};

extern "C" JNIEXPORT void JNICALL
Java_com_youraicqa_app_local_LlamaNative_nativeBackendInit(JNIEnv*, jobject) {
    llama_backend_init();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_youraicqa_app_local_LlamaNative_nativeLoadModel(
    JNIEnv* env, jobject /*thiz*/, jstring jPath, jint nCtx, jint nThreads, jint nGpuLayers) {

    const char* pathChars = env->GetStringUTFChars(jPath, nullptr);
    std::string path(pathChars ? pathChars : "");
    if (pathChars) env->ReleaseStringUTFChars(jPath, pathChars);

    if (path.empty()) {
        LOGE("nativeLoadModel: пустой путь к файлу модели");
        return 0;
    }

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = nGpuLayers > 0 ? nGpuLayers : 0;

    llama_model* model = compat::load_model(path.c_str(), modelParams);
    if (!model) {
        LOGE("nativeLoadModel: не удалось загрузить модель из %s", path.c_str());
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = nCtx > 0 ? (uint32_t) nCtx : 2048u;
    ctxParams.n_threads = nThreads > 0 ? nThreads : 4;
    ctxParams.n_threads_batch = ctxParams.n_threads;
    // Prompt is fed in n_batch chunks; a larger batch hits an assert in llama.cpp.
    ctxParams.n_batch = 512;
    ctxParams.n_ubatch = 512;

    llama_context* ctx = compat::init_context(model, ctxParams);
    if (!ctx) {
        LOGE("nativeLoadModel: не удалось создать llama_context");
        llama_model_free(model);
        return 0;
    }

    auto* session = new LlamaSession();
    session->model = model;
    session->ctx = ctx;

    LOGI("nativeLoadModel: модель загружена, n_ctx=%u, n_threads=%d", ctxParams.n_ctx, ctxParams.n_threads);
    return reinterpret_cast<jlong>(session);
}

// Length of the valid UTF-8 prefix: a token may cut a multibyte char, and NewStringUTF needs valid input.
static size_t validUtf8Prefix(const std::string& s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }
        size_t need = 1;
        if ((c & 0x80) == 0) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        size_t have = n - (i - 1);
        return have >= need ? n : (i - 1);
    }
    return n;
}

// NewStringUTF rejects 4-byte UTF-8 (emoji) and NUL, so convert to UTF-16 surrogate pairs manually.
static jstring newJString(JNIEnv* env, const std::string& utf8) {
    std::vector<jchar> out;
    out.reserve(utf8.size());
    size_t i = 0, n = utf8.size();
    while (i < n) {
        unsigned char c = (unsigned char) utf8[i];
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c & 0xE0) == 0xC0 && i + 1 < n) { cp = ((c & 0x1F) << 6) | (utf8[i+1] & 0x3F); len = 2; }
        else if ((c & 0xF0) == 0xE0 && i + 2 < n) { cp = ((c & 0x0F) << 12) | ((utf8[i+1] & 0x3F) << 6) | (utf8[i+2] & 0x3F); len = 3; }
        else if ((c & 0xF8) == 0xF0 && i + 3 < n) { cp = ((c & 0x07) << 18) | ((utf8[i+1] & 0x3F) << 12) | ((utf8[i+2] & 0x3F) << 6) | (utf8[i+3] & 0x3F); len = 4; }
        i += len;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back((jchar) (0xD800 + (cp >> 10)));
            out.push_back((jchar) (0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back((jchar) cp);
        }
    }
    return env->NewString(out.data(), (jsize) out.size());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_youraicqa_app_local_LlamaNative_nativeGenerate(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jPrompt,
    jint maxTokens, jfloat temperature, jfloat topP, jfloat repeatPenalty, jobject callback) {

    auto* session = reinterpret_cast<LlamaSession*>(handle);
    if (!session || !session->model || !session->ctx) {
        LOGE("nativeGenerate: сессия не инициализирована");
        return newJString(env, "");
    }
    session->cancelRequested = false;

    const char* promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptChars ? promptChars : "");
    if (promptChars) env->ReleaseStringUTFChars(jPrompt, promptChars);

    const llama_vocab* vocab = llama_model_get_vocab(session->model);

    // Clear the KV cache: each request is a full prompt; stale cache overflowed n_ctx -> empty replies.
    compat::clear_kv(session->ctx);

    // Tokenize: first call with a null buffer returns the negative required size.
    int nPromptTokens = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                                         nullptr, 0, /*add_special*/ true, /*parse_special*/ true);
    if (nPromptTokens <= 0) {
        LOGE("nativeGenerate: не удалось оценить размер токенизации промпта");
        return newJString(env, "");
    }
    std::vector<llama_token> promptTokens(nPromptTokens);
    int actualPromptTokens = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                                             promptTokens.data(), (int32_t) promptTokens.size(),
                                             true, true);
    if (actualPromptTokens < 0) {
        LOGE("nativeGenerate: токенизация промпта не удалась");
        return newJString(env, "");
    }
    promptTokens.resize(actualPromptTokens);

    const uint32_t nCtx = llama_n_ctx(session->ctx);
    // Reserve room for a reply, otherwise the model hits the context limit after 1-2 tokens.
    const uint32_t replyReserve = nCtx > 512 ? 64 : 16;
    if ((uint32_t) promptTokens.size() + replyReserve >= nCtx) {
        // Kotlin trims history beforehand; overflow is returned as an explicit marker, not "".
        LOGE("nativeGenerate: промпт (%d токенов) не помещается в контекст (%u)",
             (int) promptTokens.size(), nCtx);
        return newJString(env, "\x01" "CTX_OVERFLOW");
    }

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        compat::n_vocab(vocab, session->model), 64, repeatPenalty, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass callbackClass = callback ? env->GetObjectClass(callback) : nullptr;
    jmethodID onTokenMethod = callbackClass
        ? env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V")
        : nullptr;

    std::string result;
    std::string pending;  // bytes of an incomplete UTF-8 char

    const int nBatch = (int) llama_n_batch(session->ctx);
    bool failed = false;
    for (size_t off = 0; off < promptTokens.size() && !failed; off += (size_t) nBatch) {
        if (session->cancelRequested.load()) { failed = true; break; }
        int n = (int) std::min((size_t) nBatch, promptTokens.size() - off);
        llama_batch b = llama_batch_get_one(promptTokens.data() + off, n);
        if (llama_decode(session->ctx, b) != 0) {
            LOGE("nativeGenerate: llama_decode(prefill) вернул ошибку на смещении %d", (int) off);
            failed = true;
        }
    }

    uint32_t nUsed = (uint32_t) promptTokens.size();
    llama_token newToken = 0;
    for (int i = 0; !failed && i < maxTokens; i++) {
        if (session->cancelRequested.load()) {
            LOGI("nativeGenerate: генерация отменена на %d токене", i);
            break;
        }

        newToken = llama_sampler_sample(sampler, session->ctx, -1);
        llama_sampler_accept(sampler, newToken);

        if (compat::is_eog(vocab, session->model, newToken)) {
            break;
        }

        char pieceBuf[256];
        int pieceLen = llama_token_to_piece(vocab, newToken, pieceBuf, sizeof(pieceBuf), 0, true);
        if (pieceLen > 0) {
            pending.append(pieceBuf, (size_t) pieceLen);
            size_t ok = validUtf8Prefix(pending);
            if (ok > 0) {
                std::string piece = pending.substr(0, ok);
                pending.erase(0, ok);
                result += piece;
                if (onTokenMethod) {
                    jstring jPiece = newJString(env, piece);
                    env->CallVoidMethod(callback, onTokenMethod, jPiece);
                    env->DeleteLocalRef(jPiece);
                    if (env->ExceptionCheck()) { env->ExceptionClear(); }
                }
            }
        }

        nUsed++;
        if (nUsed >= nCtx) {
            LOGI("nativeGenerate: достигнут предел контекста (%u), останавливаемся", nCtx);
            break;
        }

        llama_batch b = llama_batch_get_one(&newToken, 1);
        if (llama_decode(session->ctx, b) != 0) {
            LOGE("nativeGenerate: llama_decode вернул ошибку на шаге %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);
    return newJString(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_youraicqa_app_local_LlamaNative_nativeCancel(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<LlamaSession*>(handle);
    if (session) session->cancelRequested = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_youraicqa_app_local_LlamaNative_nativeFreeModel(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<LlamaSession*>(handle);
    if (!session) return;
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

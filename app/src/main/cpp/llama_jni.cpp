/**
 * llama_jni.cpp
 * JNI bridge between Android/Kotlin and llama.cpp.
 *
 * Design principles:
 *  - One ContextHandle per loaded model (opaque jlong pointer)
 *  - All inference runs on the caller's thread (Kotlin manages the coroutine dispatcher)
 *  - Token streaming via callback into Java/Kotlin
 *  - Hard resource cleanup — every native resource is freed explicitly
 *  - GPU layer count, context size, threads, batch are all runtime-configurable
 */

#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <string>
#include <vector>
#include <atomic>
#include <memory>
#include <functional>

// llama.cpp public C API
#include "llama.h"
#include "ggml.h"

#define LOG_TAG "KayanLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Internal context struct stored as opaque jlong handle
// ─────────────────────────────────────────────────────────────────────────────
struct KayanContext {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_sampler * sampler = nullptr;
    int32_t         n_ctx   = 0;
    std::atomic<bool> cancel{false};
};

// ─────────────────────────────────────────────────────────────────────────────
// GPU vendor detection via EGL
// Returns: "Adreno" | "Mali" | "PowerVR" | "Unknown"
// ─────────────────────────────────────────────────────────────────────────────
static std::string detect_gpu_vendor() {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) return "Unknown";

    EGLint major, minor;
    if (!eglInitialize(display, &major, &minor)) return "Unknown";

    // We need a surface to make the context current; use a 1x1 pbuffer
    EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs;
    eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);

    EGLint pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbufferAttribs);

    EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);

    eglMakeCurrent(display, surface, surface, context);

    std::string renderer;
    const char* r = (const char*)glGetString(0x1F01 /* GL_RENDERER */);
    if (r) renderer = r;

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    eglDestroySurface(display, surface);
    eglTerminate(display);

    LOGI("GL_RENDERER: %s", renderer.c_str());

    if (renderer.find("Adreno") != std::string::npos) return "Adreno";
    if (renderer.find("Mali")   != std::string::npos) return "Mali";
    if (renderer.find("PowerVR")!= std::string::npos) return "PowerVR";
    if (renderer.find("Apple")  != std::string::npos) return "Apple";
    return "Unknown";
}

// ─────────────────────────────────────────────────────────────────────────────
// Throw a Java exception from native code
// ─────────────────────────────────────────────────────────────────────────────
static void throw_java_exception(JNIEnv* env, const char* cls, const char* msg) {
    jclass clazz = env->FindClass(cls);
    if (clazz) env->ThrowNew(clazz, msg);
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: detectGpuVendor() → String
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jstring JNICALL
Java_com_kayan_x_engine_LlamaEngine_detectGpuVendor(JNIEnv* env, jobject /*thiz*/) {
    std::string vendor = detect_gpu_vendor();
    return env->NewStringUTF(vendor.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: initBackend() — must be called once before anything else
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_kayan_x_engine_LlamaEngine_initBackend(JNIEnv* /*env*/, jobject /*thiz*/) {
    llama_backend_init();
    LOGI("llama backend initialised");
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: loadModel(path, nCtx, nThreads, nBatch, nGpuLayers) → jlong handle
// Returns 0 on failure.
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jlong JNICALL
Java_com_kayan_x_engine_LlamaEngine_loadModel(
        JNIEnv*  env,
        jobject  /*thiz*/,
        jstring  jModelPath,
        jint     nCtx,
        jint     nThreads,
        jint     nBatch,
        jint     nGpuLayers)
{
    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("loadModel: path=%s ctx=%d threads=%d batch=%d gpu_layers=%d",
         modelPath, nCtx, nThreads, nBatch, nGpuLayers);

    // ── Model params ────────────────────────────────────────────────────────
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;

    llama_model* model = llama_load_model_from_file(modelPath, mparams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (!model) {
        LOGE("Failed to load model");
        throw_java_exception(env, "java/io/IOException", "Failed to load GGUF model");
        return 0L;
    }

    // ── Context params ───────────────────────────────────────────────────────
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx      = static_cast<uint32_t>(nCtx);
    cparams.n_threads  = static_cast<uint32_t>(nThreads);
    cparams.n_batch    = static_cast<uint32_t>(nBatch);

    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        llama_free_model(model);
        LOGE("Failed to create llama context");
        throw_java_exception(env, "java/io/IOException", "Failed to create llama context");
        return 0L;
    }

    // ── Greedy sampler (temperature, top-p etc. can be added here) ───────────
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    auto* kctx  = new KayanContext{model, ctx, sampler, nCtx};
    LOGI("Model loaded successfully (handle=0x%llx)", (unsigned long long)kctx);
    return reinterpret_cast<jlong>(kctx);
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: freeModel(handle)
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_kayan_x_engine_LlamaEngine_freeModel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (!handle) return;
    auto* kctx = reinterpret_cast<KayanContext*>(handle);
    if (kctx->sampler) llama_sampler_free(kctx->sampler);
    if (kctx->ctx)     llama_free(kctx->ctx);
    if (kctx->model)   llama_free_model(kctx->model);
    delete kctx;
    LOGI("Model freed (handle=0x%llx)", (unsigned long long)handle);
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: cancelInference(handle)
// Thread-safe — can be called from any thread.
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_kayan_x_engine_LlamaEngine_cancelInference(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (!handle) return;
    auto* kctx = reinterpret_cast<KayanContext*>(handle);
    kctx->cancel.store(true, std::memory_order_relaxed);
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: infer(handle, prompt, maxTokens, temperature, callback) → String
//
// The `callback` object must implement:
//   interface TokenCallback { fun onToken(token: String): Boolean }
// Return false from onToken to stop generation early.
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jstring JNICALL
Java_com_kayan_x_engine_LlamaEngine_infer(
        JNIEnv*  env,
        jobject  /*thiz*/,
        jlong    handle,
        jstring  jPrompt,
        jint     maxTokens,
        jfloat   temperature,
        jobject  callback)          // nullable — if null, returns full string
{
    if (!handle) {
        throw_java_exception(env, "java/lang/IllegalStateException", "Model not loaded");
        return nullptr;
    }
    auto* kctx = reinterpret_cast<KayanContext*>(handle);
    kctx->cancel.store(false, std::memory_order_relaxed);

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string promptStr(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    // ── Tokenise ─────────────────────────────────────────────────────────────
    const llama_vocab* vocab = llama_model_get_vocab(kctx->model);
    const bool add_bos = llama_vocab_get_add_bos(vocab);

    std::vector<llama_token> tokens(promptStr.size() + 64);
    int n_tokens = llama_tokenize(
        vocab, promptStr.c_str(), (int32_t)promptStr.size(),
        tokens.data(), (int32_t)tokens.size(),
        add_bos, /*special=*/true
    );

    if (n_tokens < 0) {
        // Buffer too small; resize and retry
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            vocab, promptStr.c_str(), (int32_t)promptStr.size(),
            tokens.data(), (int32_t)tokens.size(),
            add_bos, true
        );
    }
    tokens.resize(n_tokens);

    LOGD("Prompt tokens: %d", n_tokens);

    // ── Sampler temperature (rebuild chain each call to respect parameter) ────
    llama_sampler_free(kctx->sampler);
    kctx->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(kctx->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(kctx->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ── KV cache eval ─────────────────────────────────────────────────────────
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(kctx->ctx, batch) != 0) {
        throw_java_exception(env, "java/io/IOException", "llama_decode failed on prompt");
        return nullptr;
    }

    // ── Callback lookup ───────────────────────────────────────────────────────
    jclass   cbClass  = nullptr;
    jmethodID cbMethod = nullptr;
    if (callback) {
        cbClass  = env->GetObjectClass(callback);
        cbMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    }

    // ── Generation loop ───────────────────────────────────────────────────────
    std::string result;
    result.reserve(1024);

    char piece_buf[256];
    const llama_token eos = llama_vocab_eos(vocab);

    for (int i = 0; i < maxTokens; ++i) {
        if (kctx->cancel.load(std::memory_order_relaxed)) {
            LOGI("Inference cancelled at token %d", i);
            break;
        }

        llama_token token = llama_sampler_sample(kctx->sampler, kctx->ctx, -1);
        if (token == eos) break;

        // Decode token to text
        int32_t n = llama_token_to_piece(vocab, token, piece_buf, sizeof(piece_buf), 0, true);
        if (n < 0) break;
        std::string piece(piece_buf, n);
        result += piece;

        // Stream via callback if provided
        if (callback && cbMethod) {
            jstring jPiece = env->NewStringUTF(piece.c_str());
            jboolean keepGoing = env->CallBooleanMethod(callback, cbMethod, jPiece);
            env->DeleteLocalRef(jPiece);
            if (!keepGoing || env->ExceptionCheck()) break;
        }

        // Prepare next decode
        llama_batch next = llama_batch_get_one(&token, 1);
        if (llama_decode(kctx->ctx, next) != 0) break;
    }

    llama_kv_cache_clear(kctx->ctx);  // Reset KV cache so next call is clean
    return env->NewStringUTF(result.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: getModelInfo(handle) → String (JSON)
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jstring JNICALL
Java_com_kayan_x_engine_LlamaEngine_getModelInfo(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    if (!handle) return env->NewStringUTF("{}");
    auto* kctx = reinterpret_cast<KayanContext*>(handle);

    const llama_vocab* vocab = llama_model_get_vocab(kctx->model);
    int32_t n_vocab   = llama_vocab_n_tokens(vocab);
    int32_t n_ctx_max = llama_n_ctx(kctx->ctx);
    int64_t n_params  = llama_model_n_params(kctx->model);
    size_t  mem_bytes = llama_model_size(kctx->model);

    char buf[512];
    snprintf(buf, sizeof(buf),
        "{\"n_vocab\":%d,\"n_ctx\":%d,\"n_params\":%lld,\"model_size_mb\":%.1f}",
        n_vocab, n_ctx_max, (long long)n_params, mem_bytes / 1048576.0);
    return env->NewStringUTF(buf);
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: benchmarkTokensPerSec(handle, testPrompt, nTokens) → float
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jfloat JNICALL
Java_com_kayan_x_engine_LlamaEngine_benchmarkTokensPerSec(
        JNIEnv*  env,
        jobject  /*thiz*/,
        jlong    handle,
        jstring  jPrompt,
        jint     nTokens)
{
    if (!handle) return 0.0f;
    auto* kctx = reinterpret_cast<KayanContext*>(handle);

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string promptStr(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    const llama_vocab* vocab = llama_model_get_vocab(kctx->model);
    const bool add_bos = llama_vocab_get_add_bos(vocab);

    std::vector<llama_token> tokens(promptStr.size() + 64);
    int n = llama_tokenize(vocab, promptStr.c_str(), (int32_t)promptStr.size(),
                           tokens.data(), (int32_t)tokens.size(), add_bos, true);
    if (n < 0) return 0.0f;
    tokens.resize(n);

    // Rebuild default sampler for benchmark
    llama_sampler* bench_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(bench_sampler, llama_sampler_init_greedy());

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(kctx->ctx, batch) != 0) {
        llama_sampler_free(bench_sampler);
        return 0.0f;
    }

    // Time the generation of `nTokens` tokens
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);

    const llama_token eos = llama_vocab_eos(vocab);
    int generated = 0;
    for (int i = 0; i < nTokens; ++i) {
        llama_token tok = llama_sampler_sample(bench_sampler, kctx->ctx, -1);
        if (tok == eos) break;
        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(kctx->ctx, nb) != 0) break;
        ++generated;
    }

    clock_gettime(CLOCK_MONOTONIC, &t1);
    double elapsed = (t1.tv_sec - t0.tv_sec) + (t1.tv_nsec - t0.tv_nsec) * 1e-9;

    llama_sampler_free(bench_sampler);
    llama_kv_cache_clear(kctx->ctx);

    return (generated > 0 && elapsed > 0.0) ? (float)(generated / elapsed) : 0.0f;
}

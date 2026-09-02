#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cstring>
#include "llama.h"
#include "common.h"

#define LOG_TAG "kayan_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_sampler = nullptr;

static bool load_model_internal(const char* path) {
    if (g_model) {
        if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
        if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
        llama_model_free(g_model);
        g_model = nullptr;
    }

    LOGI("Loading model from %s", path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 32;

    g_model = llama_model_load_from_file(path, mparams);
    if (!g_model) {
        LOGE("Failed to load model");
        return false;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    cparams.n_batch = 512;
    cparams.n_threads = 6;
    cparams.n_threads_batch = 6;

    // b4600 API - new name
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return false;
    }

    // Sampler chain for b4600
    auto sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(0));

    LOGI("Model loaded OK - 4096/6/512/32");
    return true;
}

static std::string generate_internal(const char* prompt_c) {
    if (!g_ctx || !g_model) {
        return "Model not loaded - اختر GGUF من Downloads أولا";
    }

    std::string prompt(prompt_c);
    std::string result;

    // Tokenize prompt
    std::vector<llama_token> tokens;
    tokens.resize(prompt.size() + 512);
    int n = llama_tokenize(g_model, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(g_model, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n);

    // Evaluate prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to decode prompt");
        return "Decode failed";
    }

    // Generation loop
    llama_token new_token;
    for (int i = 0; i < 512; i++) {
        new_token = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_token_is_eog(g_model, new_token)) break;

        char buf[256];
        int len = llama_token_to_piece(g_model, new_token, buf, sizeof(buf), 0, true);
        if (len > 0) result.append(buf, len);

        llama_batch b = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, b) != 0) break;
    }

    return result;
}

// ---- JNI for com.kayan.x.LlamaBridge ----
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kayan_x_LlamaBridge_loadModel(JNIEnv* env, jobject, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, 0);
    bool ok = load_model_internal(path);
    env->ReleaseStringUTFChars(modelPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kayan_x_LlamaBridge_generate(JNIEnv* env, jobject, jstring prompt) {
    const char* p = env->GetStringUTFChars(prompt, 0);
    std::string res = generate_internal(p);
    env->ReleaseStringUTFChars(prompt, p);
    return env->NewStringUTF(res.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_kayan_x_LlamaBridge_unloadModel(JNIEnv*, jobject) {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    LOGI("Model unloaded");
}

// ---- JNI for com.kayan.agent.LlamaPlugin (Capacitor legacy) ----
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kayan_agent_LlamaPlugin_loadModel(JNIEnv* env, jobject, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, 0);
    bool ok = load_model_internal(path);
    env->ReleaseStringUTFChars(modelPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kayan_agent_LlamaPlugin_generate(JNIEnv* env, jobject, jstring prompt) {
    const char* p = env->GetStringUTFChars(prompt, 0);
    std::string res = generate_internal(p);
    env->ReleaseStringUTFChars(prompt, p);
    return env->NewStringUTF(res.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_kayan_agent_LlamaPlugin_unloadModel(JNIEnv*, jobject) {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    LOGI("Model unloaded");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kayan_x_LlamaBridge_getVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("kayan_llama b4600 fixed - no OpenGL");
}

// FINAL b4600 - VOCAB FIX for llama_tokenize / token_to_piece / is_eog
// This fixes Build #15 error: cannot convert llama_model* to llama_vocab*
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "KayanLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static const llama_vocab* g_vocab = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_kayan_x_engine_LlamaEngine_loadModel(JNIEnv* env, jobject, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);
    
    llama_model_params mparams = llama_model_default_params();
    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    
    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }
    
    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) {
        LOGE("Failed to get vocab from model");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 512;
    
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to init context");
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        return JNI_FALSE;
    }
    
    LOGI("Model loaded with vocab %p", g_vocab);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_kayan_x_engine_LlamaEngine_unloadModel(JNIEnv*, jobject) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    LOGI("Model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_com_kayan_x_engine_LlamaEngine_isModelLoaded(JNIEnv*, jobject) {
    return (g_model && g_ctx && g_vocab) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_kayan_x_engine_LlamaEngine_generate(JNIEnv* env, jobject, jstring prompt_jstr, jint maxTokens) {
    if (!g_model || !g_ctx || !g_vocab) {
        return env->NewStringUTF("Error: Model not loaded");
    }
    
    const char* prompt_c = env->GetStringUTFChars(prompt_jstr, nullptr);
    std::string prompt(prompt_c);
    env->ReleaseStringUTFChars(prompt_jstr, prompt_c);
    
    // FIX 1: b4600 - llama_tokenize needs vocab, not model
    std::vector<llama_token> tokens(prompt.size() + 128);
    int n = llama_tokenize(g_vocab, prompt.c_str(), (int32_t)prompt.size(), tokens.data(), (int32_t)tokens.size(), true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(g_vocab, prompt.c_str(), (int32_t)prompt.size(), tokens.data(), (int32_t)tokens.size(), true, true);
    }
    tokens.resize(n);
    
    if (tokens.empty()) {
        return env->NewStringUTF("Error: tokenize failed");
    }
    
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("decode prompt failed");
        return env->NewStringUTF("Error: decode failed");
    }
    
    // b4600 sampler chain
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    
    std::string result;
    
    for (int i = 0; i < maxTokens; ++i) {
        llama_token new_token = llama_sampler_sample(sampler, g_ctx, -1);
        
        // FIX 2: b4600 - is_eog needs vocab, and new name is llama_vocab_is_eog
        if (llama_vocab_is_eog(g_vocab, new_token)) {
            break;
        }
        
        // FIX 3: b4600 - token_to_piece needs vocab
        char buf[256];
        int len = llama_token_to_piece(g_vocab, new_token, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
        }
        
        llama_batch b = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, b) != 0) {
            LOGE("decode token failed");
            break;
        }
    }
    
    llama_sampler_free(sampler);
    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

} // extern C

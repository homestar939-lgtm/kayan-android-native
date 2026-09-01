package com.kayan.x.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Kotlin engine facade over the native llama.cpp JNI layer.
 *
 * Threading contract:
 *  - All JNI calls go through [Dispatchers.Default] (bound CPU work pool).
 *  - [cancelInference] is thread-safe and can be called from any thread.
 *  - [loadModel] / [freeModel] are mutually exclusive (caller must not call both
 *    concurrently; ViewModel enforces this via state machine).
 *
 * APK size contract:
 *  - The .so is loaded via System.loadLibrary — built from llama.cpp sources via NDK.
 *  - The GGUF model file is NEVER embedded in the APK.
 *    It is provided at runtime via SAF URI → content:// → resolved native path.
 */
class LlamaEngine {

    // ── Native handle — 0 means "no model loaded" ────────────────────────────
    @Volatile private var nativeHandle: Long = 0L

    // ── Public state ─────────────────────────────────────────────────────────
    val isModelLoaded: Boolean get() = nativeHandle != 0L

    /** Wall-clock milliseconds for the last [loadModel] call. */
    var lastLoadTimeMs: Long = 0L
        private set

    private var activeConfig: InferenceConfig? = null

    fun currentConfigSnapshot(): String =
        activeConfig?.toString() ?: "no model"

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    init {
        // initBackend() must run before any model load
        initBackend()
    }

    /**
     * Load a GGUF model from [modelPath] (absolute path resolved from SAF URI).
     * Throws [IOException] on failure. Safe to call from a coroutine.
     */
    suspend fun loadModel(modelPath: String, config: InferenceConfig) {
        withContext(Dispatchers.Default) {
            if (isModelLoaded) freeModel()

            Timber.i("Loading model: $modelPath")
            Timber.i("Config: $config")

            val t0 = System.currentTimeMillis()
            val handle = loadModel(
                modelPath,
                config.nCtx,
                config.nThreads,
                config.nBatch,
                config.nGpuLayers
            )
            lastLoadTimeMs = System.currentTimeMillis() - t0

            if (handle == 0L) {
                // loadModel() already threw via JNI — this is a safety fallback
                error("Native model load returned null handle")
            }
            nativeHandle = handle
            activeConfig = config
            Timber.i("Model loaded in ${lastLoadTimeMs}ms (handle=0x${handle.toString(16)})")
        }
    }

    /** Release all native resources. Safe to call even if no model is loaded. */
    suspend fun freeModel() {
        withContext(Dispatchers.Default) {
            val h = nativeHandle
            if (h != 0L) {
                nativeHandle = 0L
                activeConfig = null
                freeModel(h)
                Timber.i("Model freed")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inference
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run inference and return the complete generated string.
     * Suspends on [Dispatchers.Default].
     */
    suspend fun infer(
        prompt: String,
        maxTokens: Int  = activeConfig?.maxTokens ?: 512,
        temperature: Float = activeConfig?.temperature ?: 0.7f
    ): String = withContext(Dispatchers.Default) {
        checkHandle()
        infer(nativeHandle, prompt, maxTokens, temperature, null)
            ?: error("Native infer() returned null")
    }

    /**
     * Streaming inference — [onToken] is called for each token fragment.
     * Return false from [onToken] to stop generation.
     * Suspends on [Dispatchers.Default].
     */
    suspend fun inferStreaming(
        prompt: String,
        maxTokens: Int     = activeConfig?.maxTokens ?: 512,
        temperature: Float = activeConfig?.temperature ?: 0.7f,
        onToken: (String) -> Boolean
    ): String = withContext(Dispatchers.Default) {
        checkHandle()
        val cb = object : TokenCallback {
            override fun onToken(token: String): Boolean = onToken(token)
        }
        infer(nativeHandle, prompt, maxTokens, temperature, cb)
            ?: error("Native infer() returned null")
    }

    /** Thread-safe cancel — can be called from main thread. */
    fun cancelInference() {
        if (isModelLoaded) cancelInference(nativeHandle)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Benchmark
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun benchmarkTokensPerSec(prompt: String, nTokens: Int = 64): Float =
        withContext(Dispatchers.Default) {
            checkHandle()
            benchmarkTokensPerSec(nativeHandle, prompt, nTokens)
        }

    suspend fun getModelInfo(): String = withContext(Dispatchers.Default) {
        if (!isModelLoaded) return@withContext "{}"
        getModelInfo(nativeHandle)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkHandle() {
        check(isModelLoaded) { "No model loaded — call loadModel() first" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JNI declarations
    // ─────────────────────────────────────────────────────────────────────────

    /** Token streaming callback — implemented as anonymous object in [inferStreaming]. */
    interface TokenCallback {
        /** Return true to continue generation, false to stop. */
        fun onToken(token: String): Boolean
    }

    private external fun initBackend()
    private external fun loadModel(
        path: String, nCtx: Int, nThreads: Int, nBatch: Int, nGpuLayers: Int
    ): Long
    private external fun freeModel(handle: Long)
    private external fun cancelInference(handle: Long)
    private external fun infer(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float,
        callback: TokenCallback?
    ): String?
    private external fun getModelInfo(handle: Long): String
    private external fun benchmarkTokensPerSec(
        handle: Long, prompt: String, nTokens: Int
    ): Float

    companion object {
        init {
            System.loadLibrary("kayan_llama")
        }

        /**
         * Query GPU vendor string via EGL — called by [DeviceProfiler].
         * Static because it does not need a model handle.
         */
        @JvmStatic
        external fun detectGpuVendor(): String
    }
}

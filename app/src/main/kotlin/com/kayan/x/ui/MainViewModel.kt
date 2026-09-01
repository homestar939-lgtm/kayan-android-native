package com.kayan.x.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kayan.x.agent.AgentOrchestrator
import com.kayan.x.agent.AgentStep
import com.kayan.x.engine.InferenceConfig
import com.kayan.x.engine.LlamaEngine
import com.kayan.x.engine.ModelPreset
import com.kayan.x.engine.profiler.BenchmarkRunner
import com.kayan.x.engine.profiler.DeviceProfiler
import com.kayan.x.files.PersistedUriStore
import com.kayan.x.files.SafFileManager
import com.kayan.x.model.ModelInfo
import com.kayan.x.model.ModelManager
import com.kayan.x.safety.ConfirmationPolicy
import com.kayan.x.safety.PathGuard
import com.kayan.x.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Infrastructure ────────────────────────────────────────────────────────
    private val ctx            = application.applicationContext
    private val engine         = LlamaEngine()
    private val modelManager   = ModelManager(ctx)
    private val uriStore       = PersistedUriStore(ctx)
    private val saf            = SafFileManager(ctx, uriStore)
    private val pathGuard      = PathGuard(uriStore)
    private val toolRegistry   = ToolRegistry(pathGuard, saf)
    private val profiler       = DeviceProfiler(ctx)
    private val benchmarkRunner = BenchmarkRunner(ctx, engine)

    private var orchestrator: AgentOrchestrator? = null
    private var agentJob: Job? = null

    // ── UI state ─────────────────────────────────────────────────────────────

    data class UiState(
        // Model
        val registeredModels: List<ModelInfo> = emptyList(),
        val activeModel: ModelInfo? = null,
        val modelLoadState: ModelLoadState = ModelLoadState.NotLoaded,
        // Config
        val currentPreset: ModelPreset = ModelPreset.SIZE_3B,
        val inferenceConfig: InferenceConfig? = null,
        val deviceProfile: DeviceProfiler.DeviceProfile? = null,
        // Chat
        val messages: List<ChatMessage> = emptyList(),
        val agentRunning: Boolean = false,
        val pendingConfirmation: PendingConfirmationUi? = null,
        // Benchmark
        val benchmarkResult: BenchmarkRunner.BenchmarkResult? = null,
        val benchmarkRunning: Boolean = false,
        // Roots
        val registeredRoots: Map<String, String> = emptyMap()  // name → uri string
    )

    sealed class ModelLoadState {
        object NotLoaded : ModelLoadState()
        data class Loading(val modelName: String) : ModelLoadState()
        data class Loaded(val modelName: String, val loadMs: Long) : ModelLoadState()
        data class Failed(val error: String) : ModelLoadState()
    }

    data class ChatMessage(
        val id: Long = System.currentTimeMillis(),
        val role: Role,
        val content: String,
        val agentSteps: List<AgentStep> = emptyList()
    ) {
        enum class Role { USER, AGENT, SYSTEM }
    }

    data class PendingConfirmationUi(
        val toolName: String,
        val params: Map<String, Any>,
        val riskLevel: ConfirmationPolicy.OperationRisk,
        val onConfirm: () -> Unit,
        val onDeny: () -> Unit
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            val profile = profiler.profile()
            val config  = profiler.recommendConfig(ModelPreset.SIZE_3B, profile)
            _uiState.value = _uiState.value.copy(
                deviceProfile    = profile,
                inferenceConfig  = config,
                registeredModels = modelManager.listModels(),
                registeredRoots  = saf.getRegisteredRoots().mapValues { it.value.toString() }
            )
            Timber.i("DeviceProfiler: $profile")
            Timber.i("Auto config: $config")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Model management
    // ─────────────────────────────────────────────────────────────────────────

    fun onModelPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val info = modelManager.registerModel(uri)
                _uiState.value = _uiState.value.copy(
                    registeredModels = modelManager.listModels()
                )
                postSystemMessage("تم تسجيل النموذج: ${info.displayName} (${info.sizeLabel})")
            } catch (e: Exception) {
                postSystemMessage("فشل تسجيل النموذج: ${e.message}")
            }
        }
    }

    fun loadModel(info: ModelInfo) {
        viewModelScope.launch {
            val config = _uiState.value.inferenceConfig ?: return@launch
            _uiState.value = _uiState.value.copy(
                modelLoadState = ModelLoadState.Loading(info.displayName)
            )
            try {
                val nativePath = modelManager.resolveNativePath(Uri.parse(info.uri))
                engine.loadModel(nativePath, config)
                _uiState.value = _uiState.value.copy(
                    modelLoadState = ModelLoadState.Loaded(info.displayName, engine.lastLoadTimeMs),
                    activeModel    = info
                )
                orchestrator = AgentOrchestrator(engine, toolRegistry)
                postSystemMessage("النموذج جاهز: ${info.displayName} (حُمِّل في ${engine.lastLoadTimeMs}ms)")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    modelLoadState = ModelLoadState.Failed(e.message ?: "خطأ غير معروف")
                )
                postSystemMessage("فشل تحميل النموذج: ${e.message}")
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            engine.freeModel()
            orchestrator = null
            _uiState.value = _uiState.value.copy(
                modelLoadState = ModelLoadState.NotLoaded,
                activeModel    = null
            )
            postSystemMessage("تم تفريغ النموذج.")
        }
    }

    fun removeModel(uri: String) {
        modelManager.removeModel(uri)
        if (_uiState.value.activeModel?.uri == uri) {
            viewModelScope.launch { unloadModel() }
        }
        _uiState.value = _uiState.value.copy(
            registeredModels = modelManager.listModels()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Preset & config
    // ─────────────────────────────────────────────────────────────────────────

    fun applyPreset(preset: ModelPreset) {
        val profile = _uiState.value.deviceProfile ?: return
        val config  = profiler.recommendConfig(preset, profile)
        _uiState.value = _uiState.value.copy(
            currentPreset   = preset,
            inferenceConfig = config
        )
    }

    fun applyManualConfig(
        nCtx: Int? = null, nThreads: Int? = null,
        nBatch: Int? = null, nGpuLayers: Int? = null,
        temperature: Float? = null, maxTokens: Int? = null
    ) {
        val base = _uiState.value.inferenceConfig ?: return
        val updated = profiler.applyUserOverride(
            base, nCtx, nThreads, nBatch, nGpuLayers, temperature, maxTokens
        )
        _uiState.value = _uiState.value.copy(inferenceConfig = updated)
    }

    fun resetToAutoConfig() {
        val profile = _uiState.value.deviceProfile ?: return
        val preset  = _uiState.value.currentPreset
        val config  = profiler.recommendConfig(preset, profile)
        _uiState.value = _uiState.value.copy(inferenceConfig = config)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SAF roots
    // ─────────────────────────────────────────────────────────────────────────

    fun registerRoot(name: String, treeUri: Uri) {
        saf.registerRoot(name, treeUri)
        _uiState.value = _uiState.value.copy(
            registeredRoots = saf.getRegisteredRoots().mapValues { it.value.toString() }
        )
        postSystemMessage("تم ربط المجلد '$name'.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Agent chat
    // ─────────────────────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (!engine.isModelLoaded) {
            postSystemMessage("لم يُحمَّل أي نموذج بعد. اختر ملف GGUF من شاشة النموذج.")
            return
        }
        if (_uiState.value.agentRunning) return

        appendMessage(ChatMessage(role = ChatMessage.Role.USER, content = text))

        agentJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(agentRunning = true)
            val orc = orchestrator ?: return@launch
            try {
                val answer = orc.run(text) { pending ->
                    // Surface confirmation to UI and suspend until user responds
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        _uiState.value = _uiState.value.copy(
                            pendingConfirmation = PendingConfirmationUi(
                                toolName  = pending.toolName,
                                params    = pending.params,
                                riskLevel = pending.riskLevel,
                                onConfirm = {
                                    viewModelScope.launch {
                                        pending.resume()
                                        _uiState.value = _uiState.value.copy(pendingConfirmation = null)
                                        cont.resume(Unit) {}
                                    }
                                },
                                onDeny = {
                                    _uiState.value = _uiState.value.copy(pendingConfirmation = null)
                                    cont.cancel()
                                }
                            )
                        )
                    }
                }
                appendMessage(ChatMessage(
                    role         = ChatMessage.Role.AGENT,
                    content      = answer,
                    agentSteps   = orc.agentState.value?.history ?: emptyList()
                ))
            } catch (_: CancellationException) {
                appendMessage(ChatMessage(role = ChatMessage.Role.SYSTEM, content = "تم إلغاء المهمة."))
            } catch (e: Exception) {
                appendMessage(ChatMessage(role = ChatMessage.Role.SYSTEM, content = "خطأ: ${e.message}"))
            } finally {
                _uiState.value = _uiState.value.copy(agentRunning = false, pendingConfirmation = null)
            }
        }
    }

    fun cancelAgent() {
        agentJob?.cancel()
        engine.cancelInference()
        _uiState.value = _uiState.value.copy(agentRunning = false, pendingConfirmation = null)
    }

    fun clearChat() {
        _uiState.value = _uiState.value.copy(messages = emptyList())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Benchmark
    // ─────────────────────────────────────────────────────────────────────────

    fun runBenchmark() {
        if (!engine.isModelLoaded || _uiState.value.benchmarkRunning) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(benchmarkRunning = true)
            try {
                val result = benchmarkRunner.run()
                _uiState.value = _uiState.value.copy(benchmarkResult = result)
                postSystemMessage(result.summary())
            } catch (e: Exception) {
                postSystemMessage("فشل الاختبار: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(benchmarkRunning = false)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun appendMessage(msg: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + msg
        )
    }

    private fun postSystemMessage(text: String) {
        appendMessage(ChatMessage(role = ChatMessage.Role.SYSTEM, content = text))
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { engine.freeModel() }
    }
}

package com.edgevoice.agent.executor.local

import android.content.Context
import com.edgevoice.agent.executor.ExecutorEvent
import com.edgevoice.agent.executor.ExecutorMode
import com.edgevoice.agent.executor.IAgentExecutor
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地端侧 SLM 执行器：MediaPipe LLM Inference（tasks-genai 0.10.14）。
 *
 * 诚实的能力边界：端侧 SLM 是**纯文本**模型（1B~3B），不具备 Realtime 音频理解能力。
 * 输入路径：上层 ASR 转写文本 -> [sendUserText] -> SLM 流式生成。
 * 音频帧 [sendAudioFrame] 被忽略（音频管道仍工作，供 VAD/UI 律动用）。
 *
 * API 对齐说明（0.10.14 实测签名）：
 *  - 流式回调 ProgressListener<String> 挂在 LlmInferenceOptions.Builder 上，非每次调用传入；
 *  - 无 cancelGenerateResponseAsync：打断 = 置 [interrupted] 丢弃后续 token + 立即发 TurnComplete。
 */
class OnDeviceLocalExecutor(
    private val context: Context,
    private val modelPath: String,
    private val maxTokens: Int = 1024,
    private val temperature: Float = 0.7f,
    private val systemPrompt: String = "你是端侧语音助手，回答简洁。",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : IAgentExecutor {

    override val mode = ExecutorMode.ON_DEVICE_LOCAL

    /** 端侧纯文本 SLM：无多模态输入通道（视觉帧只走 V2 本地帧环）。 */
    override val supportsVideoInput: Boolean = false

    private val _events = MutableSharedFlow<ExecutorEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ExecutorEvent> = _events

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready

    private var llm: LlmInference? = null
    @Volatile private var handover: String? = null
    @Volatile private var pendingContext: String? = null
    private val interrupted = AtomicBoolean(false)

    override suspend fun prepare(handoverContext: String?) = withContext(Dispatchers.IO) {
        if (_ready.value) return@withContext
        handover = handoverContext
        require(File(modelPath).exists()) { "端侧模型文件不存在: $modelPath" }
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setTemperature(temperature)
            .setResultListener { partial, done ->
                if (interrupted.get()) return@setResultListener
                if (!partial.isNullOrEmpty()) _events.tryEmit(ExecutorEvent.TextDelta(partial))
                if (done) _events.tryEmit(ExecutorEvent.TurnComplete)
            }
            .setErrorListener { err ->
                _events.tryEmit(ExecutorEvent.Error(RuntimeException(err?.message ?: "SLM error")))
            }
            .build()
        llm = LlmInference.createFromOptions(context, options)
        _ready.value = true
        _events.tryEmit(ExecutorEvent.Ready)
    }

    override fun sendAudioFrame(pcm: ByteBuffer) {
        // 本地 SLM 为纯文本模型：音频路径不接入推理，由上层 ASR 转文本后走 sendUserText
    }

    override fun sendVideoFrame(jpeg: ByteArray) {
        // 端侧 SLM 无多模态输入通道：视觉帧只进入本地帧环（V2），不上行
    }

    override fun sendUserText(text: String) {
        val engine = llm ?: return
        interrupted.set(false)

        val prompt = buildString {
            append(systemPrompt)
            handover?.let {
                append("\n\n【会话上下文摘要】").append(it)
                handover = null // 摘要只注入首轮，避免每轮重复占用上下文窗口
            }
            pendingContext?.let {
                append("\n\n").append(it)
                pendingContext = null // 系统级上下文用一次即清
            }
            append("\n\n用户：").append(text).append("\n助手：")
        }
        engine.generateResponseAsync(prompt)
    }

    override fun injectContext(text: String) {
        // 端侧无 system 角色：暂存为下一条 prompt 的前置上下文块
        pendingContext = text
    }

    override fun interrupt() {
        // 0.10.14 无原生取消：丢弃后续 token 并立即向上宣告本轮结束
        interrupted.set(true)
        _events.tryEmit(ExecutorEvent.TurnComplete)
    }

    override fun shutdown() {
        interrupted.set(true)
        runCatching { llm?.close() }
        llm = null
        _ready.value = false
    }
}

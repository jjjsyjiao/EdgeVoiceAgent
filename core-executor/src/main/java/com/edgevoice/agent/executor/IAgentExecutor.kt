package com.edgevoice.agent.executor

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

/** 执行器模式（调试抽屉双模切换开关的取值）。 */
enum class ExecutorMode { CLOUD_REALTIME, ON_DEVICE_LOCAL }

enum class ExecutorConnectionState { CONNECTING, CONFIGURING, READY, RECONNECTING, CLOSED, FAILED }

/** 本轮输出的非致命缺失；不含供应商正文，也不代表连接已断开。 */
enum class ResponseIncompleteReason { MISSING_AUDIO, INCOMPLETE, UNCONFIRMED }

/** 可恢复发送错误意味着当前输入已丢弃，上层应暂停媒体并等待下一次 Ready。 */
class RealtimeSendException(
    message: String,
    val recoverable: Boolean = true,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

/** 只在所属响应完成后交付；连接代次与 callId 用于拒绝过期工具结果。 */
data class ExecutorToolCall(
    val name: String,
    val callId: String,
    val argumentsJson: String,
    val responseId: String,
    val inputEpoch: Long,
)

/**
 * 执行器向上层（sdk-core 状态机）暴露的统一事件流。
 *
 * 设计目标：云端/本地两种实现对上层完全同构 —— 状态机只订阅这一个流，
 * 不关心背后是 WebSocket Realtime 还是 MediaPipe SLM（策略模式对上透明）。
 */
sealed class ExecutorEvent {
    data class ConnectionChanged(
        val state: ExecutorConnectionState,
        val attempt: Int = 0,
    ) : ExecutorEvent()
    /** 模型音频输出增量（PCM16；本地文本模式下不产生） */
    data class AudioDelta(val pcm: ByteArray, val responseId: String? = null) : ExecutorEvent()

    /** 一轮回答开始；用于清空该轮文字并关联后续音频/文字。 */
    data class ResponseStarted(val responseId: String) : ExecutorEvent()

    /** 模型文本输出增量（云端转写 / 本地 SLM 流式 token） */
    data class TextDelta(val text: String, val responseId: String? = null) : ExecutorEvent()

    /** 完整回答文本；替换该轮流式草稿，不能再次拼接。 */
    data class AssistantTextFinal(val text: String, val responseId: String? = null) : ExecutorEvent()

    /** 本轮文字仍可展示，连接仍可继续提问；禁止据此自动重试。 */
    data class ResponseIncomplete(
        val reason: ResponseIncompleteReason,
        val responseId: String? = null,
    ) : ExecutorEvent()

    /** 用户语音的最终 ASR 转写（云端 whisper / 本地由上层 ASR 组件喂入） */
    data class UserTranscript(val text: String, val itemId: String? = null) : ExecutorEvent()

    /** 服务端音频时间轴的问句起点；紧接 UserSpeechStarted 交付。 */
    data class UserInputIdentity(val itemId: String, val audioStartMs: Long? = null) : ExecutorEvent()

    /** 服务端音频时间轴的问句终点；在 UserSpeechStopped 前交付。 */
    data class UserInputEnd(val itemId: String?, val audioEndMs: Long?) : ExecutorEvent()

    /** 检测到用户开口（barge-in 触发源；云端来自 server VAD） */
    object UserSpeechStarted : ExecutorEvent()

    /** VAD 确认用户停止讲话，随后等待模型处理。 */
    object UserSpeechStopped : ExecutorEvent()

    /** 工具调用完整、所属响应已结束，可以开始检索。 */
    data class ToolCallReady(val call: ExecutorToolCall) : ExecutorEvent()

    /** 一轮回复结束（云端 response.done / 本地生成完成） */
    object TurnComplete : ExecutorEvent()

    /** 连接/推理就绪 */
    object Ready : ExecutorEvent()

    /** 协议级 RTT 实测（云端 session.update 往返；本地执行器不产生） */
    data class RttMeasured(val rttMs: Long) : ExecutorEvent()

    data class Error(val cause: Throwable, val recoverable: Boolean = false) : ExecutorEvent()
}

/**
 * 双模代理执行器契约（架构文档 §4.1 策略模式核心）。
 */
interface IAgentExecutor {
    val mode: ExecutorMode

    /**
     * 是否支持相机画面实时上行（能力矩阵）。
     * false 时上层视觉管道应跳过编码与入队（OpenAI 画像 / 端侧 SLM）。
     */
    val supportsVideoInput: Boolean

    /** 当前供应商且配置已注册可调用工具。 */
    val supportsTools: Boolean get() = false

    /** 事件流（SharedFlow，多订阅者安全；replay=0，事件即瞬态） */
    val events: SharedFlow<ExecutorEvent>

    /** 是否已就绪可工作 */
    val ready: StateFlow<Boolean>

    /** 输入读取之前捕获的连接代次；同一个执行器重连后会改变。 */
    val inputEpoch: Long get() = 0L

    /** 当前连接成功 append 的 PCM 累计字节（含内部重注；clear 不重置）。 */
    val inputAudioBytesSent: Long get() = 0L

    /** 服务端 VAD 时间轴：只计 live append；真实 Qwen 的 manual 原声不推进 VAD 偏移。 */
    val inputVadAudioBytesSent: Long get() = inputAudioBytesSent

    /**
     * 准备资源（建立连接 / 加载模型）。幂等。
     * @param handoverContext 热切换时从旧 executor 带入的上下文摘要；首轮有效。
     */
    suspend fun prepare(handoverContext: String? = null)

    /** 上行一帧用户麦克风 PCM；云端未就绪或发送队列拒绝时抛异常，本地文本模式忽略。 */
    fun sendAudioFrame(pcm: ByteBuffer)

    fun sendAudioFrame(pcm: ByteBuffer, expectedEpoch: Long) {
        if (expectedEpoch != inputEpoch) throw RealtimeSendException("Stale audio input was discarded", recoverable = true)
        sendAudioFrame(pcm)
    }

    /**
     * 上行一帧相机画面（已编码 JPEG 原始字节）。
     *
     * 契约：
     *  - 调用方必须保证**至少一块音频帧已先行发送**（百炼服务端硬性时序约束）；
     *  - 不支持视觉的执行器（OpenAI 画像 / 端侧 SLM）安全 no-op，不得抛异常；
     *  - 超限帧（Base64 > 256KB）或发送失败抛异常，由上层计数/降级，禁止谎报发送成功。
     */
    fun sendVideoFrame(jpeg: ByteArray)

    fun sendVideoFrame(jpeg: ByteArray, expectedEpoch: Long) {
        if (!supportsVideoInput) return
        if (expectedEpoch != inputEpoch) throw RealtimeSendException("Stale video input was discarded", recoverable = true)
        sendVideoFrame(jpeg)
    }

    /** 直接注入用户文本（本地 SLM 的主输入路径）。 */
    fun sendUserText(text: String)

    /**
     * 注入系统级上下文（如 Tier3 长期记忆检索结果）。
     * 与 [sendUserText] 的关键区别：**不触发模型回复** —— 只影响后续推理的上下文。
     */
    fun injectContext(text: String)

    /** 回传工具结果；false 表示该调用已因插话、重连或停止过期。 */
    fun completeTool(call: ExecutorToolCall, outputJson: String, requestResponse: Boolean = true): Boolean = false

    /** 同一 Realtime 连接内提交带明确 C/H 角色的合成图与原问句 PCM；等待最终文字/语音响应完成。 */
    suspend fun respondToToolWithMedia(
        call: ExecutorToolCall,
        outputJson: String,
        questionPcm: ByteArray,
        comparisonJpeg: ByteArray,
    ): Boolean = false

    /** 打断：取消当前生成/播报（barge-in）。 */
    fun interrupt()

    /** 释放资源；热切换时先停旧再启新。 */
    fun shutdown()
}

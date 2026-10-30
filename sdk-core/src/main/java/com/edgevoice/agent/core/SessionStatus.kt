package com.edgevoice.agent.core

import com.edgevoice.agent.media.audio.AudioQueueOverflowException
import com.edgevoice.agent.executor.ResponseIncompleteReason
import com.edgevoice.agent.transport.TransportFailure
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

enum class SessionPhase { STOPPED, CONNECTING, CONFIGURING, READY, RECONNECTING, FAILED }
enum class SessionErrorKind {
    NETWORK, AUTHENTICATION, RATE_LIMIT, PERMISSION, AUDIO_DEVICE, OVERLOAD, TIMEOUT, MODEL_OUTPUT, UNKNOWN,
}

data class SessionStatus(
    val phase: SessionPhase = SessionPhase.STOPPED,
    val retryAttempt: Int = 0,
    val message: String? = null,
    val errorKind: SessionErrorKind? = null,
)

/** 输出缺失只提示当前轮，不能改变连接或媒体生命周期。 */
internal fun SessionStatus.withModelOutputWarning(reason: ResponseIncompleteReason): SessionStatus {
    if (phase != SessionPhase.READY) return this
    return copy(errorKind = SessionErrorKind.MODEL_OUTPUT, message = when (reason) {
        ResponseIncompleteReason.MISSING_AUDIO -> "本轮未收到语音，已保留文字，可继续提问或重问。"
        ResponseIncompleteReason.INCOMPLETE -> "本轮回答未完整生成，可继续提问或重问。"
        ResponseIncompleteReason.UNCONFIRMED -> "本轮回答完成状态未确认，可继续提问或重问。"
    })
}

internal fun SessionStatus.clearModelOutputWarning(): SessionStatus =
    if (errorKind == SessionErrorKind.MODEL_OUTPUT) copy(message = null, errorKind = null) else this

/** User-facing explanations never echo provider bodies, endpoints or credentials. */
internal fun sessionFailureKind(error: Throwable): SessionErrorKind {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val status = causes.filterIsInstance<TransportFailure>().firstOrNull()?.statusCode
    val message = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
    return when {
        causes.any { it is SecurityException } -> SessionErrorKind.PERMISSION
        status == 401 || status == 403 || listOf("invalid_api_key", "unauthorized", "authentication", "accessdenied", "invalidapikey").any(message::contains) -> SessionErrorKind.AUTHENTICATION
        status == 429 || listOf("rate_limit", "ratelimit", "quota", "insufficient", "余额", "配额", "欠费").any(message::contains) -> SessionErrorKind.RATE_LIMIT
        causes.any { it is AudioQueueOverflowException } || listOf("budget exceeded", "queue is full", "queue overflow", "积压", "缓冲已满").any(message::contains) -> SessionErrorKind.OVERLOAD
        causes.any { it is SocketTimeoutException || it is TimeoutException } || message.contains("timed out") || message.contains("timeout") -> SessionErrorKind.TIMEOUT
        causes.any { it is java.io.IOException } -> SessionErrorKind.NETWORK
        else -> SessionErrorKind.UNKNOWN
    }
}

internal fun sessionFailureMessage(kind: SessionErrorKind, error: Throwable): String = when (kind) {
    SessionErrorKind.NETWORK -> "网络连接暂时不可用，请检查网络后重新连接。"
    SessionErrorKind.AUTHENTICATION -> "模型访问未获授权，请检查密钥和模型访问权限。"
    SessionErrorKind.RATE_LIMIT -> if (listOf("quota", "insufficient", "余额", "配额", "欠费").any { error.message.orEmpty().contains(it, true) }) {
        "模型配额或余额不足，请恢复账户额度后重新连接。"
    } else "模型请求受限，请稍等片刻后重新连接。"
    SessionErrorKind.PERMISSION -> "麦克风或相机权限不可用，请在系统设置中授权后手动开始。"
    SessionErrorKind.AUDIO_DEVICE -> "通话音频被占用或设备已断开，请检查耳机、系统静音和其他通话后重新开始。"
    SessionErrorKind.OVERLOAD -> "会话数据积压超过上限，已停止本次会话；网络稳定后请重新连接。"
    SessionErrorKind.TIMEOUT -> "连接或会话准备超时，请检查网络后重新连接。"
    SessionErrorKind.MODEL_OUTPUT -> "本轮回答未完整生成，可继续提问或重问。"
    SessionErrorKind.UNKNOWN -> "会话暂时无法继续，请检查配置后重新连接。"
}

internal fun sessionExpiration(
    nowMs: Long,
    startedAtMs: Long,
    lastActivityAtMs: Long,
    responding: Boolean,
    maxDurationMs: Long,
    idleTimeoutMs: Long,
): String? = when {
    nowMs - startedAtMs >= maxDurationMs -> "本次会话已达到时长上限，已停止收音；需要时可手动重新开始。"
    !responding && nowMs - lastActivityAtMs >= idleTimeoutMs -> "长时间未检测到提问，已停止收音；需要时可手动重新开始。"
    else -> null
}

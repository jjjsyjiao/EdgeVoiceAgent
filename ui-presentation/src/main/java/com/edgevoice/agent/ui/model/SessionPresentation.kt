package com.edgevoice.agent.ui.model

enum class ConnectionPhase {
    STOPPED, CONNECTING, CONFIGURING, READY, RECONNECTING, FAILED;

    val active: Boolean get() = this in setOf(CONNECTING, CONFIGURING, READY, RECONNECTING)

    val label: String get() = when (this) {
        STOPPED -> "已停止"
        CONNECTING -> "正在连接"
        CONFIGURING -> "准备会话"
        READY -> "已连接"
        RECONNECTING -> "正在重连"
        FAILED -> "连接失败"
    }
}

enum class RecoveryAction { RETRY_SESSION, REQUEST_MICROPHONE, REQUEST_CAMERA, OPEN_SETTINGS, NONE }
enum class PermissionTarget { MICROPHONE, CAMERA }

data class RecoveryHint(
    val title: String,
    val guidance: String,
    val action: RecoveryAction,
    val permissionTarget: PermissionTarget? = null,
) {
    val actionLabel: String get() = when (action) {
        RecoveryAction.RETRY_SESSION -> "重新连接"
        RecoveryAction.REQUEST_MICROPHONE, RecoveryAction.REQUEST_CAMERA -> "授予权限"
        RecoveryAction.OPEN_SETTINGS -> "打开系统设置"
        RecoveryAction.NONE -> ""
    }
}

data class SessionUiState(
    val phase: ConnectionPhase = ConnectionPhase.STOPPED,
    val retryAttempt: Int = 0,
    val message: String? = null,
    val audioRouteLabel: String = "",
    val recovery: RecoveryHint? = null,
)

/** Stable SDK error categories determine the operation; raw provider details stay out of the UI. */
fun recoveryHint(errorKind: String?, message: String?): RecoveryHint? {
    if (message.isNullOrBlank()) return null
    return when (errorKind) {
        "AUTHENTICATION" -> RecoveryHint("模型访问未获授权", "请检查模型账号、密钥和访问权限，配置完成后重新连接。", RecoveryAction.NONE)
        "RATE_LIMIT" -> if (listOf("quota", "余额", "配额", "欠费").any { message.contains(it, ignoreCase = true) }) {
            RecoveryHint("模型配额不足", "请检查模型账号的额度或余额，恢复后重新连接。", RecoveryAction.NONE)
        } else {
            RecoveryHint("请求过于频繁", "请稍等片刻，再重新连接。", RecoveryAction.RETRY_SESSION)
        }
        "PERMISSION" -> RecoveryHint("权限不可用", "请在系统设置中允许麦克风和需要使用的相机权限，再手动开始。", RecoveryAction.OPEN_SETTINGS)
        "AUDIO_DEVICE" -> RecoveryHint("音频暂时不可用", "请结束其他通话或占用音频的应用，并检查耳机连接后重新开始。", RecoveryAction.RETRY_SESSION)
        "NETWORK", "TIMEOUT" -> RecoveryHint("网络连接中断", "请检查网络；连接恢复后可重新开始。", RecoveryAction.RETRY_SESSION)
        "OVERLOAD" -> RecoveryHint("会话数据积压", "为避免延迟继续累积，会话已停止。网络稳定后请重新连接。", RecoveryAction.RETRY_SESSION)
        "MODEL_OUTPUT" -> RecoveryHint("本轮回答有缺失", when {
            message.contains("未收到语音") -> "本轮未收到语音，已保留文字，可继续提问或重问。"
            message.contains("未确认") -> "本轮回答完成状态未确认，可继续提问或重问。"
            else -> "本轮回答未完整生成，可继续提问或重问。"
        }, RecoveryAction.NONE)
        else -> RecoveryHint("会话未能继续", "请重新连接；若仍然失败，请检查网络与模型配置。", RecoveryAction.RETRY_SESSION)
    }
}

fun permissionHint(target: PermissionTarget, permanentlyDenied: Boolean): RecoveryHint {
    val name = if (target == PermissionTarget.MICROPHONE) "麦克风" else "相机"
    return RecoveryHint(
        title = "需要${name}权限",
        guidance = if (permanentlyDenied) "请在系统设置中允许${name}权限，返回后手动开启。" else "允许${name}权限后，才能使用此功能。",
        action = if (permanentlyDenied) RecoveryAction.OPEN_SETTINGS else when (target) {
            PermissionTarget.MICROPHONE -> RecoveryAction.REQUEST_MICROPHONE
            PermissionTarget.CAMERA -> RecoveryAction.REQUEST_CAMERA
        },
        permissionTarget = target,
    )
}

data class VisionDeliveryLabel(val text: String, val sending: Boolean)

fun visionDeliveryLabel(ready: Boolean, lastFrameAgeMs: Long?, recentFps: Float): VisionDeliveryLabel = when {
    !ready -> VisionDeliveryLabel("仅预览 · 会话未就绪", false)
    lastFrameAgeMs == null -> VisionDeliveryLabel("仅预览 · 等待首帧发送", false)
    lastFrameAgeMs > 4_000L -> VisionDeliveryLabel("仅预览 · ${(lastFrameAgeMs / 1_000L)}秒未发送", false)
    else -> VisionDeliveryLabel("正在上行 · %.1f FPS".format(java.util.Locale.ROOT, recentFps.coerceAtLeast(0f)), true)
}

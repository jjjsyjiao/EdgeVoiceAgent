package com.edgevoice.agent.core.memory

/** 上下文条目类型。 */
enum class ContextKind { USER_SPEECH, ASSISTANT_SPEECH, VISION_FRAME, SYSTEM }

/**
 * 一条上下文记录（Tier1 工作记忆的基本单元）。
 *
 * [frameRef] 只存帧的**引用标识**（路径/ID），不存原始像素 ——
 * 对齐「原始像素不入库，只落 embedding + 时间戳 + 帧路径」原则。
 * [timestampMs] 用 SystemClock.elapsedRealtime 时间轴，与 CameraX 帧时间戳同源，才能硬对齐。
 */
data class ContextItem(
    val timestampMs: Long,
    val kind: ContextKind,
    val text: String,
    val frameRef: String? = null,
    val motionScore: Float = 0f,
)

package com.edgevoice.agent.core.memory

import kotlin.math.abs

/**
 * 多模态时间戳对齐引擎（技术文档 §2.1）。
 *
 * 解决的问题：用户说「刚才看到的那个东西」时，模型需要知道**哪几帧画面**对应这句话。
 * 做法：保留最近视觉帧的时间戳序列，当一句用户语音到达时，取出时间窗内（±[maxAlignDeltaMs]）
 * 的帧，生成文本化的视觉上下文注入模型。
 *
 * 为什么文本化而非直接塞图：云端 Realtime 上行图像成本高、且当前长期记忆已收窄为偏好/事实；
 * 这里用极轻量的「帧数 + 最近帧延迟 + 运动帧数」描述提供时序锚点，图帧仍由视觉链路上行。
 */
class ContextFusionEngine(
    private val maxAlignDeltaMs: Long = 1_500L,
    private val maxFrames: Int = 30,
) {
    private val frames = ArrayDeque<ContextItem>()
    private val lock = Any()

    fun onVisionFrame(item: ContextItem) {
        synchronized(lock) {
            frames.addLast(item)
            while (frames.size > maxFrames) frames.removeFirst()
        }
    }

    /** 返回与 [speechTimestampMs] 相差不超过阈值的视觉帧（按时间升序）。 */
    fun alignTo(speechTimestampMs: Long): List<ContextItem> = synchronized(lock) {
        frames.filter { abs(it.timestampMs - speechTimestampMs) <= maxAlignDeltaMs }
    }

    /** 生成可注入模型的文本化视觉上下文；无对齐帧时返回 null。 */
    fun describe(speechTimestampMs: Long): String? {
        val aligned = alignTo(speechTimestampMs)
        if (aligned.isEmpty()) return null
        val latest = aligned.last()
        val moving = aligned.count { it.motionScore >= 0.06f }
        val delta = speechTimestampMs - latest.timestampMs
        return "【视觉对齐】用户开口前后共 ${aligned.size} 帧画面（最近一帧滞后 ${delta}ms，运动帧 $moving 个）。" +
            "用户所指的「刚才/这个」即对应此时画面。"
    }

    fun clear() {
        synchronized(lock) { frames.clear() }
    }
}

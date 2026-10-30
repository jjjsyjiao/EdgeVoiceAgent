package com.edgevoice.agent.core.vision

/**
 * 视觉帧实时上行配置（V1）。
 *
 * V0 探针实测：1fps 连续送帧即可让模型随画面回答；更高帧率会拖慢音频节奏、
 * 抬升 video_tokens 成本，故默认 1fps。所有参数默认值即线上值。
 *
 * @property enabled 视觉上行总开关（false 时相机帧只登记时间戳，不编码、不上行）
 * @property frameIntervalMs 帧间隔闸门：距上一帧被接收不足该间隔的帧直接丢弃（默认 1000ms=1fps）
 * @property maxQueuedFrames 待发帧有界队列容量：音频节奏慢于相机时「丢旧保新」，容量 1 即只保最新帧
 * @property maxFramesPerSession 单次会话上行帧数硬预算；0 表示不限
 * @property maxFrameAgeMs 从接收相机帧到发送的最大允许年龄，使用本地单调时钟，默认 3 秒
 */
data class VisionUplinkConfig(
    val enabled: Boolean = true,
    val frameIntervalMs: Long = 1_000L,
    val maxQueuedFrames: Int = 1,
    val maxFramesPerSession: Int = 0,
    val maxFrameAgeMs: Long = 3_000L,
) {
    init {
        require(frameIntervalMs > 0) { "frameIntervalMs 必须为正数" }
        require(maxQueuedFrames >= 1) { "maxQueuedFrames 至少为 1" }
        require(maxFramesPerSession >= 0) { "maxFramesPerSession 不能为负" }
        require(maxFrameAgeMs > 0) { "maxFrameAgeMs 必须为正数" }
    }

    companion object {
        /** 视觉上行完全关闭（OpenAI 画像 / 用户手动关开关时使用）。 */
        val DISABLED = VisionUplinkConfig(enabled = false)
    }
}

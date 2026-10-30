package com.edgevoice.agent.core.vision

/**
 * 云端会话帧留存配置（V3，用户已拍板三项：原始 JPEG 帧环 / 周期批量 HTTP / 默认开）。
 *
 * 与 V2 本地帧环的关系：**并存而非替代**。
 *  - V2 本地环覆盖「最近 30 分钟」，用于即时情景回忆；
 *  - V3 云端留存覆盖「24h TTL + 跨会话」，用于超出本地环窗口的超窗回忆（昨天/前天…）。
 *
 * 非阻塞硬约束：上传走独立周期协程 + 独立 HTTP 连接，绝不挤占实时 WS 音频节奏。
 *
 * @property enabled 总开关（默认关；container 按 BuildConfig 显式打开）
 * @property endpoint server/ 会话帧留存服务基址（留空=未装配客户端）
 * @property flushIntervalMs 周期批量上传间隔（ms）
 * @property maxBatchFrames 单批帧数上限（服务端接收上限 64，此处留余量）
 * @property maxFramesPerSession 单会话上传帧预算；0=不限（约 1fps×10min=600）
 * @property maxBufferedFrames 网络持续失败时本地暂存上限，溢出丢最旧（绝不无限增长）
 * @property sessionId 本会话 id（服务端分目录键，须匹配 ^[A-Za-z0-9_-]{1,64}$）
 * @property recallMaxFrames 一次远端召回的抽帧上限（控制 video_tokens 成本）
 */
data class SessionFrameConfig(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val flushIntervalMs: Long = 5_000L,
    val maxBatchFrames: Int = 8,
    val maxFramesPerSession: Int = 600,
    val maxBufferedFrames: Int = 32,
    val sessionId: String = "",
    val recallMaxFrames: Int = 12,
) {
    init {
        require(flushIntervalMs > 0) { "flushIntervalMs 必须为正数" }
        require(maxBatchFrames in 1..64) { "maxBatchFrames 必须在 1..64" }
        require(maxFramesPerSession >= 0) { "maxFramesPerSession 不能为负" }
        require(maxBufferedFrames >= maxBatchFrames) { "maxBufferedFrames 不得小于 maxBatchFrames" }
        require(recallMaxFrames >= 1) { "recallMaxFrames 至少为 1" }
    }

    /** 有效可用：开关开 + 已配置端点 + 会话 id 合法（否则装配层退化为 NoOp，零开销）。 */
    val active: Boolean get() = enabled && endpoint.isNotBlank() && sessionId.isNotBlank()

    companion object {
        val DISABLED = SessionFrameConfig(enabled = false)

        /** 生成服务端安全字符集（不含 `-`）的随机会话 id。 */
        fun newSessionId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
    }
}

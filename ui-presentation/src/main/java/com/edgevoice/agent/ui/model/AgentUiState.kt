package com.edgevoice.agent.ui.model

import com.edgevoice.agent.core.ConversationTurn
import com.edgevoice.agent.core.state.AgentState
import com.edgevoice.agent.core.history.HistoryUiState

/**
 * 展示层双模枚举。**刻意与 core-executor 的 ExecutorMode 解耦**：
 * UI 层只表达「云端 / 本地」语义，枚举映射在 app 装配层完成（Stage 8）。
 */
enum class AgentMode { CLOUD, LOCAL }

/**
 * 调试抽屉硬核 telemetry 看板数据。
 *
 * 全部为纯展示快照：UI 不采集任何指标，由 app 层从 sdk-core 各组件汇聚后灌入。
 * 暂未被引擎产出的字段给出安全默认值，后续接线无需改 UI。
 */
data class DebugTelemetry(
    val rttMs: Long = 0L,
    /** RTT 折线历史（旧 → 新，建议长度 ≤ 48）。 */
    val rttHistory: List<Long> = emptyList(),
    /** 上行 Ring Buffer 水位 0..1。 */
    val ringBufferPct: Float = 0f,
    /** Jitter Buffer 丢包率（百分比，如 0.2f 表示 0.2%）。 */
    val jitterLossPct: Float = 0f,
    val tokenUsed: Int = 0,
    val tokenLimit: Int = 32_000,
    val iterationUsed: Int = 0,
    val iterationLimit: Int = 20,
    /** 熔断三态文本：CLOSED / OPEN / HALF_OPEN。 */
    val circuitBreaker: String = "CLOSED",
    val securityHits: Int = 0,
    val renderFps: Int = 60,
    // ---- 视觉帧上行（V1；visionActive=false 时卡片隐藏，其余为 0）----
    /** 视觉上行管道是否在运行（总开关 + 执行器能力矩阵双门控后的真实状态）。 */
    val visionActive: Boolean = false,
    /** 本次会话已上行画面帧数。 */
    val visionSentFrames: Long = 0L,
    /** 被丢帧数（1fps 闸门 / 有界队列丢旧 / 切到不支持的画像清队 / 会话预算）。 */
    val visionDroppedFrames: Long = 0L,
    /** 编码全阶梯后仍超 256KB 服务端红线而被放弃的帧数。 */
    val visionRejectedFrames: Long = 0L,
    /** 最近发出一帧 Base64 后体积（KB）。 */
    val visionLastFrameKb: Int = 0,
    val visionLastFrameAgeMs: Long? = null,
    val visionRecentFps: Float = 0f,
    // ---- 情景回忆（V2；recallEnabled=false 时卡片隐藏）----
    /** 帧环+回声环+回放器是否已装配（跟随视觉总开关）。 */
    val recallEnabled: Boolean = false,
    /** 磁盘帧环当前留存帧数（30min/200MB 双限滚动淘汰）。 */
    val frameRingCount: Int = 0,
    /** 帧环总占用（MB）。 */
    val frameRingMb: Int = 0,
    /** 最旧一帧年龄（秒；null=空环）。 */
    val frameRingOldestAgeSec: Int? = null,
    /** 本次会话成功回放次数。 */
    val replayCount: Long = 0L,
    /** 最近一次回放未触发的门控原因。 */
    val replayLastSkip: String? = null,
    // ---- 云端会话帧留存（V3；sessionFrameEnabled=false 时卡片隐藏）----
    /** 会话帧上传器是否在跑（客户端+配置双就绪）。 */
    val sessionFrameEnabled: Boolean = false,
    /** 服务端确认接收的帧累计。 */
    val sessionUploadFrames: Long = 0L,
    /** 成功上传批次数。 */
    val sessionUploadBatches: Long = 0L,
    /** 失败批次数（静默降级丢弃）。 */
    val sessionUploadFailures: Long = 0L,
    /** 当前本地待上传帧数。 */
    val sessionUploadPendingFrames: Int = 0,
)

/**
 * UI 的唯一渲染输入（MVI 单状态）。组件全部无业务逻辑，只做该状态的投影。
 */
data class AgentUiState(
    val state: AgentState = AgentState.IDLE_OBSERVING,
    val mode: AgentMode = AgentMode.CLOUD,
    /** 麦克风 RMS（0..1），LISTENING 态驱动光球。 */
    val inputLevel: Float = 0f,
    /** 下行音频 RMS（0..1），SPEAKING 态驱动光球。 */
    val outputLevel: Float = 0f,
    val visionEnabled: Boolean = false,
    /** 当前视觉抽帧率（智能抽帧：静止 0.5fps / 运动 5fps）。 */
    val visionFps: Float = 0f,
    val telemetry: DebugTelemetry = DebugTelemetry(),
    val sessionRunning: Boolean = false,
    /** 最近五轮问答，按时间从旧到新排列；仅供字幕区展示。 */
    val conversationTurns: List<ConversationTurn> = emptyList(),
    val userText: String = "",
    val assistantText: String = "",
    val assistantInterrupted: Boolean = false,
    val errorMessage: String? = null,
    val session: SessionUiState = SessionUiState(),
    val history: HistoryUiState = HistoryUiState(),
)

/** 用户意图回调；具体实现由 app 装配层绑定到 OrchestrationEngine。 */
class AgentUiActions(
    val onModeChange: (AgentMode) -> Unit = {},
    val onMicClick: () -> Unit = {},
    val onVisionToggle: () -> Unit = {},
    val onOrbClick: () -> Unit = {},
    val onRecoveryClick: () -> Unit = {},
    val onHistorySaveConfirm: () -> Unit = {},
    val onHistorySaveDiscard: () -> Unit = {},
)

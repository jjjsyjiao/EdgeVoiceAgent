package com.edgevoice.agent.core

import com.edgevoice.agent.core.vision.ReplayConfig
import com.edgevoice.agent.core.vision.SessionFrameConfig
import com.edgevoice.agent.core.vision.VisionUplinkConfig
import com.edgevoice.agent.media.audio.AudioConfig

/**
 * SDK 运行时配置。
 *
 * 音频规格分离为上行采集与下行播放：OpenAI Realtime 两端都用 24kHz PCM16，
 * 直接按 24kHz 采集/播放可省掉一次端侧重采样。
 */
data class AgentConfig(
    /** 上行采集规格 */
    val captureAudio: AudioConfig = AudioConfig.REALTIME_UP,
    /** 下行播放规格 */
    val playbackAudio: AudioConfig = AudioConfig.REALTIME_UP,
    /** 采集环形缓冲深度（毫秒）：隔离采集线程与上行泵线程 */
    val ringCapacityMs: Int = 500,
    /** 音频泵送帧长（毫秒） */
    val pumpFrameMs: Int = 20,
    /** Tier3 长期记忆服务地址（同仓 server/）；留空则禁用长期记忆 */
    val memoryEndpoint: String = "",
    /** 视觉帧实时上行（V1：百炼 qwen3.5+；OpenAI 画像另有能力位门控） */
    val vision: VisionUplinkConfig = VisionUplinkConfig(),
    /** 情景回忆回放（V2：磁盘帧环 + 原声重注；依赖视觉管道开启才有帧可回放） */
    val replay: ReplayConfig = ReplayConfig(),
    /** 云端会话帧留存（V3：周期批量 HTTP 上传 + 超窗召回；与本地帧环并存） */
    val sessionFrame: SessionFrameConfig = SessionFrameConfig.DISABLED,
    /** A foreground session cannot retain resources indefinitely; user can start a new one. */
    val maxSessionDurationMs: Long = 60 * 60_000L,
    /** Stop an unused microphone/cloud connection after prolonged user silence. */
    val idleTimeoutMs: Long = 5 * 60_000L,
    /** Discard stale capture after scheduling/network stalls instead of uploading old speech. */
    val maxCaptureBacklogMs: Int = 250,
    /** Detect a missing response terminal event instead of leaving the UI processing forever. */
    val maxTurnDurationMs: Long = 90_000L,
)

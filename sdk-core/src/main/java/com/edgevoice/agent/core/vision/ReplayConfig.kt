package com.edgevoice.agent.core.vision

/**
 * 情景回忆回放配置（V2）。
 *
 * @property enabled 总开关；false 时引擎不创建回声环/编排器，零开销
 * @property echoRingMs 上行 PCM 原声尾环容量（ms）。24kHz/16bit 单声道 ≈ 48KB/s
 * @property snapshotMs 触发回放时取出重注的原声尾窗（ms）。必须完整覆盖用户刚说完的问句
 * @property minAudioMs 原声短于该值则放弃回放 —— server_vad 对无有效语音的缓冲直接丢弃
 * @property maxFrames 一次回放交错补发的历史帧上限（控制 video_tokens 成本与时间轴密度）
 * @property chunkMs 重放节奏块：原声按该粒度逐块 append，帧插在块之间（探针验证路径）
 * @property suppressTailMs 重放音频送完后，转写抑制窗还要维持多久
 *                 （覆盖 VAD 静音判定 → commit → UserTranscript 回环的全链路时延）
 * @property pcmBytesPerSec PCM 码率（24kHz*16bit*1ch = 48000）
 */
data class ReplayConfig(
    val enabled: Boolean = true,
    val echoRingMs: Long = 12_000L,
    val snapshotMs: Long = 8_000L,
    val minAudioMs: Long = 500L,
    val maxFrames: Int = 10,
    val chunkMs: Long = 100L,
    val suppressTailMs: Long = 6_000L,
    val pcmBytesPerSec: Int = 24_000 * 2,
) {
    fun echoRingBytes(): Int = (echoRingMs * pcmBytesPerSec / 1000L).toInt()
    fun snapshotBytes(): Int = (snapshotMs * pcmBytesPerSec / 1000L).toInt()
    fun minAudioBytes(): Int = (minAudioMs * pcmBytesPerSec / 1000L).toInt()
    fun chunkBytes(): Int = (chunkMs * pcmBytesPerSec / 1000L).toInt()

    companion object {
        val DISABLED = ReplayConfig(enabled = false)
    }
}

/** 一次回放尝试的结果（遥测/日志用；未触发时 [reason] 说明门控原因）。 */
data class ReplayResult(
    val triggered: Boolean,
    val reason: String? = null,
    val audioChunks: Int = 0,
    val framesInjected: Int = 0,
)

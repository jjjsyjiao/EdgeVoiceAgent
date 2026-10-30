package com.edgevoice.agent.core.vision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 情景回忆回放编排器（V2 核心，纯 Kotlin 无 Android 依赖，可 JVM 单测）。
 *
 * 探针实测背书的唯一可行路径（qwen server_vad 生产同构模式）：
 *  1. 打断当前模型回复（[interrupt] + 上层清播放）；
 *  2. 暂停实时视觉上行（音频上行不停 —— 回声 PCM 本身要走音频通道）；
 *  3. 把用户**原声**尾窗按 [ReplayConfig.chunkMs] 实时节奏逐块 append；
 *  4. 历史帧均匀插在音频块**之间**（音频先行 ≥1 个块，最后一个块后不插图）；
 *  5. **绝不手动 commit**：等服务端 VAD 识别原声后自动提交、自动回答；
 *  6. 原声重注会产生一轮重复 UserTranscript，[suppressUntilMs] 时间窗内由引擎丢弃，防循环。
 *
 * 并发：[tryTrigger] 由引擎事件消费协程调用，内部以 [inflight] 保证同一时刻只有一次回放；
 * 重放循环本身不持锁，耗时节奏全部走可注入 [sleeper]（测试用假时钟即时跑完）。
 *
 * V3 扩展：帧源可插**远端**（服务端会话帧留存）。策略「本地优先、缺失回退」：
 * 时间窗被本地磁盘帧环完整覆盖时只取本地；否则（长窗：昨天/前天…，或本地为空）先问远端，
 * 远端无命中再退回本地（可能为空 → no_frames 跳过）。
 */
class VisionReplayPlayer(
    private val config: ReplayConfig,
    private val frameRing: DiskFrameRing,
    private val echoRing: PcmEchoRing,
    /** 上行一块 PCM（引擎接 executor.sendAudioFrame）。 */
    private val audioSender: (ByteBuffer) -> Unit,
    /** 上行一帧历史 JPEG（引擎接 executor.sendVideoFrame）。 */
    private val videoSender: (ByteArray) -> Unit,
    /** 打断当前回复（引擎接 executor.interrupt + 清本地播放）。 */
    private val interrupt: () -> Unit,
    /** 当前执行器是否接受视觉帧（OpenAI/端侧画像下回放无意义）。 */
    private val supportsVideo: () -> Boolean,
    /** 回放窗口开始/结束回调（引擎接 visionPipeline.setUplinkPaused）。 */
    private val onReplayWindow: (active: Boolean) -> Unit = {},
    /**
     * V3 远端帧源（挂起，引擎接 sessionFrameUploader.recall）：
     * `(fromMs, toMs, maxFrames) -> List<jpeg>`；null = 无远端，纯本地（V2 行为）。
     */
    private val remoteFrames: (suspend (fromMs: Long, toMs: Long, maxFrames: Int) -> List<ByteArray>)? = null,
    /** 与帧环同一时基的墙钟（帧时间戳用 System.currentTimeMillis）。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: suspend (Long) -> Unit = { ms -> delay(ms) },
) {
    private val inflight = AtomicBoolean(false)

    /** 转写抑制截止墙钟；引擎收到 UserTranscript 时先查 [isSuppressed]。 */
    @Volatile private var suppressUntilMs = 0L

    private val replayCount = AtomicLong(0)
    @Volatile private var lastSkipReason: String? = null

    fun isSuppressed(nowMs: Long = clock()): Boolean = nowMs < suppressUntilMs

    fun replayCount(): Long = replayCount.get()
    fun lastSkipReason(): String? = lastSkipReason

    /**
     * 尝试触发回放。非回忆语句/门控不满足时返回 triggered=false 且**无任何副作用**；
     * 已有回放在飞时返回 reason="busy"。
     */
    suspend fun tryTrigger(transcript: String): ReplayResult {
        if (!config.enabled) return skip("disabled")
        val query = VisionRecallHeuristic.parse(transcript) ?: return skip("not_recall")
        if (!supportsVideo()) return skip("video_unsupported")
        if (!inflight.compareAndSet(false, true)) return skip("busy")

        val now = clock()
        try {
            val fromMs = if (query.lookbackMs == Long.MAX_VALUE) {
                frameRing.stats().oldestTimestampMs ?: now
            } else {
                now - query.lookbackMs
            }
            val frames = loadFrames(fromMs, now)
            if (frames.isEmpty()) return skip("no_frames")

            val pcm = echoRing.snapshotTrailing(config.snapshotBytes())
            if (pcm.size < config.minAudioBytes()) return skip("audio_too_short")

            return runReplay(pcm, frames)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // 回放是增强路径：任何意外都不得拖垮引擎事件循环
            return skip("error:${t.javaClass.simpleName}")
        } finally {
            inflight.set(false)
        }
    }

    /**
     * 帧源选择（V3）：本地优先、缺失回退远端。
     *
     * 判定「本地覆盖」= 帧环已覆盖到窗口起点（oldest <= fromMs）。覆盖 → 只取本地（零网络）；
     * 未覆盖（长窗超出本地 30min 环，或本地为空）→ 优先远端；远端无命中再退回本地。
     */
    private suspend fun loadFrames(fromMs: Long, toMs: Long): List<ByteArray> {
        val local = frameRing.sampleJpegs(fromMs, toMs, config.maxFrames)
        val oldest = frameRing.stats().oldestTimestampMs
        if (oldest != null && oldest <= fromMs) return local // 窗口完整落在本地环内
        val remote = remoteFrames?.invoke(fromMs, toMs, config.maxFrames) ?: emptyList()
        return if (remote.isNotEmpty()) remote else local
    }

    private suspend fun runReplay(pcm: ByteArray, frames: List<ByteArray>): ReplayResult {
        val chunkSize = config.chunkBytes()
        val chunkCount = (pcm.size + chunkSize - 1) / chunkSize
        check(chunkCount >= 3) { "minAudio 门控后至少应有 3 个节奏块（首块音频先行、末块纯音频收尾）" }

        // 帧 → 块下标映射：第 0 块只放音频（音频先行硬约束），末块（chunkCount-1）
        // 之后也不插图。多帧在 [1, chunkCount-2] 上等距分布（探针的 15%/50%/75% 泛化）。
        val frameAtChunk: Map<Int, List<ByteArray>> = frames.mapIndexed { f, jpeg ->
            val idx = if (frames.size == 1) {
                chunkCount / 2
            } else {
                1 + f * (chunkCount - 3) / (frames.size - 1)
            }
            idx.coerceIn(1, chunkCount - 2) to jpeg
        }.groupBy({ it.first }, { it.second })

        interrupt()
        onReplayWindow(true)
        var sentChunks = 0
        try {
            var off = 0
            var chunkIdx = 0
            while (off < pcm.size) {
                val len = minOf(chunkSize, pcm.size - off)
                audioSender(ByteBuffer.wrap(pcm, off, len))
                off += len
                sentChunks++
                frameAtChunk[chunkIdx]?.forEach { videoSender(it) }
                chunkIdx++
                if (off < pcm.size) sleeper(config.chunkMs) // 末块发完不必再等
            }
        } finally {
            onReplayWindow(false)
        }

        // 循环按实时节奏耗完了原声时长；重复转写在「VAD 静音判定 → commit →
        // UserTranscript 回环」之后才到，故抑制窗从现在起留尾部余量即可
        suppressUntilMs = clock() + config.suppressTailMs
        replayCount.incrementAndGet()
        lastSkipReason = null
        return ReplayResult(
            triggered = true,
            audioChunks = sentChunks,
            framesInjected = frames.size,
        )
    }

    /** 引擎 stop() 时复位（回声环本体由引擎另行清理）。 */
    fun reset() {
        inflight.set(false)
        suppressUntilMs = 0L
        lastSkipReason = null
    }

    private fun skip(reason: String): ReplayResult {
        lastSkipReason = reason
        return ReplayResult(triggered = false, reason = reason)
    }
}

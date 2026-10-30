package com.edgevoice.agent.core.vision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 端侧上传运行计数（调试抽屉 1s 轮询快照）。
 *
 * @property uploadedFrames 服务端确认接收的帧累计
 * @property batches 成功上传的批次数
 * @property failures 失败批次数（本批直接丢弃，静默降级）
 * @property pendingFrames 当前本地待上传帧数
 * @property enqueuedFrames 累计入队帧数（含已上传/已丢弃）
 * @property droppedFrames 累计丢弃帧数（会话预算耗尽 + 缓冲溢出）
 */
data class SessionUploadStats(
    val uploadedFrames: Long,
    val batches: Long,
    val failures: Long,
    val pendingFrames: Int,
    val enqueuedFrames: Long,
    val droppedFrames: Long,
)

/**
 * 会话帧周期批量上传器（V3）。
 *
 * 线程/协程模型（与音频热路径完全解耦）：
 *  1. 编码协程 → [onFrame]：仅入本地缓冲（[FrameUploadBuffer]），零网络零阻塞；
 *  2. flush 协程（[scope] 独立 launch）：每 [SessionFrameConfig.flushIntervalMs] 取一批
 *     `client.upload`；失败即丢弃本批并计数（增强能力，不重试、不反噬主链路）。
 *
 * [start]/[stop] 由引擎随视觉管道同生命周期调用；[flushOnce] 为 internal，供单测绕过定时器直接驱动。
 */
class SessionFrameUploader(
    private val config: SessionFrameConfig,
    private val client: ISessionFrameClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val sessionId = config.sessionId
    private val buffer = FrameUploadBuffer(
        maxBatchFrames = config.maxBatchFrames,
        maxFramesPerSession = config.maxFramesPerSession,
        maxBufferedFrames = config.maxBufferedFrames,
        clock = clock,
    )

    private var flushJob: Job? = null
    private val uploaded = AtomicLong(0)
    private val batches = AtomicLong(0)
    private val failures = AtomicLong(0)

    /** 编码协程调用：只入本地缓冲，绝不做网络 IO。 */
    fun onFrame(jpeg: ByteArray) {
        buffer.offer(jpeg)
    }

    fun start() {
        if (flushJob != null || !config.active) return
        flushJob = scope.launch {
            while (isActive) {
                delay(config.flushIntervalMs)
                runCatching { flushOnce() }
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
        buffer.clear()
    }

    /**
     * 单次 flush：取一批 → 上传。成功计入 uploaded/batches，失败（异常/连接失败/非 2xx）
     * 计入 failures 并丢弃本批 —— 会话留存是增强路径，绝不阻塞、绝不重试堆积。
     */
    internal suspend fun flushOnce() {
        val batch = buffer.drainBatch()
        if (batch.isEmpty()) return
        val accepted = try {
            client.upload(sessionId, batch)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
        if (accepted == null) {
            failures.incrementAndGet()
        } else {
            batches.incrementAndGet()
            uploaded.addAndGet(accepted.toLong())
        }
    }

    /** V3-3 远端召回入口（复用同一客户端与 sessionId；[maxFrames] 默认取配置）。 */
    suspend fun recall(fromMs: Long, toMs: Long, maxFrames: Int = config.recallMaxFrames): List<SessionFrame> {
        if (!config.active) return emptyList()
        return try {
            client.recall(fromMs, toMs, maxFrames, sessionId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun stats(): SessionUploadStats {
        val b = buffer.stats()
        return SessionUploadStats(
            uploadedFrames = uploaded.get(),
            batches = batches.get(),
            failures = failures.get(),
            pendingFrames = b.pending,
            enqueuedFrames = b.enqueued,
            droppedFrames = b.droppedBudget + b.droppedOverflow,
        )
    }

    /** 供测试/遥测读取当前 sessionId。 */
    fun sessionId(): String = sessionId
}

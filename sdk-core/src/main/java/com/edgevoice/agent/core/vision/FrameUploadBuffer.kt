package com.edgevoice.agent.core.vision

/** 上传批缓冲计数快照。 */
data class FrameUploadBufferStats(
    val pending: Int,
    val enqueued: Long,
    val droppedBudget: Long,
    val droppedOverflow: Long,
)

/**
 * 端侧上传批缓冲（V3，纯 Kotlin 可 JVM 单测）。
 *
 * 职责：把编码协程「每帧一次」的产出攒成「每批 N 帧」，供周期 flush 一次性上传，
 * 从而把 HTTP 次数从 1fps 降到 ~0.2fps（5s/批）。
 *
 * 两条不变量（非阻塞硬约束）：
 *  1. [offer] 只做内存操作，永不做网络 IO —— 由编码协程调用，绝不可阻塞其线程；
 *  2. 缓冲有界：网络持续失败时新帧照收、最旧的先丢（丢旧保新），绝不无限增长。
 *
 * 线程安全：仅在一个锁上串行（offer 来自编码协程，drain 来自 flush 协程）。
 *
 * @param maxBatchFrames 单批上限
 * @param maxFramesPerSession 单会话上传预算；0=不限
 * @param maxBufferedFrames 本地暂存上限（≥ maxBatchFrames）
 */
class FrameUploadBuffer(
    private val maxBatchFrames: Int,
    private val maxFramesPerSession: Int,
    private val maxBufferedFrames: Int,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val queue = ArrayDeque<SessionFrame>()
    private var accepted = 0L
    private var droppedBudget = 0L
    private var droppedOverflow = 0L

    /**
     * 入队一帧。返回 false 表示被丢弃（预算耗尽 / 空帧）。
     * 缓冲溢出时丢最旧一帧腾位（frames 仍入队，返回 true）。
     */
    fun offer(jpeg: ByteArray): Boolean {
        if (jpeg.isEmpty()) return false
        synchronized(lock) {
            if (maxFramesPerSession > 0 && accepted >= maxFramesPerSession) {
                droppedBudget++
                return false
            }
            if (queue.size >= maxBufferedFrames) {
                queue.removeFirst()
                droppedOverflow++
            }
            queue.addLast(SessionFrame(clock(), jpeg))
            accepted++
            return true
        }
    }

    /** 取一批（最多 [maxBatchFrames] 帧，最旧优先）并从缓冲移除。空缓冲返回空列表。 */
    fun drainBatch(): List<SessionFrame> = synchronized(lock) {
        if (queue.isEmpty()) return emptyList()
        val n = minOf(maxBatchFrames, queue.size)
        val out = ArrayList<SessionFrame>(n)
        repeat(n) { out.add(queue.removeFirst()) }
        out
    }

    fun stats(): FrameUploadBufferStats = synchronized(lock) {
        FrameUploadBufferStats(
            pending = queue.size,
            enqueued = accepted,
            droppedBudget = droppedBudget,
            droppedOverflow = droppedOverflow,
        )
    }

    fun clear() = synchronized(lock) { queue.clear() }
}

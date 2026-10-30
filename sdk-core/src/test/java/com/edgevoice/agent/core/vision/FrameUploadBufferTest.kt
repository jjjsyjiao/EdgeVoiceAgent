package com.edgevoice.agent.core.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [FrameUploadBuffer] 批缓冲语义验证：入队/取批/预算/溢出丢旧/空帧容忍（纯 JVM，假时钟）。
 */
class FrameUploadBufferTest {

    private var now = 1_700_000_000_000L

    private fun buffer(
        maxBatchFrames: Int = 8,
        maxFramesPerSession: Int = 0,
        maxBufferedFrames: Int = 32,
    ) = FrameUploadBuffer(
        maxBatchFrames = maxBatchFrames,
        maxFramesPerSession = maxFramesPerSession,
        maxBufferedFrames = maxBufferedFrames,
        clock = { now },
    )

    private fun jpeg(tag: Byte) = ByteArray(4) { tag }

    @Test
    fun offer_stamps_timestamp_and_counts_pending() {
        val b = buffer()
        assertThat(b.offer(jpeg(1))).isTrue()
        now += 1_000L
        assertThat(b.offer(jpeg(2))).isTrue()

        val stats = b.stats()
        assertThat(stats.pending).isEqualTo(2)
        assertThat(stats.enqueued).isEqualTo(2L)
        assertThat(stats.droppedBudget).isEqualTo(0L)
        assertThat(stats.droppedOverflow).isEqualTo(0L)

        val batch = b.drainBatch()
        assertThat(batch.map { it.tsMs }).containsExactly(1_700_000_000_000L, 1_700_000_001_000L).inOrder()
    }

    @Test
    fun drain_batch_returns_at_most_max_and_removes_oldest_first() {
        val b = buffer(maxBatchFrames = 3)
        repeat(5) { b.offer(jpeg((it + 1).toByte())) }

        val first = b.drainBatch()
        assertThat(first.map { it.jpeg[0].toInt() }).containsExactly(1, 2, 3).inOrder()
        assertThat(b.stats().pending).isEqualTo(2)

        val second = b.drainBatch()
        assertThat(second.map { it.jpeg[0].toInt() }).containsExactly(4, 5).inOrder()
        assertThat(b.drainBatch()).isEmpty()
    }

    @Test
    fun session_budget_drops_extra_offers() {
        val b = buffer(maxFramesPerSession = 2)
        assertThat(b.offer(jpeg(1))).isTrue()
        assertThat(b.offer(jpeg(2))).isTrue()
        assertThat(b.offer(jpeg(3))).isFalse() // 超预算

        val stats = b.stats()
        assertThat(stats.pending).isEqualTo(2)
        assertThat(stats.droppedBudget).isEqualTo(1L)
        // 已入队的仍在，drain 只拿到预算内的 2 帧
        assertThat(b.drainBatch()).hasSize(2)
    }

    @Test
    fun overflow_drops_oldest_and_keeps_new() {
        val b = buffer(maxBatchFrames = 2, maxBufferedFrames = 3)
        b.offer(jpeg(1)); b.offer(jpeg(2)); b.offer(jpeg(3))
        b.offer(jpeg(4)) // 溢出：丢最旧的帧1

        val stats = b.stats()
        assertThat(stats.pending).isEqualTo(3)
        assertThat(stats.droppedOverflow).isEqualTo(1L)
        // 帧1 已被丢弃，剩余 2/3/4
        val all = ArrayList<Int>()
        repeat(2) { b.drainBatch().forEach { all.add(it.jpeg[0].toInt()) } }
        assertThat(all).containsExactly(2, 3, 4).inOrder()
    }

    @Test
    fun empty_jpeg_is_rejected() {
        val b = buffer()
        assertThat(b.offer(ByteArray(0))).isFalse()
        assertThat(b.stats().pending).isEqualTo(0)
        assertThat(b.stats().enqueued).isEqualTo(0L)
    }

    @Test
    fun clear_empties_pending_but_keeps_counters() {
        val b = buffer()
        b.offer(jpeg(1)); b.offer(jpeg(2))
        b.clear()
        assertThat(b.stats().pending).isEqualTo(0)
        assertThat(b.stats().enqueued).isEqualTo(2L) // 计数保留，供遥测
    }
}

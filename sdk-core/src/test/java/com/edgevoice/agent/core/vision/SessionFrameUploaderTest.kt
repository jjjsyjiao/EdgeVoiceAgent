package com.edgevoice.agent.core.vision

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [SessionFrameUploader] 周期批量上传语义验证：成功/失败/异常三条路径 + 召回透传。
 * 用假 [ISessionFrameClient] 绕开网络，直接驱动 internal [SessionFrameUploader.flushOnce]。
 */
class SessionFrameUploaderTest {

    private class FakeClient(
        var uploadResult: Int? = 0,
        var throwOnUpload: Throwable? = null,
    ) : ISessionFrameClient {
        val uploads = mutableListOf<Pair<String, List<SessionFrame>>>()
        var recallCalls = 0
        var lastRecallMaxFrames = -1
        var lastRecallSessionId: String? = null
        var recalled: List<SessionFrame> = emptyList()

        override suspend fun upload(sessionId: String, frames: List<SessionFrame>): Int? {
            throwOnUpload?.let { throw it }
            uploads.add(sessionId to frames)
            return uploadResult
        }

        override suspend fun recall(
            fromMs: Long,
            toMs: Long,
            maxFrames: Int,
            sessionId: String?,
        ): List<SessionFrame> {
            recallCalls++
            lastRecallMaxFrames = maxFrames
            lastRecallSessionId = sessionId
            return recalled
        }
    }

    private var now = 1_700_000_000_000L

    private fun config(
        enabled: Boolean = true,
        endpoint: String = "http://localhost:8001",
        sessionId: String = "sess001",
        maxBatchFrames: Int = 2,
        recallMaxFrames: Int = 5,
    ) = SessionFrameConfig(
        enabled = enabled,
        endpoint = endpoint,
        sessionId = sessionId,
        maxBatchFrames = maxBatchFrames,
        recallMaxFrames = recallMaxFrames,
        maxBufferedFrames = 8,
    )

    private fun jpeg(tag: Byte) = ByteArray(3) { tag }

    @Test
    fun flush_success_counts_and_drains_buffer() = runTest {
        val client = FakeClient(uploadResult = 2)
        val u = SessionFrameUploader(config(), client, this, clock = { now })
        u.onFrame(jpeg(1)); u.onFrame(jpeg(2))
        // 单批上限 2：两次入队刚好一批
        u.flushOnce()

        val stats = u.stats()
        assertThat(stats.uploadedFrames).isEqualTo(2L)
        assertThat(stats.batches).isEqualTo(1L)
        assertThat(stats.failures).isEqualTo(0L)
        assertThat(stats.pendingFrames).isEqualTo(0)
        assertThat(client.uploads).hasSize(1)
        assertThat(client.uploads[0].first).isEqualTo("sess001")
        assertThat(client.uploads[0].second.map { it.jpeg[0].toInt() }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun flush_failure_null_result_counts_failures_and_drops_batch() = runTest {
        val client = FakeClient(uploadResult = null)
        val u = SessionFrameUploader(config(), client, this, clock = { now })
        u.onFrame(jpeg(1))
        u.flushOnce()

        val stats = u.stats()
        assertThat(stats.uploadedFrames).isEqualTo(0L)
        assertThat(stats.batches).isEqualTo(0L)
        assertThat(stats.failures).isEqualTo(1L)
        assertThat(stats.pendingFrames).isEqualTo(0) // 本批已丢弃，不重试堆积
    }

    @Test
    fun flush_exception_is_silently_degraded() = runTest {
        val client = FakeClient(throwOnUpload = RuntimeException("network down"))
        val u = SessionFrameUploader(config(), client, this, clock = { now })
        u.onFrame(jpeg(1))
        u.flushOnce() // 不得抛出

        assertThat(u.stats().failures).isEqualTo(1L)
        assertThat(u.stats().pendingFrames).isEqualTo(0)
    }

    @Test
    fun flush_empty_buffer_is_noop() = runTest {
        val client = FakeClient()
        val u = SessionFrameUploader(config(), client, this, clock = { now })
        u.flushOnce()
        assertThat(client.uploads).isEmpty()
        assertThat(u.stats().batches).isEqualTo(0L)
        assertThat(u.stats().failures).isEqualTo(0L)
    }

    @Test
    fun recall_passes_session_id_and_budget_through() = runTest {
        val client = FakeClient().apply {
            recalled = listOf(SessionFrame(111L, jpeg(9)))
        }
        val u = SessionFrameUploader(config(recallMaxFrames = 5), client, this, clock = { now })

        val frames = u.recall(fromMs = 100L, toMs = 999L)
        assertThat(frames).hasSize(1)
        assertThat(frames[0].tsMs).isEqualTo(111L)
        assertThat(client.recallCalls).isEqualTo(1)
        assertThat(client.lastRecallMaxFrames).isEqualTo(5)
        assertThat(client.lastRecallSessionId).isEqualTo("sess001")
    }

    @Test
    fun inactive_config_makes_recall_empty_without_calling_client() = runTest {
        val client = FakeClient()
        val u = SessionFrameUploader(config(enabled = false), client, this, clock = { now })
        assertThat(u.recall(100L, 999L)).isEmpty()
        assertThat(client.recallCalls).isEqualTo(0)
    }

    @Test
    fun session_id_and_stats_snapshot_expose_counters() = runTest {
        val client = FakeClient(uploadResult = 1)
        val u = SessionFrameUploader(config(), client, this, clock = { now })
        assertThat(u.sessionId()).isEqualTo("sess001")
        u.onFrame(jpeg(1))
        assertThat(u.stats().enqueuedFrames).isEqualTo(1L)
    }
}

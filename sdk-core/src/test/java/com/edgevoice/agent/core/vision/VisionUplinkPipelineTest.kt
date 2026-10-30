package com.edgevoice.agent.core.vision

import com.edgevoice.agent.media.video.FrameJpegCodec
import com.edgevoice.agent.media.video.JpegFrameEncoder
import com.edgevoice.agent.media.video.ResizedJpeg
import com.edgevoice.agent.media.video.VideoFrame
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [VisionUplinkPipeline] 并发时序单测。
 *
 * Unconfined 让编码协程在 trySend 的调用栈内同步跑完（CONFLATED 通道恢复即执行），
 * 因此所有时序断言都是确定性的，不依赖虚拟时钟调度；帧间隔用注入时钟手工推进。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VisionUplinkPipelineTest {

    /** 纯 JVM fake：每次压缩产出可辨识序号字节的小 JPEG（100B，Base64 136B，远低于闸）。 */
    private class FakeCodec(private val fixedSize: Int = 100) : FrameJpegCodec {
        var compressCount = 0
        var lastInputMarker = 0
        var afterCompress: (() -> Unit)? = null
        override fun compress(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
            compressCount++
            val serial = compressCount
            lastInputMarker = nv21[0].toInt()
            afterCompress?.invoke()
            return ByteArray(fixedSize).also { it[0] = (serial and 0xFF).toByte() }
        }
        override fun resize(jpeg: ByteArray, longEdge: Int, quality: Int): ResizedJpeg =
            ResizedJpeg(ByteArray(fixedSize), 8, 8)
    }

    private var clock = 1_000L

    private fun frame(marker: Int = 0, timestampNs: Long = 0L) = VideoFrame(
        timestampNs = timestampNs,
        width = 6,
        height = 4,
        rotationDegrees = 0,
        motionScore = 0.5f,
        isMotion = true,
        nv21 = ByteArray(6 * 4 * 3 / 2).also { it[0] = marker.toByte() },
    )

    private fun newPipeline(
        codec: FakeCodec,
        config: VisionUplinkConfig = VisionUplinkConfig(),
        supports: () -> Boolean = { true },
        sink: ((com.edgevoice.agent.media.video.VideoFrame, com.edgevoice.agent.media.video.EncodedFrame) -> Unit)? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        sender: (ByteArray) -> Unit,
    ): VisionUplinkPipeline {
        return VisionUplinkPipeline(
            config = config,
            encoder = JpegFrameEncoder(codec),
            scope = scope,
            supportsVideo = supports,
            sender = sender,
            frameSink = sink,
            timeSource = { clock },
        )
    }

    @Test
    fun no_frame_is_sent_before_first_audio_block() = runBlocking {
        // V0 探针硬约束：音频先行。帧已编码排队，但首块音频前绝不放行
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(FakeCodec()) { sent += it }
        p.start()

        p.onFrame(frame())

        assertThat(sent).isEmpty()
        assertThat(p.stats().queuedFrames).isEqualTo(1)

        p.onAudioFrameSent()
        assertThat(sent).hasSize(1)
        assertThat(p.stats().sentFrames).isEqualTo(1)
        p.stop()
    }

    @Test
    fun bounded_queue_evicts_old_and_newest_is_sent() = runBlocking {
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec, VisionUplinkConfig(frameIntervalMs = 1000, maxQueuedFrames = 1)) { sent += it }
        p.start()

        p.onFrame(frame())
        clock += 1000
        p.onFrame(frame()) // 队列满：第 1 帧被挤掉，第 2 帧保留

        assertThat(p.stats().queuedFrames).isEqualTo(1)
        assertThat(p.stats().droppedFrames).isEqualTo(1)

        p.onAudioFrameSent()
        assertThat(sent).hasSize(1)
        // 发出去的必须是最新帧（fake 第 2 次压缩，首字节序号=2）
        assertThat(sent[0][0].toInt()).isEqualTo(2)
        p.stop()
    }

    @Test
    fun unsupported_executor_skips_encode_and_send() = runBlocking {
        // 能力矩阵：OpenAI/端侧画像 —— 连编码都不做，更不发送
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec, supports = { false }) { sent += it }
        p.start()

        p.onFrame(frame())
        clock += 1000
        p.onFrame(frame())
        p.onAudioFrameSent()

        assertThat(codec.compressCount).isEqualTo(0)
        assertThat(sent).isEmpty()
        assertThat(p.stats()).isEqualTo(VisionUplinkStats(0, 0, 0, 0, 0))
        p.stop()
    }

    @Test
    fun fps_gate_drops_frames_arriving_within_interval() = runBlocking {
        val codec = FakeCodec()
        val p = newPipeline(codec) {}
        p.start()

        p.onFrame(frame())      // t=1000 接收
        clock += 400
        p.onFrame(frame())      // t=1400 间隔不足 1000ms：fps 闸门丢弃，不进编码
        clock += 600
        p.onFrame(frame())      // t=2000 接收并编码

        assertThat(codec.compressCount).isEqualTo(2)
        // 1 次 fps 丢弃 + 1 次容量=1 队列挤旧
        assertThat(p.stats().droppedFrames).isEqualTo(2)
        assertThat(p.stats().queuedFrames).isEqualTo(1)
        p.stop()
    }

    @Test
    fun oversized_frame_is_rejected_and_never_queued() = runBlocking {
        // fake 恒产 300KB raw → b64 400KB，所有阶梯失败，encode 返回 null
        val codec = FakeCodec(fixedSize = 300_000)
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec) { sent += it }
        p.start()

        p.onFrame(frame())
        p.onAudioFrameSent()

        assertThat(sent).isEmpty()
        assertThat(p.stats().rejectedFrames).isEqualTo(1)
        assertThat(p.stats().queuedFrames).isEqualTo(0)
        p.stop()
    }

    @Test
    fun capability_revoked_after_hot_switch_clears_queue() = runBlocking {
        // 模拟双模热切换：帧已按百炼能力编码入队，切到 OpenAI 后下一音频轮必须清队
        var supported = true
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec, supports = { supported }) { sent += it }
        p.start()

        p.onFrame(frame())
        assertThat(p.stats().queuedFrames).isEqualTo(1)
        supported = false

        p.onAudioFrameSent()
        assertThat(sent).isEmpty()
        assertThat(p.stats().queuedFrames).isEqualTo(0)
        assertThat(p.stats().droppedFrames).isEqualTo(1)
        p.stop()
    }

    @Test
    fun session_frame_budget_caps_actual_uplink() = runBlocking {
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(
            codec,
            VisionUplinkConfig(frameIntervalMs = 1000, maxQueuedFrames = 2, maxFramesPerSession = 1),
        ) { sent += it }
        p.start()

        p.onFrame(frame())
        clock += 1000
        p.onFrame(frame())
        p.onAudioFrameSent() // 第 1 帧放行，预算用尽
        p.onAudioFrameSent() // 队列里的第 2 帧必须被清掉，不得发送

        assertThat(sent).hasSize(1)
        assertThat(p.stats().droppedFrames).isEqualTo(1)
        p.stop()
    }

    @Test
    fun stop_resets_all_state_and_restart_works() = runBlocking {
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec) { sent += it }
        p.start()
        p.onFrame(frame())
        p.onAudioFrameSent()
        assertThat(p.stats().sentFrames).isEqualTo(1)

        p.stop()
        assertThat(p.stats()).isEqualTo(VisionUplinkStats(0, 0, 0, 0, 0))

        // stop 后迟到的相机/音频回调必须是空操作
        p.onFrame(frame())
        p.onAudioFrameSent()
        assertThat(p.stats()).isEqualTo(VisionUplinkStats(0, 0, 0, 0, 0))

        // 同一会话对象 start 复用：计数重新累计
        p.start()
        p.onFrame(frame())
        p.onAudioFrameSent()
        assertThat(p.stats().sentFrames).isEqualTo(1)
        p.stop()
    }

    @Test
    fun disabled_pipeline_never_accepts_frames() = runBlocking {
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec, VisionUplinkConfig.DISABLED) { sent += it }
        p.start()

        p.onFrame(frame())
        p.onAudioFrameSent()

        assertThat(codec.compressCount).isEqualTo(0)
        assertThat(sent).isEmpty()
        p.stop()
    }

    @Test
    fun local_sink_receives_frames_even_when_uplink_unsupported() = runBlocking {
        // V2：OpenAI/端侧画像不上云，但磁盘帧环必须照常拿到编码产物
        val codec = FakeCodec()
        val sunk = mutableListOf<Int>()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec, supports = { false }, sink = { _, enc -> sunk += enc.finalQuality }) { sent += it }
        p.start()

        p.onFrame(frame())
        p.onAudioFrameSent()

        assertThat(codec.compressCount).isEqualTo(1)
        assertThat(sunk).containsExactly(80)
        assertThat(sent).isEmpty() // 上行仍严格门控
        assertThat(p.stats().queuedFrames).isEqualTo(0)
        p.stop()
    }

    @Test
    fun sink_exception_is_swallowed_and_pipeline_survives() = runBlocking {
        // 磁盘满/IO 异常绝不能反噬相机链路
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec, sink = { _, _ -> throw RuntimeException("disk full") }) { sent += it }
        p.start()

        p.onFrame(frame())
        clock += 1000
        p.onFrame(frame())
        p.onAudioFrameSent()
        p.onAudioFrameSent()

        assertThat(sent).hasSize(1) // 上行不受 sink 爆炸影响（第2帧挤掉第1帧）
        p.stop()
    }

    @Test
    fun uplink_pause_keeps_sink_but_never_sends() = runBlocking {
        // 回放窗口：实时帧不上行，但本地帧环继续落盘
        val codec = FakeCodec()
        val sunk = mutableListOf<Int>()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec, sink = { _, enc -> sunk += enc.finalQuality }) { sent += it }
        p.start()

        p.setUplinkPaused(true)
        p.onFrame(frame())
        clock += 1000
        p.onFrame(frame())
        p.onAudioFrameSent()
        p.onAudioFrameSent()

        assertThat(sent).isEmpty()
        assertThat(sunk).hasSize(2)
        assertThat(p.stats().queuedFrames).isEqualTo(0)

        // 恢复后新帧重新走上行
        p.setUplinkPaused(false)
        clock += 1000
        p.onFrame(frame())
        p.onAudioFrameSent()
        assertThat(sent).hasSize(1)
        p.stop()
    }

    @Test
    fun connection_reset_discards_pending_frames_and_restarts_gate_and_recent_stats() {
        clock = 0L // A first frame at monotonic time zero must also pass the gate.
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(FakeCodec()) { sent += it }
        p.start()
        p.onFrame(frame())
        p.onAudioFrameSent()
        assertThat(p.stats().lastSentAtMs).isEqualTo(0L)
        assertThat(p.stats().recentFps).isWithin(0.00001f).of(0.2f)

        clock = 1_000L
        p.onFrame(frame())
        assertThat(p.stats().queuedFrames).isEqualTo(1)
        p.resetConnection()
        val reset = p.stats()
        assertThat(reset.sentFrames).isEqualTo(1)
        assertThat(reset.droppedFrames).isEqualTo(1)
        assertThat(reset.queuedFrames).isEqualTo(0)
        assertThat(reset.lastSentAtMs).isNull()
        assertThat(reset.lastSentBase64Bytes).isEqualTo(0)
        assertThat(reset.recentFps).isEqualTo(0f)

        // The previous acceptance time must not throttle the replacement session.
        clock = 1_001L
        p.onFrame(frame())
        assertThat(p.stats().queuedFrames).isEqualTo(1)
        assertThat(sent).hasSize(1) // New connection still needs its own audio enqueue.
        p.onAudioFrameSent()
        assertThat(sent.map { it[0].toInt() }).containsExactly(1, 3).inOrder()
        assertThat(p.stats().lastSentAtMs).isEqualTo(1_001L)
        assertThat(p.stats().recentFps).isWithin(0.00001f).of(0.2f)
        p.stop()
    }

    @Test
    fun encoding_that_finishes_after_connection_reset_never_enters_new_session() {
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec) { sent += it }
        codec.afterCompress = {
            codec.afterCompress = null
            // Simulates a reset racing a synchronous/non-cancellable JPEG codec.
            p.resetConnection()
        }
        p.start()
        p.onFrame(frame())
        assertThat(p.stats().queuedFrames).isEqualTo(0)
        assertThat(p.stats().droppedFrames).isEqualTo(1)
        p.onAudioFrameSent()
        assertThat(sent).isEmpty()

        p.onFrame(frame()) // Same clock is accepted after reset.
        p.onAudioFrameSent()
        assertThat(sent.single()[0].toInt()).isEqualTo(2)
        p.stop()
    }

    @Test
    fun late_encoder_after_stop_and_restart_cannot_pollute_new_session_or_history() {
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val history = mutableListOf<Int>()
        val p = newPipeline(codec, sink = { _, encoded -> history += encoded.jpeg[0].toInt() }) { sent += it }
        codec.afterCompress = {
            codec.afterCompress = null
            p.stop()
            p.start()
        }
        p.start()
        p.onFrame(frame())
        assertThat(p.stats()).isEqualTo(VisionUplinkStats(0, 0, 0, 0, 0))
        assertThat(history).isEmpty()
        p.onAudioFrameSent()
        assertThat(sent).isEmpty()

        p.onFrame(frame())
        p.onAudioFrameSent()
        assertThat(sent.single()[0].toInt()).isEqualTo(2)
        assertThat(history).containsExactly(2)
        p.stop()
    }

    @Test
    fun reset_also_discards_work_still_waiting_for_encoder() = runTest {
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec, scope = this) { sent += it }
        p.start()
        p.onFrame(frame(marker = 1)) // The test dispatcher has not run the encoder yet.
        p.resetConnection()
        runCurrent()
        assertThat(codec.compressCount).isEqualTo(0)
        assertThat(p.stats().droppedFrames).isEqualTo(1)

        p.onFrame(frame(marker = 2))
        runCurrent()
        p.onAudioFrameSent()
        assertThat(codec.lastInputMarker).isEqualTo(2)
        assertThat(sent).hasSize(1)
        p.stop()
        runCurrent()
    }

    @Test
    fun encoder_conflation_counts_only_actual_replacements_and_keeps_latest_frame() = runTest {
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec, scope = this) { sent += it }
        p.start()
        p.onFrame(frame(marker = 1))
        clock += 1_000L
        p.onFrame(frame(marker = 2))
        clock += 1_000L
        p.onFrame(frame(marker = 3))
        assertThat(p.stats().droppedFrames).isEqualTo(2)
        runCurrent()
        assertThat(codec.compressCount).isEqualTo(1)
        assertThat(codec.lastInputMarker).isEqualTo(3)
        p.onAudioFrameSent()
        assertThat(sent).hasSize(1)
        p.stop()
        runCurrent()
    }

    @Test
    fun expired_queued_frame_is_skipped_and_fresh_frame_can_send_on_same_audio_block() {
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(
            FakeCodec(),
            VisionUplinkConfig(maxQueuedFrames = 3, maxFrameAgeMs = 1_500L),
        ) { sent += it }
        p.start()
        p.onFrame(frame(timestampNs = Long.MAX_VALUE))
        clock += 1_000L
        p.onFrame(frame(timestampNs = 0L))
        clock += 1_000L
        p.onAudioFrameSent()
        assertThat(sent.single()[0].toInt()).isEqualTo(2)
        assertThat(p.stats().droppedFrames).isEqualTo(1)
        assertThat(p.stats().queuedFrames).isEqualTo(0)
        p.stop()
    }

    @Test
    fun time_spent_encoding_counts_toward_frame_age() {
        val codec = FakeCodec()
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(codec) { sent += it }
        codec.afterCompress = { clock += 3_001L }
        p.start()
        p.onFrame(frame(timestampNs = Long.MAX_VALUE))
        p.onAudioFrameSent()
        assertThat(sent).isEmpty()
        assertThat(p.stats().queuedFrames).isEqualTo(0)
        assertThat(p.stats().droppedFrames).isEqualTo(1)
        p.stop()
    }

    @Test
    fun max_age_boundary_is_allowed_but_older_frame_is_discarded() {
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(FakeCodec()) { sent += it }
        p.start()
        p.onFrame(frame())
        clock += 3_000L
        p.onAudioFrameSent()
        assertThat(sent).hasSize(1)
        clock += 1_000L
        p.onFrame(frame())
        clock += 3_001L
        p.onAudioFrameSent()
        assertThat(sent).hasSize(1)
        assertThat(p.stats().droppedFrames).isEqualTo(1)
        p.stop()
    }

    @Test
    fun recent_fps_uses_successful_enqueues_and_ages_out_without_new_camera_frames() {
        var rejectNextSend = true
        val sent = mutableListOf<ByteArray>()
        val p = newPipeline(FakeCodec()) {
            if (rejectNextSend) {
                rejectNextSend = false
                error("transport queue rejected frame")
            }
            sent += it
        }
        p.start()
        p.onFrame(frame())
        p.onAudioFrameSent()
        assertThat(p.stats().sentFrames).isEqualTo(0)
        assertThat(p.stats().lastSentAtMs).isNull()
        assertThat(p.stats().recentFps).isEqualTo(0f)

        clock = 2_000L
        p.onFrame(frame())
        p.onAudioFrameSent()
        clock = 3_000L
        p.onFrame(frame())
        p.onAudioFrameSent()
        assertThat(p.stats().sentFrames).isEqualTo(2)
        assertThat(p.stats().recentFps).isWithin(0.00001f).of(0.4f)
        assertThat(p.stats().lastSentAtMs).isEqualTo(3_000L)
        clock = 7_000L
        assertThat(p.stats().recentFps).isWithin(0.00001f).of(0.2f)
        clock = 8_000L
        assertThat(p.stats().recentFps).isEqualTo(0f)
        assertThat(p.stats().lastSentAtMs).isEqualTo(3_000L)
        p.resetConnection()
        assertThat(p.stats().lastSentAtMs).isNull()
        assertThat(p.stats().recentFps).isEqualTo(0f)
        assertThat(p.stats().sentFrames).isEqualTo(2)
        p.stop()
    }

    @Test
    fun maximum_frame_age_must_be_positive() {
        assertThrows(IllegalArgumentException::class.java) { VisionUplinkConfig(maxFrameAgeMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { VisionUplinkConfig(maxFrameAgeMs = -1) }
    }
}

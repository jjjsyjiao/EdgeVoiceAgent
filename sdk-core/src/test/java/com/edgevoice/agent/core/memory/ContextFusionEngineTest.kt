package com.edgevoice.agent.core.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 多模态时间戳对齐：±1.5s 窗口、运动帧计数、窗口外帧不描述、容量裁剪。 */
class ContextFusionEngineTest {

    private fun frame(ts: Long, motion: Float = 0f) =
        ContextItem(ts, ContextKind.VISION_FRAME, "", frameRef = "f-$ts", motionScore = motion)

    @Test
    fun describe_returns_null_when_no_frame_in_window() {
        val fusion = ContextFusionEngine()
        fusion.onVisionFrame(frame(10_000L))
        // 开口时间与帧相差超过 1.5s
        assertThat(fusion.describe(20_000L)).isNull()
    }

    @Test
    fun describe_counts_aligned_frames_and_moving_ones() {
        val fusion = ContextFusionEngine(maxAlignDeltaMs = 1_500L)
        // 开口时刻 10_000：前 1.2s/前 0.5s/后 0.3s 各一帧
        fusion.onVisionFrame(frame(8_800L, motion = 0.02f)) // 窗口内，静止
        fusion.onVisionFrame(frame(9_500L, motion = 0.30f)) // 窗口内，运动
        fusion.onVisionFrame(frame(10_300L, motion = 0.40f)) // 窗口内，运动
        fusion.onVisionFrame(frame(20_000L, motion = 0.90f)) // 窗口外，不计

        val text = fusion.describe(10_000L)
        assertThat(text).isNotNull()
        assertThat(text).contains("共 3 帧画面")
        assertThat(text).contains("运动帧 2 个")
        // 最近一帧滞后 = 10_000 - 10_300 = -300ms（后于开口也对齐）
        assertThat(text).contains("滞后 -300ms")
    }

    @Test
    fun align_window_boundary_is_inclusive() {
        val fusion = ContextFusionEngine(maxAlignDeltaMs = 1_500L)
        fusion.onVisionFrame(frame(8_500L)) // 恰好相差 1500ms：边界内
        fusion.onVisionFrame(frame(8_499L)) // 超出 1ms：窗口外
        val aligned = fusion.alignTo(10_000L)
        assertThat(aligned.map { it.timestampMs }).containsExactly(8_500L)
    }

    @Test
    fun oldest_frames_are_evicted_beyond_capacity() {
        val fusion = ContextFusionEngine(maxFrames = 3)
        repeat(5) { fusion.onVisionFrame(frame(it.toLong())) }
        val aligned = fusion.alignTo(100L) // 窗口足够大
        // 只保留最近 3 帧
        assertThat(aligned.map { it.timestampMs }).containsExactly(2L, 3L, 4L).inOrder()
    }
}

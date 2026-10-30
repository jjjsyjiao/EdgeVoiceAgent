package com.edgevoice.agent.media.video

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer

/** 智能抽帧的运动检测：Y 平面降采样 MAD 归一化与阈值。 */
class MotionDetectorTest {

    /** 构造 width×height、像素初值 [fill] 的紧凑 Y 平面（rowStride=width, pixelStride=1）。 */
    private fun yPlane(width: Int, height: Int, fill: (Int) -> Int): ByteBuffer {
        val arr = ByteArray(width * height) { idx -> fill(idx).toByte() }
        return ByteBuffer.wrap(arr)
    }

    @Test
    fun first_frame_has_zero_score_no_baseline() {
        val detector = MotionDetector()
        val score = detector.onYPlane(yPlane(64, 64) { 100 }, 64, 64, 64, 1)
        assertThat(score).isEqualTo(0f)
        assertThat(detector.isMotion(score)).isFalse()
    }

    @Test
    fun identical_consecutive_frames_have_zero_score() {
        val detector = MotionDetector()
        detector.onYPlane(yPlane(64, 64) { (it % 200) }, 64, 64, 64, 1)
        val score = detector.onYPlane(yPlane(64, 64) { (it % 200) }, 64, 64, 64, 1)
        assertThat(score).isEqualTo(0f)
    }

    @Test
    fun full_luminance_flip_scores_one_and_triggers_motion() {
        val detector = MotionDetector(motionThreshold = 0.06f)
        detector.onYPlane(yPlane(64, 64) { 0 }, 64, 64, 64, 1)
        val score = detector.onYPlane(yPlane(64, 64) { 255 }, 64, 64, 64, 1)
        // 每个采样点差 255：sum/(n*255) = 1.0
        assertThat(score).isEqualTo(1f)
        assertThat(detector.isMotion(score)).isTrue()
    }

    @Test
    fun small_change_below_threshold_is_still() {
        val detector = MotionDetector(motionThreshold = 0.06f, sampleStride = 8)
        // 64x64 降采样到 8x8=64 点。只翻转其中 1 个采样点亮度 +255：
        // 总分 255/(64*255) = 1/64 ≈ 0.0156 < 0.06
        detector.onYPlane(yPlane(64, 64) { 0 }, 64, 64, 64, 1)
        val score = detector.onYPlane(
            yPlane(64, 64) { idx -> if (idx == 0) 255 else 0 },
            64, 64, 64, 1,
        )
        assertThat(score).isWithin(1e-6f).of(1f / 64f)
        assertThat(detector.isMotion(score)).isFalse()
    }

    @Test
    fun reset_clears_baseline() {
        val detector = MotionDetector()
        detector.onYPlane(yPlane(64, 64) { 0 }, 64, 64, 64, 1)
        detector.onYPlane(yPlane(64, 64) { 255 }, 64, 64, 64, 1)
        detector.reset()
        // 重置后来一帧视为首帧：分数归零，不与旧基线比较
        val score = detector.onYPlane(yPlane(64, 64) { 255 }, 64, 64, 64, 1)
        assertThat(score).isEqualTo(0f)
    }
}

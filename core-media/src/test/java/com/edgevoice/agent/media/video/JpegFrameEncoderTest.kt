package com.edgevoice.agent.media.video

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [JpegFrameEncoder] 闸门编排验证（注入 [FrameJpegCodec] 的 fake，不碰 android.graphics）。
 * 阈值口径：256KB Base64 ≈ raw 196.6KB；raw 200_000 → b64 266_668 必超，196_000 → 261_336 可过。
 */
class JpegFrameEncoderTest {

    /** 记录全部调用，并按测试给定的规则决定产出 JPEG 字节数；resize 按 16:9 取偶。 */
    private class FakeCodec(
        val onCompress: (w: Int, h: Int, q: Int) -> Int = { _, _, _ -> 100 },
        val onResize: (longEdge: Int, q: Int) -> Int = { _, _ -> 100 },
    ) : FrameJpegCodec {
        val compressCalls = mutableListOf<Triple<Int, Int, Int>>()
        val resizeCalls = mutableListOf<Pair<Int, Int>>()

        override fun compress(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
            compressCalls += Triple(width, height, quality)
            return ByteArray(onCompress(width, height, quality))
        }

        override fun resize(jpeg: ByteArray, longEdge: Int, quality: Int): ResizedJpeg {
            resizeCalls += longEdge to quality
            val nh = (longEdge * 9 / 16).let { it - it % 2 }
            return ResizedJpeg(ByteArray(onResize(longEdge, quality)), longEdge, nh)
        }
    }

    private fun nv21(w: Int, h: Int) = ByteArray(w * h * 3 / 2)

    @Test
    fun first_try_q80_passes_and_uses_rotated_dimensions() {
        val codec = FakeCodec()
        val encoder = JpegFrameEncoder(codec)

        // 1280x720 顺时针 90° → 编码输入应为 720x1280（竖屏正向）
        val frame = encoder.encode(nv21(1280, 720), 1280, 720, 90)!!

        assertThat(frame.finalQuality).isEqualTo(80)
        assertThat(frame.width).isEqualTo(720)
        assertThat(frame.height).isEqualTo(1280)
        assertThat(codec.compressCalls).containsExactly(Triple(720, 1280, 80))
        assertThat(codec.resizeCalls).isEmpty()
    }

    @Test
    fun quality_ladder_steps_down_until_fit() {
        // 80 超、60 过：最终质量 60，且未进入降分辨率轮
        val codec = FakeCodec(onCompress = { _, _, q -> if (q >= 80) 200_000 else 196_000 })
        val encoder = JpegFrameEncoder(codec)

        val frame = encoder.encode(nv21(1280, 720), 1280, 720, 0)!!

        assertThat(frame.finalQuality).isEqualTo(60)
        assertThat(codec.compressCalls.map { it.third }).containsExactly(80, 60).inOrder()
        assertThat(codec.resizeCalls).isEmpty()
    }

    @Test
    fun downscales_when_all_qualities_oversized() {
        // 原尺寸所有质量均超；960 长边档 q80 通过
        val codec = FakeCodec(
            onCompress = { _, _, _ -> 300_000 },
            onResize = { edge, q -> if (edge <= 960 && q <= 80) 196_000 else 300_000 },
        )
        val encoder = JpegFrameEncoder(codec)

        val frame = encoder.encode(nv21(1280, 720), 1280, 720, 0)!!

        assertThat(frame.finalQuality).isEqualTo(80)
        assertThat(frame.width).isEqualTo(960)
        assertThat(frame.height).isEqualTo(540)
        assertThat(codec.resizeCalls.first()).isEqualTo(960 to 80)
        // 原尺寸 4 档质量全部尝试后才降采样
        assertThat(codec.compressCalls.map { it.third }).containsExactly(80, 60, 45, 30).inOrder()
    }

    @Test
    fun returns_null_when_every_ladder_fails() {
        val codec = FakeCodec(
            onCompress = { _, _, _ -> 300_000 },
            onResize = { _, _ -> 300_000 },
        )
        val encoder = JpegFrameEncoder(codec)

        val frame = encoder.encode(nv21(1280, 720), 1280, 720, 0)
        assertThat(frame).isNull()
        // 4 轮（1280/960/720/540）× 4 档质量 = 16 次尝试
        assertThat(codec.compressCalls.size + codec.resizeCalls.size).isEqualTo(16)
        // 降采样轮每档质量各尝试一次；轮次顺序必须是 960→720→540
        assertThat(codec.resizeCalls.map { it.first }.distinct()).containsExactly(960, 720, 540).inOrder()
    }

    @Test
    fun small_source_never_upscales() {
        // 640x480 源：长边候选只保留严格更小的 540；960/720 不得出现（绝不放大）
        val codec = FakeCodec(
            onCompress = { _, _, _ -> 300_000 },
            onResize = { _, _ -> 300_000 },
        )
        val encoder = JpegFrameEncoder(codec)

        assertThat(encoder.encode(nv21(640, 480), 640, 480, 0)).isNull()
        assertThat(codec.resizeCalls.map { it.first }.toSet()).containsExactly(540)
        assertThat(codec.compressCalls.map { it.first }.toSet()).containsExactly(640)
    }

    @Test
    fun base64_size_matches_padded_triplet_rule() {
        assertThat(JpegConstraints.base64EncodedLength(1)).isEqualTo(4)
        assertThat(JpegConstraints.base64EncodedLength(2)).isEqualTo(4)
        assertThat(JpegConstraints.base64EncodedLength(3)).isEqualTo(4)
        assertThat(JpegConstraints.base64EncodedLength(78 * 1024)).isEqualTo(106_496)
    }
}

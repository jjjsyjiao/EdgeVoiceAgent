package com.edgevoice.agent.media.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/** PCM16-LE RMS 归一化（光球律动/VAD 能量门限的基础）。 */
class PcmUtilsTest {

    private fun leSample(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    @Test
    fun silence_is_zero() {
        val pcm = ByteArray(160) // 80 个采样全 0
        assertThat(PcmUtils.rms(pcm)).isEqualTo(0f)
    }

    @Test
    fun positive_full_scale_is_one() {
        val pcm = ByteArray(160)
        for (i in 0 until 80) {
            val s = leSample(32767)
            pcm[i * 2] = s[0]
            pcm[i * 2 + 1] = s[1]
        }
        val rms = PcmUtils.rms(pcm)
        assertThat(abs(rms - 1f)).isLessThan(1e-4f)
    }

    @Test
    fun negative_full_scale_is_clamped_to_one() {
        // -32768(0x8000) 幅值比 32767 大 1/32768，归一化后应被 coerceIn 截到 1.0
        val pcm = ByteArray(4)
        pcm[0] = 0x00
        pcm[1] = 0x80.toByte()
        pcm[2] = 0x00
        pcm[3] = 0x80.toByte()
        assertThat(PcmUtils.rms(pcm)).isEqualTo(1f)
    }

    @Test
    fun half_scale_is_about_half() {
        // 16384 ≈ 32768/2 => rms ≈ 0.5
        val pcm = leSample(16384) + leSample(16384)
        val rms = PcmUtils.rms(pcm)
        assertThat(abs(rms - 0.5f)).isLessThan(1e-4f)
    }

    @Test
    fun empty_or_odd_byte_input_is_zero() {
        assertThat(PcmUtils.rms(ByteArray(0))).isEqualTo(0f)
        // 只有 1 字节构不成一个采样
        assertThat(PcmUtils.rms(byteArrayOf(0x12))).isEqualTo(0f)
    }

    @Test
    fun respects_offset_and_length() {
        // 前后各塞 2 个静音采样，中间 2 个满幅采样；只统计中间区间
        val pcm = leSample(0) + leSample(0) + leSample(32767) + leSample(32767) + leSample(0) + leSample(0)
        val rms = PcmUtils.rms(pcm, offset = 4, length = 4)
        assertThat(abs(rms - 1f)).isLessThan(1e-4f)
    }
}

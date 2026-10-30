package com.edgevoice.agent.media.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 音频规格换算（24kHz 单声道 PCM16 是端云统一规格）。 */
class AudioConfigTest {

    private val cfg = AudioConfig.REALTIME_UP

    @Test
    fun realtime_spec_is_24k_mono_pcm16() {
        assertThat(cfg.sampleRateHz).isEqualTo(24_000)
        assertThat(cfg.channelCount).isEqualTo(1)
        assertThat(cfg.bitsPerSample).isEqualTo(16)
        assertThat(cfg.bytesPerSample).isEqualTo(2)
        assertThat(cfg.frameSizeBytes).isEqualTo(2)
    }

    @Test
    fun twenty_ms_frame_is_960_bytes_at_24k() {
        // 24000 samples/s * 2 bytes * 0.02s = 960 bytes（上行泵帧长）
        assertThat(cfg.bytesFor(20)).isEqualTo(960L)
    }

    @Test
    fun bytes_and_millis_are_inverse() {
        listOf(20, 60, 120, 500).forEach { ms ->
            assertThat(cfg.millisFor(cfg.bytesFor(ms))).isEqualTo(ms)
        }
    }

    @Test
    fun ring_capacity_500ms_is_24000_bytes() {
        // Stage 默认 ringCapacityMs=500：24000*2*500/1000 = 24000
        assertThat(cfg.bytesFor(500)).isEqualTo(24_000L)
    }
}

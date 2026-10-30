package com.edgevoice.agent.media.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCaptureBufferTest {
    @Test
    fun platform_read_without_position_advance_exposes_actual_pcm_bytes() {
        val direct = ByteBuffer.allocateDirect(40).order(ByteOrder.LITTLE_ENDIAN)
        // AudioRecord writes to the base address and leaves position at zero.
        direct.putShort(0, 8_000)
        direct.putShort(2, (-8_000).toShort())
        assertThat(direct.position()).isEqualTo(0)
        capturedPcmWindow(direct, 4)
        assertThat(direct.position()).isEqualTo(0)
        assertThat(direct.remaining()).isEqualTo(4)
        assertThat(PcmUtils.rms(direct, 4)).isGreaterThan(0f)
    }

    @Test
    fun next_read_discards_stale_position_and_previous_limit() {
        val direct = ByteBuffer.allocateDirect(40)
        direct.position(30)
        capturedPcmWindow(direct, 12)
        assertThat(direct.position()).isEqualTo(0)
        assertThat(direct.limit()).isEqualTo(12)
        capturedPcmWindow(direct, 0)
        assertThat(direct.remaining()).isEqualTo(0)
    }
}

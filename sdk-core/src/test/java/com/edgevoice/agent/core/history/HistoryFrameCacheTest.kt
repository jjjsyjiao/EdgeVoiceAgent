package com.edgevoice.agent.core.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistoryFrameCacheTest {
    @Test fun freshnessUsesCaptureReceiptNotEncoderCompletion() {
        val cache = HistoryFrameCache()
        cache.accepted(999_999_999L, 100, 1_000)
        cache.encoded(999_999_999L, byteArrayOf(1), 3_101)
        assertThat(cache.snapshot(3_101)).isNull()
    }

    @Test fun frozenSnapshotKeepsSourceTimeAndCannotBeMutatedByLaterFrame() {
        val cache = HistoryFrameCache()
        cache.accepted(1, 100, 1_000)
        val bytes = byteArrayOf(1)
        cache.encoded(1, bytes, 200)
        bytes[0] = 9
        val frozen = cache.snapshot(300)!!
        cache.accepted(2, 400, 1_300)
        cache.encoded(2, byteArrayOf(2), 500)
        assertThat(frozen.capturedAtMs).isEqualTo(1_000)
        assertThat(frozen.jpeg.toList()).containsExactly(1.toByte())
        assertThat(cache.snapshot(3_401)).isNull()
    }

    @Test fun lateEncodingFromPreviousSessionCannotRepopulateCache() {
        val cache = HistoryFrameCache()
        cache.accepted(1, 100, 1_000)
        cache.clear()
        cache.encoded(1, byteArrayOf(1), 200)
        assertThat(cache.snapshot(200)).isNull()
    }
}

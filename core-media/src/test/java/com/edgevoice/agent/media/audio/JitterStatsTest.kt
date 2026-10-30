package com.edgevoice.agent.media.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Stage 8 新增的抖动统计：异常分片占比。
 * （JitterBuffer 本体依赖 NDK RingBuffer，纯 JVM 不可测；其统计聚合是纯数据计算。）
 */
class JitterStatsTest {

    @Test
    fun zero_traffic_has_zero_loss() {
        val stats = JitterStats(pushedChunks = 0, underruns = 0, droppedChunks = 0, bufferedMs = 0)
        assertThat(stats.lossRate).isEqualTo(0f)
    }

    @Test
    fun loss_rate_is_underruns_plus_drops_over_pushed() {
        val stats = JitterStats(
            pushedChunks = 200,
            underruns = 3,
            droppedChunks = 7,
            bufferedMs = 80,
        )
        // (3+7)/200 = 0.05
        assertThat(stats.lossRate).isWithin(1e-6f).of(0.05f)
        assertThat(stats.bufferedMs).isEqualTo(80)
    }

    @Test
    fun healthy_traffic_has_zero_loss() {
        val stats = JitterStats(pushedChunks = 1_000, underruns = 0, droppedChunks = 0, bufferedMs = 60)
        assertThat(stats.lossRate).isEqualTo(0f)
    }
}

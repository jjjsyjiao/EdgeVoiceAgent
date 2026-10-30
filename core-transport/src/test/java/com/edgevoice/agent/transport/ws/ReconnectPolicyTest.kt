package com.edgevoice.agent.transport.ws

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 指数退避 + 抖动 + 有界重试（纯 Kotlin，确定性部分严格断言、抖动断言区间）。 */
class ReconnectPolicyTest {

    private val policy = ReconnectPolicy(baseMs = 1_000L, maxDelayMs = 30_000L, jitterMs = 500L, maxAttempts = 8)

    @Test
    fun delay_grows_exponentially_before_cap() {
        // 抖动在 [0,500)：断言「指数底 <= delay < 指数底+500」区间
        // attempt 0 => 1000 + jitter
        val d0 = policy.nextDelayMs(0)!!
        assertThat(d0).isAtLeast(1_000L)
        assertThat(d0).isLessThan(1_500L)

        // attempt 4 => 16000 + jitter（未触顶）
        val d4 = policy.nextDelayMs(4)!!
        assertThat(d4).isAtLeast(16_000L)
        assertThat(d4).isLessThan(16_500L)
    }

    @Test
    fun delay_is_capped_at_max() {
        // 1000 shl 5 = 32000 > 30000 上限
        val d5 = policy.nextDelayMs(5)!!
        assertThat(d5).isAtLeast(30_000L)
        assertThat(d5).isLessThan(30_500L)

        // 继续重试也永远不超过上限+抖动
        val d6 = policy.nextDelayMs(6)!!
        assertThat(d6).isAtLeast(30_000L)
        assertThat(d6).isLessThan(30_500L)
    }

    @Test
    fun returns_null_after_max_attempts() {
        // attempt 从 0 计：0..7 共 8 次，第 8 次越界
        assertThat(policy.nextDelayMs(7)).isNotNull()
        assertThat(policy.nextDelayMs(8)).isNull()
        assertThat(policy.nextDelayMs(100)).isNull()
    }

    @Test
    fun shift_is_guarded_against_overflow_for_large_attempts() {
        // 放宽重试上限才能触达大 attempt：shl 被 coerceAtMost(15) 限制，返回封顶值而非溢出
        val lenient = ReconnectPolicy(maxAttempts = 100)
        val d = lenient.nextDelayMs(15)!!
        assertThat(d).isAtLeast(30_000L)
        assertThat(d).isLessThan(30_500L)
    }
}

package com.edgevoice.agent.core.governance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 熔断三态流转（注入虚拟时钟，不依赖真实时间）：
 * CLOSED --失败达阈值--> OPEN --冷却到期+放行--> HALF_OPEN --再失败--> OPEN；
 * HALF_OPEN 成功则恢复 CLOSED。
 */
class CircuitBreakerTest {

    private var now = 1_000_000L
    private val breaker = CircuitBreaker(
        failureThreshold = 3,
        openDurationMs = 30_000L,
        clock = { now },
    )

    @Test
    fun starts_closed_and_allows() {
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(breaker.allow()).isTrue()
    }

    @Test
    fun opens_after_threshold_failures() {
        breaker.onFailure()
        breaker.onFailure()
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.CLOSED)
        breaker.onFailure() // 第 3 次达阈值
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.OPEN)
        // 冷却期内快速失败
        assertThat(breaker.allow()).isFalse()
    }

    @Test
    fun half_open_after_cooldown_and_closes_on_success() {
        repeat(3) { breaker.onFailure() }
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.OPEN)

        now += 30_000L // 冷却到期
        assertThat(breaker.allow()).isTrue()
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.HALF_OPEN)

        breaker.onSuccess()
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun failure_in_half_open_reopens_immediately() {
        repeat(3) { breaker.onFailure() }
        now += 30_000L
        assertThat(breaker.allow()).isTrue() // -> HALF_OPEN

        breaker.onFailure() // 探针失败：无需再凑阈值，立即重新 OPEN
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.OPEN)
        assertThat(breaker.allow()).isFalse()
    }

    @Test
    fun success_resets_failure_count() {
        breaker.onFailure()
        breaker.onFailure()
        breaker.onSuccess() // 未熔断前成功也清零计数
        breaker.onFailure()
        breaker.onFailure()
        // 只有连续 2 次失败（计数被成功重置），未达阈值
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.CLOSED)
    }
}

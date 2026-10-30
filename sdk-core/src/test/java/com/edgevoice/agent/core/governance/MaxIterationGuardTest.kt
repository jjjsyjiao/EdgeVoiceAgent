package com.edgevoice.agent.core.governance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 死循环阻断：深度计数、超深 blocked 累计、onTerminal 归零。 */
class MaxIterationGuardTest {

    @Test
    fun enter_allows_within_limit_and_blocks_beyond() {
        val guard = MaxIterationGuard(maxIterations = 3)
        assertThat(guard.enter()).isTrue() // depth 1
        assertThat(guard.enter()).isTrue() // depth 2
        assertThat(guard.enter()).isTrue() // depth 3
        assertThat(guard.currentDepth()).isEqualTo(3)

        assertThat(guard.enter()).isFalse() // depth 4 超深
        assertThat(guard.blockedCount()).isEqualTo(1)
        assertThat(guard.enter()).isFalse()
        assertThat(guard.blockedCount()).isEqualTo(2)
    }

    @Test
    fun onTerminal_resets_depth_but_keeps_blocked_total() {
        val guard = MaxIterationGuard(maxIterations = 1)
        guard.enter()
        guard.enter() // blocked
        assertThat(guard.blockedCount()).isEqualTo(1)

        guard.onTerminal() // 一轮正常结束
        assertThat(guard.currentDepth()).isEqualTo(0)
        // 新一轮重新计数
        assertThat(guard.enter()).isTrue()
        // blocked 是会话级累计指标，不归零
        assertThat(guard.blockedCount()).isEqualTo(1)
    }

    @Test
    fun reset_clears_depth_and_blocked() {
        val guard = MaxIterationGuard(maxIterations = 1)
        guard.enter()
        guard.enter()
        guard.reset()
        assertThat(guard.currentDepth()).isEqualTo(0)
        assertThat(guard.blockedCount()).isEqualTo(0)
    }
}

package com.edgevoice.agent.core.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 五态迁移表（AgentState.canTransitionTo）与状态机串行迁移的单测。 */
class AgentStateMachineTest {

    @Test
    fun idle_can_only_enter_listening_or_processing() {
        val idle = AgentState.IDLE_OBSERVING
        assertThat(idle.canTransitionTo(AgentState.LISTENING)).isTrue()
        assertThat(idle.canTransitionTo(AgentState.PROCESSING)).isTrue()
        // 未开始监听不能直接说话/打断
        assertThat(idle.canTransitionTo(AgentState.SPEAKING)).isFalse()
        assertThat(idle.canTransitionTo(AgentState.INTERRUPTED)).isFalse()
    }

    @Test
    fun any_active_state_can_be_preempted_by_interrupted() {
        listOf(AgentState.LISTENING, AgentState.PROCESSING, AgentState.SPEAKING).forEach { s ->
            assertThat(s.canTransitionTo(AgentState.INTERRUPTED)).isTrue()
        }
        // barge-in 之后只能回到聆听或空闲，不能直接回 SPEAKING/PROCESSING
        val interrupted = AgentState.INTERRUPTED
        assertThat(interrupted.canTransitionTo(AgentState.LISTENING)).isTrue()
        assertThat(interrupted.canTransitionTo(AgentState.IDLE_OBSERVING)).isTrue()
        assertThat(interrupted.canTransitionTo(AgentState.SPEAKING)).isFalse()
        assertThat(interrupted.canTransitionTo(AgentState.INTERRUPTED)).isTrue()
    }

    @Test
    fun transitionTo_applies_legal_and_rejects_illegal() {
        val sm = AgentStateMachine(AgentState.IDLE_OBSERVING)

        assertThat(sm.transitionTo(AgentState.LISTENING)).isTrue()
        assertThat(sm.state.value).isEqualTo(AgentState.LISTENING)

        // LISTENING -> SPEAKING 合法
        assertThat(sm.transitionTo(AgentState.SPEAKING)).isTrue()
        assertThat(sm.state.value).isEqualTo(AgentState.SPEAKING)
    }

    @Test
    fun transitionTo_illegal_keeps_current_state() {
        val sm = AgentStateMachine(AgentState.IDLE_OBSERVING)
        assertThat(sm.transitionTo(AgentState.SPEAKING)).isFalse()
        assertThat(sm.state.value).isEqualTo(AgentState.IDLE_OBSERVING)
    }

    @Test
    fun transitionTo_same_state_is_idempotent_success() {
        val sm = AgentStateMachine(AgentState.LISTENING)
        // 同态视为成功（高频重复事件不应被当作非法迁移）
        assertThat(sm.transitionTo(AgentState.LISTENING)).isTrue()
        assertThat(sm.state.value).isEqualTo(AgentState.LISTENING)
    }

    @Test
    fun forceTo_skips_validation() {
        val sm = AgentStateMachine(AgentState.IDLE_OBSERVING)
        // 即使是非法直连，forceTo 也必须生效（初始化/复位/异常兜底场景）
        sm.forceTo(AgentState.SPEAKING)
        assertThat(sm.state.value).isEqualTo(AgentState.SPEAKING)
    }
}

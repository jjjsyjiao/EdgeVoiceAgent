package com.edgevoice.agent.core.state

/**
 * Agent 生命周期状态（对齐设计规范文档 §3.1 灵动球状态绑定）。
 *
 * IDLE_OBSERVING -> LISTENING -> PROCESSING -> SPEAKING，
 * 任一态均可被 INTERRUPTED 抢占（barge-in），INTERRUPTED 后回归 LISTENING。
 */
enum class AgentState {
    IDLE_OBSERVING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    INTERRUPTED;

    /**
     * 合法状态迁移判定。
     * 说明：实时双工场景下迁移极为频繁（尤其打断），这里只挡明显非法迁移，
     * 不追求严格 FSM —— 过度约束反而会在打断瞬间卡住状态。
     */
    fun canTransitionTo(next: AgentState): Boolean {
        if (next == this) return true
        return when (this) {
            IDLE_OBSERVING -> next == LISTENING || next == PROCESSING
            LISTENING -> next == PROCESSING || next == SPEAKING || next == IDLE_OBSERVING || next == INTERRUPTED
            PROCESSING -> next == SPEAKING || next == LISTENING || next == IDLE_OBSERVING || next == INTERRUPTED
            SPEAKING -> next == LISTENING || next == PROCESSING || next == IDLE_OBSERVING || next == INTERRUPTED
            INTERRUPTED -> next == LISTENING || next == IDLE_OBSERVING
        }
    }
}

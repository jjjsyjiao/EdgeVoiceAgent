package com.edgevoice.agent.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 线程安全的状态机。UI 层通过 [state]（StateFlow）响应式订阅，符合单向数据流原则。
 *
 * 并发：迁移用 `synchronized`（对标 Java 的同步块）串行化；
 * 事件来自网络线程/播放线程，StateFlow 的写入是线程安全的，读-改-写整体加锁即可。
 */
class AgentStateMachine(initial: AgentState = AgentState.IDLE_OBSERVING) {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val lock = Any()

    /** 合法迁移；返回是否发生了迁移。 */
    fun transitionTo(next: AgentState): Boolean = synchronized(lock) {
        val cur = _state.value
        if (cur == next) return true
        if (!cur.canTransitionTo(next)) return false
        _state.value = next
        true
    }

    /** 强制迁移（跳过合法性校验，用于初始化/复位）。 */
    fun forceTo(next: AgentState) {
        synchronized(lock) { _state.value = next }
    }
}

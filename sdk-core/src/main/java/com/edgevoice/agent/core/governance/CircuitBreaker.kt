package com.edgevoice.agent.core.governance

/**
 * 熔断降级（技术文档 §2.2）：外部工具调用连续失败到阈值后熔断，
 * 冷却期内快速失败（降级到兜底逻辑），冷却结束后放一个探针请求试探恢复。
 *
 * 三态语义（对标 Resilience4j / Hystrix）：
 *   CLOSED(正常) --失败达阈值--> OPEN(熔断) --冷却到期--> HALF_OPEN(试探) --成功--> CLOSED
 *                                                        --失败--> OPEN
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 30_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state = State.CLOSED
    private var failures = 0
    private var openedAt = 0L

    /** 是否放行本次调用。 */
    @Synchronized
    fun allow(): Boolean = when (state) {
        State.CLOSED -> true
        State.HALF_OPEN -> true
        State.OPEN -> {
            if (clock() - openedAt >= openDurationMs) {
                state = State.HALF_OPEN
                true
            } else {
                false
            }
        }
    }

    @Synchronized
    fun onSuccess() {
        failures = 0
        state = State.CLOSED
    }

    @Synchronized
    fun onFailure() {
        failures++
        if (state == State.HALF_OPEN || failures >= failureThreshold) {
            state = State.OPEN
            openedAt = clock()
        }
    }

    @Synchronized
    fun currentState(): State = state
}

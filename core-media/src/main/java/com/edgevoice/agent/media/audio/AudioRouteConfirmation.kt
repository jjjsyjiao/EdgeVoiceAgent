package com.edgevoice.agent.media.audio

/** Pure state for asynchronous communication-route requests; caller supplies actual route IDs. */
internal class AudioRouteConfirmation(private val timeoutMs: Long = 3_000L) {
    enum class Result { IDLE, PENDING, CONFIRMED, LOST, TIMED_OUT }
    private var targetId: Int? = null
    private var confirmed = false
    private var deadlineMs = 0L

    init { require(timeoutMs > 0) }

    fun begin(targetId: Int, nowMs: Long) {
        this.targetId = targetId
        confirmed = false
        deadlineMs = nowMs + timeoutMs
    }

    fun observe(actualId: Int?, nowMs: Long): Result {
        val target = targetId ?: return Result.IDLE
        if (actualId == target) {
            confirmed = true
            return Result.CONFIRMED
        }
        return when {
            confirmed -> Result.LOST
            nowMs >= deadlineMs -> Result.TIMED_OUT
            else -> Result.PENDING
        }
    }

    fun remainingMs(nowMs: Long): Long =
        if (targetId == null || confirmed) 0L else (deadlineMs - nowMs).coerceAtLeast(0L)

    fun clear() {
        targetId = null
        confirmed = false
        deadlineMs = 0L
    }
}

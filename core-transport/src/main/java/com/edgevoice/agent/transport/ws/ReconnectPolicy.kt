package com.edgevoice.agent.transport.ws

import kotlin.math.min
import kotlin.random.Random

/**
 * 指数退避重连策略（带抖动）。
 *
 * delay(n) = min(maxDelayMs, baseMs * 2^n) + [0, jitterMs)
 *
 * 为什么加抖动：服务端闪断会导致海量客户端同时重连（Thundering Herd），
 * 纯确定性退避会让重试波峰对齐，抖动把波峰抹平 —— 等价于 Java 里
 * Resilience4j / gRPC retry 策略中的 jitter 参数。
 *
 * 有界重试：超过 [maxAttempts] 后返回 null，由上层决定降级（如切本地 executor）。
 */
class ReconnectPolicy(
    private val baseMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val jitterMs: Long = 500L,
    val maxAttempts: Int = 8,
) {
    init {
        require(baseMs > 0 && maxDelayMs > 0 && jitterMs >= 0 && maxAttempts >= 0)
    }
    /** 第 [attempt] 次重试（从 0 计）的等待毫秒数；超限返回 null。 */
    fun nextDelayMs(attempt: Int): Long? {
        if (attempt < 0 || attempt >= maxAttempts) return null
        // baseMs shl 等价于 baseMs * 2^n，即指数增长
        val factor = 1L shl attempt.coerceAtMost(30)
        val exp = if (baseMs > maxDelayMs / factor) maxDelayMs else min(maxDelayMs, baseMs * factor)
        // Random.nextLong生成 [0, jitterMs) 区间内的随机数
        return exp + if (jitterMs == 0L) 0L else Random.nextLong(0, jitterMs)
    }
}

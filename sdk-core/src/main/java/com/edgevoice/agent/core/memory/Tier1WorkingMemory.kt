package com.edgevoice.agent.core.memory

/**
 * Tier1 工作记忆：**纯进程内**，会话结束随进程销毁，不做任何持久化。
 *
 * 双重裁剪：条数上限 [maxItems] + 时间窗上限 [maxWindowMs]，
 * 防止长会话内存无限增长（对齐 Token 预算的端侧兜底）。
 *
 * 并发：用 `synchronized` 保护 ArrayDeque 的读改写；访问频率低（每轮对话数条），
 * 不需要无锁结构 —— 与音频热路径（RingBuffer）刻意采取不同策略。
 */
class Tier1WorkingMemory(
    private val maxItems: Int = 200,
    private val maxWindowMs: Long = 5 * 60_000L,
) {
    private val items = ArrayDeque<ContextItem>()
    private val lock = Any()

    fun add(item: ContextItem) {
        synchronized(lock) {
            items.addLast(item)
            while (items.size > maxItems) items.removeFirst()
            val cutoff = item.timestampMs - maxWindowMs
            while (items.isNotEmpty() && items.first().timestampMs < cutoff) items.removeFirst()
        }
    }

    fun snapshot(): List<ContextItem> = synchronized(lock) { items.toList() }

    fun recent(count: Int): List<ContextItem> = synchronized(lock) {
        val n = items.size
        if (count >= n) items.toList() else items.toList().subList(n - count, n)
    }

    fun size(): Int = synchronized(lock) { items.size }

    fun clear() {
        synchronized(lock) { items.clear() }
    }
}

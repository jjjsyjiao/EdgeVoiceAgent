package com.edgevoice.agent.core.governance

/**
 * Token 预算硬约束（技术文档 §2.3）。
 *
 * 端侧不做真分词（成本高），用启发式估算：CJK 每字≈1 token，其余按 4 字符≈1 token。
 * 达到上限即拒绝继续注入，触发上层「历史抽稀 / 摘要压缩」。
 */
class TokenBudget(private val maxTokens: Int = 8_000) {

    private var used = 0

    /** 尝试占用 [text] 的预算；超额返回 false（调用方应触发压缩）。 */
    @Synchronized
    fun tryConsume(text: String): Boolean {
        val cost = estimateTokens(text)
        if (used + cost > maxTokens) return false
        used += cost
        return true
    }

    @Synchronized
    fun remaining(): Int = (maxTokens - used).coerceAtLeast(0)

    @Synchronized
    fun usedTokens(): Int = used

    @Synchronized
    fun reset() {
        used = 0
    }

    companion object {
        fun estimateTokens(text: String): Int {
            var cjk = 0
            var other = 0
            for (c in text) {
                val code = c.code
                if (code in 0x4E00..0x9FFF || code in 0x3000..0x30FF || code in 0xFF00..0xFFEF) cjk++ else other++
            }
            return cjk + (other + 3) / 4
        }
    }
}

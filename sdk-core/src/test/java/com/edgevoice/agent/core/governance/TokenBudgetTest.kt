package com.edgevoice.agent.core.governance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Token 预算启发式估算与硬约束（端侧 CJK 1 token/字，其余 4 字符 1 token）。 */
class TokenBudgetTest {

    @Test
    fun estimate_cjk_counts_one_token_per_char() {
        // 10 个纯中文字符 => 10 token
        assertThat(TokenBudget.estimateTokens("一二三四五六七八九十")).isEqualTo(10)
    }

    @Test
    fun estimate_latin_uses_four_chars_per_token_rounded_up() {
        // 8 个 ASCII 字符 => (8+3)/4 = 2
        assertThat(TokenBudget.estimateTokens("abcdefgh")).isEqualTo(2)
        // 1 个字符也至少 1 token（向上取整）
        assertThat(TokenBudget.estimateTokens("a")).isEqualTo(1)
    }

    @Test
    fun tryConsume_accepts_until_limit_then_rejects() {
        val budget = TokenBudget(maxTokens = 5)
        assertThat(budget.tryConsume("一二三")).isTrue() // 3
        assertThat(budget.usedTokens()).isEqualTo(3)
        assertThat(budget.remaining()).isEqualTo(2)

        // 再放 3 个会超额（3+3>5），拒绝且不占用
        assertThat(budget.tryConsume("四五六")).isFalse()
        assertThat(budget.usedTokens()).isEqualTo(3)
    }

    @Test
    fun boundary_consumption_to_exactly_limit_is_accepted() {
        val budget = TokenBudget(maxTokens = 4)
        assertThat(budget.tryConsume("一二三四")).isTrue()
        assertThat(budget.remaining()).isEqualTo(0)
        // 已满：任何正数消耗都被拒绝
        assertThat(budget.tryConsume("a")).isFalse()
    }

    @Test
    fun reset_zeroes_usage() {
        val budget = TokenBudget(maxTokens = 10)
        budget.tryConsume("一二三四五")
        budget.reset()
        assertThat(budget.usedTokens()).isEqualTo(0)
        assertThat(budget.remaining()).isEqualTo(10)
    }
}

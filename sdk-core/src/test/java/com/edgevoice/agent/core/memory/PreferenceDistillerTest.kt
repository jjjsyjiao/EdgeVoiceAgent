package com.edgevoice.agent.core.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 显式「记住」指令抽取 + 自动提炼轮次阈值。 */
class PreferenceDistillerTest {

    private val distiller = PreferenceDistiller(autoTriggerTurns = 3)

    @Test
    fun detects_chinese_explicit_remember_and_trims_punctuation() {
        assertThat(distiller.detectExplicit("请记住我喜欢冰美式"))
            .isEqualTo("我喜欢冰美式")
        assertThat(distiller.detectExplicit("记住一下，我对花生过敏。"))
            .isEqualTo("我对花生过敏")
        assertThat(distiller.detectExplicit("牢记：会议室在 3 楼"))
            .isEqualTo("会议室在 3 楼")
    }

    @Test
    fun detects_english_explicit_remember_case_insensitive() {
        assertThat(distiller.detectExplicit("Remember that I prefer dark mode"))
            .isEqualTo("I prefer dark mode")
    }

    @Test
    fun returns_null_for_normal_dialogue() {
        assertThat(distiller.detectExplicit("今天天气怎么样")).isNull()
        assertThat(distiller.detectExplicit("")).isNull()
    }

    @Test
    fun auto_tick_fires_only_at_threshold_then_resets() {
        assertThat(distiller.tick()).isFalse() // 1
        assertThat(distiller.tick()).isFalse() // 2
        assertThat(distiller.tick()).isTrue()  // 3 达阈值
        // 触发后计数归零重新累计
        assertThat(distiller.tick()).isFalse()
        assertThat(distiller.tick()).isFalse()
        assertThat(distiller.tick()).isTrue()
    }
}

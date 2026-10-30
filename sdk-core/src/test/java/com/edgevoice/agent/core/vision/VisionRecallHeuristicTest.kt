package com.edgevoice.agent.core.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisionRecallHeuristicTest {

    @Test
    fun classic_recall_phrases_hit_with_time_tiers() {
        val q1 = VisionRecallHeuristic.parse("哎，我刚才有没有经过一家手机店？")
        assertThat(q1).isNotNull()
        assertThat(q1!!.timeWord).isEqualTo("刚才")
        assertThat(q1.lookbackMs).isEqualTo(180_000L)
        assertThat(q1.triggerWord).isEqualTo("经过")

        assertThat(VisionRecallHeuristic.parse("我刚刚看到一只猫跑过去了吗？")!!.lookbackMs)
            .isEqualTo(60_000L)
        assertThat(VisionRecallHeuristic.parse("我们前面路过的那家便利店叫什么？")!!.lookbackMs)
            .isEqualTo(300_000L)
        assertThat(VisionRecallHeuristic.parse("你之前看见过一个红色的招牌吗？")!!.lookbackMs)
            .isEqualTo(600_000L)
    }

    @Test
    fun last_time_word_spans_full_ring() {
        val q = VisionRecallHeuristic.parse("我上次打开 app 的时候有没有看到一家银行？")
        assertThat(q).isNotNull()
        assertThat(q!!.timeWord).isEqualTo("上次")
        assertThat(q.lookbackMs).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun long_window_time_words_map_to_extended_lookbacks() {
        val HOUR = 3_600_000L
        val DAY = 24 * HOUR
        assertThat(VisionRecallHeuristic.parse("我今天看到过一只松鼠")!!.let { it.timeWord to it.lookbackMs })
            .isEqualTo("今天" to 12 * HOUR)
        assertThat(VisionRecallHeuristic.parse("我昨天看见过一家书店")!!.let { it.timeWord to it.lookbackMs })
            .isEqualTo("昨天" to DAY)
        assertThat(VisionRecallHeuristic.parse("我前天见过一只白色的猫")!!.let { it.timeWord to it.lookbackMs })
            .isEqualTo("前天" to 2 * DAY)
        // 「前几天」必须先于「前天」命中，不能被截断
        assertThat(VisionRecallHeuristic.parse("前几天我路过一家便利店")!!.let { it.timeWord to it.lookbackMs })
            .isEqualTo("前几天" to 3 * DAY)
        assertThat(VisionRecallHeuristic.parse("上周我看到过一辆红色跑车")!!.let { it.timeWord to it.lookbackMs })
            .isEqualTo("上周" to 7 * DAY)
        assertThat(VisionRecallHeuristic.parse("上个月我经过一家花店")!!.let { it.timeWord to it.lookbackMs })
            .isEqualTo("上个月" to 30 * DAY)
    }

    @Test
    fun present_tense_questions_do_not_trigger_recall() {
        // 当下提问应走实时画面通道，而不是回放历史帧
        assertThat(VisionRecallHeuristic.parse("这个菜单都有什么？")).isNull()
        assertThat(VisionRecallHeuristic.parse("你看到我现在指的这个东西了吗")).isNull()
        assertThat(VisionRecallHeuristic.parse("帮我查一下天气")).isNull()
        assertThat(VisionRecallHeuristic.parse("刚才你说什么？")).isNull() // 无视觉指向词
    }

    @Test
    fun vision_word_without_time_anchor_does_not_trigger() {
        // 只有视觉词没有时间词：可能是正在看的东西，不回放
        assertThat(VisionRecallHeuristic.parse("你看到那栋楼了吗？")).isNull()
    }

    @Test
    fun time_word_without_vision_word_does_not_trigger() {
        assertThat(VisionRecallHeuristic.parse("我们刚才聊到哪了？")).isNull()
        assertThat(VisionRecallHeuristic.parse("上次的会议纪要发我一下")).isNull()
    }

    @Test
    fun punctuation_and_case_are_normalized() {
        assertThat(VisionRecallHeuristic.parse("  我刚才，经过一家手机店？！ ")).isNotNull()
    }

    @Test
    fun blank_and_short_input_returns_null() {
        assertThat(VisionRecallHeuristic.parse(null)).isNull()
        assertThat(VisionRecallHeuristic.parse("")).isNull()
        assertThat(VisionRecallHeuristic.parse("   ")).isNull()
        // 4 字且双词齐全仍应命中（长度护栏只挡残缺输入）
        assertThat(VisionRecallHeuristic.parse("刚才看到")).isNotNull()
    }

    @Test
    fun specific_time_word_wins_over_generic() {
        // 「刚刚」必须先于「刚才」命中；「之前」不能抢「前面」
        assertThat(VisionRecallHeuristic.parse("我刚刚是不是经过一家奶茶店")!!.timeWord)
            .isEqualTo("刚刚")
        assertThat(VisionRecallHeuristic.parse("我前面有没有看到共享单车")!!.timeWord)
            .isEqualTo("前面")
    }
}

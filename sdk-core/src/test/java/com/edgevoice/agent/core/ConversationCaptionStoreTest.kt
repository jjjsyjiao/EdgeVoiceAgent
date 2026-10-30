package com.edgevoice.agent.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConversationCaptionStoreTest {
    @Test fun restored_caption_preserves_current_round_and_interrupt_flag_until_a_new_turn() {
        val previousRound = ConversationCaption("还没说完？", "这是保留下来的回答", true)
        val captions = ConversationCaptionStore()
        captions.restore(previousRound)

        assertThat(captions.state.value).isEqualTo(previousRound)
        captions.cancelPendingTurn() // OrchestrationEngine.start keeps the display history.
        assertThat(captions.state.value).isEqualTo(previousRound)
        captions.nextTurn()
        captions.user("新的问题")
        captions.beginResponse()
        captions.append("新的回答")
        assertThat(captions.state.value).isEqualTo(ConversationCaption(
            "新的问题", "新的回答", false,
            listOf(ConversationTurn("还没说完？", "这是保留下来的回答", true)),
        ))
    }

    @Test fun final_text_reconciles_deltas_without_repeating_and_new_turn_keeps_previous_answer() {
        val captions = ConversationCaptionStore()
        captions.nextTurn()
        captions.user("图里是什么？")
        captions.beginResponse()
        captions.append("这是")
        captions.append("数字七")
        captions.finish("这是数字7。")
        assertThat(captions.state.value.assistantText).isEqualTo("这是数字7。")
        captions.interrupt()
        assertThat(captions.state.value.assistantInterrupted).isTrue()
        captions.nextTurn()
        captions.user("再说一次")
        captions.beginResponse()
        captions.append("7")
        assertThat(captions.state.value.visibleTurns).containsExactly(
            ConversationTurn("图里是什么？", "这是数字7。", true),
            ConversationTurn("再说一次", "7", false),
        ).inOrder()
        captions.reset()
        assertThat(captions.state.value).isEqualTo(ConversationCaption())
    }

    @Test fun six_identical_question_turns_keep_only_the_last_five_in_order() {
        val captions = ConversationCaptionStore()
        repeat(6) { index ->
            captions.nextTurn()
            captions.user("同一个问题")
            captions.beginResponse()
            captions.append("回答${index + 1}")
        }

        assertThat(captions.state.value.visibleTurns).containsExactly(
            ConversationTurn("同一个问题", "回答2"),
            ConversationTurn("同一个问题", "回答3"),
            ConversationTurn("同一个问题", "回答4"),
            ConversationTurn("同一个问题", "回答5"),
            ConversationTurn("同一个问题", "回答6"),
        ).inOrder()
        assertThat(captions.state.value.previousTurns).hasSize(4)
    }

    @Test fun response_before_transcript_stays_in_the_new_turn_and_false_vad_does_not_evict() {
        val captions = ConversationCaptionStore()
        repeat(5) { index ->
            captions.nextTurn()
            captions.user("问题$index")
            captions.finish("回答$index")
        }
        val original = captions.state.value.visibleTurns

        captions.nextTurn()
        captions.beginResponse() // tool-only or response.created before ASR: still no text.
        captions.cancelPendingTurn()
        assertThat(captions.state.value.visibleTurns).isEqualTo(original)

        captions.nextTurn()
        captions.beginResponse()
        captions.append("先收到的回答")
        captions.user("迟到的转写")
        assertThat(captions.state.value.visibleTurns).containsExactly(
            original[1], original[2], original[3], original[4],
            ConversationTurn("迟到的转写", "先收到的回答"),
        ).inOrder()
    }

    @Test fun interrupted_response_is_retained_and_new_answer_is_streamed_separately() {
        val captions = ConversationCaptionStore()
        captions.nextTurn()
        captions.user("数到二十")
        captions.beginResponse()
        captions.append("一、二、三")
        captions.interrupt()
        captions.nextTurn()
        captions.user("一加一等于几")
        captions.beginResponse()
        captions.append("二")
        captions.finish("一加一等于二。")

        assertThat(captions.state.value.visibleTurns).containsExactly(
            ConversationTurn("数到二十", "一、二、三", true),
            ConversationTurn("一加一等于几", "一加一等于二。"),
        ).inOrder()
    }
}

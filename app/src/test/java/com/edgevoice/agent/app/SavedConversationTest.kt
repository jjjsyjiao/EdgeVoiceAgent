package com.edgevoice.agent.app

import androidx.lifecycle.SavedStateHandle
import com.edgevoice.agent.core.ConversationCaption
import com.edgevoice.agent.core.ConversationTurn
import com.edgevoice.agent.app.SavedConversation.Companion.ASSISTANT_INTERRUPTED
import com.edgevoice.agent.app.SavedConversation.Companion.ASSISTANT_TEXT
import com.edgevoice.agent.app.SavedConversation.Companion.MAX_ASSISTANT_CHARS
import com.edgevoice.agent.app.SavedConversation.Companion.MAX_USER_CHARS
import com.edgevoice.agent.app.SavedConversation.Companion.PREVIOUS_TURNS
import com.edgevoice.agent.app.SavedConversation.Companion.USER_TEXT
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedConversationTest {
    @Test fun five_rounds_survive_a_fresh_saved_state_owner() {
        val handle = SavedStateHandle()
        val currentRound = ConversationCaption(
            " 看见什么？🙂\n", "第一行\n还有未说完的回答 ", true,
            (1..4).map { ConversationTurn("问题$it", "回答$it", it == 2) },
        )
        SavedConversation(handle).save(currentRound)

        val restoredHandle = copyHandle(handle)

        assertThat(SavedConversation(restoredHandle).restore()).isEqualTo(currentRound)
        assertThat(restoredHandle.keys()).containsExactly(USER_TEXT, ASSISTANT_TEXT, ASSISTANT_INTERRUPTED, PREVIOUS_TURNS)
        assertThat(SavedConversation(restoredHandle).restore().visibleTurns).hasSize(5)
    }

    @Test fun cleared_caption_replaces_all_saved_rounds() {
        val handle = SavedStateHandle()
        val saved = SavedConversation(handle)
        saved.save(ConversationCaption("旧问题", "旧回答", true))

        // Explicitly clearing the display replaces the saved snapshot.
        saved.save(ConversationCaption())

        assertThat(SavedConversation(copyHandle(handle)).restore()).isEqualTo(ConversationCaption())
        assertThat(handle.get<ArrayList<String>>(PREVIOUS_TURNS)).isEmpty()
    }

    @Test fun malformed_or_missing_fields_restore_without_casting_failures() {
        val malformed = SavedStateHandle(mapOf(
            USER_TEXT to 23,
            ASSISTANT_TEXT to listOf("错误类型"),
            ASSISTANT_INTERRUPTED to "true",
            PREVIOUS_TURNS to arrayListOf("问题", 42, "1", "有效问题", "有效回答", "0"),
        ))
        val saved = SavedConversation(malformed)

        assertThat(saved.restore()).isEqualTo(ConversationCaption(previousTurns = listOf(
            ConversationTurn("有效问题", "有效回答"),
        )))
        // Initialization rewrites sanitized values so corrupt data is not saved again.
        saved.save(saved.restore())
        assertThat(malformed.get<Any?>(USER_TEXT)).isEqualTo("")
        assertThat(malformed.get<Any?>(ASSISTANT_TEXT)).isEqualTo("")
        assertThat(malformed.get<Any?>(ASSISTANT_INTERRUPTED)).isEqualTo(false)
        assertThat(malformed.get<ArrayList<String>>(PREVIOUS_TURNS)).containsExactly("有效问题", "有效回答", "0")
        assertThat(SavedConversation(SavedStateHandle()).restore()).isEqualTo(ConversationCaption())
    }

    @Test fun persistence_and_restoration_keep_only_a_bounded_current_round() {
        val user = "问".repeat(MAX_USER_CHARS)
        val assistant = "答".repeat(MAX_ASSISTANT_CHARS)
        val oversized = ConversationCaption(user + "超出", assistant + "超出", true)
        val expected = ConversationCaption(user, assistant, true)
        val handle = SavedStateHandle()

        SavedConversation(handle).save(oversized)

        assertThat(handle.get<String>(USER_TEXT)).isEqualTo(user)
        assertThat(handle.get<String>(ASSISTANT_TEXT)).isEqualTo(assistant)
        assertThat(SavedConversation(copyHandle(handle)).restore()).isEqualTo(expected)
        val oldOversizedState = SavedStateHandle(mapOf(
            USER_TEXT to oversized.userText,
            ASSISTANT_TEXT to oversized.assistantText,
            ASSISTANT_INTERRUPTED to true,
        ))
        assertThat(SavedConversation(oldOversizedState).restore()).isEqualTo(expected)
    }

    @Test fun oversized_history_keeps_the_last_five_with_a_bounded_bundle_budget() {
        val hugeUser = "问".repeat(MAX_USER_CHARS + 10)
        val hugeAnswer = "答".repeat(MAX_ASSISTANT_CHARS + 10)
        val oversized = ConversationCaption(
            hugeUser, hugeAnswer, true,
            (1..8).map { ConversationTurn("$it$hugeUser", "$it$hugeAnswer", true) },
        )
        val handle = SavedStateHandle()
        SavedConversation(handle).save(oversized)

        val restored = SavedConversation(copyHandle(handle)).restore()
        assertThat(restored.visibleTurns).hasSize(5)
        assertThat(restored.previousTurns.map { it.userText.first() }).containsExactly('5', '6', '7', '8').inOrder()
        assertThat(restored.visibleTurns.all { it.userText.length <= MAX_USER_CHARS &&
            it.assistantText.length <= MAX_ASSISTANT_CHARS }).isTrue()
        assertThat(restored.visibleTurns.sumOf { it.userText.length + it.assistantText.length })
            .isAtMost(5 * (MAX_USER_CHARS + MAX_ASSISTANT_CHARS))
    }

    @Test fun legacy_three_field_snapshot_restores_as_one_round() {
        val legacy = SavedStateHandle(mapOf(
            USER_TEXT to "旧问题",
            ASSISTANT_TEXT to "旧回答",
            ASSISTANT_INTERRUPTED to true,
        ))

        assertThat(SavedConversation(legacy).restore()).isEqualTo(ConversationCaption("旧问题", "旧回答", true))
    }

    @Test fun bounding_does_not_split_unicode_or_restore_an_empty_interruption() {
        val handle = SavedStateHandle()
        val userPrefix = "a".repeat(MAX_USER_CHARS - 1)
        val assistantPrefix = "b".repeat(MAX_ASSISTANT_CHARS - 1)
        val saved = SavedConversation(handle)
        saved.save(ConversationCaption(userPrefix + "🙂tail", assistantPrefix + "🙂tail", true))

        assertThat(saved.restore()).isEqualTo(ConversationCaption(userPrefix, assistantPrefix, true))

        saved.save(ConversationCaption(assistantInterrupted = true))
        assertThat(saved.restore()).isEqualTo(ConversationCaption())
    }

    private fun copyHandle(handle: SavedStateHandle): SavedStateHandle = SavedStateHandle(
        handle.keys().associateWith { handle.get<Any?>(it) },
    )
}

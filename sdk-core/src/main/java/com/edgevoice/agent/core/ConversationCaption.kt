package com.edgevoice.agent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 仅用于本机界面展示的一轮字幕；不注入模型上下文。 */
data class ConversationTurn(
    val userText: String = "",
    val assistantText: String = "",
    val assistantInterrupted: Boolean = false,
) {
    val hasText: Boolean get() = userText.isNotBlank() || assistantText.isNotBlank()
}

/** 最近五轮展示字幕。原有三个字段仍表示当前轮，便于流式更新和旧快照恢复。 */
data class ConversationCaption(
    val userText: String = "",
    val assistantText: String = "",
    val assistantInterrupted: Boolean = false,
    val previousTurns: List<ConversationTurn> = emptyList(),
) {
    val visibleTurns: List<ConversationTurn>
        get() {
            val active = ConversationTurn(userText, assistantText, assistantInterrupted)
            val history = previousTurns.filter(ConversationTurn::hasText)
            return if (active.hasText) (history.takeLast(MAX_VISIBLE_TURNS - 1) + active)
            else history.takeLast(MAX_VISIBLE_TURNS)
        }

    companion object {
        const val MAX_VISIBLE_TURNS = 5
    }
}

class ConversationCaptionStore {
    private val mutable = MutableStateFlow(ConversationCaption())
    val state = mutable.asStateFlow()

    /** VAD 开口先记边界；误触且没有转写/回答时不挤掉最旧一轮。 */
    private var pendingTurn = false

    @Synchronized fun reset() {
        pendingTurn = false
        mutable.value = ConversationCaption()
    }

    /** 只恢复展示字幕，不产生模型或媒体事件。 */
    @Synchronized fun restore(caption: ConversationCaption) {
        pendingTurn = false
        mutable.value = caption.copy(previousTurns = caption.previousTurns.filter(ConversationTurn::hasText).takeLast(
            if (caption.userText.isNotBlank() || caption.assistantText.isNotBlank())
                ConversationCaption.MAX_VISIBLE_TURNS - 1 else ConversationCaption.MAX_VISIBLE_TURNS,
        ))
    }

    @Synchronized fun nextTurn() { pendingTurn = true }

    /** 会话结束或重新开始时丢弃尚无字幕的 VAD 误触。 */
    @Synchronized fun cancelPendingTurn() { pendingTurn = false }

    @Synchronized fun user(text: String) {
        realizePendingTurn()
        mutable.value = mutable.value.copy(userText = text)
    }

    @Synchronized fun beginResponse() {
        // Realtime 可先送 response.created，稍后才送 ASR。等实际文字到来再切轮。
        if (pendingTurn) return
        mutable.value = mutable.value.copy(assistantText = "", assistantInterrupted = false)
    }

    @Synchronized fun append(text: String) {
        if (text.isEmpty()) return
        realizePendingTurn()
        mutable.value = mutable.value.copy(assistantText = mutable.value.assistantText + text)
    }

    @Synchronized fun finish(text: String) {
        if (text.isEmpty()) return
        realizePendingTurn()
        mutable.value = mutable.value.copy(assistantText = text)
    }

    @Synchronized fun interrupt() {
        // 新问句只有 VAD、还没有实际字幕时，不把上一轮已完成的回答误标为打断。
        if (pendingTurn) return
        if (mutable.value.assistantText.isNotEmpty()) {
            mutable.value = mutable.value.copy(assistantInterrupted = true)
        }
    }

    private fun realizePendingTurn() {
        if (!pendingTurn) return
        pendingTurn = false
        val current = mutable.value
        val active = ConversationTurn(current.userText, current.assistantText, current.assistantInterrupted)
        val history = if (active.hasText) current.previousTurns + active else current.previousTurns
        mutable.value = ConversationCaption(previousTurns = history.takeLast(ConversationCaption.MAX_VISIBLE_TURNS - 1))
    }
}

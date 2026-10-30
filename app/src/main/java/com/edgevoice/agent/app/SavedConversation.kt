package com.edgevoice.agent.app

import androidx.lifecycle.SavedStateHandle
import com.edgevoice.agent.core.ConversationCaption
import com.edgevoice.agent.core.ConversationTurn

/**
 * Android 可恢复的最近五轮展示字幕。无媒体，也不恢复模型上下文。
 * 每轮用户 4,096、回答 8,192 个 UTF-16 单位，五轮文本总上限约 120 KiB，
 * 给 Activity 的其他 SavedState 留出 Bundle 预算。
 */
internal class SavedConversation(private val handle: SavedStateHandle) {
    fun restore(): ConversationCaption = bounded(
        ConversationCaption(
            userText = handle.get<Any?>(USER_TEXT) as? String ?: "",
            assistantText = handle.get<Any?>(ASSISTANT_TEXT) as? String ?: "",
            assistantInterrupted = handle.get<Any?>(ASSISTANT_INTERRUPTED) as? Boolean ?: false,
            previousTurns = restorePreviousTurns(),
        ),
    )

    fun save(caption: ConversationCaption) {
        val snapshot = bounded(caption)
        handle[USER_TEXT] = snapshot.userText
        handle[ASSISTANT_TEXT] = snapshot.assistantText
        handle[ASSISTANT_INTERRUPTED] = snapshot.assistantInterrupted
        handle[PREVIOUS_TURNS] = ArrayList<String>(snapshot.previousTurns.size * 3).apply {
            snapshot.previousTurns.forEach { turn ->
                add(turn.userText)
                add(turn.assistantText)
                add(if (turn.assistantInterrupted) "1" else "0")
            }
        }
    }

    private fun bounded(caption: ConversationCaption): ConversationCaption {
        val active = ConversationTurn(caption.userText, caption.assistantText, caption.assistantInterrupted).bounded()
        val previousLimit = if (active.hasText) ConversationCaption.MAX_VISIBLE_TURNS - 1
            else ConversationCaption.MAX_VISIBLE_TURNS
        return ConversationCaption(
            userText = active.userText,
            assistantText = active.assistantText,
            assistantInterrupted = active.assistantInterrupted,
            previousTurns = caption.previousTurns.filter(ConversationTurn::hasText).takeLast(previousLimit)
                .map { it.bounded() },
        )
    }

    private fun ConversationTurn.bounded(): ConversationTurn {
        val assistant = assistantText.within(MAX_ASSISTANT_CHARS)
        return copy(
            userText = userText.within(MAX_USER_CHARS),
            assistantText = assistant,
            assistantInterrupted = assistantInterrupted && assistant.isNotEmpty(),
        )
    }

    private fun restorePreviousTurns(): List<ConversationTurn> {
        // 新字段缺席就是旧版本的三字段单轮快照；损坏的条目只跳过该轮。
        val raw = handle.get<Any?>(PREVIOUS_TURNS) as? List<*> ?: return emptyList()
        return raw.chunked(3).takeLast(ConversationCaption.MAX_VISIBLE_TURNS).mapNotNull { row ->
            if (row.size != 3) return@mapNotNull null
            val user = row[0] as? String ?: return@mapNotNull null
            val assistant = row[1] as? String ?: return@mapNotNull null
            val interrupted = when (row[2]) {
                "1" -> true
                "0" -> false
                else -> return@mapNotNull null
            }
            ConversationTurn(user, assistant, interrupted).takeIf(ConversationTurn::hasText)
        }
    }

    private fun String.within(limit: Int): String {
        if (length <= limit) return this
        // 不能把表情等补充平面字符的 UTF-16 代理对截成一半。
        val end = if (Character.isHighSurrogate(this[limit - 1]) && Character.isLowSurrogate(this[limit])) {
            limit - 1
        } else {
            limit
        }
        return substring(0, end)
    }

    companion object {
        internal const val USER_TEXT = "conversation.userText"
        internal const val ASSISTANT_TEXT = "conversation.assistantText"
        internal const val ASSISTANT_INTERRUPTED = "conversation.assistantInterrupted"
        internal const val PREVIOUS_TURNS = "conversation.previousTurns"
        internal const val MAX_USER_CHARS = 4_096
        // SavedState 的总文本预算是五轮共享的；运行中的流式字幕不受此限制。
        internal const val MAX_ASSISTANT_CHARS = 8_192
    }
}

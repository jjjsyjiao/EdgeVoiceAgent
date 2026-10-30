package com.edgevoice.agent.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 两层记忆的统一门面（记忆架构已按用户确认收窄）：
 *
 *   Tier1 [working]  —— 进程内工作记忆，会话即焚；
 *   Tier3 [longTerm] —— 云端长期记忆（偏好/关键事实），跨会话持久；
 *   无 Tier2（不做端侧会话历史持久化，已删除 Room 依赖）。
 *
 * [fusion] 提供多模态时间戳对齐，但只影响**本会话**的上下文注入。
 * 所有网络类操作都 try/catch 降级为空，保证记忆层永不阻断主链路。
 */
class ContextStore(
    val working: Tier1WorkingMemory = Tier1WorkingMemory(),
    val fusion: ContextFusionEngine = ContextFusionEngine(),
    val longTerm: ILongTermMemory = NoOpLongTermMemory,
) {
    private val _lastRecall = MutableStateFlow<List<MemoryHit>>(emptyList())
    val lastRecall: StateFlow<List<MemoryHit>> = _lastRecall.asStateFlow()

    fun recordUserSpeech(text: String, timestampMs: Long) {
        working.add(ContextItem(timestampMs, ContextKind.USER_SPEECH, text))
    }

    fun recordAssistantSpeech(text: String, timestampMs: Long) {
        working.add(ContextItem(timestampMs, ContextKind.ASSISTANT_SPEECH, text))
    }

    fun recordSystem(text: String, timestampMs: Long) {
        working.add(ContextItem(timestampMs, ContextKind.SYSTEM, text))
    }

    /** 视觉帧只登记时间戳/运动分/引用标识，不落原始像素。 */
    fun recordVisionFrame(timestampMs: Long, frameRef: String?, motionScore: Float) {
        fusion.onVisionFrame(ContextItem(timestampMs, ContextKind.VISION_FRAME, "", frameRef, motionScore))
    }

    /** 用当前用户话语检索长期记忆。 */
    suspend fun recall(query: String, topK: Int = 3): List<MemoryHit> {
        val hits = runCatching { longTerm.recall(query, topK) }.getOrDefault(emptyList())
        _lastRecall.value = hits
        return hits
    }

    suspend fun remember(text: String, kind: String = "preference", source: String = "explicit"): Boolean =
        runCatching { longTerm.remember(text, kind, source) }.getOrDefault(false)

    /** 自动提炼：把最近对话轮次交给云端抽取偏好，并回写入库。 */
    suspend fun distillRecent(maxTurns: Int = 12): List<String> {
        val turns = working.snapshot()
            .filter { it.kind == ContextKind.USER_SPEECH || it.kind == ContextKind.ASSISTANT_SPEECH }
            .takeLast(maxTurns)
            .map { it.text }
        if (turns.size < 2) return emptyList()
        val prefs = runCatching { longTerm.distill(turns) }.getOrDefault(emptyList())
        prefs.forEach { runCatching { longTerm.remember(it, "preference", "auto") } }
        return prefs
    }

    /**
     * 供热切换使用的压缩摘要（**端侧启发式，不依赖网络**）。
     * 取最近若干轮对话直接拼接；热切换窗口很短，够用且零延迟。
     */
    fun summarizeForHandover(maxTurns: Int = 6): String? {
        val recent = working.snapshot()
            .filter { it.kind == ContextKind.USER_SPEECH || it.kind == ContextKind.ASSISTANT_SPEECH }
            .takeLast(maxTurns)
        if (recent.isEmpty()) return null
        return recent.joinToString("\n") { "${it.kind}: ${it.text.take(120)}" }
    }

    /** 把检索到的长期记忆渲染成可注入模型的文本块。 */
    fun renderRecall(hits: List<MemoryHit>): String? =
        hits.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "- ${it.text}" }
            ?.let { "【长期记忆（用户偏好/事实）】\n$it" }
}

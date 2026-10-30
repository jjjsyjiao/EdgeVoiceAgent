package com.edgevoice.agent.core.memory

/**
 * 长期记忆写入触发器（已与用户确认的「双通道」策略）。
 *
 *  - 显式通道：识别「记住…」类指令，直接抽出入库内容（[detectExplicit]）；
 *  - 自动通道：累计对话轮次，达阈值后由上层触发云端提炼（[tick]）。
 *
 * 之所以把「提炼」本身放到云端：端侧没有合适的抽取模型，
 * 而 server 已有 LLM/embedding 能力，端侧只负责触发与触发时机。
 */
class PreferenceDistiller(
    private val autoTriggerTurns: Int = 8,
) {
    private val explicitPatterns = listOf(
        Regex("(?:请|帮我)?\\s*(?:记住|记下|牢记|记得)(?:一下)?[，,：:]?\\s*(.+)"),
        Regex("(?:remember|note)\\s+that\\s+(.+)", RegexOption.IGNORE_CASE),
    )

    private var turnsSinceLastAuto = 0

    /** 命中显式指令则返回待入库内容，否则 null。 */
    fun detectExplicit(text: String): String? {
        val input = text.trim()
        for (p in explicitPatterns) {
            val m = p.find(input) ?: continue
            val content = (m.groupValues.lastOrNull() ?: "").trim().trimEnd('。', '.', '!', '！')
            if (content.isNotEmpty()) return content
        }
        return null
    }

    /** 每轮对话调用；返回是否应触发一次自动提炼。 */
    fun tick(): Boolean {
        turnsSinceLastAuto++
        if (turnsSinceLastAuto >= autoTriggerTurns) {
            turnsSinceLastAuto = 0
            return true
        }
        return false
    }

    fun reset() {
        turnsSinceLastAuto = 0
    }
}

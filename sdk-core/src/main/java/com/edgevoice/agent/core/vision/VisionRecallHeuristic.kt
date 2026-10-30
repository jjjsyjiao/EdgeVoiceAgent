package com.edgevoice.agent.core.vision

/**
 * 一次「情景回忆」解析结果。
 *
 * @property lookbackMs 应从帧源回看的毫秒窗（短窗分级：刚刚 60s / 刚才 180s / 前面 300s /
 * 之前·以前 600s；长窗分级：今天 12h / 昨天 24h / 前天·前几天 48~72h / 上周 7d / 上个月 30d；
 * 上次=整环/跨会话全量）
 * @property triggerWord 命中的视觉指示词（日志/遥测用）
 * @property timeWord 命中的时间指示词
 */
data class RecallQuery(
    val lookbackMs: Long,
    val triggerWord: String,
    val timeWord: String,
)

/**
 * 「刚才我有没有经过一家手机店？」类回忆意图的关键词启发式（V2，用户已拍板）。
 *
 * 双命中规则（压误触发）：
 *  1. 必须含**视觉指示词**（看到/见过/经过/路过…），排除纯听觉话题；
 *  2. 必须含**时间指示词**（刚才/之前/上次/昨天…），把「这个菜单都有什么」这类
 *     当下提问留给实时画面通道，而不是回放历史帧。
 *
 * V3 扩展：时间词分**短窗 / 长窗**两级 —— 短窗落在本地磁盘帧环覆盖内（V2 本地直取），
 * 长窗（今天/昨天/前天…）超出本地环窗口 → 端侧回放器回退到服务端会话帧留存（V3 召回）。
 *
 * 纯字符串规则、零网络零延迟；命中即触发，漏判由后续 VLM/模型意图分类演进。
 */
object VisionRecallHeuristic {

    /** 视觉指向词：话题关于「画面里存在/出现过什么」。 */
    private val VISION_WORDS = listOf(
        "看到", "看见", "见着", "见过", "经过", "路过",
    )

    private val HOUR = 3_600_000L
    private val DAY = 24 * HOUR

    /** 时间分级，顺序即优先级：越具体越先匹配（「刚刚」先于「刚才」，「上次」最后兜底）。 */
    private val TIME_TIERS: List<Pair<String, Long>> = listOf(
        // —— 短窗：落在本地 30min 帧环内，本地直取 ——
        "刚刚" to 60_000L,
        "刚才" to 180_000L,
        "前面" to 300_000L,
        "之前" to 600_000L,
        "以前" to 600_000L,
        // —— 长窗：超出本地帧环 → 回退服务端会话帧留存（V3） ——
        "今天" to 12 * HOUR,
        "昨天" to DAY,
        "前几天" to 3 * DAY,   // 先于「前天」，避免被其截断
        "前天" to 2 * DAY,
        "上星期" to 7 * DAY,   // 先于「上周」同义分支（互不包含，顺序仅求清晰）
        "上周" to 7 * DAY,
        "上个月" to 30 * DAY,
        // —— 全量兜底 ——
        "上次" to Long.MAX_VALUE,
    )

    private val PUNCT = "，。？！,.?!~～、 　;:：;\"'“”‘’（）()【】[]".toSet()

    fun parse(raw: String?): RecallQuery? {
        if (raw.isNullOrBlank()) return null
        val text = normalize(raw)
        if (text.length < 4) return null

        val trigger = VISION_WORDS.firstOrNull { text.contains(it) } ?: return null
        val (timeWord, lookback) = TIME_TIERS.firstOrNull { (w, _) -> text.contains(w) }
            ?: return null
        return RecallQuery(
            lookbackMs = lookback,
            triggerWord = trigger,
            timeWord = timeWord,
        )
    }

    private fun normalize(s: String): String =
        s.filter { it !in PUNCT }.lowercase()
}

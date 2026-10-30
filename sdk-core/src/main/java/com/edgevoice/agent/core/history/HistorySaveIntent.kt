package com.edgevoice.agent.core.history

import org.json.JSONArray
import org.json.JSONObject

/** A local save may start only from the original user's explicit ASR command. */
object HistorySaveIntent {
    private val command = Regex("记录一下|记录下来|保存一下|保存下来|保存起来|存一下|存下来|存起来|记一下|记下来|记住|记着|留个记录|留一份记录")
    // A homophone alone never authorizes upload; it only permits an on-screen confirmation.
    private val ambiguousCommand = Regex("系一下|计一下|寄一下|纪一下")
    private val denied = Regex("(?:不要|不用|别|不需要|不想|不打算|不能|先别|暂时不)[^，,。.!！?？；;]{0,12}(?:记|保存|存|留|系|计|寄|纪)")
    private val future = Regex("(?:以后|下次|明天|改天)[^，,。.!！?？；;]{0,12}$")
    private val reported = Regex("如果|假如|假设|要是|比如|例如|我说|你说|他说|说过|提到|提过|听到|听说|写着|包含|意思是|问你|让我|我自己|我来|已经|曾经")
    private val question = Regex("是不是|要不要|该不该|是否|为什么|怎么|啥意思|什么|如何|有没有|会不会|能否")
    private val requestPrefix = Regex("(?:帮我|给我|替我|请你?|麻烦你?|能不能|可以|可不可以|麻烦|我想让你)$")
    private val end = Regex("^(?:吧|呀|啊|哦)?(?:[，,。.!！?？；;]|$)")
    private val metaAfter = Regex("是什么意思|怎么写|怎么说|什么意思")
    private val queryAfter = Regex("^(?:的|过|了吗|了么|没|没有|是否|是不是|怎么|为什么|啥意思|什么|如何|在哪|在哪里|何时)")
    private val locationClause = Regex("(?:放在|放到|放进|摆在|搁在|位于|它在)([^，,。.!！?？；;]{1,200})")

    fun isExplicit(transcript: String): Boolean = matchesCommand(transcript, command)

    /** A model save call plus this ASR may ask the user to confirm, but never starts an upload. */
    fun needsConfirmation(transcript: String): Boolean =
        !isExplicit(transcript) && matchesCommand(transcript, ambiguousCommand)

    private fun matchesCommand(transcript: String, pattern: Regex): Boolean {
        val text = transcript.replace(Regex("\\s+"), "")
        if (text.isEmpty() || text.length > 4_096 || denied.containsMatchIn(text)) return false
        val match = pattern.find(text) ?: return false
        val before = text.substring(0, match.range.first)
        val after = text.substring(match.range.last + 1)
        val clause = before.split(Regex("[，,。.!！?？；;]")).last()
        if (future.containsMatchIn(clause) || reported.containsMatchIn(clause) ||
            question.containsMatchIn(clause) || metaAfter.containsMatchIn(after) || queryAfter.containsMatchIn(after)) return false
        // A quoted command describes words rather than authorizing an image upload.
        val openQuotes = before.count { it in "“‘「『" }
        val closeQuotes = before.count { it in "”’」』" }
        val asciiQuoteOpen = before.count { it == '"' } % 2 != 0 || before.count { it == '\'' } % 2 != 0
        if (openQuotes > closeQuotes || asciiQuoteOpen || after.firstOrNull()?.let { it in "”’」』\"'" } == true) return false
        // Covers “记一下这个包”, “这个包帮我记一下”, and “这个包记一下，放在桌上”.
        return clause.isEmpty() || requestPrefix.containsMatchIn(clause) || end.containsMatchIn(after)
    }

    /** Fallback label is conservative; the original ASR is also stored as the user note. */
    fun objectName(transcript: String): String = objectNameFrom(transcript, command)

    private fun objectNameFrom(transcript: String, pattern: Regex): String {
        val text = transcript.replace(Regex("\\s+"), "")
        val match = pattern.find(text) ?: return "这件物品"
        val before = text.substring(0, match.range.first)
        val after = text.substring(match.range.last + 1)
        val afterClause = after.trimStart('，', ',', '。', '：', ':')
        val raw = if (afterClause.isNotBlank() && !afterClause.startsWith("放在") &&
            !afterClause.startsWith("放到") && !afterClause.startsWith("放进") &&
            !afterClause.startsWith("它在") && !afterClause.startsWith("它放在")) after else before
        val name = raw.trim('，', ',', '。', '！', '!', '？', '?', '：', ':')
            .replace(Regex("^(?:帮我|给我|替我|请你?|麻烦你?)"), "")
            .replace(Regex("(?:帮我|给我|替我|请你?|麻烦你?)$"), "")
            .replace(Regex("^(?:把|这个|这件|这部|这台|这只|这把|这张|这盏)"), "")
            .split(Regex("放在|放到|放进|摆在|搁在|位于|[，,。.!！?？；;]"), limit = 2).first()
            .trim()
        return name.take(80).ifBlank { "这件物品" }
    }

    /** Used only for a pending confirmation; the resulting name is never saved without a tap. */
    fun safeConfirmationArguments(modelArguments: String, transcript: String): String {
        require(needsConfirmation(transcript))
        val safe = JSONObject(safeArguments(modelArguments, transcript))
        val spokenName = objectNameFrom(transcript, ambiguousCommand)
        val candidate = safe.optString("object_name")
        fun normalized(value: String) = value.replace(Regex("[\\s，,。.!！?？；;的]"), "")
        if (spokenName != "这件物品" && (candidate == "这件物品" ||
                ambiguousCommand.containsMatchIn(candidate) ||
                (!normalized(spokenName).contains(normalized(candidate)) &&
                    !normalized(candidate).contains(normalized(spokenName))))) {
            safe.put("object_name", spokenName)
        }
        return safe.toString()
    }

    /** Preserve only a literal location phrase the user actually spoke. */
    fun location(transcript: String): String = locationClause.find(transcript.replace(Regex("\\s+"), ""))
        ?.groupValues?.get(1)?.trim()?.removeSuffix("吧") ?: ""

    /** Repair malformed model arguments before any upload; only ASR-grounded fields survive. */
    fun safeArguments(modelArguments: String, transcript: String): String {
        val supplied = modelArguments.takeIf { it.length <= 8_192 }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun normalized(value: String) = value.replace(Regex("[\\s，,。.!！?？；;的]"), "")
        val spoken = normalized(transcript)
        fun grounded(value: String): Boolean = normalized(value).let { it.isNotBlank() && spoken.contains(it) }
        val fallbackName = objectName(transcript)
        val fallbackLocation = location(transcript)
        val proposedName = supplied?.optString("object_name").orEmpty().trim()
        val name = proposedName.takeIf {
            it.length in 1..80 && grounded(it) && (fallbackName == "这件物品" ||
                normalized(fallbackName).contains(normalized(it)) || normalized(it).contains(normalized(fallbackName)))
        } ?: fallbackName
        val proposedLocation = supplied?.optString("location").orEmpty().trim()
        val place = proposedLocation.takeIf {
            it.length in 1..200 && grounded(it) && fallbackLocation.isNotBlank() &&
                (normalized(fallbackLocation).contains(normalized(it)) || normalized(it).contains(normalized(fallbackLocation)))
        } ?: fallbackLocation
        val aliases = JSONArray()
        val proposedAliases = supplied?.optJSONArray("aliases")
        if (proposedAliases != null) {
            for (index in 0 until minOf(proposedAliases.length(), 8)) {
                val alias = proposedAliases.optString(index).trim()
                if (alias.length in 1..80 && grounded(alias)) aliases.put(alias)
            }
        }
        return JSONObject().put("object_name", name).put("aliases", aliases)
            .put("location", place).put("note", "").toString()
    }
}

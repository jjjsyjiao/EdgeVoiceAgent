package com.edgevoice.agent.core.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HistoryDateRange(val fromTsMs: Long?, val toTsMs: Long?) {
    companion object {
        /** Natural days in the user's zone; the end is exclusive, including across DST. */
        fun resolve(expression: String, nowMs: Long, zoneId: String): HistoryDateRange {
            val zone = ZoneId.of(zoneId)
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val value = expression.trim()
            fun days(first: LocalDate, end: LocalDate) = HistoryDateRange(
                first.atStartOfDay(zone).toInstant().toEpochMilli(),
                end.atStartOfDay(zone).toInstant().toEpochMilli(),
            )
            return when (value) {
                "", "上次", "最后一次", "以前", "之前", "最近" -> HistoryDateRange(null, nowMs + 1)
                "今天", "今日" -> days(today, today.plusDays(1))
                "昨天", "昨日" -> days(today.minusDays(1), today)
                "前天" -> days(today.minusDays(2), today.minusDays(1))
                "刚才" -> HistoryDateRange(nowMs - 30 * 60_000L, nowMs + 1)
                "最近7天", "近7天", "最近七天", "近七天", "一周内", "最近一周" -> days(today.minusDays(6), today.plusDays(1))
                else -> {
                    val date = runCatching { LocalDate.parse(value) }.getOrNull()
                        ?: throw IllegalArgumentException("暂不支持这个时间范围，请说昨天、前天、今天、最近7天或具体日期。")
                    require(!date.isAfter(today)) { "不能检索未来的记录，请确认日期。" }
                    days(date, date.plusDays(1))
                }
            }
        }
    }
}

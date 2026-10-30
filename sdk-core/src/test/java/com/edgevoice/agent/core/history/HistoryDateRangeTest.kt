package com.edgevoice.agent.core.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class HistoryDateRangeTest {
    @Test fun yesterdayIsLocalCalendarDayNotRolling24Hours() {
        val range = HistoryDateRange.resolve("昨天", ms("2026-10-28T05:34:00Z"), "Asia/Shanghai")
        assertThat(range.fromTsMs).isEqualTo(ms("2026-10-26T16:00:00Z"))
        assertThat(range.toTsMs).isEqualTo(ms("2026-10-27T16:00:00Z"))
    }

    @Test fun daylightSavingDayHas23Hours() {
        val range = HistoryDateRange.resolve("2026-03-08", ms("2026-03-10T10:00:00Z"), "America/New_York")
        assertThat(range.toTsMs!! - range.fromTsMs!!).isEqualTo(23 * 3_600_000L)
    }

    @Test fun lastTimeSpansSessionsAndStopsAtNow() {
        val range = HistoryDateRange.resolve("上次", 123_456, "UTC")
        assertThat(range.fromTsMs).isNull()
        assertThat(range.toTsMs).isEqualTo(123_457)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedExpressionRequiresClarification() {
        HistoryDateRange.resolve("去年春节", ms("2026-10-28T05:34:00Z"), "Asia/Shanghai")
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureDateIsRejected() {
        HistoryDateRange.resolve("2027-01-01", ms("2026-10-28T05:34:00Z"), "Asia/Shanghai")
    }

    private fun ms(value: String) = Instant.parse(value).toEpochMilli()
}

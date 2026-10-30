package com.edgevoice.agent.core.history

/** Camera timestamps have device-specific clock origins: freshness uses local receipt time. */
class HistoryFrameCache(private val maxAgeMs: Long = 3_000L) {
    private data class Acceptance(val timestampNs: Long, val elapsedMs: Long, val wallMs: Long)
    private val accepted = ArrayDeque<Acceptance>()
    private var latest: HistoryCapturedFrame? = null

    @Synchronized fun accepted(timestampNs: Long, elapsedMs: Long, wallMs: Long) {
        accepted.addLast(Acceptance(timestampNs, elapsedMs, wallMs))
        while (accepted.size > 64 || (accepted.isNotEmpty() && elapsedMs - accepted.first().elapsedMs > maxAgeMs)) accepted.removeFirst()
    }

    @Synchronized fun encoded(timestampNs: Long, jpeg: ByteArray, nowElapsedMs: Long) {
        val receipt = accepted.lastOrNull { it.timestampNs == timestampNs } ?: return
        if (nowElapsedMs - receipt.elapsedMs !in 0..maxAgeMs || jpeg.isEmpty()) return
        if ((latest?.acceptedAtElapsedMs ?: -1) > receipt.elapsedMs) return
        latest = HistoryCapturedFrame(jpeg.copyOf(), receipt.wallMs, receipt.elapsedMs)
    }

    @Synchronized fun snapshot(nowElapsedMs: Long): HistoryCapturedFrame? = latest
        ?.takeIf { nowElapsedMs - it.acceptedAtElapsedMs in 0..maxAgeMs }
        ?.let { it.copy(jpeg = it.jpeg.copyOf()) }

    @Synchronized fun clear() {
        accepted.clear()
        latest = null
    }
}

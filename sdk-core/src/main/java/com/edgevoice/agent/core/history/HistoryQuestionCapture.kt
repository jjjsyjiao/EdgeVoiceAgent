package com.edgevoice.agent.core.history

/** Bounded microphone PCM, frozen at the original server speech_stopped event, before HTTP work. */
class HistoryQuestionCapture(
    private val bytesPerSecond: Int = 48_000,
    private val maxQuestionSeconds: Int = 30,
    private val preRollMs: Int = 1_000,
) {
    private data class Chunk(val start: Long, val pcm: ByteArray)
    private val chunks = ArrayDeque<Chunk>()
    private var total = 0L
    private var speechStart: Long? = null
    private var speechEnd: Long? = null
    private val capacity = bytesPerSecond * (maxQuestionSeconds + 2)

    init {
        require(bytesPerSecond > 0 && bytesPerSecond % 2 == 0)
        require(maxQuestionSeconds in 1..60 && preRollMs in 0..2_000)
    }

    @Synchronized fun append(pcm: ByteArray, absoluteStartBytes: Long = total) {
        require(pcm.size % 2 == 0)
        if (pcm.isEmpty()) return
        require(absoluteStartBytes >= total) { "Audio timeline must not move backwards" }
        if (absoluteStartBytes != total) {
            // An internally replayed question creates a gap in live microphone samples.
            chunks.clear()
            total = absoluteStartBytes
        }
        chunks.addLast(Chunk(total, pcm.copyOf()))
        total += pcm.size
        val earliest = total - capacity
        while (chunks.isNotEmpty() && chunks.first().start + chunks.first().pcm.size <= earliest) chunks.removeFirst()
    }

    @Synchronized fun speechStarted() {
        speechEnd = null
        speechStart = (total - bytesPerSecond.toLong() * preRollMs / 1_000L)
            .coerceAtLeast(chunks.firstOrNull()?.start ?: total)
    }

    /** Server offsets use the session's appended PCM timeline, never event-arrival wall time. */
    @Synchronized fun speechStartedAt(audioStartMs: Long?) {
        speechEnd = null
        speechStart = audioStartMs?.takeIf { it >= 0 }?.let { it * bytesPerSecond / 1_000L }
    }

    @Synchronized fun speechEndedAt(audioEndMs: Long?) {
        speechEnd = audioEndMs?.takeIf { it >= 0 }?.let { it * bytesPerSecond / 1_000L }
    }

    /** Null means missing/truncated/too long, never silently replay just the tail of a question. */
    @Synchronized fun speechStopped(): ByteArray? {
        val start = speechStart ?: return null
        speechStart = null
        val end = speechEnd ?: total
        speechEnd = null
        val length = end - start
        if (length < bytesPerSecond / 5 || length > bytesPerSecond.toLong() * maxQuestionSeconds ||
            end > total || start < (chunks.firstOrNull()?.start ?: total)) return null
        val output = ByteArray(length.toInt())
        for (chunk in chunks) {
            val from = maxOf(start, chunk.start)
            val to = minOf(end, chunk.start + chunk.pcm.size)
            if (from < to) chunk.pcm.copyInto(output, (from - start).toInt(), (from - chunk.start).toInt(), (to - chunk.start).toInt())
        }
        return output
    }

    @Synchronized fun clear() {
        chunks.clear()
        total = 0
        speechStart = null
        speechEnd = null
    }
}

package com.edgevoice.agent.media.audio

import java.io.Closeable
import java.nio.ByteBuffer

/** A full queue rejects the entire incoming chunk; already queued speech is retained. */
class AudioQueueOverflowException(
    val bufferedBytes: Int,
    val incomingBytes: Int,
    val capacityBytes: Int,
) : IllegalStateException(
    "播放缓冲已满（$bufferedBytes + $incomingBytes > $capacityBytes 字节），本轮语音无法完整播放",
)

/** Session diagnostics. Rejected chunks are reported instead of silently clipping speech. */
data class JitterStats(
    val pushedChunks: Long,
    val underruns: Long,
    val droppedChunks: Long,
    val bufferedMs: Int,
) {
    val lossRate: Float
        get() = if (pushedChunks == 0L) 0f
        else (underruns + droppedChunks).toFloat() / pushedChunks
}

/**
 * Bounded, lossless PCM queue. Model audio can arrive faster than it is spoken, so a
 * normal burst must not be treated as obsolete live media and discarded.
 *
 * Only initial/rebuffer priming waits for [targetMs]. Once playing, all remaining
 * samples are available. [finishResponse] releases even a response shorter than the
 * priming threshold. All queue operations share one lock, including interruption.
 * No Android or JNI dependency is needed to exercise the byte stream in JVM tests.
 */
class JitterBuffer(
    val config: AudioConfig,
    private val capacityMs: Int = DEFAULT_CAPACITY_MS,
    private val minTargetMs: Int = 60,
    private val maxTargetMs: Int = 400,
    private val adaptStepMs: Int = 20,
) : Closeable {
    companion object {
        // Generated speech can arrive much faster than wall-clock playback. Retain
        // one full 90-second turn (4.32 MB at 24 kHz PCM16), rather than rejecting
        // ordinary longer answers after only 15 seconds of queued speech.
        const val DEFAULT_CAPACITY_MS = 90_000
    }

    init {
        require(config.frameSizeBytes > 0)
        require(minTargetMs > 0 && capacityMs >= minTargetMs)
        require(maxTargetMs >= minTargetMs && adaptStepMs > 0)
        require(config.bytesFor(capacityMs) in 1..Int.MAX_VALUE.toLong())
    }

    val capacityBytes: Int = config.bytesFor(capacityMs).toInt()
    private val bytes = ByteArray(capacityBytes)
    private var head = 0
    private var size = 0
    private var primed = false
    private var finished = false
    private var closed = false
    private var version = 0L
    private var pushedChunks = 0L
    private var underruns = 0L
    private var rejectedChunks = 0L

    @Volatile var targetMs: Int = minTargetMs
        private set

    /** Copies the complete chunk without changing the source position/limit. */
    @Synchronized
    fun push(src: ByteBuffer): Int {
        check(!closed) { "JitterBuffer 已关闭" }
        val count = src.remaining()
        if (count == 0) return 0
        require(count % config.frameSizeBytes == 0) { "PCM 分片必须包含完整采样帧" }
        pushedChunks++
        if (count > capacityBytes - size) {
            rejectedChunks++
            throw AudioQueueOverflowException(size, count, capacityBytes)
        }
        val copy = src.duplicate()
        val tail = (head + size) % capacityBytes
        val first = minOf(count, capacityBytes - tail)
        copy.get(bytes, tail, first)
        if (first < count) copy.get(bytes, 0, count - first)
        size += count
        version++
        finished = false
        if (size >= targetBytes()) primed = true
        return count
    }

    /**
     * Writes at dst.position(), preserving its position/limit (the old ring contract).
     * Returns whole PCM frames; once primed, a short tail is never held back.
     */
    @Synchronized
    fun readInto(dst: ByteBuffer): Int {
        check(!closed) { "JitterBuffer 已关闭" }
        if (size == 0) {
            if (primed && !finished) onUnderrun()
            return 0
        }
        if (!primed && !finished) return 0
        val count = minOf(size, dst.remaining()).let { it - it % config.frameSizeBytes }
        if (count == 0) return 0
        val copy = dst.duplicate()
        val first = minOf(count, capacityBytes - head)
        copy.put(bytes, head, first)
        if (first < count) copy.put(bytes, 0, count - first)
        head = (head + count) % capacityBytes
        size -= count
        return count
    }

    @Synchronized
    fun shouldPlay(): Boolean = size > 0 && (primed || finished)

    val availableBytes: Long
        @Synchronized get() = size.toLong()
    val bufferedMs: Int
        @Synchronized get() = config.millisFor(size.toLong())
    fun targetBytes(): Long = config.bytesFor(targetMs)

    @Synchronized
    fun onUnderrun() {
        underruns++
        targetMs = (targetMs + adaptStepMs).coerceAtMost(minOf(maxTargetMs, capacityMs))
        primed = false
    }

    @Synchronized
    fun beginResponse() {
        check(!closed) { "JitterBuffer 已关闭" }
        finished = false
    }

    @Synchronized
    fun finishResponse() {
        check(!closed) { "JitterBuffer 已关闭" }
        finished = true
    }

    internal data class Snapshot(val availableBytes: Int, val version: Long)

    @Synchronized
    internal fun snapshot(): Snapshot = Snapshot(size, version)

    @Synchronized
    fun stats(): JitterStats = JitterStats(
        pushedChunks = pushedChunks,
        underruns = underruns,
        droppedChunks = rejectedChunks,
        bufferedMs = config.millisFor(size.toLong()),
    )

    /** Explicit interruption/session reset; never used to hide ordinary backlog. */
    @Synchronized
    fun reset(resetStats: Boolean = false) {
        head = 0
        size = 0
        primed = false
        finished = false
        targetMs = minTargetMs
        version++
        if (resetStats) {
            pushedChunks = 0
            underruns = 0
            rejectedChunks = 0
        }
    }

    @Synchronized
    override fun close() {
        reset()
        closed = true
    }
}

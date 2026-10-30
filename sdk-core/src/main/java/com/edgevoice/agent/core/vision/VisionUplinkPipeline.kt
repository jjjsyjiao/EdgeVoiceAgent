package com.edgevoice.agent.core.vision

import android.os.SystemClock
import com.edgevoice.agent.media.video.EncodedFrame
import com.edgevoice.agent.media.video.JpegFrameEncoder
import com.edgevoice.agent.media.video.VideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Counts successful executor enqueue operations, rather than camera capture attempts. */
data class VisionUplinkStats(
    val sentFrames: Long,
    val droppedFrames: Long,
    val rejectedFrames: Long,
    val queuedFrames: Int,
    val lastSentBase64Bytes: Int,
    val lastSentAtMs: Long? = null,
    /** Successful enqueues in the rolling last five seconds divided by five. */
    val recentFps: Float = 0f,
)

/**
 * Camera -> conflated encoder work -> bounded latest-frame queue -> audio-led send.
 * Each work item keeps the local acceptance time and connection epoch. Connection
 * reset, queue mutation and the nonblocking sender share a lock, so an old frame
 * cannot be dequeued before reset and then enqueued onto the replacement session.
 * JPEG encoding and the optional local history sink run outside that lock.
 */
class VisionUplinkPipeline(
    private val config: VisionUplinkConfig,
    private val encoder: JpegFrameEncoder,
    private val scope: CoroutineScope,
    private val supportsVideo: () -> Boolean,
    /** Must return only after a successful, nonblocking transport enqueue; failure throws. */
    private val sender: (ByteArray) -> Unit,
    private val frameSink: ((frame: VideoFrame, encoded: EncodedFrame) -> Unit)? = null,
    private val timeSource: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private data class EncodeWork(
        val frame: VideoFrame,
        val acceptedAtMs: Long,
        val connectionEpoch: Long,
        val lifecycleEpoch: Long,
    )

    private data class QueuedFrame(
        val encoded: EncodedFrame,
        val acceptedAtMs: Long,
        val connectionEpoch: Long,
    )

    private val lock = Any()
    @Volatile private var started = false
    private var encodeJob: Job? = null
    private var encodeChannel: Channel<EncodeWork>? = null
    private var connectionEpoch = 0L
    private var lifecycleEpoch = 0L
    private var audioPrimed = false
    private var uplinkPaused = false
    private var lastAcceptedMs: Long? = null
    private val pending = ArrayDeque<QueuedFrame>()
    private val recentSendTimes = ArrayDeque<Long>()
    private var sent = 0L
    private var dropped = 0L
    private var rejected = 0L
    private var lastSentBase64Bytes = 0
    private var lastSentAtMs: Long? = null

    fun start() {
        synchronized(lock) {
            if (started || !config.enabled) return
            started = true
            lifecycleEpoch++
            connectionEpoch++
            val channel = Channel<EncodeWork>(
                capacity = Channel.CONFLATED,
                onUndeliveredElement = { work ->
                    synchronized(lock) {
                        if (isCurrentLifecycle(work)) dropped++
                    }
                },
            )
            encodeChannel = channel
            encodeJob = scope.launch {
                for (work in channel) {
                    if (!isActive) break
                    encodeAndEnqueue(work)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            started = false
            lifecycleEpoch++
            connectionEpoch++
            encodeJob?.cancel()
            encodeJob = null
            encodeChannel?.cancel()
            encodeChannel = null
            uplinkPaused = false
            clearConnectionState()
            sent = 0L
            dropped = 0L
            rejected = 0L
        }
    }

    /**
     * Invalidates work accepted on the previous model connection, including JPEGs
     * still being encoded. Session counters survive; recent connection timing does not.
     */
    fun resetConnection() {
        synchronized(lock) {
            connectionEpoch++
            if (started) dropped += pending.size
            clearConnectionState()
            val channel = encodeChannel
            while (channel != null) {
                val queued = channel.tryReceive().getOrNull() ?: break
                if (isCurrentLifecycle(queued)) dropped++
            }
        }
    }

    private fun clearConnectionState() {
        pending.clear()
        audioPrimed = false
        lastAcceptedMs = null
        lastSentBase64Bytes = 0
        lastSentAtMs = null
        recentSendTimes.clear()
    }

    /** Camera thread: acceptance is timed locally, independently of camera timestamps. */
    fun onFrame(frame: VideoFrame) {
        if (!started || frame.nv21 == null) return
        if (!supportsVideo() && frameSink == null) return
        val now = timeSource()
        synchronized(lock) {
            if (!started) return
            val previous = lastAcceptedMs
            if (previous != null && now - previous < config.frameIntervalMs) {
                dropped++
                return
            }
            lastAcceptedMs = now
            val work = EncodeWork(frame, now, connectionEpoch, lifecycleEpoch)
            if (encodeChannel?.trySend(work)?.isSuccess != true) dropped++
        }
    }

    /** Paused replay windows still preserve the optional local history sink. */
    fun setUplinkPaused(paused: Boolean) {
        synchronized(lock) {
            uplinkPaused = paused
            if (paused) {
                dropped += pending.size
                pending.clear()
            }
        }
    }

    /** Call after successful PCM enqueue on the current connection. Sends at most one fresh frame. */
    fun onAudioFrameSent() {
        synchronized(lock) {
            if (!started) return
            audioPrimed = true
            if (uplinkPaused) return
            if (!supportsVideo() || budgetExhausted()) {
                dropped += pending.size
                pending.clear()
                return
            }
            if (!audioPrimed) return
            val now = timeSource()
            var next: QueuedFrame? = null
            while (pending.isNotEmpty()) {
                val candidate = pending.removeFirst()
                if (candidate.connectionEpoch != connectionEpoch || expired(candidate.acceptedAtMs, now)) {
                    dropped++
                } else {
                    next = candidate
                    break
                }
            }
            val frame = next ?: return
            val sendingEpoch = connectionEpoch
            val sendingLifecycle = lifecycleEpoch
            runCatching { sender(frame.encoded.jpeg) }
                .onSuccess {
                    if (!started || sendingLifecycle != lifecycleEpoch) return@onSuccess
                    sent++
                    // A reentrant test/fake sender can reset the connection itself.
                    // Never repopulate the new epoch's timing with the old enqueue.
                    if (started && sendingEpoch == connectionEpoch) {
                        val sentAt = timeSource()
                        lastSentAtMs = sentAt
                        lastSentBase64Bytes = frame.encoded.base64Size
                        recentSendTimes.addLast(sentAt)
                        pruneRecent(sentAt)
                    }
                }
                .onFailure {
                    if (started && sendingLifecycle == lifecycleEpoch) dropped++
                }
        }
    }

    fun stats(): VisionUplinkStats = synchronized(lock) {
        pruneRecent(timeSource())
        VisionUplinkStats(
            sentFrames = sent,
            droppedFrames = dropped,
            rejectedFrames = rejected,
            queuedFrames = pending.size,
            lastSentBase64Bytes = lastSentBase64Bytes,
            lastSentAtMs = lastSentAtMs,
            recentFps = recentSendTimes.size / 5f,
        )
    }

    private fun encodeAndEnqueue(work: EncodeWork) {
        synchronized(lock) {
            if (!isCurrentLifecycle(work)) return
            if (work.connectionEpoch != connectionEpoch) {
                dropped++
                return
            }
        }
        val frame = work.frame
        val nv21 = frame.nv21 ?: return
        val encoded = runCatching {
            encoder.encode(nv21, frame.width, frame.height, frame.rotationDegrees)
        }.getOrNull()
        if (encoded == null) {
            synchronized(lock) {
                if (isCurrentLifecycle(work)) {
                    if (work.connectionEpoch == connectionEpoch) rejected++ else dropped++
                }
            }
            return
        }
        synchronized(lock) { if (!isCurrentLifecycle(work)) return }
        // Local history is independent of live connectivity, as before. Stop/restart
        // work is excluded; a connection change does not turn old history into live input.
        frameSink?.let { sink -> runCatching { sink(frame, encoded) } }
        synchronized(lock) {
            if (!isCurrentLifecycle(work)) return
            if (work.connectionEpoch != connectionEpoch || expired(work.acceptedAtMs, timeSource())) {
                dropped++
                return
            }
            if (uplinkPaused || !supportsVideo()) return
            while (pending.size >= config.maxQueuedFrames) {
                pending.removeFirst()
                dropped++
            }
            pending.addLast(QueuedFrame(encoded, work.acceptedAtMs, work.connectionEpoch))
        }
    }

    private fun isCurrentLifecycle(work: EncodeWork): Boolean = started && work.lifecycleEpoch == lifecycleEpoch

    private fun expired(acceptedAtMs: Long, nowMs: Long): Boolean = nowMs - acceptedAtMs > config.maxFrameAgeMs

    private fun pruneRecent(nowMs: Long) {
        while (recentSendTimes.isNotEmpty() && nowMs - recentSendTimes.first() >= 5_000L) {
            recentSendTimes.removeFirst()
        }
    }

    private fun budgetExhausted(): Boolean = config.maxFramesPerSession > 0 && sent >= config.maxFramesPerSession
}

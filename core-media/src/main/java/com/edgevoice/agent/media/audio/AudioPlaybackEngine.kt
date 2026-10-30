package com.edgevoice.agent.media.audio

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small device boundary so queueing, partial writes and completion can be tested on JVM. */
internal interface PcmPlaybackOutput : Closeable {
    /** Monotonic played PCM frames, reset to zero by flush(). */
    val playedFrames: Long
    val minimumStartFrames: Int get() = 0
    fun play()
    /** Nonblocking write, returning accepted bytes or an AudioTrack error code. */
    fun write(buffer: ByteBuffer, byteCount: Int): Int
    fun flush()
    fun stop()
    fun onPlaybackThreadStart() {}
}

/**
 * Plays complete model speech, including short answers and final tails. The server's
 * response.done only arms [finishResponse]; completion waits for both the software
 * queue and AudioTrack's playback head. Callbacks include the answer generation so
 * callers can reject an old completion after switching answers/sessions.
 */
class AudioPlaybackEngine internal constructor(
    private val config: AudioConfig,
    private val jitter: JitterBuffer,
    private val frameMs: Int,
    private val onPlaybackComplete: (Long) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val outputFactory: () -> PcmPlaybackOutput,
) : Closeable {
    constructor(
        config: AudioConfig,
        jitter: JitterBuffer,
        frameMs: Int = 20,
        onPlaybackComplete: (Long) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) : this(config, jitter, frameMs, onPlaybackComplete, onError, {
        AndroidPcmPlaybackOutput(config, frameMs)
    })

    private data class Completion(
        val generation: Long,
        val queueVersion: Long,
        var primingPadded: Boolean = false,
    )

    private val commands = Any()
    @Volatile private var running = false
    @Volatile private var output: PcmPlaybackOutput? = null
    private var thread: Thread? = null
    private var generation = 0L
    private var flushSequence = 0L
    private var completion: Completion? = null
    private val frameBytes = config.bytesFor(frameMs).toInt()

    /** Bytes successfully accepted by AudioTrack during the current session. */
    @Volatile var playedBytes: Long = 0L
        private set

    init {
        require(frameBytes > 0 && frameBytes % config.frameSizeBytes == 0)
    }

    @Synchronized
    fun start() {
        if (running) return
        check(thread?.isAlive != true) { "上一条音频播放线程尚未退出" }
        val sink = outputFactory()
        try {
            jitter.reset(resetStats = true)
            synchronized(commands) {
                generation++
                completion = null
                flushSequence = 0L
                playedBytes = 0L
                output = sink
                running = true
            }
            sink.play()
            thread = Thread({ playbackLoop(sink) }, "evs-audio-playback").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            running = false
            output = null
            runCatching { sink.close() }
            throw error
        }
    }

    /** Cancels an older completion marker without discarding queued speech. */
    fun beginResponse(): Long = synchronized(commands) {
        generation++
        completion = null
        jitter.beginResponse()
        generation
    }

    /** Call only after all audio chunks for the current answer have been queued. */
    fun finishResponse() {
        synchronized(commands) {
            if (!running) return
            jitter.finishResponse()
            completion = Completion(generation, jitter.snapshot().version)
        }
    }

    private fun playbackLoop(sink: PcmPlaybackOutput) {
        var appliedFlush = 0L
        var writtenFrames = 0L
        val direct = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val silence = ByteArray(frameBytes)
        fun writePending(expectedFlush: Long, paddingGeneration: Long? = null) {
            while (running && direct.hasRemaining()) {
                val invalidated = synchronized(commands) {
                    flushSequence != expectedFlush ||
                        (paddingGeneration != null && generation != paddingGeneration)
                }
                if (invalidated) break
                val position = direct.position()
                val remaining = direct.remaining()
                val accepted = sink.write(direct, remaining)
                check(accepted >= 0) { playbackFailureReason(accepted) }
                check(accepted <= remaining && accepted % config.frameSizeBytes == 0) {
                    "AudioTrack 返回无效 PCM 写入长度：$accepted"
                }
                if (accepted == 0) {
                    Thread.sleep(2L)
                } else {
                    direct.position(position + accepted)
                    playedBytes += accepted
                    writtenFrames += accepted / config.frameSizeBytes
                }
            }
        }
        try {
            sink.onPlaybackThreadStart()
            while (running) {
                val requestedFlush = synchronized(commands) { flushSequence }
                if (requestedFlush != appliedFlush) {
                    sink.flush()
                    writtenFrames = 0L
                    appliedFlush = requestedFlush
                    // interrupt() already cleared old software audio. Clearing here
                    // would also delete a new answer queued while the flush ran.
                    continue
                }

                direct.clear()
                val count = synchronized(commands) {
                    if (flushSequence != appliedFlush) 0 else jitter.readInto(direct)
                }
                if (count > 0) {
                    direct.limit(count)
                    // AudioTrack may accept only part of a buffer (or zero). Retain
                    // the remainder until accepted or an explicit interruption.
                    writePending(appliedFlush)
                }

                val padding = synchronized(commands) {
                    val done = completion
                    val snapshot = jitter.snapshot()
                    if (done != null && !done.primingPadded &&
                        done.queueVersion == snapshot.version && snapshot.availableBytes == 0 &&
                        flushSequence == appliedFlush && writtenFrames > 0
                    ) {
                        val pendingFrames = (writtenFrames - sink.playedFrames).coerceAtLeast(0L)
                        // Only actual unplayed PCM can need a device start threshold.
                        // An empty response must not invent a new silence tail after
                        // old speech was already heard; retained real tails still prime.
                        if (pendingFrames > 0L) {
                            done.primingPadded = true
                            done.generation to (sink.minimumStartFrames - pendingFrames).coerceAtLeast(0L)
                        } else null
                    } else null
                }
                if (padding != null) {
                    var remainingFrames = padding.second
                    while (running && remainingFrames > 0) {
                        val frames = minOf(remainingFrames, frameBytes / config.frameSizeBytes.toLong())
                        val byteCount = (frames * config.frameSizeBytes).toInt()
                        direct.clear()
                        direct.put(silence, 0, byteCount)
                        direct.flip()
                        writePending(appliedFlush, padding.first)
                        remainingFrames -= frames
                        if (synchronized(commands) { generation != padding.first }) break
                    }
                }

                synchronized(commands) {
                    val done = completion
                    val snapshot = jitter.snapshot()
                    if (done != null && done.queueVersion != snapshot.version) {
                        // Data arrived after the end marker: it must be finished
                        // explicitly and must not reuse an older completion.
                        completion = null
                    } else if (
                        running && done != null && done.generation == generation &&
                        flushSequence == appliedFlush && snapshot.availableBytes == 0 &&
                        sink.playedFrames >= writtenFrames
                    ) {
                        completion = null
                        onPlaybackComplete(done.generation)
                    }
                }
                if (count == 0) Thread.sleep(5L)
            }
        } catch (interrupted: InterruptedException) {
            if (running) runCatching { onError(interrupted) }
        } catch (error: Throwable) {
            if (running) runCatching { onError(error) }
        } finally {
            synchronized(commands) {
                running = false
                generation++
                completion = null
                jitter.reset()
            }
            runCatching { sink.stop() }
            runCatching { sink.close() }
            if (output === sink) output = null
        }
    }

    /** Invalidates old completion and discards old speech, preserving later pushes. */
    fun interrupt() {
        synchronized(commands) {
            generation++
            completion = null
            jitter.reset()
            flushSequence++
        }
    }

    @Synchronized
    fun stop() {
        synchronized(commands) {
            running = false
            generation++
            completion = null
            jitter.reset()
            flushSequence++
        }
        val worker = thread
        // Stop the hardware immediately and unblock a device write before join.
        runCatching { output?.stop() }
        worker?.interrupt()
        if (worker !== Thread.currentThread()) worker?.join(1_000L)
        if (worker?.isAlive != true) thread = null
        // A stuck worker retains ownership of its sink and cannot race a restart:
        // start() refuses a second worker until the first has released resources.
    }

    override fun close() = stop()

    /** Terminal cleanup on a dedicated IO thread, after stop(); waits for real sink release. */
    @Synchronized
    fun awaitStopped() {
        check(!running) { "请先停止播放，再等待播放线程退出" }
        val worker = thread
        check(worker !== Thread.currentThread()) { "播放线程不能等待自身退出" }
        worker?.join()
        if (thread === worker) thread = null
    }
}

private class AndroidPcmPlaybackOutput(config: AudioConfig, frameMs: Int) : PcmPlaybackOutput {
    private val track: AudioTrack
    override val minimumStartFrames: Int
    private var lastHead = 0L
    private var headWrap = 0L

    init {
        require(config.channelCount == 1 && config.bitsPerSample == 16)
        val minBuffer = AudioTrack.getMinBufferSize(
            config.sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "设备不支持 ${config.sampleRateHz}Hz 单声道播放" }
        track = AudioTrack.Builder()
            .setAudioAttributes(communicationAudioAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(config.sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, config.bytesFor(frameMs * 8).toInt()))
            .build()
        minimumStartFrames = try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack 初始化失败" }
            // Streaming tracks require a minimum fill before starting/restarting after
            // underrun. API 31 exposes this threshold; older versions need a small
            // silence tail to release very short answers. It never replaces speech.
            if (Build.VERSION.SDK_INT >= 31) {
                track.setStartThresholdInFrames(1)
            } else {
                track.bufferSizeInFrames
            }
        } catch (failure: Throwable) {
            // The output factory has not returned a sink yet, so start() cannot
            // release this successfully built track if post-build setup fails.
            runCatching { track.release() }
            throw failure
        }
    }

    override val playedFrames: Long
        get() {
            val raw = track.playbackHeadPosition.toLong() and 0xffff_ffffL
            if (raw < lastHead) headWrap += 1L shl 32
            lastHead = raw
            return headWrap + raw
        }

    override fun onPlaybackThreadStart() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }

    override fun play() = track.play()
    override fun write(buffer: ByteBuffer, byteCount: Int): Int =
        track.write(buffer, byteCount, AudioTrack.WRITE_NON_BLOCKING)

    override fun flush() {
        track.pause()
        track.flush()
        lastHead = 0L
        headWrap = 0L
        track.play()
    }

    override fun stop() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
    }

    override fun close() = track.release()
}

internal fun playbackFailureReason(code: Int): String = when (code) {
    AudioTrack.ERROR_DEAD_OBJECT -> "系统音频服务或输出设备已失效，请重新连接耳机后重试（错误码 $code）"
    AudioTrack.ERROR_INVALID_OPERATION -> "系统已回收语音播放通道，请结束其他通话后重试（错误码 $code）"
    AudioTrack.ERROR_BAD_VALUE -> "当前输出设备不支持语音播放参数，请切换耳机或外放后重试（错误码 $code）"
    else -> "语音播放失败，请检查系统音频设备后重试（错误码 $code）"
}

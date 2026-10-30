package com.edgevoice.agent.media.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioPlaybackEngineTest {
    private val config = AudioConfig(sampleRateHz = 1_000)

    private class FakeOutput(
        private val maxWrite: Int = 16,
        private var zeroWrites: Int = 0,
        override val minimumStartFrames: Int = 0,
        private val failureCode: Int? = null,
        private val beforeClose: (() -> Unit)? = null,
    ) : PcmPlaybackOutput {
        private val received = ByteArrayOutputStream()
        private var bytesSinceFlush = 0
        @Volatile override var playedFrames = 0L
        @Volatile var flushes = 0
        @Volatile var closed = false
        override fun play() {}
        @Synchronized override fun write(buffer: ByteBuffer, byteCount: Int): Int {
            failureCode?.let { return it }
            if (zeroWrites-- > 0) return 0
            val count = minOf(byteCount, maxWrite)
            val chunk = ByteArray(count)
            buffer.get(chunk)
            received.write(chunk)
            bytesSinceFlush += count
            return count
        }
        @Synchronized fun bytes(): ByteArray = received.toByteArray()
        @Synchronized fun playAll() { playedFrames = bytesSinceFlush / 2L }
        @Synchronized override fun flush() {
            bytesSinceFlush = 0
            playedFrames = 0
            flushes++
        }
        override fun stop() {}
        override fun close() { beforeClose?.invoke(); closed = true }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue(description, condition())
    }

    private fun engine(
        queue: JitterBuffer,
        sink: FakeOutput,
        complete: (Long) -> Unit = {},
        error: (Throwable) -> Unit = {},
    ) = AudioPlaybackEngine(config, queue, 20, complete, error, { sink })

    @Test
    fun long_generated_burst_plays_in_order_and_waits_for_the_hardware_tail() {
        val queue = JitterBuffer(config)
        val sink = FakeOutput(maxWrite = 30, zeroWrites = 2)
        val completed = CountDownLatch(1)
        val player = engine(queue, sink, { completed.countDown() })
        val speech = ByteArray(config.bytesFor(24_037).toInt()) { (it % 127).toByte() }
        try {
            player.start()
            player.beginResponse()
            queue.push(ByteBuffer.wrap(speech))
            player.finishResponse()
            awaitCondition("the complete long burst reached the hardware") {
                player.playedBytes == speech.size.toLong()
            }
            assertThat(sink.bytes()).isEqualTo(speech)
            assertThat(queue.availableBytes).isEqualTo(0)
            assertThat(completed.count).isEqualTo(1)
            sink.playAll()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
        } finally { player.stop() }
    }

    @Test
    fun partial_and_zero_writes_preserve_audio_and_wait_for_hardware_playback() {
        val queue = JitterBuffer(config)
        val sink = FakeOutput(maxWrite = 6, zeroWrites = 2)
        val completed = CountDownLatch(1)
        val callbacks = CopyOnWriteArrayList<Long>()
        val player = engine(queue, sink, { callbacks.add(it); completed.countDown() })
        try {
            player.start()
            val generation = player.beginResponse()
            val speech = ByteArray(42) { (it + 1).toByte() }
            queue.push(ByteBuffer.wrap(speech))
            player.finishResponse()
            awaitCondition("all speech was accepted and counted") {
                sink.bytes().size == speech.size && player.playedBytes == speech.size.toLong()
            }
            assertThat(sink.bytes()).isEqualTo(speech)
            assertThat(player.playedBytes).isEqualTo(speech.size.toLong())
            assertThat(completed.count).isEqualTo(1)
            sink.playAll()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertThat(callbacks).containsExactly(generation)
        } finally { player.stop() }
        assertThat(sink.closed).isTrue()
    }

    @Test
    fun interrupt_invalidates_old_completion_without_deleting_next_answer() {
        val queue = JitterBuffer(config)
        val sink = FakeOutput()
        val callbacks = CopyOnWriteArrayList<Long>()
        val player = engine(queue, sink, callbacks::add)
        try {
            player.start()
            player.beginResponse()
            queue.push(ByteBuffer.wrap(ByteArray(40) { 1 }))
            player.finishResponse()
            awaitCondition("first answer reached hardware") { sink.bytes().size == 40 }
            player.interrupt()
            val next = player.beginResponse()
            val speech = ByteArray(40) { 2 }
            queue.push(ByteBuffer.wrap(speech))
            player.finishResponse()
            awaitCondition("new answer survived the hardware flush") {
                sink.flushes > 0 && sink.bytes().size == 80
            }
            assertThat(callbacks).isEmpty()
            sink.playAll()
            awaitCondition("only the new completion fired") { callbacks.size == 1 }
            assertThat(callbacks).containsExactly(next)
            assertThat(sink.bytes().takeLast(40)).containsExactlyElementsIn(speech.toList()).inOrder()
        } finally { player.stop() }
    }

    @Test
    fun empty_response_after_spoken_answer_does_not_add_fake_audio_padding() {
        val queue = JitterBuffer(config)
        val sink = FakeOutput(minimumStartFrames = 100)
        val callbacks = CopyOnWriteArrayList<Long>()
        val player = engine(queue, sink, callbacks::add)
        try {
            player.start()
            player.beginResponse()
            queue.push(ByteBuffer.wrap(ByteArray(20) { 5 }))
            player.finishResponse()
            awaitCondition("first answer reached the start threshold") { player.playedBytes == 200L }
            sink.playAll()
            awaitCondition("first answer completed") { callbacks.size == 1 }
            val empty = player.beginResponse()
            player.finishResponse()
            awaitCondition("empty response completed without a hardware write") { callbacks.size == 2 }
            assertThat(callbacks.last()).isEqualTo(empty)
            assertThat(player.playedBytes).isEqualTo(200L)
            assertThat(sink.bytes().size).isEqualTo(200)
        } finally { player.stop() }
    }

    @Test
    fun older_device_start_threshold_is_met_with_silence_after_short_speech() {
        val queue = JitterBuffer(config)
        val sink = FakeOutput(minimumStartFrames = 100)
        val complete = CountDownLatch(1)
        val player = engine(queue, sink, { complete.countDown() })
        try {
            player.start()
            player.beginResponse()
            val speech = ByteArray(20) { 5 }
            queue.push(ByteBuffer.wrap(speech))
            player.finishResponse()
            awaitCondition("short speech primed the device") { sink.bytes().size == 200 }
            assertThat(sink.bytes().copyOfRange(0, 20)).isEqualTo(speech)
            assertThat(sink.bytes().copyOfRange(20, 200)).isEqualTo(ByteArray(180))
            assertThat(complete.count).isEqualTo(1)
            sink.playAll()
            assertTrue(complete.await(2, TimeUnit.SECONDS))
        } finally { player.stop() }
    }

    @Test
    fun stop_and_restart_clear_old_audio_and_reset_session_counter() {
        val queue = JitterBuffer(config)
        val sinks = CopyOnWriteArrayList<FakeOutput>()
        val player = AudioPlaybackEngine(config, queue, 20, {}, {}, {
            FakeOutput().also(sinks::add)
        })
        try {
            player.start()
            player.beginResponse()
            queue.push(ByteBuffer.wrap(ByteArray(120) { 1 }))
            awaitCondition("initial audio was written") { player.playedBytes > 0 }
            player.stop()
            queue.push(ByteBuffer.wrap(ByteArray(120) { 9 }))
            player.start()
            assertThat(queue.availableBytes).isEqualTo(0)
            assertThat(player.playedBytes).isEqualTo(0)
            player.beginResponse()
            queue.push(ByteBuffer.wrap(ByteArray(20) { 2 }))
            player.finishResponse()
            awaitCondition("new session audio was written") { sinks[1].bytes().size == 20 }
            assertThat(sinks[1].bytes()).isEqualTo(ByteArray(20) { 2 })
        } finally { player.stop() }
        assertThat(sinks.all { it.closed }).isTrue()
    }

    @Test
    fun hardware_error_is_reported_and_worker_releases_output() {
        val queue = JitterBuffer(config)
        val sink = FakeOutput(failureCode = -6)
        val errors = CountDownLatch(1)
        val callbacks = CopyOnWriteArrayList<Long>()
        val player = engine(queue, sink, callbacks::add, { errors.countDown() })
        try {
            player.start()
            player.beginResponse()
            queue.push(ByteBuffer.wrap(ByteArray(40)))
            player.finishResponse()
            assertTrue(errors.await(2, TimeUnit.SECONDS))
            awaitCondition("failed output was released") { sink.closed }
            assertThat(callbacks).isEmpty()
        } finally { player.stop() }
    }

    @Test
    fun terminal_await_waits_for_worker_and_real_output_release_after_stop_timeout() {
        val queue = JitterBuffer(config)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val awaited = CountDownLatch(1)
        val sink = FakeOutput(beforeClose = {
            closeEntered.countDown()
            // Model a slow native close that does not honor Thread.interrupt().
            while (releaseClose.count > 0) {
                try { releaseClose.await() } catch (_: InterruptedException) { /* keep waiting */ }
            }
        })
        val player = engine(queue, sink)
        var cleanup: Thread? = null
        try {
            player.start()
            // stop() has a bounded join; a slow native resource release may outlive it.
            player.stop()
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS))
            assertThat(sink.closed).isFalse()
            cleanup = Thread {
                player.awaitStopped()
                awaited.countDown()
            }.apply { start() }
            assertFalse(awaited.await(30, TimeUnit.MILLISECONDS))
            releaseClose.countDown()
            assertTrue(awaited.await(2, TimeUnit.SECONDS))
            assertThat(sink.closed).isTrue()
        } finally {
            releaseClose.countDown()
            cleanup?.join(2_000L)
            player.stop()
            player.awaitStopped()
        }
    }
}

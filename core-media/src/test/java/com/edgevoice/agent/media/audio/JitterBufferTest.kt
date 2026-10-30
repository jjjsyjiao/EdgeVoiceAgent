package com.edgevoice.agent.media.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class JitterBufferTest {
    private val config = AudioConfig(sampleRateHz = 1_000)

    @Test
    fun real_sample_rate_long_speech_burst_is_retained_with_a_finite_turn_budget() {
        val realConfig = AudioConfig.REALTIME_UP
        JitterBuffer(realConfig).use { queue ->
            val speech = ByteArray(realConfig.bytesFor(24_037).toInt()) { (it % 127).toByte() }
            // The server may generate the whole answer before hardware consumes it.
            queue.push(ByteBuffer.wrap(speech))
            queue.finishResponse()
            val output = ByteArrayOutputStream()
            val frame = ByteBuffer.allocate(realConfig.bytesFor(20).toInt())
            while (queue.shouldPlay()) {
                frame.clear()
                val count = queue.readInto(frame)
                output.write(frame.array(), 0, count)
            }
            assertThat(output.toByteArray()).isEqualTo(speech)
            assertThat(queue.stats().droppedChunks).isEqualTo(0)
            val fullTurn = ByteArray(realConfig.bytesFor(90_000).toInt())
            queue.push(ByteBuffer.wrap(fullTurn))
            assertThrows(AudioQueueOverflowException::class.java) {
                queue.push(ByteBuffer.wrap(byteArrayOf(1, 2)))
            }
            assertThat(queue.availableBytes).isEqualTo(fullTurn.size.toLong())
        }
    }

    @Test
    fun burst_and_final_tail_are_preserved_byte_for_byte() {
        val queue = JitterBuffer(config, capacityMs = 1_000)
        val speech = ByteArray(config.bytesFor(510).toInt()) { (it % 127).toByte() }
        queue.push(ByteBuffer.wrap(speech))
        val output = ByteArrayOutputStream()
        val frame = ByteBuffer.allocate(40)
        while (queue.shouldPlay()) {
            frame.clear()
            val count = queue.readInto(frame)
            output.write(frame.array(), 0, count)
        }
        assertThat(output.toByteArray()).isEqualTo(speech)
        assertThat(queue.availableBytes).isEqualTo(0)
        assertThat(queue.stats().droppedChunks).isEqualTo(0)
    }

    @Test
    fun response_shorter_than_priming_is_released_by_end_marker() {
        val queue = JitterBuffer(config)
        val speech = ByteArray(20) { 7 }
        queue.push(ByteBuffer.wrap(speech))
        val frame = ByteBuffer.allocate(40)
        assertThat(queue.readInto(frame)).isEqualTo(0)
        queue.finishResponse()
        assertThat(queue.readInto(frame)).isEqualTo(speech.size)
        assertThat(frame.position()).isEqualTo(0)
        assertThat(frame.array().take(speech.size)).containsExactlyElementsIn(speech.toList()).inOrder()
        assertThat(queue.availableBytes).isEqualTo(0)
    }

    @Test
    fun overflow_rejects_whole_chunk_and_retains_existing_speech() {
        val queue = JitterBuffer(config, capacityMs = 100)
        val speech = ByteArray(160) { it.toByte() }
        queue.push(ByteBuffer.wrap(speech))
        val rejected = ByteBuffer.wrap(ByteArray(60) { 9 })
        assertThrows(AudioQueueOverflowException::class.java) { queue.push(rejected) }
        assertThat(rejected.position()).isEqualTo(0)
        assertThat(queue.availableBytes).isEqualTo(speech.size.toLong())
        val frame = ByteBuffer.allocate(speech.size)
        assertThat(queue.readInto(frame)).isEqualTo(speech.size)
        assertThat(frame.array()).isEqualTo(speech)
        assertThat(queue.stats().droppedChunks).isEqualTo(1)
    }

    @Test
    fun wrapped_queue_and_source_window_keep_order_and_positions() {
        val queue = JitterBuffer(config, capacityMs = 100)
        val first = ByteArray(160) { it.toByte() }
        val second = ByteArray(120) { (it + 20).toByte() }
        queue.push(ByteBuffer.wrap(first))
        val consumed = ByteBuffer.allocate(120)
        queue.readInto(consumed)
        val source = ByteBuffer.wrap(byteArrayOf(99, 99) + second + byteArrayOf(88, 88))
        source.position(2)
        source.limit(122)
        queue.push(source)
        assertThat(source.position()).isEqualTo(2)
        assertThat(source.limit()).isEqualTo(122)
        queue.finishResponse()
        val remainder = ByteBuffer.allocate(160)
        assertThat(queue.readInto(remainder)).isEqualTo(160)
        assertThat(remainder.array()).isEqualTo(first.copyOfRange(120, 160) + second)
    }

    @Test
    fun reset_removes_previous_session_and_clears_end_marker() {
        val queue = JitterBuffer(config)
        queue.push(ByteBuffer.wrap(ByteArray(120) { 1 }))
        queue.finishResponse()
        queue.reset(resetStats = true)
        assertThat(queue.availableBytes).isEqualTo(0)
        assertThat(queue.stats().pushedChunks).isEqualTo(0)
        queue.push(ByteBuffer.wrap(ByteArray(20) { 2 }))
        assertThat(queue.readInto(ByteBuffer.allocate(120))).isEqualTo(0)
        queue.finishResponse()
        val frame = ByteBuffer.allocate(20)
        assertThat(queue.readInto(frame)).isEqualTo(20)
        assertThat(frame.array()).isEqualTo(ByteArray(20) { 2 })
    }
}

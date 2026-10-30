package com.edgevoice.agent.transport.ws

import com.edgevoice.agent.transport.TransportFailure
import com.edgevoice.agent.transport.TransportListener
import com.edgevoice.agent.transport.TransportState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketRecoveryTest {
    private class Socket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val messages = mutableListOf<String>()
        var queuedBytes = 0L
        var cancelled = false
        var retainOutgoing = false
        override fun request() = request
        override fun queueSize() = queuedBytes
        override fun send(text: String): Boolean {
            if (cancelled) return false
            messages.add(text)
            if (retainOutgoing) queuedBytes += text.toByteArray().size
            return true
        }
        override fun send(bytes: ByteString): Boolean = send(bytes.utf8())
        override fun close(code: Int, reason: String?): Boolean { cancelled = true; return true }
        override fun cancel() { cancelled = true; queuedBytes = 0 }
        fun response(code: Int) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("test response").build()
        fun open() { listener.onOpen(this, response(101)) }
        fun fail(status: Int? = null, cause: Throwable = IOException("test network failure")) {
            listener.onFailure(this, cause, status?.let(::response))
        }
    }

    private class Harness {
        val sockets = mutableListOf<Socket>()
        val errors = mutableListOf<TransportFailure>()
        val states = mutableListOf<TransportState>()
        val received = mutableListOf<String>()
        fun transport(scope: CoroutineScope, capacity: Int = 256, maxBytes: Long = 1024, age: Long = 3000, clock: () -> Long = System::nanoTime) =
            WebSocketTransport(
                url = "http://127.0.0.1:1/realtime", scope = scope,
                reconnectPolicy = ReconnectPolicy(baseMs = 10, maxDelayMs = 20, jitterMs = 0, maxAttempts = 2),
                callbackCapacity = capacity, maxOutgoingBytes = maxBytes, maxOutgoingAgeMs = age,
                nanoTime = clock, socketFactory = { request, listener -> Socket(request, listener).also(sockets::add) },
            ).also { transport ->
                transport.setListener(object : TransportListener {
                    override fun onStateChanged(state: TransportState) { states.add(state) }
                    override fun onTextMessage(text: String) { received.add(text) }
                    override fun onError(cause: Throwable) { errors.add(cause as TransportFailure) }
                })
            }
    }

    @Test
    fun heartbeat_failure_recovers_with_new_epoch_and_old_callbacks_are_rejected() = runTest {
        val harness = Harness()
        val transport = harness.transport(backgroundScope)
        transport.connect()
        val old = harness.sockets.single()
        old.open()
        runCurrent()
        val epoch = transport.connectionEpoch
        old.fail(cause = SocketTimeoutException("missing pong"))
        runCurrent()
        assertThat(transport.state.value).isEqualTo(TransportState.RECONNECTING)
        assertThat(harness.errors.single().recoverable).isTrue()
        assertThat(old.cancelled).isTrue()
        old.listener.onMessage(old, "stale answer")
        advanceTimeBy(10)
        runCurrent()
        harness.sockets.last().open()
        harness.sockets.last().listener.onMessage(harness.sockets.last(), "new answer")
        runCurrent()
        assertThat(transport.connectionEpoch).isGreaterThan(epoch)
        assertThat(harness.received).containsExactly("new answer")
        transport.close()
    }

    @Test
    fun unauthorized_handshake_is_terminal_without_retry() = runTest {
        val harness = Harness()
        val transport = harness.transport(backgroundScope)
        transport.connect()
        harness.sockets.single().fail(401)
        advanceTimeBy(100)
        runCurrent()
        assertThat(transport.state.value).isEqualTo(TransportState.FAILED)
        assertThat(harness.errors.single().recoverable).isFalse()
        assertThat(harness.errors.single().statusCode).isEqualTo(401)
        assertThat(harness.sockets).hasSize(1)
    }

    @Test
    fun rate_limit_and_repeated_short_connections_have_bounded_backoff() = runTest {
        val harness = Harness()
        val transport = harness.transport(backgroundScope)
        transport.connect()
        harness.sockets.last().fail(429)
        advanceTimeBy(10)
        runCurrent()
        assertThat(harness.sockets).hasSize(2)
        // A successful handshake alone must not reset the retry budget.
        harness.sockets.last().open()
        harness.sockets.last().fail(429)
        advanceTimeBy(20)
        runCurrent()
        assertThat(harness.sockets).hasSize(3)
        harness.sockets.last().fail(429)
        runCurrent()
        assertThat(transport.state.value).isEqualTo(TransportState.FAILED)
        assertThat(harness.errors.last().recoverable).isFalse()
        assertThat(harness.sockets).hasSize(3)
    }

    @Test
    fun incoming_overload_is_terminal_and_does_not_replay_queued_messages() = runTest {
        val harness = Harness()
        val transport = harness.transport(backgroundScope, capacity = 3)
        transport.connect()
        harness.sockets.single().open()
        runCurrent()
        repeat(8) { harness.sockets.single().listener.onMessage(harness.sockets.single(), "packet $it") }
        runCurrent()
        assertThat(transport.state.value).isEqualTo(TransportState.FAILED)
        assertThat(harness.received).isEmpty()
        assertThat(harness.errors.single().recoverable).isFalse()
    }

    @Test
    fun retry_budget_resets_only_after_a_stable_configured_connection() = runTest {
        val harness = Harness()
        val transport = harness.transport(backgroundScope)
        transport.connect()
        harness.sockets.last().fail()
        advanceTimeBy(10)
        runCurrent()
        harness.sockets.last().open()
        runCurrent()
        transport.markSessionReady()
        assertThat(transport.reconnectAttempt).isEqualTo(1)
        advanceTimeBy(30_001)
        runCurrent()
        assertThat(transport.reconnectAttempt).isEqualTo(0)
        transport.close()
    }

    @Test
    fun outgoing_overload_cancels_old_socket_instead_of_replaying_pcm() = runTest {
        val harness = Harness()
        val transport = harness.transport(backgroundScope, maxBytes = 100)
        transport.connect()
        val old = harness.sockets.single()
        old.open()
        runCurrent()
        old.retainOutgoing = true
        assertThat(transport.sendText("a".repeat(60))).isTrue()
        assertThat(transport.sendText("b".repeat(60))).isFalse()
        assertThat(old.cancelled).isTrue()
        advanceTimeBy(10)
        runCurrent()
        val next = harness.sockets.last()
        next.open()
        runCurrent()
        assertThat(transport.sendText("fresh audio")).isTrue()
        assertThat(next.messages).containsExactly("fresh audio")
        transport.close()
    }

    @Test
    fun stale_outgoing_queue_is_cancelled_by_watchdog_without_another_send() = runTest {
        val harness = Harness()
        val transport = harness.transport(backgroundScope, age = 500, clock = { testScheduler.currentTime * 1_000_000L })
        transport.connect()
        val old = harness.sockets.single()
        old.open()
        runCurrent()
        old.retainOutgoing = true
        transport.sendText("queued media")
        advanceTimeBy(501)
        runCurrent()
        assertThat(old.cancelled).isTrue()
        assertThat(transport.state.value).isEqualTo(TransportState.RECONNECTING)
        transport.close()
    }

    @Test
    fun atomic_epoch_send_refuses_old_input_on_a_ready_new_socket() = runTest {
        val harness = Harness()
        val transport = harness.transport(backgroundScope)
        transport.connect()
        val old = harness.sockets.single()
        old.open()
        runCurrent()
        val oldEpoch = transport.connectionEpoch
        old.fail()
        advanceTimeBy(10)
        runCurrent()
        val next = harness.sockets.last()
        next.open()
        runCurrent()
        assertThat(transport.sendText("stale pcm", oldEpoch)).isFalse()
        assertThat(next.messages).isEmpty()
        assertThat(transport.state.value).isEqualTo(TransportState.CONNECTED)
        assertThat(transport.reconnectAttempt).isEqualTo(1)
        assertThat(transport.sendText("fresh pcm", transport.connectionEpoch)).isTrue()
        assertThat(next.messages).containsExactly("fresh pcm")
        transport.close()
    }
}

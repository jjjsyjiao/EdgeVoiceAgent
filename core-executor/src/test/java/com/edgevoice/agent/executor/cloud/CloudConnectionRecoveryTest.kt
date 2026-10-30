package com.edgevoice.agent.executor.cloud

import com.edgevoice.agent.executor.ExecutorConnectionState
import com.edgevoice.agent.executor.ExecutorEvent
import com.edgevoice.agent.executor.RealtimeSendException
import com.edgevoice.agent.transport.ITransportClient
import com.edgevoice.agent.transport.TransportFailure
import com.edgevoice.agent.transport.TransportListener
import com.edgevoice.agent.transport.TransportState
import com.edgevoice.agent.transport.realtime.RealtimeProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CloudConnectionRecoveryTest {
    private class RecoveringTransport(private val maxRetries: Int = 2) : ITransportClient {
        private val mutable = MutableStateFlow(TransportState.IDLE)
        @Volatile var onNextStateRead: (() -> Unit)? = null
        @Volatile var onNextReadyMark: (() -> Unit)? = null
        override val state: StateFlow<TransportState> = object : StateFlow<TransportState> by mutable {
            override val value: TransportState get() {
                val snapshot = mutable.value
                onNextStateRead?.also { onNextStateRead = null }?.invoke()
                return snapshot
            }
        }
        @Volatile override var connectionEpoch = 0L
        @Volatile override var reconnectAttempt = 0
        @Volatile var callback: TransportListener? = null
        var autoAcknowledge = true
        var closed = false
        override var queuedBytes = 0L
        val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var configurations = 0
        var failNextSend: TransportFailure? = null
        var connectFailure: TransportFailure? = null
        val readyEpochs: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        override fun setListener(listener: TransportListener?) { callback = listener }
        override fun connect() {
            connectFailure?.let {
                connectionEpoch++
                mutable.value = TransportState.FAILED
                callback?.onError(it, connectionEpoch)
                callback?.onConnectionChanged(TransportState.FAILED, connectionEpoch, reconnectAttempt)
                return
            }
            open()
        }
        fun open() {
            connectionEpoch++
            change(TransportState.CONNECTING)
            change(TransportState.CONNECTED)
        }
        private fun change(state: TransportState) {
            mutable.value = state
            callback?.onConnectionChanged(state, connectionEpoch, reconnectAttempt)
        }
        override fun sendText(text: String): Boolean {
            if (closed || mutable.value != TransportState.CONNECTED) return false
            failNextSend?.let { failure ->
                failNextSend = null
                callback?.onError(failure, connectionEpoch)
                return false
            }
            sent.add(text)
            if (text.contains("\"type\":\"session.update\"")) {
                configurations++
                if (autoAcknowledge) {
                    message("""{"type":"session.created","session":{"id":"s$connectionEpoch"}}""")
                    message("""{"type":"session.updated","session":{"id":"s$connectionEpoch"}}""")
                }
            }
            return true
        }
        override fun sendBinary(data: ByteBuffer) = true
        override fun requestReconnect(cause: Throwable): Boolean {
            connectionEpoch++
            if (reconnectAttempt >= maxRetries) {
                mutable.value = TransportState.FAILED
                callback?.onError(TransportFailure("retries exhausted", false), connectionEpoch)
                return false
            }
            reconnectAttempt++
            change(TransportState.RECONNECTING)
            callback?.onError(TransportFailure("temporary disconnect", true), connectionEpoch)
            return true
        }
        override fun markSessionReady(expectedEpoch: Long) {
            onNextReadyMark?.also { onNextReadyMark = null }?.invoke()
            if (expectedEpoch == connectionEpoch) readyEpochs.add(expectedEpoch)
        }
        fun message(text: String, epoch: Long = connectionEpoch) { callback?.onTextMessage(text, epoch) }
        override fun close() { closed = true; mutable.value = TransportState.CLOSED }
    }

    private fun executor(
        fake: RecoveringTransport,
        scope: CoroutineScope,
        callbackCapacity: Int = 256,
        eventCapacity: Int = 256,
        profile: RealtimeProfile = RealtimeProfile.OPENAI,
    ) =
        CloudRealtimeExecutor(
            wsUrl = "http://127.0.0.1:1/realtime", apiKey = "test-key", model = "test", scope = scope,
            transportFactory = { _, _ -> fake }, configurationTimeoutMs = 100L,
            callbackCapacity = callbackCapacity, eventCapacity = eventCapacity,
            profile = profile,
        )

    @Test
    fun reconnect_reconfigures_and_does_not_replay_old_pcm_or_answer_events() = runTest {
        val fake = RecoveringTransport()
        val executor = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare()
        runCurrent()
        executor.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(1, 2)))
        val oldEpoch = fake.connectionEpoch
        fake.message("""{"type":"response.created","response":{"id":"old"}}""")
        fake.message("""{"type":"response.audio.delta","response_id":"old","delta":"AQI="}""")
        fake.requestReconnect(TransportFailure("offline", true))
        runCurrent()
        assertThat(executor.ready.value).isFalse()
        assertThat(events.filterIsInstance<ExecutorEvent.Error>().last().recoverable).isTrue()
        assertThat(events.filterIsInstance<ExecutorEvent.AudioDelta>()).isEmpty()
        val failure = runCatching { executor.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(3, 4))) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(RealtimeSendException::class.java)
        assertThat((failure as RealtimeSendException).recoverable).isTrue()

        fake.open()
        runCurrent()
        assertThat(executor.ready.value).isTrue()
        fake.message("""{"type":"response.audio.delta","response_id":"old","delta":"AQI="}""", oldEpoch)
        fake.message("""{"type":"response.text.delta","response_id":"old","delta":"stale"}""", oldEpoch)
        fake.message("""{"type":"response.done","response":{"id":"old"}}""", oldEpoch)
        executor.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(5, 6)))
        fake.message("""{"type":"response.created","response":{"id":"fresh"}}""")
        fake.message("""{"type":"response.audio_transcript.delta","response_id":"fresh","delta":"fresh answer"}""")
        fake.message("""{"type":"response.done","response":{"id":"fresh","status":"completed"}}""")
        runCurrent()
        assertThat(fake.sent.count { it.contains("\"type\":\"session.update\"") }).isEqualTo(2)
        assertThat(fake.sent.count { it.contains("\"type\":\"input_audio_buffer.append\"") }).isEqualTo(2)
        assertThat(events.filterIsInstance<ExecutorEvent.TextDelta>().map { it.text }).containsExactly("fresh answer")
        assertThat(events.filterIsInstance<ExecutorEvent.ConnectionChanged>().map { it.state })
            .containsAtLeast(ExecutorConnectionState.RECONNECTING, ExecutorConnectionState.CONFIGURING, ExecutorConnectionState.READY)
        executor.shutdown()
    }

    @Test
    fun runtime_configuration_timeout_retries_and_auth_error_is_terminal() = runTest {
        val fake = RecoveringTransport()
        val executor = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare()
        runCurrent()
        fake.requestReconnect(TransportFailure("offline", true))
        runCurrent()
        fake.autoAcknowledge = false
        fake.open()
        runCurrent()
        advanceTimeBy(101L)
        runCurrent()
        assertThat(fake.reconnectAttempt).isEqualTo(2)
        assertThat(executor.ready.value).isFalse()
        fake.open()
        runCurrent()
        fake.message("""{"type":"error","error":{"code":"invalid_api_key","message":"invalid credentials"}}""")
        runCurrent()
        assertThat(executor.connectionState.value).isEqualTo(ExecutorConnectionState.FAILED)
        assertThat(events.filterIsInstance<ExecutorEvent.Error>().last().recoverable).isFalse()
        assertThat(fake.closed).isTrue()
        executor.shutdown()
    }

    @Test
    fun rate_limit_is_recoverable_but_retry_budget_exhaustion_is_terminal() = runTest {
        val fake = RecoveringTransport(maxRetries = 1)
        val executor = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare()
        fake.message("""{"type":"error","error":{"code":"rate_limit_exceeded","message":"busy"}}""")
        runCurrent()
        assertThat(fake.reconnectAttempt).isEqualTo(1)
        assertThat(events.filterIsInstance<ExecutorEvent.Error>().last().recoverable).isTrue()
        fake.open()
        runCurrent()
        fake.message("""{"type":"error","error":{"code":"rate_limit_exceeded","message":"busy"}}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.Error>().last().recoverable).isFalse()
        assertThat(executor.ready.value).isFalse()
        executor.shutdown()
    }

    @Test
    fun overloaded_callback_queue_reports_terminal_failure_instead_of_losing_turn_complete() = runTest {
        val fake = RecoveringTransport()
        val executor = executor(fake, backgroundScope, callbackCapacity = 4)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare()
        runCurrent()
        repeat(20) { fake.message("""{"type":"response.text.delta","response_id":"r1","delta":"$it"}""") }
        runCurrent()
        assertThat(executor.ready.value).isFalse()
        assertThat(fake.closed).isTrue()
        assertThat(events.filterIsInstance<ExecutorEvent.Error>().single().recoverable).isFalse()
        executor.shutdown()
    }

    @Test
    fun slow_subscriber_overload_has_bounded_backlog_and_explicit_failure() = runTest {
        val fake = RecoveringTransport()
        val executor = executor(fake, backgroundScope, eventCapacity = 8)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            executor.events.collect {
                if (it is ExecutorEvent.TextDelta) delay(10L)
                events.add(it)
            }
        }
        executor.prepare()
        runCurrent()
        fake.message("""{"type":"response.created","response":{"id":"r1"}}""")
        repeat(30) { fake.message("""{"type":"response.text.delta","response_id":"r1","delta":"$it"}""") }
        advanceTimeBy(50)
        runCurrent()
        assertThat(executor.connectionState.value).isEqualTo(ExecutorConnectionState.FAILED)
        assertThat(events.filterIsInstance<ExecutorEvent.Error>().single().recoverable).isFalse()
        assertThat(events).doesNotContain(ExecutorEvent.TurnComplete)
        executor.shutdown()
    }

    @Test
    fun queue_diagnostics_are_public_bounded_and_zero_after_shutdown() = runTest {
        val fake = RecoveringTransport()
        val executor = executor(fake, backgroundScope)
        executor.prepare()
        fake.queuedBytes = 123L
        fake.message("""{"type":"response.text.delta","response_id":"r1","delta":"queued"}""")
        val pending = executor.queueStats()
        assertThat(pending.callbackBytes).isGreaterThan(0L)
        assertThat(pending.outgoingBytes).isEqualTo(123L)
        assertThat(pending.connectionEpoch).isEqualTo(fake.connectionEpoch)
        assertThat(pending.eventBytes).isAtMost(4L * 1024 * 1024)
        executor.shutdown()
        assertThat(executor.queueStats().callbackBytes).isEqualTo(0L)
        assertThat(executor.queueStats().eventBytes).isEqualTo(0L)
        assertThat(executor.queueStats().outgoingBytes).isEqualTo(0L)
    }

    @Test
    fun captured_old_epoch_rejects_audio_and_video_without_disturbing_recovered_connection() = runTest {
        val fake = RecoveringTransport()
        val executor = executor(fake, backgroundScope, profile = RealtimeProfile.QWEN)
        executor.prepare()
        val oldInputEpoch = executor.inputEpoch
        fake.requestReconnect(TransportFailure("offline", true))
        runCurrent()
        fake.open()
        runCurrent()
        val before = fake.sent.size
        val audioFailure = runCatching {
            executor.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(1, 2)), oldInputEpoch)
        }.exceptionOrNull()
        val videoFailure = runCatching {
            executor.sendVideoFrame(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), oldInputEpoch)
        }.exceptionOrNull()
        assertThat(audioFailure).isInstanceOf(RealtimeSendException::class.java)
        assertThat((audioFailure as RealtimeSendException).recoverable).isTrue()
        assertThat(videoFailure).isInstanceOf(RealtimeSendException::class.java)
        assertThat(fake.sent).hasSize(before)
        assertThat(fake.reconnectAttempt).isEqualTo(1)
        assertThat(executor.ready.value).isTrue()
        executor.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(3, 4)), executor.inputEpoch)
        executor.sendVideoFrame(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), executor.inputEpoch)
        assertThat(fake.sent).hasSize(before + 2)
        executor.shutdown()
    }

    @Test
    fun previous_connection_done_cannot_warn_in_recovered_session() = runTest {
        val fake = RecoveringTransport()
        val executor = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare()
        val oldEpoch = fake.connectionEpoch
        fake.message("""{"type":"response.done","response":{"id":"old","status":"incomplete"}}""", oldEpoch)
        fake.requestReconnect(TransportFailure("offline", true))
        runCurrent()
        fake.open()
        runCurrent()
        fake.message("""{"type":"response.done","response":{"id":"late-old","status":"completed"}}""", oldEpoch)
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.ResponseIncomplete>()).isEmpty()
        assertThat(executor.ready.value).isTrue()
        executor.shutdown()
    }

    @Test
    fun old_ack_blocked_in_state_getter_cannot_mark_a_new_epoch_ready() = runBlocking {
        val runtime = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = RecoveringTransport().apply { autoAcknowledge = false }
        val executor = CloudRealtimeExecutor(
            wsUrl = "http://127.0.0.1:1/realtime", apiKey = "test-key", model = "test",
            scope = runtime, transportFactory = { _, _ -> fake }, prepareTimeoutMs = 5_000,
            configurationTimeoutMs = 4_000,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preparation = async(Dispatchers.Default) { executor.prepare() }
        try {
            withTimeout(2_000) { while (fake.configurations != 1) delay(1) }
            fake.onNextStateRead = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS)) { "test getter release timed out" }
            }
            fake.message("""{"type":"session.updated","session":{"id":"old"}}""")
            check(entered.await(2, TimeUnit.SECONDS)) { "old acknowledgement did not reach state getter" }
            // This must proceed while the getter is blocked: no epochGate may cover external state reads.
            fake.requestReconnect(TransportFailure("offline", true))
            fake.open()
            release.countDown()
            withTimeout(2_000) { while (fake.configurations != 2) delay(1) }
            assertThat(executor.ready.value).isFalse()
            assertThat(preparation.isCompleted).isFalse()
            fake.message("""{"type":"session.updated","session":{"id":"fresh"}}""")
            withTimeout(2_000) { preparation.await() }
            assertThat(executor.ready.value).isTrue()
        } finally {
            release.countDown()
            executor.shutdown()
            preparation.cancelAndJoin()
            runtime.cancel()
        }
    }

    @Test
    fun terminal_send_failure_keeps_auth_or_rate_limit_cause_before_error_event_delivery() = runTest {
        for (status in listOf(401, 429)) {
            val fake = RecoveringTransport()
            val executor = executor(fake, backgroundScope)
            executor.prepare()
            val original = TransportFailure("terminal provider failure", recoverable = false, statusCode = status)
            fake.failNextSend = original
            val failure = runCatching { executor.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(1, 2))) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(RealtimeSendException::class.java)
            assertThat((failure as RealtimeSendException).recoverable).isFalse()
            assertThat(failure.cause).isSameInstanceAs(original)
            val repeated = runCatching { executor.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(3, 4))) }.exceptionOrNull()
            assertThat(repeated?.cause).isSameInstanceAs(original)
            executor.shutdown()
        }
    }

    @Test
    fun ack_blocked_in_transport_confirmation_cannot_publish_ready_or_complete_prepare_after_reconnect(): Unit = runBlocking {
        val runtime = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = RecoveringTransport().apply { autoAcknowledge = false }
        val executor = CloudRealtimeExecutor(
            wsUrl = "http://127.0.0.1:1/realtime", apiKey = "test-key", model = "test",
            scope = runtime, transportFactory = { _, _ -> fake }, prepareTimeoutMs = 5_000,
            configurationTimeoutMs = 4_000,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<ExecutorEvent>())
        val observer = runtime.launch { executor.events.collect { events.add(it) } }
        val preparation = async(Dispatchers.Default) { executor.prepare() }
        try {
            withTimeout(2_000) { while (fake.configurations != 1) delay(1) }
            val oldEpoch = fake.connectionEpoch
            fake.onNextReadyMark = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS)) { "test confirmation release timed out" }
            }
            fake.message("""{"type":"session.updated","session":{"id":"old"}}""")
            check(entered.await(2, TimeUnit.SECONDS)) { "old acknowledgement did not reach transport confirmation" }
            fake.message("""{"type":"error","error":{"code":"invalid_api_key","message":"old error"}}""", oldEpoch)
            fake.requestReconnect(TransportFailure("offline", true))
            fake.open()
            release.countDown()
            withTimeout(2_000) { while (fake.configurations != 2) delay(1) }
            assertThat(executor.ready.value).isFalse()
            assertThat(preparation.isCompleted).isFalse()
            assertThat(synchronized(events) { events.filterIsInstance<ExecutorEvent.Ready>() }).isEmpty()
            assertThat(fake.readyEpochs).isEmpty()
            fake.message("""{"type":"session.updated","session":{"id":"fresh"}}""")
            withTimeout(2_000) { preparation.await() }
            fake.callback?.onError(TransportFailure("late old terminal callback", false, statusCode = 401), oldEpoch)
            assertThat(executor.ready.value).isTrue()
            assertThat(fake.closed).isFalse()
            assertThat(fake.readyEpochs).containsExactly(fake.connectionEpoch)
        } finally {
            release.countDown()
            executor.shutdown()
            preparation.cancelAndJoin()
            observer.cancelAndJoin()
            runtime.cancel()
        }
    }

    @Test
    fun terminal_handshake_before_connecting_callback_preserves_provider_cause() = runTest {
        for (status in listOf(401, 429)) {
            val original = TransportFailure("early handshake failure", false, statusCode = status)
            val fake = RecoveringTransport().apply { connectFailure = original }
            val executor = executor(fake, backgroundScope)
            assertThat(runCatching { executor.prepare() }.exceptionOrNull()).isSameInstanceAs(original)
            val rejected = runCatching { executor.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(1, 2))) }.exceptionOrNull()
            assertThat(rejected).isInstanceOf(RealtimeSendException::class.java)
            assertThat((rejected as RealtimeSendException).recoverable).isFalse()
            assertThat(rejected.cause).isSameInstanceAs(original)
        }
    }
}

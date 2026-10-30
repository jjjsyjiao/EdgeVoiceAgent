package com.edgevoice.agent.executor.cloud

import com.edgevoice.agent.executor.ExecutorEvent
import com.edgevoice.agent.executor.ResponseIncompleteReason
import com.edgevoice.agent.transport.ITransportClient
import com.edgevoice.agent.transport.TransportListener
import com.edgevoice.agent.transport.TransportState
import com.edgevoice.agent.transport.realtime.RealtimeProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/** 真实协议契约，使用可控假通道；没有任何网络或付费模型调用。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CloudRealtimeExecutorTest {
    private class FakeTransport(
        val autoConnect: Boolean = true,
        val autoAcknowledge: Boolean = true,
    ) : ITransportClient {
        private val mutableState = MutableStateFlow(TransportState.IDLE)
        override val state: StateFlow<TransportState> = mutableState
        val sent = mutableListOf<String>()
        var capturedHeaders: Map<String, String> = emptyMap()
        var capturedUrl = ""
        var acceptSends = true
        var closed = false
        private var callback: TransportListener? = null

        override fun connect() {
            if (autoConnect) changeState(TransportState.CONNECTED)
        }
        override fun sendText(text: String): Boolean {
            if (!acceptSends) return false
            sent.add(text)
            if (autoAcknowledge && text.contains("\"type\":\"session.update\"")) {
                message("""{"type":"session.created","session":{"id":"s1"}}""")
                message("""{"type":"session.updated","session":{"id":"s1"}}""")
            }
            return true
        }
        override fun sendBinary(data: ByteBuffer): Boolean = acceptSends
        override fun close() { closed = true }
        override fun setListener(listener: TransportListener?) { callback = listener }
        fun changeState(state: TransportState) {
            mutableState.value = state
            callback?.onStateChanged(state)
        }
        fun message(text: String) { callback?.onTextMessage(text) }
        fun rtt(value: Long) { callback?.onRttMeasured(value) }
        fun failure(cause: Throwable) { callback?.onError(cause) }
    }

    private fun newExecutor(
        fake: FakeTransport,
        scope: CoroutineScope,
        profile: RealtimeProfile = RealtimeProfile.OPENAI,
        model: String = "realtime-test",
    ) = CloudRealtimeExecutor(
        wsUrl = "http://127.0.0.1:1/realtime",
        apiKey = "test-key",
        model = model,
        scope = scope,
        profile = profile,
        transportFactory = { url, headers ->
            fake.capturedUrl = url
            fake.capturedHeaders = headers
            fake
        },
        prepareTimeoutMs = 1_000L,
    )

    @Test
    fun prepare_waits_for_configuration_ack_not_session_created() = runTest {
        val fake = FakeTransport(autoAcknowledge = false)
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        val preparation = async { executor.prepare(null) }
        runCurrent()
        assertThat(fake.sent).hasSize(1)
        assertThat(executor.ready.value).isFalse()
        assertThat(preparation.isCompleted).isFalse()

        fake.message("""{"type":"session.created","session":{"id":"s1"}}""")
        runCurrent()
        assertThat(executor.ready.value).isFalse()
        assertThat(events.any { it == ExecutorEvent.Ready }).isFalse()

        fake.message("""{"type":"session.updated","session":{"id":"s1"}}""")
        runCurrent()
        preparation.await()
        assertThat(executor.ready.value).isTrue()
        assertThat(events.count { it == ExecutorEvent.Ready }).isEqualTo(1)
        executor.shutdown()
    }

    @Test
    fun initial_ready_survives_factory_subscribing_after_prepare() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        executor.prepare(null)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        runCurrent()
        assertThat(events.count { it == ExecutorEvent.Ready }).isEqualTo(1)
        executor.shutdown()
    }

    @Test
    fun prepare_timeout_is_catchable_and_closes_transport() = runTest {
        val fake = FakeTransport(autoConnect = false)
        val executor = newExecutor(fake, backgroundScope)
        val result = async { runCatching { executor.prepare(null) } }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertThat(result.await().exceptionOrNull()).isInstanceOf(TimeoutException::class.java)
        assertThat(fake.closed).isTrue()
        assertThat(executor.ready.value).isFalse()
    }

    @Test
    fun external_timeout_remains_cancellation_and_releases_transport() = runTest {
        val fake = FakeTransport(autoConnect = false)
        val executor = newExecutor(fake, backgroundScope)
        val result = async { runCatching { withTimeout(100L) { executor.prepare(null) } } }
        runCurrent()
        advanceTimeBy(101L)
        runCurrent()
        assertThat(result.await().exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(fake.closed).isTrue()
    }

    @Test
    fun explicit_caller_cancellation_is_not_converted_to_business_timeout() = runTest {
        val fake = FakeTransport(autoConnect = false)
        val executor = newExecutor(fake, backgroundScope)
        val preparation = async { executor.prepare(null) }
        runCurrent()
        preparation.cancel(CancellationException("user stopped"))
        runCurrent()
        assertThat(runCatching { preparation.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(fake.closed).isTrue()
    }

    @Test
    fun configuration_rejection_is_returned_to_prepare_caller() = runTest {
        val fake = FakeTransport(autoAcknowledge = false)
        val executor = newExecutor(fake, backgroundScope)
        val result = async { runCatching { executor.prepare(null) } }
        runCurrent()
        fake.message("""{"type":"error","error":{"code":"invalid_value","message":"bad format"}}""")
        runCurrent()
        assertThat(result.await().exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(fake.closed).isTrue()
    }

    @Test
    fun connection_failure_without_event_subscriber_still_releases_prepare() = runTest {
        val fake = FakeTransport(autoConnect = false)
        val executor = newExecutor(fake, backgroundScope)
        val result = async { runCatching { executor.prepare(null) } }
        runCurrent()
        fake.failure(IOException("handshake failed"))
        runCurrent()
        assertThat(result.await().exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(fake.closed).isTrue()
        assertThat(executor.ready.value).isFalse()
    }

    @Test
    fun reconnect_clears_ready_and_reconfigures_once_per_connection() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare(null)
        runCurrent()
        fake.changeState(TransportState.RECONNECTING)
        assertThat(executor.ready.value).isFalse()
        runCurrent()
        fake.changeState(TransportState.CONNECTING)
        fake.changeState(TransportState.CONNECTED)
        runCurrent()
        assertThat(executor.ready.value).isTrue()
        assertThat(fake.sent.count { it.contains("\"type\":\"session.update\"") }).isEqualTo(2)
        assertThat(events.count { it == ExecutorEvent.Ready }).isEqualTo(2)
        executor.shutdown()
    }

    @Test
    fun cancel_only_active_response_and_filter_its_late_output() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare(null)
        fake.message("""{"type":"input_audio_buffer.speech_started"}""")
        runCurrent()
        assertThat(fake.sent.none { it.contains("response.cancel") }).isTrue()

        fake.message("""{"type":"response.created","response":{"id":"old"}}""")
        runCurrent()
        executor.interrupt()
        executor.interrupt()
        assertThat(fake.sent.count { it.contains("response.cancel") }).isEqualTo(1)
        fake.message("""{"type":"response.audio.delta","response_id":"old","delta":"AQI="}""")
        fake.message("""{"type":"response.audio_transcript.delta","response_id":"old","delta":"stale"}""")
        fake.message("""{"type":"response.done","response":{"id":"old","status":"cancelled"}}""")
        fake.message("""{"type":"response.created","response":{"id":"new"}}""")
        fake.message("""{"type":"response.audio_transcript.delta","response_id":"new","delta":"new text"}""")
        fake.message("""{"type":"response.audio_transcript.done","response_id":"new","transcript":"new text"}""")
        fake.message("""{"type":"response.done","response":{"id":"new","status":"completed"}}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.AudioDelta>()).isEmpty()
        assertThat(events.filterIsInstance<ExecutorEvent.TextDelta>())
            .containsExactly(ExecutorEvent.TextDelta("new text", "new"))
        assertThat(events.filterIsInstance<ExecutorEvent.AssistantTextFinal>())
            .containsExactly(ExecutorEvent.AssistantTextFinal("new text", "new"))
        assertThat(events.count { it == ExecutorEvent.TurnComplete }).isEqualTo(1)
        executor.shutdown()
    }

    @Test
    fun speech_started_cancels_active_response_and_speech_stopped_is_forwarded() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare(null)
        fake.message("""{"type":"response.created","response":{"id":"r1"}}""")
        fake.message("""{"type":"input_audio_buffer.speech_started"}""")
        fake.message("""{"type":"input_audio_buffer.speech_stopped"}""")
        runCurrent()
        assertThat(fake.sent.count { it.contains("response.cancel") }).isEqualTo(1)
        assertThat(events.takeLast(2)).containsExactly(ExecutorEvent.UserSpeechStarted, ExecutorEvent.UserSpeechStopped).inOrder()
        executor.shutdown()
    }

    @Test
    fun text_and_audio_transcript_streams_are_not_concatenated_twice() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare(null)
        fake.message("""{"type":"response.created","response":{"id":"r1"}}""")
        fake.message("""{"type":"response.output_text.delta","response_id":"r1","delta":"你好"}""")
        fake.message("""{"type":"response.output_audio_transcript.delta","response_id":"r1","delta":"你好"}""")
        fake.message("""{"type":"response.output_text.done","response_id":"r1","text":"你好。"}""")
        fake.message("""{"type":"response.output_audio_transcript.done","response_id":"r1","transcript":"你好。"}""")
        fake.message("""{"type":"response.done","response":{"id":"r1","status":"completed"}}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.TextDelta>()).containsExactly(ExecutorEvent.TextDelta("你好", "r1"))
        assertThat(events.filterIsInstance<ExecutorEvent.AssistantTextFinal>()).containsExactly(ExecutorEvent.AssistantTextFinal("你好。", "r1"))
        executor.shutdown()
    }

    @Test
    fun burst_larger_than_shared_flow_buffer_keeps_order_with_slow_subscriber() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            executor.events.collect {
                if (it is ExecutorEvent.TextDelta) delay(1L)
                events.add(it)
            }
        }
        executor.prepare(null)
        fake.message("""{"type":"response.created","response":{"id":"r1"}}""")
        repeat(200) { index ->
            fake.message("""{"type":"response.text.delta","response_id":"r1","delta":"$index,"}""")
        }
        fake.message("""{"type":"response.done","response":{"id":"r1","status":"completed"}}""")
        advanceTimeBy(250L)
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.TextDelta>().map { it.text })
            .containsExactlyElementsIn((0 until 200).map { "$it," }).inOrder()
        assertThat(events.last()).isEqualTo(ExecutorEvent.TurnComplete)
        assertThat(events.filterIsInstance<ExecutorEvent.AssistantTextFinal>().single().text)
            .isEqualTo((0 until 200).joinToString("") { "$it," })
        executor.shutdown()
    }

    @Test
    fun response_done_supplies_final_text_when_delta_events_are_absent() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare(null)
        fake.message("""{"type":"response.done","response":{"id":"r1","status":"completed","output":[{"content":[{"type":"audio","transcript":"完整回答"}]}]}}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.AssistantTextFinal>()).containsExactly(ExecutorEvent.AssistantTextFinal("完整回答", "r1"))
        executor.shutdown()
    }

    @Test
    fun completed_text_without_audio_keeps_text_and_ready_with_a_nonfatal_warning() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare()
        fake.message("""{"type":"response.created","response":{"id":"silent"}}""")
        fake.message("""{"type":"response.text.delta","response_id":"silent","delta":"一加一等于二。"}""")
        fake.message("""{"type":"response.audio.delta","response_id":"silent","delta":""}""")
        fake.message("""{"type":"response.done","response":{"id":"silent","status":"completed"}}""")
        runCurrent()
        assertThat(events.takeLast(3)).containsExactly(
            ExecutorEvent.AssistantTextFinal("一加一等于二。", "silent"),
            ExecutorEvent.ResponseIncomplete(ResponseIncompleteReason.MISSING_AUDIO, "silent"),
            ExecutorEvent.TurnComplete,
        ).inOrder()
        assertThat(events.filterIsInstance<ExecutorEvent.AudioDelta>()).isEmpty()
        assertThat(events.filterIsInstance<ExecutorEvent.Error>()).isEmpty()
        assertThat(executor.ready.value).isTrue()
        assertThat(fake.closed).isFalse()
        executor.shutdown()
    }

    @Test
    fun received_pcm_on_completed_response_does_not_warn_and_resets_for_next_response() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare()
        fake.message("""{"type":"response.audio.delta","response_id":"spoken","delta":"AQI="}""")
        fake.message("""{"type":"response.done","response":{"id":"spoken","status":"completed"}}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.ResponseIncomplete>()).isEmpty()
        assertThat(events.filterIsInstance<ExecutorEvent.AudioDelta>().single().pcm.toList()).containsExactly(1.toByte(), 2.toByte())
        fake.message("""{"type":"response.done","response":{"id":"silent","status":"completed"}}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.ResponseIncomplete>())
            .containsExactly(ExecutorEvent.ResponseIncomplete(ResponseIncompleteReason.MISSING_AUDIO, "silent"))
        assertThat(executor.ready.value).isTrue()
        executor.shutdown()
    }

    @Test
    fun incomplete_or_unconfirmed_status_warns_even_when_some_audio_arrived() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare()
        fake.message("""{"type":"response.audio.delta","response_id":"partial","delta":"AQI="}""")
        fake.message("""{"type":"response.done","response":{"id":"partial","status":"incomplete","status_details":{"reason":"private provider body"},"output":[{"content":[{"text":"已生成的文字"}]}]}}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.AssistantTextFinal>())
            .containsExactly(ExecutorEvent.AssistantTextFinal("已生成的文字", "partial"))
        fake.message("""{"type":"response.done","response":{"id":"unknown"}}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.ResponseIncomplete>()).containsExactly(
            ExecutorEvent.ResponseIncomplete(ResponseIncompleteReason.INCOMPLETE, "partial"),
            ExecutorEvent.ResponseIncomplete(ResponseIncompleteReason.UNCONFIRMED, "unknown"),
        ).inOrder()
        assertThat(executor.ready.value).isTrue()
        executor.shutdown()
    }

    @Test
    fun cancelled_response_and_late_done_do_not_create_an_output_warning() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.prepare()
        fake.message("""{"type":"response.done","response":{"id":"server-cancelled","status":"cancelled"}}""")
        fake.message("""{"type":"response.created","response":{"id":"user-cancelled"}}""")
        runCurrent()
        executor.interrupt()
        fake.message("""{"type":"response.done","response":{"id":"user-cancelled","status":"completed"}}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.ResponseIncomplete>()).isEmpty()
        assertThat(executor.ready.value).isTrue()
        executor.shutdown()
    }

    @Test
    fun warning_waiting_behind_slow_subscriber_is_invalidated_by_next_question() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            executor.events.collect {
                if (it is ExecutorEvent.TextDelta) delay(100L)
                events.add(it)
            }
        }
        executor.prepare()
        fake.message("""{"type":"response.text.delta","response_id":"old","delta":"旧回答"}""")
        runCurrent()
        fake.message("""{"type":"response.done","response":{"id":"old","status":"completed"}}""")
        runCurrent()
        executor.interrupt() // 已完成生成但提示尚未交付，此次用户操作仍须使旧提示失效。
        fake.message("""{"type":"response.audio.delta","response_id":"new","delta":"AQI="}""")
        fake.message("""{"type":"response.done","response":{"id":"new","status":"completed"}}""")
        advanceTimeBy(101L)
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.ResponseIncomplete>()).isEmpty()
        assertThat(events.filterIsInstance<ExecutorEvent.AudioDelta>().single().responseId).isEqualTo("new")
        assertThat(executor.ready.value).isTrue()
        executor.shutdown()
    }

    @Test
    fun outgoing_audio_and_video_failures_are_not_reported_as_success() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope, RealtimeProfile.QWEN)
        executor.prepare(null)
        fake.acceptSends = false
        assertThat(runCatching { executor.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(1, 2))) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(runCatching { executor.sendVideoFrame(byteArrayOf(1, 2)) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        executor.shutdown()
    }

    @Test
    fun qwen_configuration_and_image_uplink_keep_vendor_contract() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope, RealtimeProfile.QWEN, "qwen3.5-omni-flash-realtime")
        executor.prepare(null)
        assertThat(fake.sent.first()).contains("qwen3-asr-flash-realtime")
        assertThat(fake.sent.first()).contains("\"voice\":\"Tina\"")
        assertThat(fake.sent.first()).contains("\"sample_rate\":24000")
        assertThat(fake.sent.first()).doesNotContain("pcm16")
        assertThat(fake.capturedHeaders.containsKey("OpenAI-Beta")).isFalse()
        assertThat(fake.capturedUrl).isEqualTo("http://127.0.0.1:1/realtime?model=qwen3.5-omni-flash-realtime")
        executor.sendVideoFrame(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        assertThat(fake.sent.last()).contains("\"type\":\"input_image_buffer.append\"")
        assertThat(runCatching { executor.sendVideoFrame(ByteArray(200_000)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        executor.shutdown()
    }

    @Test
    fun unsupported_video_remains_noop_and_rtt_errors_are_forwarded() = runTest {
        val fake = FakeTransport()
        val executor = newExecutor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.events.collect { events.add(it) } }
        executor.sendVideoFrame(byteArrayOf(1)) // 未连接时不支持视频仍是 no-op
        executor.prepare(null)
        val count = fake.sent.size
        executor.sendVideoFrame(byteArrayOf(1))
        assertThat(fake.sent).hasSize(count)
        fake.rtt(42L)
        runCurrent()
        fake.failure(IOException("connection reset"))
        runCurrent()
        assertThat(events).contains(ExecutorEvent.RttMeasured(42L))
        assertThat(events.any { it is ExecutorEvent.Error }).isTrue()
        assertThat(executor.ready.value).isFalse()
        executor.shutdown()
    }
}

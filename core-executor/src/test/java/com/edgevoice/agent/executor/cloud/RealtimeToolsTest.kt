package com.edgevoice.agent.executor.cloud

import com.edgevoice.agent.executor.ExecutorEvent
import com.edgevoice.agent.executor.ExecutorToolCall
import com.edgevoice.agent.transport.ITransportClient
import com.edgevoice.agent.transport.TransportListener
import com.edgevoice.agent.transport.TransportState
import com.edgevoice.agent.transport.realtime.RealtimeProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

/** Function calling/controlled media contracts; no network, real model or private photos. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RealtimeToolsTest {
    private class Fake : ITransportClient {
        override val state = MutableStateFlow(TransportState.IDLE)
        var callback: TransportListener? = null
        val sent = mutableListOf<JSONObject>()
        var acknowledgeSessions = true
        var acknowledgeCommits = true
        override fun connect() = changeState(TransportState.CONNECTED)
        override fun close() { state.value = TransportState.CLOSED }
        override fun setListener(listener: TransportListener?) { this.callback = listener }
        override fun sendBinary(data: ByteBuffer) = true
        override fun sendText(text: String): Boolean {
            val obj = JSONObject(text)
            sent.add(obj)
            when (obj.optString("type")) {
                "session.update" -> if (acknowledgeSessions) {
                    message(JSONObject().put("type", "session.updated").put("session", obj.getJSONObject("session")).toString())
                }
                "input_audio_buffer.commit" -> if (acknowledgeCommits) {
                    message("""{"type":"input_audio_buffer.committed","item_id":"comparison-input"}""")
                }
            }
            return true
        }
        fun changeState(next: TransportState) { state.value = next; callback?.onStateChanged(next) }
        fun message(text: String) { callback?.onTextMessage(text) }
        fun callStart() {
            message("""{"type":"response.created","response":{"id":"tool-response"}}""")
            message("""{"type":"response.function_call_arguments.done","response_id":"tool-response","name":"search_visual_history","call_id":"call-1","arguments":"{\"query\":\"台灯\"}"}""")
        }
        fun callDone() = message("""{"type":"response.done","response":{"id":"tool-response","status":"completed"}}""")
        fun answer(audio: Boolean = true) {
            message("""{"type":"response.created","response":{"id":"comparison-response"}}""")
            message("""{"type":"response.audio_transcript.done","response_id":"comparison-response","transcript":"历史画面在昨天，当前画面在今天。"}""")
            if (audio) {
                message("""{"type":"response.audio.delta","response_id":"comparison-response","delta":"AQIDBA=="}""")
                message("""{"type":"response.audio.done","response_id":"comparison-response"}""")
            }
            message("""{"type":"response.done","response":{"id":"comparison-response","status":"completed"}}""")
        }
        fun types() = sent.map { it.getString("type") }
    }

    private val tools = """[{"type":"function","function":{"name":"search_visual_history","parameters":{"type":"object","properties":{"query":{"type":"string"}}}}}]"""
    private fun executor(fake: Fake, scope: CoroutineScope) = CloudRealtimeExecutor(
        wsUrl = "https://example.invalid/realtime", apiKey = "fixture-only", model = "qwen3.5-fixture",
        scope = scope, profile = RealtimeProfile.QWEN, toolsJson = tools,
        transportFactory = { _, _ -> fake }, configurationTimeoutMs = 100L, mediaResponseTimeoutMs = 500L,
    )

    @Test fun tool_is_issued_after_parent_done_without_missing_audio_or_turn_complete() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare(); fake.callStart(); runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.ToolCallReady>()).isEmpty()
        fake.callDone(); runCurrent()
        val call = events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call
        assertThat(call.name).isEqualTo("search_visual_history")
        assertThat(call.inputEpoch).isEqualTo(ex.inputEpoch)
        assertThat(events.filterIsInstance<ExecutorEvent.ResponseIncomplete>()).isEmpty()
        assertThat(events.any { it == ExecutorEvent.TurnComplete }).isFalse()
        assertThat(ex.completeTool(call, "{\"status\":\"no_matches\"}")).isTrue()
        assertThat(fake.sent.takeLast(2).map { it.getString("type") }).containsExactly("conversation.item.create", "response.create").inOrder()
        assertThat(ex.completeTool(call, "{}")).isFalse()
        ex.shutdown()
    }

    @Test fun response_done_fallback_tool_schema_and_user_interruption_reject_stale_result() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare()
        fake.message("""{"type":"response.done","response":{"id":"fallback","status":"completed","output":[{"type":"function_call","call_id":"c2","name":"search_visual_history","arguments":"{}"}]}}""")
        runCurrent()
        val call = events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call
        fake.message("""{"type":"input_audio_buffer.speech_started"}"""); runCurrent()
        val before = fake.sent.size
        assertThat(ex.completeTool(call, "{}")).isFalse()
        assertThat(fake.sent).hasSize(before)
        ex.shutdown()
    }

    @Test fun controlled_comparison_waits_for_commit_final_media_and_restoration_without_second_ready() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare(); fake.callStart(); fake.callDone(); runCurrent()
        val call = events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call
        val result = async { ex.respondToToolWithMedia(call, "{\"status\":\"evidence_ready\"}", ByteArray(9_600), byteArrayOf(1, 2, 3)) }
        runCurrent(); advanceTimeBy(220L); runCurrent()
        val sent = fake.types()
        assertThat(sent.indexOf("input_audio_buffer.clear")).isLessThan(sent.indexOf("input_audio_buffer.append"))
        assertThat(sent.indexOf("input_audio_buffer.append")).isLessThan(sent.indexOf("input_image_buffer.append"))
        assertThat(sent.indexOf("input_image_buffer.append")).isLessThan(sent.indexOf("input_audio_buffer.commit"))
        assertThat(sent.indexOf("input_audio_buffer.commit")).isLessThan(sent.indexOf("response.create"))
        assertThat(result.isCompleted).isFalse()
        // Stale real microphone writes are ignored while controlled input owns this connection.
        val count = fake.sent.size
        ex.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(7, 8)))
        assertThat(fake.sent).hasSize(count)
        fake.answer(); runCurrent()
        assertThat(result.await()).isTrue()
        assertThat(ex.inputAudioBytesSent).isEqualTo(9_600L)
        assertThat(ex.inputVadAudioBytesSent).isEqualTo(0L)
        assertThat(events.count { it == ExecutorEvent.Ready }).isEqualTo(1)
        val configs = fake.sent.filter { it.getString("type") == "session.update" }.map { it.getJSONObject("session") }
        assertThat(configs).hasSize(3)
        assertThat(configs[1].isNull("turn_detection")).isTrue()
        assertThat(configs[1].getJSONArray("tools").length()).isEqualTo(0)
        assertThat(configs[2].isNull("turn_detection")).isFalse()
        assertThat(configs[2].getJSONArray("tools").length()).isEqualTo(1)
        ex.sendAudioFrame(ByteBuffer.wrap(byteArrayOf(7, 8)))
        assertThat(fake.types().last()).isEqualTo("input_audio_buffer.append")
        assertThat(ex.inputAudioBytesSent).isEqualTo(9_602L)
        assertThat(ex.inputVadAudioBytesSent).isEqualTo(2L)
        ex.shutdown()
    }

    @Test fun comparison_without_audio_is_false_and_live_configuration_is_restored() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare(); fake.callStart(); fake.callDone(); runCurrent()
        val result = async { ex.respondToToolWithMedia(events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call,
            "{}", ByteArray(9_600), byteArrayOf(1)) }
        runCurrent(); advanceTimeBy(220L); runCurrent(); fake.answer(audio = false); runCurrent()
        assertThat(result.await()).isFalse()
        assertThat(ex.ready.value).isTrue()
        assertThat(fake.sent.last().getJSONObject("session").isNull("turn_detection")).isFalse()
        ex.shutdown()
    }

    @Test fun reconnection_during_manual_ack_invalidates_call_and_never_replays_old_media() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare(); fake.callStart(); fake.callDone(); runCurrent()
        val call = events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call
        fake.acknowledgeSessions = false
        val result = async { ex.respondToToolWithMedia(call, "{}", ByteArray(9_600), byteArrayOf(1)) }
        runCurrent(); fake.changeState(TransportState.RECONNECTING); runCurrent()
        assertThat(result.await()).isFalse()
        assertThat(fake.types()).doesNotContain("input_image_buffer.append")
        assertThat(ex.completeTool(call, "{}")).isFalse()
        ex.shutdown()
    }

    @Test fun commit_ack_timeout_never_creates_response_and_still_restores_live_mode() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare(); fake.callStart(); fake.callDone(); runCurrent()
        fake.acknowledgeCommits = false
        val result = async { ex.respondToToolWithMedia(events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call,
            "{}", ByteArray(9_600), byteArrayOf(1)) }
        runCurrent(); advanceTimeBy(350L); runCurrent()
        assertThat(result.await()).isFalse()
        assertThat(fake.types()).doesNotContain("response.create")
        assertThat(ex.ready.value).isTrue()
        ex.shutdown()
    }
    @Test fun late_internal_transcript_after_restore_never_becomes_a_new_user_question() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare()
        fake.message("""{"type":"input_audio_buffer.speech_started","item_id":"original-question","audio_start_ms":100}""")
        fake.message("""{"type":"input_audio_buffer.speech_stopped","item_id":"original-question","audio_end_ms":700}""")
        fake.callStart(); fake.callDone(); runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.UserInputIdentity>().single()).isEqualTo(ExecutorEvent.UserInputIdentity("original-question", 100L))
        assertThat(events.filterIsInstance<ExecutorEvent.UserInputEnd>().single()).isEqualTo(ExecutorEvent.UserInputEnd("original-question", 700L))
        val result = async { ex.respondToToolWithMedia(events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call,
            "{}", ByteArray(9_600), byteArrayOf(1)) }
        runCurrent(); advanceTimeBy(220L); runCurrent(); fake.answer(); runCurrent()
        assertThat(result.await()).isTrue()
        fake.message("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"comparison-input","transcript":"内部原声，禁止重复显示和保存"}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.UserTranscript>()).isEmpty()
        fake.message("""{"type":"input_audio_buffer.speech_started","item_id":"next-question","audio_start_ms":1000}""")
        fake.message("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"original-question","transcript":"迟到上一轮"}""")
        fake.message("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"next-question","transcript":"下一轮真实提问"}""")
        runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.UserTranscript>()).containsExactly(ExecutorEvent.UserTranscript("下一轮真实提问", "next-question"))
        ex.shutdown()
    }

    @Test fun cancellation_during_audio_replay_clears_partial_input_and_restores_vad() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare(); fake.callStart(); fake.callDone(); runCurrent()
        val result = async { ex.respondToToolWithMedia(events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call,
            "{}", ByteArray(9_600), byteArrayOf(1)) }
        runCurrent()
        val audioChunks = fake.types().count { it == "input_audio_buffer.append" }
        result.cancel(); runCurrent()
        assertThat(result.isCancelled).isTrue()
        assertThat(ex.ready.value).isTrue()
        assertThat(fake.types().count { it == "input_audio_buffer.append" }).isEqualTo(audioChunks)
        assertThat(fake.types().takeLast(2)).containsExactly("input_audio_buffer.clear", "session.update").inOrder()
        assertThat(fake.types()).doesNotContain("response.create")
        ex.shutdown()
    }

    @Test fun cancellation_before_created_never_allows_an_unidentifiable_late_answer() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare(); fake.callStart(); fake.callDone(); runCurrent()
        val result = async { ex.respondToToolWithMedia(events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call,
            "{}", ByteArray(9_600), byteArrayOf(1)) }
        runCurrent(); advanceTimeBy(220L); runCurrent()
        assertThat(fake.types()).contains("response.create")
        ex.interrupt(); runCurrent()
        assertThat(result.await()).isFalse()
        // This fake cannot reconnect, so safe recovery becomes a closed connection.
        assertThat(ex.ready.value).isFalse()
        fake.answer(); runCurrent()
        assertThat(events.filterIsInstance<ExecutorEvent.AudioDelta>()).isEmpty()
        ex.shutdown()
    }

    @Test fun stale_normal_session_ack_cannot_confirm_temporary_manual_configuration() = runTest {
        val fake = Fake(); val ex = executor(fake, backgroundScope)
        val events = mutableListOf<ExecutorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ex.events.collect { events.add(it) } }
        ex.prepare(); fake.callStart(); fake.callDone(); runCurrent()
        fake.acknowledgeSessions = false
        val result = async { ex.respondToToolWithMedia(events.filterIsInstance<ExecutorEvent.ToolCallReady>().single().call,
            "{}", ByteArray(9_600), byteArrayOf(1)) }
        runCurrent()
        fake.message("""{"type":"session.updated","session":{"turn_detection":{"type":"server_vad"},"tools":[{}]}}""")
        runCurrent()
        assertThat(fake.types()).doesNotContain("input_audio_buffer.append")
        fake.message("""{"type":"session.updated","session":{"turn_detection":null,"tools":[]}}""")
        runCurrent(); advanceTimeBy(220L); runCurrent()
        fake.acknowledgeSessions = true
        fake.answer(); runCurrent()
        assertThat(result.await()).isTrue()
        ex.shutdown()
    }

}

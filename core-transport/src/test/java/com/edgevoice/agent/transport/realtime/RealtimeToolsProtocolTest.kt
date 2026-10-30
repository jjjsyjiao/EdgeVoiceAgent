package com.edgevoice.agent.transport.realtime

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RealtimeToolsProtocolTest {
    @Test fun qwen_nested_tool_schema_is_preserved_and_unsupported_tool_choice_is_absent() {
        val tools = """[{"type":"function","function":{"name":"retrieve_visual_memory","parameters":{"type":"object","properties":{"query":{"type":"string"}}}}}]"""
        val session = JSONObject(RealtimeProtocol.sessionUpdate(model = "qwen3.5-fixture", profile = RealtimeProfile.QWEN,
            toolsJson = tools)).getJSONObject("session")
        assertThat(session.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
            .isEqualTo("retrieve_visual_memory")
        assertThat(session.has("tool_choice")).isFalse()
        assertThat(session.has("parallel_tool_calls")).isFalse()
    }

    @Test fun tool_output_preserves_call_id_and_json_string_instead_of_claiming_urls_are_images() {
        val output = """{"status":"evidence_ready","evidence":[{"role":"H1","captured_at_ms":1000}]}"""
        val item = JSONObject(RealtimeProtocol.toolOutput("call-1", output)).getJSONObject("item")
        assertThat(item.getString("type")).isEqualTo("function_call_output")
        assertThat(item.getString("call_id")).isEqualTo("call-1")
        assertThat(item.getString("output")).isEqualTo(output)
        assertThat(item.has("content")).isFalse()
    }

    @Test fun parses_completed_arguments_and_parent_done_tool_fallback() {
        val call = RealtimeProtocol.parseServerEvent("""{"type":"response.function_call_arguments.done","response_id":"r1","name":"retrieve_visual_memory","call_id":"c1","arguments":"{\"query\":\"台灯\"}"}""")
            as RealtimeProtocol.ServerEvent.FunctionCall
        assertThat(call.argumentsJson).isEqualTo("{\"query\":\"台灯\"}")
        assertThat(call.responseId).isEqualTo("r1")
        val done = RealtimeProtocol.parseServerEvent("""{"type":"response.done","response":{"id":"r1","status":"completed","output":[{"type":"function_call","name":"retrieve_visual_memory","call_id":"c1","arguments":"{}"}]}}""")
            as RealtimeProtocol.ServerEvent.ResponseDone
        assertThat(done.toolCalls.single().callId).isEqualTo("c1")
    }

    @Test fun session_ack_exposes_manual_and_tools_state_for_control_ack_matching() {
        val ack = RealtimeProtocol.parseServerEvent("""{"type":"session.updated","session":{"id":"s1","turn_detection":null,"tools":[]}}""")
            as RealtimeProtocol.ServerEvent.SessionUpdated
        assertThat(ack.serverVad).isFalse()
        assertThat(ack.toolCount).isEqualTo(0)
        assertThat(JSONObject(RealtimeProtocol.clearAudio()).getString("type")).isEqualTo("input_audio_buffer.clear")
        assertThat(RealtimeProtocol.parseServerEvent("""{"type":"input_audio_buffer.committed","item_id":"i1"}"""))
            .isEqualTo(RealtimeProtocol.ServerEvent.AudioCommitted("i1"))
        assertThat(RealtimeProtocol.parseServerEvent("""{"type":"response.audio.done","response_id":"r1"}"""))
            .isEqualTo(RealtimeProtocol.ServerEvent.AudioDone("r1"))
    }
    @Test fun speech_boundaries_preserve_item_identity_and_audio_time_offsets() {
        assertThat(RealtimeProtocol.parseServerEvent("""{"type":"input_audio_buffer.speech_started","item_id":"user-1","audio_start_ms":3647}"""))
            .isEqualTo(RealtimeProtocol.ServerEvent.SpeechBoundary(true, "user-1", audioStartMs = 3647L))
        assertThat(RealtimeProtocol.parseServerEvent("""{"type":"input_audio_buffer.speech_stopped","item_id":"user-1","audio_end_ms":4453}"""))
            .isEqualTo(RealtimeProtocol.ServerEvent.SpeechBoundary(false, "user-1", audioEndMs = 4453L))
    }

}

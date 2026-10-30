package com.edgevoice.agent.transport.realtime

import android.util.Base64
import com.edgevoice.agent.transport.realtime.RealtimeProtocol.ServerEvent
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Realtime 协议编解码（依赖 android.util.Base64/org.json，故跑在 Robolectric 上）。
 */
@RunWith(RobolectricTestRunner::class)
class RealtimeProtocolTest {

    // ---------------- 上行编码 ----------------

    @Test
    fun session_update_carries_model_voice_and_server_vad() {
        val json = JSONObject(
            RealtimeProtocol.sessionUpdate(model = "gpt-4o-realtime", voice = "nova"),
        )
        assertThat(json.getString("type")).isEqualTo("session.update")

        val session = json.getJSONObject("session")
        assertThat(session.getString("model")).isEqualTo("gpt-4o-realtime")
        assertThat(session.getString("voice")).isEqualTo("nova")
        assertThat(session.getJSONObject("input_audio_transcription").getString("model"))
            .isEqualTo("whisper-1")

        val vad = session.getJSONObject("turn_detection")
        assertThat(vad.getString("type")).isEqualTo("server_vad")
        assertThat(vad.getDouble("threshold")).isEqualTo(0.5)
        assertThat(vad.getInt("silence_duration_ms")).isEqualTo(500)
    }

    @Test
    fun openai_profile_uses_flat_pcm16_format_and_no_nested_audio_block() {
        // 显式锁定 OpenAI 默认画像：扁平字符串字段、不携带百炼嵌套 audio 块
        val session = JSONObject(
            RealtimeProtocol.sessionUpdate(model = "gpt-4o-realtime", profile = RealtimeProfile.OPENAI),
        ).getJSONObject("session")

        assertThat(session.getString("input_audio_format")).isEqualTo("pcm16")
        assertThat(session.getString("output_audio_format")).isEqualTo("pcm16")
        assertThat(session.has("audio")).isFalse()
        assertThat(session.getJSONObject("input_audio_transcription").getString("model"))
            .isEqualTo("whisper-1")
    }

    @Test
    fun qwen_profile_emits_nested_audio_format_and_qwen_transcription() {
        val json = JSONObject(
            RealtimeProtocol.sessionUpdate(
                model = "qwen3.5-omni-flash-realtime",
                voice = "Tina",
                profile = RealtimeProfile.QWEN,
            ),
        )
        val session = json.getJSONObject("session")

        // 百炼 qwen3.5+：audio.{input,output}.format{type,sample_rate}
        val inputFmt = session.getJSONObject("audio").getJSONObject("input").getJSONObject("format")
        assertThat(inputFmt.getString("type")).isEqualTo("pcm")
        assertThat(inputFmt.getInt("sample_rate")).isEqualTo(24_000)
        val outputFmt = session.getJSONObject("audio").getJSONObject("output").getJSONObject("format")
        assertThat(outputFmt.getString("type")).isEqualTo("pcm")
        assertThat(outputFmt.getInt("sample_rate")).isEqualTo(24_000)

        // 转写模型是百炼固定值，不再是 whisper-1
        assertThat(session.getJSONObject("input_audio_transcription").getString("model"))
            .isEqualTo("qwen3-asr-flash-realtime")

        // VAD 协议两家同源，照常携带
        assertThat(session.getJSONObject("turn_detection").getString("type")).isEqualTo("server_vad")
    }

    @Test
    fun qwen_profile_must_not_carry_openai_flat_format_fields() {
        // 扁平 "pcm16" 是 OpenAI 取值；百炼格式枚举为 "pcm"，混发会被服务端判 invalid_value
        val session = JSONObject(
            RealtimeProtocol.sessionUpdate(model = "m", profile = RealtimeProfile.QWEN),
        ).getJSONObject("session")
        assertThat(session.has("input_audio_format")).isFalse()
        assertThat(session.has("output_audio_format")).isFalse()
    }

    @Test
    fun profile_presets_carry_vendor_specific_contract() {
        assertThat(RealtimeProfile.OPENAI.defaultVoice).isEqualTo("alloy")
        assertThat(RealtimeProfile.OPENAI.sendRealtimeBetaHeader).isTrue()
        assertThat(RealtimeProfile.OPENAI.nestedAudioFormat).isFalse()

        assertThat(RealtimeProfile.QWEN.defaultVoice).isEqualTo("Tina")
        assertThat(RealtimeProfile.QWEN.sendRealtimeBetaHeader).isFalse()
        assertThat(RealtimeProfile.QWEN.nestedAudioFormat).isTrue()
        assertThat(RealtimeProfile.QWEN.audioSampleRate).isEqualTo(24_000)
    }

    @Test
    fun fromId_maps_aliases_and_falls_back_to_openai() {
        assertThat(RealtimeProfile.fromId("qwen")).isEqualTo(RealtimeProfile.QWEN)
        assertThat(RealtimeProfile.fromId("Qwen")).isEqualTo(RealtimeProfile.QWEN)
        assertThat(RealtimeProfile.fromId("dashscope")).isEqualTo(RealtimeProfile.QWEN)
        assertThat(RealtimeProfile.fromId("bailian")).isEqualTo(RealtimeProfile.QWEN)
        assertThat(RealtimeProfile.fromId("aliyun")).isEqualTo(RealtimeProfile.QWEN)

        assertThat(RealtimeProfile.fromId("openai")).isEqualTo(RealtimeProfile.OPENAI)
        // 配错/留空/未来未知值：回落官方路径而非崩溃
        assertThat(RealtimeProfile.fromId(null)).isEqualTo(RealtimeProfile.OPENAI)
        assertThat(RealtimeProfile.fromId("")).isEqualTo(RealtimeProfile.OPENAI)
        assertThat(RealtimeProfile.fromId("gemini")).isEqualTo(RealtimeProfile.OPENAI)
    }

    @Test
    fun session_update_includes_instructions_only_when_present() {
        val without = JSONObject(RealtimeProtocol.sessionUpdate(model = "m"))
        assertThat(without.getJSONObject("session").has("instructions")).isFalse()

        val with = JSONObject(RealtimeProtocol.sessionUpdate(model = "m", instructions = "你是简洁助手"))
        assertThat(with.getJSONObject("session").getString("instructions")).isEqualTo("你是简洁助手")
    }

    @Test
    fun session_update_without_vad_emits_null_turn_detection() {
        val json = JSONObject(RealtimeProtocol.sessionUpdate(model = "m", serverVad = false))
        assertThat(json.getJSONObject("session").isNull("turn_detection")).isTrue()
    }

    @Test
    fun append_audio_roundtrips_pcm_base64() {
        val pcm = byteArrayOf(0x01, 0x02, 0x7F.toByte(), 0x80.toByte(), 0xFE.toByte())
        val msg = JSONObject(RealtimeProtocol.appendAudio(pcm))
        assertThat(msg.getString("type")).isEqualTo("input_audio_buffer.append")

        val decoded = Base64.decode(msg.getString("audio"), Base64.NO_WRAP)
        assertThat(decoded).isEqualTo(pcm)
    }

    @Test
    fun append_video_frame_emits_naked_base64_image() {
        // V0 探针锁定：input_image_buffer.append + image 键 + 裸 Base64（无 data: 前缀、无换行）
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 1, 2, 3)
        val msg = JSONObject(RealtimeProtocol.appendVideoFrame(jpeg))

        assertThat(msg.getString("type")).isEqualTo("input_image_buffer.append")
        val image = msg.getString("image")
        assertThat(image).doesNotContain("\n")
        assertThat(image).doesNotContain("data:")
        assertThat(Base64.decode(image, Base64.NO_WRAP)).isEqualTo(jpeg)
    }

    @Test
    fun video_input_capability_is_qwen_only() {
        // 能力矩阵：OpenAI Realtime 无图像缓冲事件，绝不能对其发送 appendVideoFrame
        assertThat(RealtimeProfile.OPENAI.supportsVideoInput).isFalse()
        assertThat(RealtimeProfile.QWEN.supportsVideoInput).isTrue()
    }

    @Test
    fun control_messages_have_correct_types() {
        assertThat(JSONObject(RealtimeProtocol.commitAudio()).getString("type"))
            .isEqualTo("input_audio_buffer.commit")
        assertThat(JSONObject(RealtimeProtocol.cancelResponse()).getString("type"))
            .isEqualTo("response.cancel")

        val create = JSONObject(RealtimeProtocol.createResponse(instructions = "说慢点"))
        assertThat(create.getString("type")).isEqualTo("response.create")
        assertThat(create.getJSONObject("response").getString("instructions")).isEqualTo("说慢点")

        val createBare = JSONObject(RealtimeProtocol.createResponse())
        assertThat(createBare.has("response")).isFalse()
    }

    // ---------------- 下行解析 ----------------

    @Test
    fun parses_session_updated() {
        val event = RealtimeProtocol.parseServerEvent(
            """{"type":"session.updated","session":{"id":"sess_123"}}""",
        )
        assertThat(event).isInstanceOf(ServerEvent.SessionUpdated::class.java)
        assertThat((event as ServerEvent.SessionUpdated).sessionId).isEqualTo("sess_123")
    }

    @Test
    fun parses_audio_delta_back_to_bytes() {
        val pcm = byteArrayOf(0x10, 0x20, 0x30)
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val event = RealtimeProtocol.parseServerEvent(
            """{"type":"response.audio.delta","delta":"$b64"}""",
        )
        assertThat(event).isInstanceOf(ServerEvent.AudioDelta::class.java)
        assertThat((event as ServerEvent.AudioDelta).pcm).isEqualTo(pcm)
    }

    @Test
    fun parses_transcript_and_speech_lifecycle() {
        assertThat(
            RealtimeProtocol.parseServerEvent(
                """{"type":"response.audio_transcript.delta","delta":"你好"}""",
            ),
        ).isEqualTo(ServerEvent.AudioTranscriptDelta("你好"))

        assertThat(
            RealtimeProtocol.parseServerEvent("""{"type":"input_audio_buffer.speech_started"}"""),
        ).isEqualTo(ServerEvent.SpeechStarted)

        assertThat(
            RealtimeProtocol.parseServerEvent("""{"type":"input_audio_buffer.speech_stopped"}"""),
        ).isEqualTo(ServerEvent.SpeechStopped)

        assertThat(
            RealtimeProtocol.parseServerEvent("""{"type":"response.done"}"""),
        ).isEqualTo(ServerEvent.ResponseDone())
    }

    @Test
    fun parses_user_transcript_completed() {
        val event = RealtimeProtocol.parseServerEvent(
            """{"type":"conversation.item.input_audio_transcription.completed",""" +
                """"transcript":"我要一杯拿铁","item_id":"item_1"}""",
        )
        assertThat(event).isInstanceOf(ServerEvent.UserTranscript::class.java)
        val ut = event as ServerEvent.UserTranscript
        assertThat(ut.text).isEqualTo("我要一杯拿铁")
        assertThat(ut.itemId).isEqualTo("item_1")
    }

    @Test
    fun session_created_is_distinct_from_configuration_confirmation() {
        assertThat(RealtimeProtocol.parseServerEvent(
            """{"type":"session.created","session":{"id":"s1"}}""",
        )).isEqualTo(ServerEvent.SessionCreated("s1"))
        assertThat(RealtimeProtocol.parseServerEvent(
            """{"type":"session.updated","session":{"id":"s1"}}""",
        )).isEqualTo(ServerEvent.SessionUpdated("s1"))
    }

    @Test
    fun parses_response_ids_and_both_text_event_families() {
        assertThat(RealtimeProtocol.parseServerEvent(
            """{"type":"response.created","response":{"id":"r1"}}""",
        )).isEqualTo(ServerEvent.ResponseCreated("r1"))
        for (type in listOf("response.text.delta", "response.output_text.delta")) {
            assertThat(RealtimeProtocol.parseServerEvent(
                """{"type":"$type","response_id":"r1","delta":"hello"}""",
            )).isEqualTo(ServerEvent.TextDelta("hello", "r1"))
        }
        for (type in listOf("response.text.done", "response.output_text.done")) {
            assertThat(RealtimeProtocol.parseServerEvent(
                """{"type":"$type","response_id":"r1","text":"hello"}""",
            )).isEqualTo(ServerEvent.TextDone("hello", "r1"))
        }
        for (type in listOf("response.audio_transcript.delta", "response.output_audio_transcript.delta")) {
            assertThat(RealtimeProtocol.parseServerEvent(
                """{"type":"$type","response_id":"r1","delta":"hello"}""",
            )).isEqualTo(ServerEvent.AudioTranscriptDelta("hello", "r1"))
        }
        for (type in listOf("response.audio_transcript.done", "response.output_audio_transcript.done")) {
            assertThat(RealtimeProtocol.parseServerEvent(
                """{"type":"$type","response_id":"r1","transcript":"hello"}""",
            )).isEqualTo(ServerEvent.AudioTranscriptDone("hello", "r1"))
        }
    }

    @Test
    fun response_done_prefers_audio_transcript_and_keeps_status() {
        assertThat(RealtimeProtocol.parseServerEvent(
            """{"type":"response.done","response":{"id":"r1","status":"completed","output":[{"content":[{"type":"text","text":"written"},{"type":"audio","transcript":"spoken"}]}]}}""",
        )).isEqualTo(ServerEvent.ResponseDone("r1", "completed", "spoken"))
    }

    @Test
    fun parses_error_event() {
        val event = RealtimeProtocol.parseServerEvent(
            """{"type":"error","error":{"code":"invalid_request","message":"bad"}}""",
        )
        assertThat(event).isInstanceOf(ServerEvent.Error::class.java)
        val e = event as ServerEvent.Error
        assertThat(e.code).isEqualTo("invalid_request")
        assertThat(e.message).isEqualTo("bad")
    }

    @Test
    fun unknown_type_keeps_raw_type_and_bad_json_becomes_parse_error() {
        val unknown = RealtimeProtocol.parseServerEvent("""{"type":"some.future.event"}""")
        assertThat(unknown).isInstanceOf(ServerEvent.Unknown::class.java)
        assertThat((unknown as ServerEvent.Unknown).type).isEqualTo("some.future.event")

        val broken = RealtimeProtocol.parseServerEvent("{not json")
        assertThat(broken).isInstanceOf(ServerEvent.Error::class.java)
        assertThat((broken as ServerEvent.Error).code).isEqualTo("parse_error")
    }
}

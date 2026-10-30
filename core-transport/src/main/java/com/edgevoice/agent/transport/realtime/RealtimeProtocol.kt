package com.edgevoice.agent.transport.realtime

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenAI Realtime API 协议编解码器。
 *
 * 分层原因：ITransportClient 只搬字节，不理解协议；本类是唯一知道 Realtime
 * JSON schema 的地方 —— 将来协议升级或换供应商（如 Gemini Live）只改本文件。
 *
 * 上行事件（client events）：
 *  - session.update            配置会话（模型、voice、VAD、转写）
 *  - input_audio_buffer.append 追加 PCM16 base64 音频
 *  - input_audio_buffer.commit 手动提交（server_vad 模式下由服务端 VAD 自动提交）
 *  - response.create           手动触发一轮回复
 *  - response.cancel           barge-in 时取消当前回复
 *
 * 下行事件（server events）解析为 [ServerEvent] 密封类，上层按类型分发。
 */
object RealtimeProtocol {

    /**
     * 单帧图像 Base64 后字节数硬上限（V0 探针实测：超出服务端直接切断 WebSocket，
     * 既不回 error 事件也不发 close 帧）。发送前任何一层都必须以此拦截，宁可丢帧。
     */
    const val MAX_IMAGE_BASE64_BYTES: Int = 256 * 1024

    /** Base64 编码后长度（4 字符/3 字节，向上补齐到 4 的倍数）。 */
    fun base64Length(byteCount: Int): Int = 4 * ((byteCount + 2) / 3)

    // ---------- 上行 ----------

    /**
     *  instructions： 系统消息，用于设定模型的目标或角色。
     */
    fun sessionUpdate(
        model: String,
        voice: String = "alloy",
        instructions: String? = null,
        modalities: List<String> = listOf("text", "audio"),
        inputAudioFormat: String = "pcm16",
        outputAudioFormat: String = "pcm16",
        serverVad: Boolean = true,
        vadThreshold: Float = 0.5f,
        vadSilenceMs: Int = 500,
        profile: RealtimeProfile = RealtimeProfile.OPENAI,
        toolsJson: String? = null,
    ): String {
        val session = JSONObject()
            .put("model", model)
            .put("modalities", org.json.JSONArray(modalities))
            .put("voice", voice)

        // 音频格式声明两家不同：OpenAI 用扁平 "pcm16" 字符串；
        // 百炼 qwen3.5+ 用嵌套 audio.{input,output}.format{type,sample_rate}
        if (profile.nestedAudioFormat) {
            fun formatBlock() = JSONObject()
                .put("type", "pcm")
                .put("sample_rate", profile.audioSampleRate)
            session.put(
                "audio",
                JSONObject()
                    .put("input", JSONObject().put("format", formatBlock()))
                    .put("output", JSONObject().put("format", formatBlock())),
            )
        } else {
            session
                .put("input_audio_format", inputAudioFormat)
                .put("output_audio_format", outputAudioFormat)
        }

        // input_audio_transcription-model：负责把用户说的话转成文字。
        // 注意和上面最外层model区别，最外层model负责理解并回答
        session.put(
            "input_audio_transcription",
            JSONObject().put("model", profile.transcriptionModel),
        )
        instructions?.let { session.put("instructions", it) }
        // turn_detection：是否开启语音活动检测。若需启用，需传入一个配置对象，服务端将据此自动检测语音起止。 设置为null表示由客户端决定何时发起模型响应。
        if (serverVad) {
            session.put(
                "turn_detection",
                JSONObject()
                    // VAD类型，取值为server_vad或semantic_vad。使用qwen3.5-omni-realtime系列模型时推荐设为semantic_vad。
                    .put("type", "server_vad")
                    // threshold：VAD检测阈值。建议在嘈杂的环境中增加，在安静的环境中降低。
                    .put("threshold", vadThreshold.toDouble())
                    .put("prefix_padding_ms", 300)
                    // silence_duration_ms：检测语音停止的静音持续时间，超过此值后会触发模型响应。取值范围 200-6000，默认 800。金融核实等短回答场景建议 500-600。
                    .put("silence_duration_ms", vadSilenceMs),
            )
        } else {
            session.put("turn_detection", JSONObject.NULL)
        }
        toolsJson?.let { session.put("tools", org.json.JSONArray(it)) }
        return JSONObject()
            .put("type", "session.update")
            .put("session", session)
            .toString()
    }

    /** 追加音频（PCM16LE -> base64）。单帧建议 20ms~100ms。 */
    fun appendAudio(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): String =
        JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(pcm, offset, length, Base64.NO_WRAP))
            .toString()

    /**
     * 追加视频帧（百炼 input_image_buffer.append，[jpegBytes] 为已编码 JPEG 原始字节）。
     *
     * V0 探针实测硬约束（违反任何一条都会导致会话失败，调用方必须自行保证）：
     *  1. 首帧图像必须在至少一块音频 append **之后**发送，否则
     *     invalid_request_error "Error append image before append audio."；
     *  2. 单帧 Base64 后 ≤ 256KB，否则服务端直接切断 WebSocket（无 error 事件）；
     *  3. payload 为裸 Base64（NO_WRAP，无 data:image 前缀）。
     */
    fun appendVideoFrame(jpegBytes: ByteArray): String =
        JSONObject()
            .put("type", "input_image_buffer.append")
            .put("image", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            .toString()

    fun commitAudio(): String =
        JSONObject().put("type", "input_audio_buffer.commit").toString()

    fun clearAudio(): String = JSONObject().put("type", "input_audio_buffer.clear").toString()

    /** 工具结果是字符串 JSON；图片必须通过 input_image_buffer 另外发送。 */
    fun toolOutput(callId: String, outputJson: String): String = JSONObject()
        .put("type", "conversation.item.create")
        .put("item", JSONObject().put("type", "function_call_output")
            .put("call_id", callId).put("output", outputJson))
        .toString()

    /** 打断：服务端立即停发当前 response 音频。 */
    fun cancelResponse(): String =
        JSONObject().put("type", "response.cancel").toString()

    fun createResponse(instructions: String? = null): String {
        val obj = JSONObject().put("type", "response.create")
        if (instructions != null) {
            obj.put("response", JSONObject().put("instructions", instructions))
        }
        return obj.toString()
    }

    // ---------- 下行 ----------

    sealed class ServerEvent {
        data class SessionCreated(val sessionId: String?) : ServerEvent()
        data class SessionUpdated(
            val sessionId: String?,
            val serverVad: Boolean? = null,
            val toolCount: Int? = null,
        ) : ServerEvent()
        data class ResponseCreated(val responseId: String) : ServerEvent()
        data class AudioDelta(val pcm: ByteArray, val responseId: String? = null) : ServerEvent()
        data class AudioDone(val responseId: String? = null) : ServerEvent()
        data class TextDelta(val text: String, val responseId: String? = null) : ServerEvent()
        data class TextDone(val text: String, val responseId: String? = null) : ServerEvent()
        data class AudioTranscriptDelta(val text: String, val responseId: String? = null) : ServerEvent()
        data class AudioTranscriptDone(val text: String, val responseId: String? = null) : ServerEvent()
        data class UserTranscript(val text: String, val itemId: String?) : ServerEvent()
        data class FunctionCall(
            val name: String,
            val callId: String,
            val argumentsJson: String,
            val responseId: String? = null,
        ) : ServerEvent()
        data class AudioCommitted(val itemId: String?) : ServerEvent()
        data class SpeechBoundary(
            val started: Boolean,
            val itemId: String?,
            val audioStartMs: Long? = null,
            val audioEndMs: Long? = null,
        ) : ServerEvent()
        object SpeechStarted : ServerEvent()
        object SpeechStopped : ServerEvent()
        data class ResponseDone(
            val responseId: String? = null,
            val status: String? = null,
            val text: String? = null,
            val toolCalls: List<FunctionCall> = emptyList(),
        ) : ServerEvent()
        data class Error(val code: String?, val message: String) : ServerEvent()
        data class Unknown(val type: String) : ServerEvent()
    }

    fun parseServerEvent(text: String): ServerEvent = try {
        val json = JSONObject(text)
        val responseId = json.optString("response_id").ifBlank { null }
        when (val type = json.optString("type")) {
            "session.created" -> ServerEvent.SessionCreated(
                json.optJSONObject("session")?.optString("id")?.ifBlank { null },
            )
            "session.updated" ->
                ServerEvent.SessionUpdated(
                    json.optJSONObject("session")?.optString("id")?.ifBlank { null },
                    json.optJSONObject("session")?.let { session ->
                        if (session.has("turn_detection")) !session.isNull("turn_detection") else null
                    },
                    json.optJSONObject("session")?.optJSONArray("tools")?.length(),
                )
            "response.created" -> ServerEvent.ResponseCreated(
                json.optJSONObject("response")?.optString("id")?.ifBlank { null }
                    ?: responseId.orEmpty(),
            )
            "response.audio.delta", "response.output_audio.delta" ->
                ServerEvent.AudioDelta(
                    Base64.decode(json.optString("delta"), Base64.NO_WRAP),
                    responseId,
                )
            "response.audio.done", "response.output_audio.done" -> ServerEvent.AudioDone(responseId)
            "response.text.delta", "response.output_text.delta" ->
                ServerEvent.TextDelta(json.optString("delta"), responseId)
            "response.text.done", "response.output_text.done" ->
                ServerEvent.TextDone(json.optString("text"), responseId)
            "response.audio_transcript.delta", "response.output_audio_transcript.delta" ->
                ServerEvent.AudioTranscriptDelta(json.optString("delta"), responseId)
            "response.audio_transcript.done", "response.output_audio_transcript.done" ->
                ServerEvent.AudioTranscriptDone(json.optString("transcript"), responseId)
            "conversation.item.input_audio_transcription.completed" ->
                ServerEvent.UserTranscript(
                    json.optString("transcript"),
                    json.optString("item_id").ifBlank { null },
                )
            "response.function_call_arguments.done" -> ServerEvent.FunctionCall(
                json.optString("name"), json.optString("call_id"),
                json.optString("arguments"), responseId,
            )
            "input_audio_buffer.committed" -> ServerEvent.AudioCommitted(
                json.optString("item_id").ifBlank { null },
            )
            "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped" -> {
                val started = type.endsWith("speech_started")
                val itemId = json.optString("item_id").ifBlank { null }
                val start = if (json.has("audio_start_ms") && !json.isNull("audio_start_ms")) json.optLong("audio_start_ms") else null
                val end = if (json.has("audio_end_ms") && !json.isNull("audio_end_ms")) json.optLong("audio_end_ms") else null
                if (itemId != null || start != null || end != null) ServerEvent.SpeechBoundary(started, itemId, start, end)
                else if (started) ServerEvent.SpeechStarted else ServerEvent.SpeechStopped
            }
            "response.done" -> {
                val response = json.optJSONObject("response")
                ServerEvent.ResponseDone(
                    response?.optString("id")?.ifBlank { null } ?: responseId,
                    response?.optString("status")?.ifBlank { null },
                    response?.let(::responseText),
                    response?.let(::responseToolCalls).orEmpty(),
                )
            }
            "error" -> {
                val e = json.optJSONObject("error")
                ServerEvent.Error(e?.optString("code"), e?.optString("message") ?: "unknown error")
            }
            else -> ServerEvent.Unknown(type)
        }
    } catch (t: Throwable) {
        ServerEvent.Error("parse_error", t.message ?: "protocol parse failure")
    }

    private fun responseToolCalls(response: JSONObject): List<ServerEvent.FunctionCall> {
        val output = response.optJSONArray("output") ?: return emptyList()
        return (0 until output.length()).mapNotNull { index ->
            val item = output.optJSONObject(index) ?: return@mapNotNull null
            if (item.optString("type") != "function_call") return@mapNotNull null
            ServerEvent.FunctionCall(item.optString("name"), item.optString("call_id"),
                item.optString("arguments"), response.optString("id").ifBlank { null })
        }
    }

    /** response.done 可携带完整结果；有音频转写时优先使用它，避免双流重复。 */
    private fun responseText(response: JSONObject): String? {
        val transcripts = mutableListOf<String>()
        val texts = mutableListOf<String>()
        val output = response.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                part.optString("transcript").takeIf { it.isNotBlank() }?.let(transcripts::add)
                part.optString("text").takeIf { it.isNotBlank() }?.let(texts::add)
            }
        }
        return (transcripts.ifEmpty { texts }).takeIf { it.isNotEmpty() }?.joinToString("")
    }

    // ---------- 工具 ----------

    /** DirectByteBuffer(PCM16LE) -> base64 字符串（appendAudio 的零拷贝友好变体） */
    fun encodePcmToBase64(buffer: ByteBuffer, byteLength: Int): String {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytes = ByteArray(byteLength)
        dup.get(bytes, 0, minOf(byteLength, dup.remaining()))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

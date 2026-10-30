package com.edgevoice.agent.transport.realtime

/**
 * Realtime 供应商差异画像。
 *
 * OpenAI 官方 Realtime 与阿里云百炼 Qwen-Omni-Realtime 的事件流同源（百炼对齐了
 * OpenAI Realtime 协议），仅在会话配置的少数字段上有差异，全部收敛到本类，
 * 使 [RealtimeProtocol] 与执行器无需 if-else 散落各处。
 *
 * 差异对照（2026-10 核对百炼客户端事件文档）：
 *  - 输入转写模型名：whisper-1 vs qwen3-asr-flash-realtime（固定值，不可改）
 *  - 默认音色：alloy vs Tina
 *  - 音频格式声明：OpenAI 扁平字符串 "pcm16"；百炼 qwen3.5+ 用嵌套
 *    session.audio.input/output.format.{type,sample_rate}
 *  - OpenAI-Beta 协议头：OpenAI 需要，百炼仅 Bearer 鉴权，不发
 *  - 视觉帧上行：百炼 qwen3.5+ 支持 input_image_buffer.append（JPEG 挂音频时间轴），
 *    OpenAI 官方 Realtime 事件集无此通道 —— 能力位 false 时上层只做本地抽帧、不上行
 *
 * @property id 画像标识（openai / qwen）
 * @property defaultVoice 该供应商的默认音色
 * @property transcriptionModel 输入语音转写模型名
 * @property audioSampleRate 声明给服务端的音频采样率；两端管线均为 24kHz
 * @property nestedAudioFormat true=百炼嵌套 audio{input/output.format}；false=OpenAI 扁平 pcm16
 * @property sendRealtimeBetaHeader 是否在握手头携带 OpenAI-Beta: realtime=v1
 * @property supportsVideoInput 是否支持视觉帧（JPEG）实时上行事件
 */
data class RealtimeProfile(
    val id: String,
    val defaultVoice: String,
    val transcriptionModel: String,
    val audioSampleRate: Int = 24_000,
    val nestedAudioFormat: Boolean,
    val sendRealtimeBetaHeader: Boolean,
    val supportsVideoInput: Boolean = false,
) {
    companion object {
        /** OpenAI 官方 Realtime（默认，保持历史行为）。 */
        val OPENAI = RealtimeProfile(
            id = "openai",
            defaultVoice = "alloy",
            transcriptionModel = "whisper-1",
            nestedAudioFormat = false,
            sendRealtimeBetaHeader = true,
            supportsVideoInput = false,
        )

        /** 阿里云百炼 Qwen-Omni-Realtime（北京/新加坡地域，协议对齐 OpenAI，扩展视频帧）。 */
        val QWEN = RealtimeProfile(
            id = "qwen",
            defaultVoice = "Tina",
            transcriptionModel = "qwen3-asr-flash-realtime",
            nestedAudioFormat = true,
            sendRealtimeBetaHeader = false,
            supportsVideoInput = true,
        )

        /**
         * 由配置字符串解析画像；未知值一律回落 [OPENAI]，保证配错也能走官方路径而不是崩。
         * 接受 qwen/dashscope/bailian 等同义写法（大小写不敏感）。
         */
        fun fromId(id: String?): RealtimeProfile = when (id?.trim()?.lowercase()) {
            "qwen", "dashscope", "bailian", "aliyun" -> QWEN
            else -> OPENAI
        }
    }
}

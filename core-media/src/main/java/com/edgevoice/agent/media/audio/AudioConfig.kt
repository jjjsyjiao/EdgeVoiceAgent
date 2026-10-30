package com.edgevoice.agent.media.audio

/**
 * 音频流参数（采集 / 播放 / 抖动缓冲共享同一份规格）。
 *
 * 设计权衡：上层（sdk-core / core-executor）只依赖本类做字节数换算，
 * 避免在热路径里出现 `sampleRate * 2 * ms / 1000` 这类散落魔法数字。
 */
data class AudioConfig(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
) {
    /** 单采样字节数（PCM16 -> 2） */
    val bytesPerSample: Int get() = bitsPerSample / 8

    /** 单声道一帧（单个时间点）的字节数 */
    val frameSizeBytes: Int get() = bytesPerSample * channelCount

    /** 指定时长（毫秒）对应的 PCM 字节数 */
    fun bytesFor(durationMs: Int): Long =
        sampleRateHz.toLong() * frameSizeBytes.toLong() * durationMs.toLong() / 1000L

    /** 指定字节数对应的时长（毫秒） */
    fun millisFor(bytes: Long): Int =
        if (frameSizeBytes == 0) 0 else (bytes * 1000L / (sampleRateHz.toLong() * frameSizeBytes)).toInt()

    companion object {
        /** 本地 ASR / VAD 常用规格：16kHz 单声道 PCM16 */
        val DEFAULT = AudioConfig()

        /**
         * 云端 OpenAI Realtime 上行要求：24kHz 单声道 PCM16。
         * 直接用 24kHz 采集可规避一次 resample，降低端侧 CPU。
         */
        val REALTIME_UP = AudioConfig(sampleRateHz = 24_000)
    }
}

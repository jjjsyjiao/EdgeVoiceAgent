package com.edgevoice.agent.core.vision

/**
 * 上行 PCM 原声尾环（V2 回放的「用户原声」来源）。
 *
 * 音频泵每送一帧麦克风 PCM 就镜像写入本环，只保留最近 [capacityBytes] 字节
 * （24kHz/16bit 单声道 ≈ 48KB/s，12s 约 576KB，内存代价可忽略）。
 * 触发回放时取出尾部 [snapshotTrailing] —— 恰好包含用户刚问完的那句话，
 * 重注入后服务端 VAD 把它识别为一个新回合，历史帧交错在音频时间轴上补发。
 *
 * 线程模型：音频泵线程写、回放协程读；全部方法 @Synchronized，
 * 锁内只做内存拷贝（微秒级），绝不阻塞音频节奏。
 */
class PcmEchoRing(private val capacityBytes: Int) {

    private val buffer = ByteArray(capacityBytes)
    private var start = 0      // 最旧字节下标
    private var count = 0      // 有效字节数（≤ capacityBytes）

    /** 追加一段 PCM；超过容量的最旧字节被覆盖。 */
    @Synchronized
    fun append(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset) {
        if (length <= 0) return
        if (length >= capacityBytes) {
            // 整段比环大：只留末尾整环（直接覆盖写满，start 归零）
            System.arraycopy(pcm, offset + length - capacityBytes, buffer, 0, capacityBytes)
            start = 0
            count = capacityBytes
            return
        }
        var written = 0
        while (written < length) {
            val dst = (start + count) % capacityBytes
            val chunk = minOf(length - written, capacityBytes - dst)
            System.arraycopy(pcm, offset + written, buffer, dst, chunk)
            written += chunk
            if (count < capacityBytes) {
                val grown = minOf(chunk, capacityBytes - count)
                count += grown
                if (grown < chunk) start = (start + (chunk - grown)) % capacityBytes
            } else {
                start = (start + chunk) % capacityBytes
            }
        }
    }

    /**
     * 取最近 [maxBytes] 字节（不足则全取），按时间正序拷贝到新数组。
     * 不取引用 —— 调用方会把它切成 100ms 块慢速重放，期间音频泵仍在写。
     */
    @Synchronized
    fun snapshotTrailing(maxBytes: Int = capacityBytes): ByteArray {
        val take = minOf(maxBytes, count)
        if (take <= 0) return ByteArray(0)
        val out = ByteArray(take)
        val begin = (start + count - take) % capacityBytes
        val firstPart = minOf(take, capacityBytes - begin)
        System.arraycopy(buffer, begin, out, 0, firstPart)
        if (firstPart < take) {
            System.arraycopy(buffer, 0, out, firstPart, take - firstPart)
        }
        return out
    }

    @Synchronized
    fun size(): Int = count

    @Synchronized
    fun clear() {
        start = 0
        count = 0
    }
}

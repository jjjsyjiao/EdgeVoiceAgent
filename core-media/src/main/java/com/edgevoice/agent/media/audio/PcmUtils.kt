package com.edgevoice.agent.media.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * PCM16-LE 采样工具：计算归一化 RMS（0..1）。
 *
 * 用途：
 *  - 灵动球 LISTENING / SPEAKING 态的振幅律动（对齐设计规范文档的 RMS 驱动）；
 *  - VAD 粗判（阈值的初级能量门限）。
 *
 * 非目标：这里不做重采样、不做浮点 DSP —— 保持每帧 O(n) 单遍扫描，热路径零分配。
 */
object PcmUtils {

    /** 归一化 RMS。输入 PCM16LE 字节数组区间 [offset, offset+length)。 */
    fun rms(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): Float {
        var sum = 0.0
        var count = 0
        val end = minOf(offset + length, pcm.size)
        var i = offset
        while (i + 1 < end) {
            // 低位在前（LE）：hi 需保留符号位，与 lo 组合即为 16 位有符号值
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample.toDouble() * sample.toDouble()
            count++
            i += 2
        }
        return if (count == 0) 0f else (sqrt(sum / count) / 32768.0).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * 归一化 RMS。输入 DirectByteBuffer（要求 position=0），只读前 [byteLength] 字节。
     * 内部 duplicate 后绝对寻址，不改动调用方的 position / limit。
     */
    fun rms(buffer: ByteBuffer, byteLength: Int): Float {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(0)
        val limit = minOf(byteLength, dup.limit())
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < limit) {
            val sample = dup.getShort(i).toInt()
            sum += sample.toDouble() * sample.toDouble()
            count++
            i += 2
        }
        return if (count == 0) 0f else (sqrt(sum / count) / 32768.0).coerceIn(0.0, 1.0).toFloat()
    }
}

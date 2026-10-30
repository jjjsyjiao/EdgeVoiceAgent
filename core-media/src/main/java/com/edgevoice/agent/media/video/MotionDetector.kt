package com.edgevoice.agent.media.video

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 运动检测器：对 Y（亮度）平面降采样后，计算相邻帧的平均绝对差，输出运动分 [0,1]。
 *
 * 为什么只取 Y 平面：亮度对位移最敏感且是三分量里唯一全分辨率平面（色度已 2x2 下采样），
 * 单平面扫描成本最低，适合 5fps 以内的低开销节流决策。
 *
 * 降采样（[sampleStride]）：每隔若干像素采一点，把 O(W*H) 降到 O(W*H/stride²)，
 * 640x480 在 stride=8 时仅 4800 个采样点，纳秒级。
 *
 * 用法：每个 ImageAnalysis 帧调用一次 [onYPlane]，用 [isMotion] 决定目标帧率。
 */
class MotionDetector(
    /** 运动判定阈值：平均绝对差/255 超过该值即认为画面在动 */
    val motionThreshold: Float = 0.06f,
    private val sampleStride: Int = 8,
) {
    private var prev: IntArray? = null

    /**
     * 输入 Y 平面并返回运动分。
     * @param y        Y 平面 buffer（绝对寻址，不改动 position）
     * @param rowStride 每行字节跨度
     * @param pixelStride 相邻像素字节跨度
     */
    @Synchronized
    fun onYPlane(
        y: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): Float {
        val cols = maxOf(1, width / sampleStride)
        val rows = maxOf(1, height / sampleStride)
        val size = cols * rows
        val cur = IntArray(size)

        var idx = 0
        var r = 0
        while (r < rows) {
            val rowBase = (r * sampleStride) * rowStride
            var c = 0
            while (c < cols) {
                val offset = rowBase + (c * sampleStride) * pixelStride
                cur[idx++] = y.get(offset).toInt() and 0xFF
                c++
            }
            r++
        }

        val old = prev
        var score = 0f
        if (old != null && old.size == size) {
            var sum = 0L
            var i = 0
            while (i < size) {
                sum += abs(cur[i] - old[i]).toLong()
                i++
            }
            score = (sum.toDouble() / (size * 255.0)).toFloat()
        }
        prev = cur
        return score
    }

    fun isMotion(score: Float): Boolean = score >= motionThreshold

    /** 重置基线（如相机重启 / 隐私态切换后）。 */
    fun reset() {
        prev = null
    }
}

package com.edgevoice.agent.media.video

/**
 * NV21（Y + VU 交织）画面的纯字节变换：旋转到设备正向。
 *
 * 为什么不用 Bitmap/Matrix：相机回调给的是 NV21，先走 [android.graphics.YuvImage] 压 JPEG
 * 再解码旋转会产生两次有损压缩；NV21 逐像素旋转后只压一次 JPEG，画质与功耗都更优，
 * 且本类是**纯 JVM 字节运算**，可以不依赖设备直接单测。
 *
 * 坐标约定（x 向右、y 向下，单位像素）：
 *  - Y：data[y * width + x]
 *  - 色度（2x2 采样）：data[ySize + cy * width + cx*2] = V，+1 = U（NV21 为 V 在前）
 *
 * 仅支持 CameraX 保证的四个角度 0/90/180/270（顺时针）；宽高必须为偶数（420 约束）。
 */
object Nv21Transform {

    data class Rotated(val data: ByteArray, val width: Int, val height: Int)

    fun rotate(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int): Rotated {
        require(width > 0 && height > 0) { "非法尺寸: ${width}x$height" }
        require(width % 2 == 0 && height % 2 == 0) { "NV21 宽高必须为偶数: ${width}x$height" }
        val expected = width * height * 3 / 2
        require(nv21.size == expected) { "NV21 长度不符: ${nv21.size} != $expected" }
        return when (rotationDegrees) {
            0 -> Rotated(nv21.copyOf(), width, height)
            90 -> Rotated(rotate90(nv21, width, height), height, width)
            180 -> Rotated(rotate180(nv21, width, height), width, height)
            270 -> Rotated(rotate270(nv21, width, height), height, width)
            else -> throw IllegalArgumentException("仅支持 0/90/180/270，收到: $rotationDegrees")
        }
    }

    /** 顺时针 90°：输出 (x',y')=(h-1-y, x)，输出尺寸 h*w。 */
    private fun rotate90(nv21: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(nv21.size)
        val ySize = w * h
        // 反向映射：输出 (x',y') 的亮度来自输入 (x=y', y=h-1-x')
        var yo = 0
        for (yp in 0 until w) {
            for (xp in 0 until h) {
                out[yo++] = nv21[(h - 1 - xp) * w + yp]
            }
        }
        // 色度输出平面宽 h/2、高 w/2：(cx',cy') 来自 (cx=cy', cy=h/2-1-cx')
        val hc = h / 2
        var co = ySize
        for (cyp in 0 until w / 2) {
            for (cxp in 0 until h / 2) {
                val inIdx = ySize + (hc - 1 - cxp) * w + cyp * 2
                out[co++] = nv21[inIdx]     // V
                out[co++] = nv21[inIdx + 1] // U
            }
        }
        return out
    }

    /** 180°：输出 (x',y') 来自输入 (w-1-x', h-1-y')。 */
    private fun rotate180(nv21: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(nv21.size)
        val ySize = w * h
        var yo = 0
        for (yp in 0 until h) {
            for (xp in 0 until w) {
                out[yo++] = nv21[(h - 1 - yp) * w + (w - 1 - xp)]
            }
        }
        var co = ySize
        for (cyp in 0 until h / 2) {
            for (cxp in 0 until w / 2) {
                val inIdx = ySize + (h / 2 - 1 - cyp) * w + (w / 2 - 1 - cxp) * 2
                out[co++] = nv21[inIdx]
                out[co++] = nv21[inIdx + 1]
            }
        }
        return out
    }

    /** 顺时针 270°：输出 (x',y')=(y, w-1-x)，输出尺寸 h*w。 */
    private fun rotate270(nv21: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(nv21.size)
        val ySize = w * h
        var yo = 0
        for (yp in 0 until w) {
            for (xp in 0 until h) {
                // 输出 (xp,yp) 来自输入 (x=w-1-yp, y=xp)
                out[yo++] = nv21[xp * w + (w - 1 - yp)]
            }
        }
        // 色度：(cx',cy') 来自 (cx=w/2-1-cy', cy=cx')
        val wc = w / 2
        var co = ySize
        for (cyp in 0 until w / 2) {
            for (cxp in 0 until h / 2) {
                val inIdx = ySize + cxp * w + (wc - 1 - cyp) * 2
                out[co++] = nv21[inIdx]
                out[co++] = nv21[inIdx + 1]
            }
        }
        return out
    }
}

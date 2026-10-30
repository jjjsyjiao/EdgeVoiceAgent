package com.edgevoice.agent.media.video

import androidx.camera.core.ImageProxy

/**
 * YUV_420_888 -> NV21(VU 交织) 转换。
 *
 * 供两处使用：
 *  - 上行/入库前编码（NV21 是 Android 图像编码链路的常见输入）；
 *  - 云端 CLIP 编码前处理（未来视觉长期记忆，当前未启用）。
 *
 * 实现要点：必须按 [ImageProxy.PlaneProxy] 的 rowStride / pixelStride 逐像素拷贝，
 * 不能假设 stride == width（多数设备 Y 行有 padding）。
 */
object YuvUtils {

    fun toNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // --- 亮度平面：逐行逐列拷贝 ---
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        var pos = 0
        var row = 0
        while (row < height) {
            val rowBase = row * yRow
            var col = 0
            while (col < width) {
                out[pos++] = yBuf.get(rowBase + col * yPix)
                col++
            }
            row++
        }

        // --- 色度平面：按 2x2 块交织为 VU（NV21 顺序） ---
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vBuf = vPlane.buffer
        val uBuf = uPlane.buffer
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        row = 0
        while (row < chromaHeight) {
            var col = 0
            while (col < chromaWidth) {
                val vIdx = row * vRow + col * vPix
                val uIdx = row * uRow + col * uPix
                out[pos++] = vBuf.get(vIdx)
                out[pos++] = uBuf.get(uIdx)
                col++
            }
            row++
        }
        return out
    }
}

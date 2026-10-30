package com.edgevoice.agent.media.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/**
 * [FrameJpegCodec] 的 Android 平台实现：YuvImage 一次压缩 + Bitmap 降采样，零三方依赖。
 *
 * 线程约定：YuvImage/Bitmap 均为调用线程内的短生命周期对象，方法本身无线程亲和性，
 * 但调用方（引擎视觉管道）固定在单个 Default 协程上串行调用，避免并发重采样。
 */
object AndroidFrameJpegCodec : FrameJpegCodec {

    override fun compress(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
        // strides=null 表示行紧密（SmartFrameAnalyzer 产出的 NV21 逐像素拷贝、无 padding）
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream(jpegInitialCapacity(width, height))
        val ok = yuv.compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(1, 100), out)
        check(ok) { "YuvImage.compressToJpeg 失败: ${width}x$height q=$quality" }
        return out.toByteArray()
    }

    override fun resize(jpeg: ByteArray, longEdge: Int, quality: Int): ResizedJpeg {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: error("JPEG 解码失败，无法降采样到长边 $longEdge")
        try {
            val scale = longEdge.toFloat() / maxOf(src.width, src.height)
            // NV21/JPEG 要求宽高为偶数；至少 2px
            val targetW = evenFloor((src.width * scale).toInt().coerceAtLeast(2))
            val targetH = evenFloor((src.height * scale).toInt().coerceAtLeast(2))
            val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
            try {
                val out = ByteArrayOutputStream(64 * 1024)
                scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
                return ResizedJpeg(out.toByteArray(), targetW, targetH)
            } finally {
                if (scaled !== src) scaled.recycle()
            }
        } finally {
            src.recycle()
        }
    }

    private fun evenFloor(v: Int): Int = v - (v and 1)

    /** 给 ByteArrayOutputStream 一个合理初始容量，减少生长拷贝；按每像素 1.5 字节估。 */
    private fun jpegInitialCapacity(width: Int, height: Int): Int =
        minOf(width * height * 3 / 2, 512 * 1024)
}

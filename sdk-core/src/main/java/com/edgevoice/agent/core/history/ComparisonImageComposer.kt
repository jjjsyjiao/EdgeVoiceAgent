package com.edgevoice.agent.core.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Actual decoded pixels, not image URLs in a tool's text output. */
class ComparisonImageComposer(private val zoneId: String) {
    fun compose(current: HistoryCapturedFrame?, history: List<HistoryMemoryRecord>): ByteArray {
        require(history.isNotEmpty() && history.size <= 3) { "缺少可用的历史照片，不能比较。" }
        val images = buildList {
            current?.let { add(Triple("C CURRENT", it.capturedAtMs, it.jpeg)) }
            history.forEachIndexed { index, record -> add(Triple("H${index + 1} HISTORY", record.capturedAtMs, record.jpeg)) }
        }
        val decoded = mutableListOf<Bitmap>()
        var canvasBitmap: Bitmap? = null
        try {
            images.forEach { (_, _, jpeg) -> decoded.add(decode(jpeg)) }
            val columns = if (images.size == 1) 1 else 2
            val rows = (images.size + columns - 1) / columns
            val panelWidth = 480
            val panelHeight = 440
            val bitmap = Bitmap.createBitmap(columns * panelWidth, rows * panelHeight, Bitmap.Config.RGB_565)
            canvasBitmap = bitmap
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 27f; isFakeBoldText = true }
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of(zoneId))
            images.forEachIndexed { index, (label, timestamp, _) ->
                val left = (index % columns) * panelWidth
                val top = (index / columns) * panelHeight
                canvas.drawText(label, left + 12f, top + 32f, paint)
                paint.textSize = 21f
                canvas.drawText(formatter.format(Instant.ofEpochMilli(timestamp)), left + 12f, top + 60f, paint)
                paint.textSize = 27f
                val source = decoded[index]
                val scale = minOf((panelWidth - 16f) / source.width, (panelHeight - 84f) / source.height)
                val width = (source.width * scale).toInt().coerceAtLeast(1)
                val height = (source.height * scale).toInt().coerceAtLeast(1)
                val x = left + (panelWidth - width) / 2
                val y = top + 76 + (panelHeight - 84 - height) / 2
                canvas.drawBitmap(source, null, Rect(x, y, x + width, y + height), imagePaint)
            }
            // DashScope limits BASE64 video_frame to 256KiB, thus raw JPEG <=192KiB.
            for (quality in listOf(82, 70, 58, 45, 32)) {
                val stream = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream))
                if (stream.size() <= MAX_JPEG_BYTES) return stream.toByteArray()
            }
            throw IllegalArgumentException("证据图片超出模型输入大小，请减少照片后重试。")
        } finally {
            decoded.forEach { it.recycle() }
            canvasBitmap?.recycle()
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_SOURCE_BYTES) { "历史照片为空或过大，不能比较。" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 20_000_000L) { "历史照片无法解码，不能比较。" }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            inSampleSize = sample
        }
        return requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)) { "历史照片无法解码，不能比较。" }
    }

    companion object {
        const val MAX_JPEG_BYTES = 192 * 1024
        private const val MAX_SOURCE_BYTES = 512 * 1024
    }
}

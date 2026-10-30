package com.edgevoice.agent.media.video

/**
 * 视觉帧 JPEG 编码器 + 百炼 Realtime 硬约束闸门。
 *
 * V0 协议探针实测（2026-10，qwen3.5-omni-flash-realtime）：
 *  1. 上行事件 input_image_buffer.append 的 payload 是**裸 Base64（无 data: 前缀）**；
 *  2. 单帧 Base64 后长度硬上限 256KB —— 超限不是可恢复 error，而是整条 WebSocket
 *     被服务端异常切断（无 error 事件、无 close 帧），因此必须在发送前自行拦截；
 *  3. 720p / JPEG q80 实拍菜单卡约 58~78KB，距离上限余量充足。
 *
 * 超限处置阶梯（保证在常见画面上永远能发出帧）：
 *  先在原始尺寸上按质量 80→60→45→30 递降；仍超限则按长边 960→720→540 降分辨率，
 *  每档重新走质量梯子；全部失败返回 null（调用方计 rejected 并丢弃，绝不尝试上行）。
 *
 * 平台相关的 YuvImage/Bitmap 操作收敛到 [FrameJpegCodec]，本类是纯 Kotlin 编排，
 * 可用假 codec 在 JVM 单测中覆盖全部阶梯分支。
 */

/** 上行 JPEG 的协议硬约束与尺寸换算。 */
object JpegConstraints {
    /** 单帧 Base64 后的最大字节数（百炼官方文档 + V0 探针实测）。 */
    const val MAX_BASE64_BYTES: Int = 256 * 1024

    /** Base64（NO_WRAP）输出长度 = 4 * ceil(n/3)。 */
    fun base64EncodedLength(byteLength: Int): Int = 4 * ((byteLength + 2) / 3)
}

/**
 * 平台图像编解码原语（生产实现 [AndroidFrameJpegCodec]；单测注入 fake）。
 * 实现必须同步、快速返回，不得持有输入数组之外的资源。
 */
interface FrameJpegCodec {
    /** 已摆正的紧密 NV21 → JPEG 字节。 */
    fun compress(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray

    /** 已有 JPEG 解码 → 等比缩放到长边 [longEdge]（宽高取偶）→ 重新压缩。 */
    fun resize(jpeg: ByteArray, longEdge: Int, quality: Int): ResizedJpeg
}

data class ResizedJpeg(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * 编码成功产物。用普通 class 而非 data class：内含 ByteArray，
 * data class 的 equals/hashCode 基于数组引用无意义（同项目 [VideoFrame] 约定）。
 */
class EncodedFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val finalQuality: Int,
    val base64Size: Int,
) {
    override fun toString(): String =
        "EncodedFrame(${width}x$height, q=$finalQuality, b64=${base64Size / 1024}KB)"
}

class JpegFrameEncoder(
    private val codec: FrameJpegCodec,
    private val maxBase64Bytes: Int = JpegConstraints.MAX_BASE64_BYTES,
    private val qualities: List<Int> = listOf(80, 60, 45, 30),
    private val downscaleLongEdges: List<Int> = listOf(960, 720, 540),
) {
    /**
     * 旋转 + 编码 + 闸门。返回 null 表示所有阶梯都无法满足 256KB 约束，调用方必须丢弃该帧。
     */
    fun encode(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int): EncodedFrame? {
        val rotated = Nv21Transform.rotate(nv21, width, height, rotationDegrees)
        val srcLongEdge = maxOf(rotated.width, rotated.height)

        // 第一轮原尺寸；后续轮次只走低一档的长边（绝不放大）
        val rounds = buildList {
            add(srcLongEdge)
            downscaleLongEdges.filter { it < srcLongEdge }.forEach(::add)
        }

        var latest: ByteArray? = null
        var curW = rotated.width
        var curH = rotated.height

        for ((round, longEdge) in rounds.withIndex()) {
            // 轮内所有质量档都基于「上一轮最终产物」重采样，避免同代际反复缩放的画质损失
            val roundSource = latest
            for (q in qualities) {
                val jpeg = if (round == 0) {
                    codec.compress(rotated.data, curW, curH, q)
                } else {
                    val r = codec.resize(roundSource ?: continue, longEdge, q)
                    curW = r.width
                    curH = r.height
                    r.jpeg
                }
                latest = jpeg
                if (JpegConstraints.base64EncodedLength(jpeg.size) <= maxBase64Bytes) {
                    return EncodedFrame(
                        jpeg = jpeg,
                        width = curW,
                        height = curH,
                        finalQuality = q,
                        base64Size = JpegConstraints.base64EncodedLength(jpeg.size),
                    )
                }
            }
        }
        return null
    }
}

package com.edgevoice.agent.media.video

/**
 * 一次抽帧产物。
 *
 * 注意：这里**不含原始像素的长期存储**语义 —— [nv21] 是随取随用的临时编码数据，
 * 上层（transport 上行 / 云端）消费后即可丢弃。对齐「原始像素不入库，只落 embedding + 时间戳 + 帧路径」原则。
 *
 * 用普通 class 而非 data class：内含 ByteArray，data class 的 equals/hashCode 基于数组引用无意义。
 */
class VideoFrame(
    /** 采集时间戳（纳秒，与 AudioRecord 时间轴对齐用） */
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    /** 显示旋转角度，用于把图像摆正后再编码/入库 */
    val rotationDegrees: Int,
    /** 运动分 [0,1]，供抽帧节流与调试抽屉可视化 */
    val motionScore: Float,
    /** 是否判定为运动帧 */
    val isMotion: Boolean,
    /** NV21 编码数据（可为 null，表示本次不产出编码数据） */
    val nv21: ByteArray?,
) {
    override fun toString(): String =
        "VideoFrame(ts=$timestampNs, ${width}x$height, rot=$rotationDegrees, " +
            "motion=$motionScore, isMotion=$isMotion, nv21=${nv21?.size ?: 0}B)"
}

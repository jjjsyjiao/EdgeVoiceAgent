package com.edgevoice.agent.media.video

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * 智能抽帧分析器：运动检测 + 自适应帧率节流。
 *
 * 策略（对齐技术文档 §3.2）：
 *  - 静止 -> [stillFps]（默认 0.5fps）：省带宽、省算力、省发热；
 *  - 运动 -> [motionFps]（默认 5fps）：瞬间拉满，保证「刚才看到的那个东西」的时效性。
 *
 * 节流在**发出（emit）**环节做：无论相机本身多少 fps，只有满足当前目标帧间隔的帧才回调 [onFrame]。
 * 注意 CameraX 用 STRATEGY_KEEP_ONLY_LATEST，因此未 emit 的帧会直接被后续帧覆盖，不会堆积。
 */
class SmartFrameAnalyzer(
    private val onFrame: (VideoFrame) -> Unit,
    private val motionDetector: MotionDetector = MotionDetector(),
    private val stillFps: Double = 0.5,
    private val motionFps: Double = 5.0,
    /** 是否在 emit 时顺带产出 NV21（编码有 CPU 成本，纯预览/调参场景可关） */
    private val encodeNv21: Boolean = true,
    private val onFpsChanged: ((Double) -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    private var hasEmitted = false
    private var lastEmitNs = 0L

    @Volatile var currentFps: Double = stillFps
        private set

    override fun analyze(image: ImageProxy) {
        try {
            val y = image.planes[0]
            val score = motionDetector.onYPlane(
                y.buffer, image.width, image.height, y.rowStride, y.pixelStride,
            )
            val motion = motionDetector.isMotion(score)
            val target = if (motion) motionFps else stillFps
            if (target != currentFps) {
                currentFps = target
                onFpsChanged?.invoke(target)
            }

            val ts = image.imageInfo.timestamp
            val intervalNs = (1_000_000_000.0 / target).toLong()
            if (!hasEmitted || ts - lastEmitNs >= intervalNs) {
                hasEmitted = true
                lastEmitNs = ts
                onFrame(
                    VideoFrame(
                        timestampNs = ts,
                        width = image.width,
                        height = image.height,
                        rotationDegrees = image.imageInfo.rotationDegrees,
                        motionScore = score,
                        isMotion = motion,
                        nv21 = if (encodeNv21) YuvUtils.toNv21(image) else null,
                    ),
                )
            }
        } finally {
            // ImageProxy 必须归还，否则会耗尽底层 buffer
            image.close()
        }
    }

    fun reset() {
        hasEmitted = false
        motionDetector.reset()
    }
}

package com.edgevoice.agent.media.video

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 抽帧抓取器：把 [ImageAnalysis] 绑定到 Lifecycle，分析回调交由专用单线程执行。
 *
 * 依赖倒置：本类只关心「如何取帧」，不关心「帧怎么用」——把用途抽象为传入的 [analyzer]
 * （通常是 [SmartFrameAnalyzer]）。这样视觉链路与业务链路彻底解耦。
 *
 * [preview] 为可选预览用例（由宿主设置好 SurfaceProvider 后传入），与分析用例共享同一次
 * bindToLifecycle —— 预览与抽帧是同一个相机会话，官方推荐的组合方式。
 */
class CameraFrameGrabber(
    private val context: Context,
    private val analyzer: ImageAnalysis.Analyzer,
    private val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    private val targetWidth: Int = 640,
    private val targetHeight: Int = 480,
    private val preview: Preview? = null,
) {
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null

    /** 绑定相机并开始抽帧；须在宿主进入 STARTED 生命周期后调用。 */
    suspend fun bind(lifecycleOwner: LifecycleOwner) {
        unbind()
        // ProcessCameraProvider.getInstance 返回 ListenableFuture：在 IO 线程阻塞获取，避免占用主线程
        val p = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "evs-camera-analyze") }
        provider = p
        executor = exec

        withContext(Dispatchers.Main) {
            // CameraX 1.3+：ResolutionSelector 取代已弃用的 setTargetResolution
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(targetWidth, targetHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
            val ia = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            ia.setAnalyzer(exec, analyzer)
            analysis = ia
            val useCases: List<UseCase> = listOfNotNull(preview, ia)
            p.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                *useCases.toTypedArray(),
            )
        }
    }

    /** 解绑：隐私态（Vision Off）或宿主销毁时调用，彻底切断抽帧与预览链路。 */
    fun unbind() {
        runCatching { analysis?.clearAnalyzer() }
        // unbindAll：preview 与 analysis 同属本抓取器会话，必须一并释放，避免隐私态残留预览
        runCatching { provider?.unbindAll() }
        analysis = null
        executor?.shutdown()
        executor = null
    }
}

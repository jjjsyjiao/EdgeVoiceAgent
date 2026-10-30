package com.edgevoice.agent.app.camera

import android.content.Context
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.edgevoice.agent.media.video.CameraFrameGrabber
import com.edgevoice.agent.media.video.SmartFrameAnalyzer
import com.edgevoice.agent.media.video.VideoFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 视觉链路的 app 层控制器：把 CameraX 预览 + 智能抽帧装配成一次 bindToLifecycle。
 *
 * 线程/生命周期约定：
 *  - [surfaceProvider] 由 Compose 的 PreviewView 在 AndroidView factory 中注入（早于权限授予）；
 *  - [bind] 必须在相机权限授予后调用，[LifecycleOwner] 只在 bind 期间使用、不持有，避免泄漏；
 *  - [unbind] 对应视觉折叠（隐私态）：预览与抽帧一起释放。
 *
 * 帧去向：[onFrame] 直连 OrchestrationEngine.onVisionFrame —— 除登记时间戳/运动分做
 * 多模态对齐外，V1 起帧经「1fps 闸门 → 后台 JPEG 编码（≤256KB 阶梯）→ 音频时间轴」
 * 实时上行（仅 qwen 画像；OpenAI/端侧画像由能力位门控为本地登记，不上云）。
 */
class AgentCameraController(
    private val context: Context,
    private val onFrame: (VideoFrame) -> Unit,
) {
    private val _fps = MutableStateFlow(0f)
    /** 当前实际抽帧率（静止 0.5 / 运动 5.0）；未绑定为 0。 */
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private var grabber: CameraFrameGrabber? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    /** PreviewView 创建后立即注入；bind 之前可反复替换。 */
    fun attachSurfaceProvider(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
    }

    /** 绑定相机并开始预览+抽帧。重复调用先解绑旧会话。 */
    suspend fun bind(lifecycleOwner: LifecycleOwner) {
        unbind()
        val analyzer = SmartFrameAnalyzer(
            onFrame = onFrame,
            onFpsChanged = { _fps.value = it.toFloat() },
        )
        val preview = surfaceProvider?.let { sp ->
            Preview.Builder().build().apply { setSurfaceProvider(sp) }
        }
        CameraFrameGrabber(
            context = context,
            analyzer = analyzer,
            preview = preview,
        ).also {
            grabber = it
            it.bind(lifecycleOwner)
        }
        // 智能抽帧静止档基线（首帧前 onFpsChanged 不会回调）
        _fps.value = 0.5f
    }

    /** 解绑相机（隐私态 / ViewModel 清场）。 */
    fun unbind() {
        grabber?.unbind()
        grabber = null
        _fps.value = 0f
    }
}

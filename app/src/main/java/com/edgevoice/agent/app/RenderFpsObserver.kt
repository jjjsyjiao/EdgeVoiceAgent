package com.edgevoice.agent.app

import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * 用 Choreographer 帧回调自测真实渲染 FPS（调试抽屉 Render FPS 指标）。
 *
 * 放在 app 层而非 ui-presentation：FPS 是宿主环境指标，与业务状态无关。
 * 仅在本 Composable 处于组合中时占用一个 VS 监听，每秒结算一次回调 [onFps]。
 */
@Composable
fun RenderFpsObserver(onFps: (Int) -> Unit) {
    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        var frames = 0
        var windowStartNs = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (windowStartNs == 0L) windowStartNs = frameTimeNanos
                frames++
                val elapsed = frameTimeNanos - windowStartNs
                if (elapsed >= WINDOW_NS) {
                    onFps((frames * NS_PER_SECOND / elapsed).toInt().coerceAtMost(240))
                    frames = 0
                    windowStartNs = frameTimeNanos
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose { choreographer.removeFrameCallback(callback) }
    }
}

private const val WINDOW_NS = 1_000_000_000L
private const val NS_PER_SECOND = 1_000_000_000L

package com.edgevoice.agent.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.edgevoice.agent.core.state.AgentState
import com.edgevoice.agent.ui.camera.CameraOverlay
import com.edgevoice.agent.ui.drawer.DebugDrawer
import com.edgevoice.agent.ui.history.HistoryEvidenceCard
import com.edgevoice.agent.ui.model.AgentMode
import com.edgevoice.agent.ui.model.AgentUiActions
import com.edgevoice.agent.ui.model.AgentUiState
import com.edgevoice.agent.ui.model.ConnectionPhase
import com.edgevoice.agent.ui.orb.DynamicOrb
import com.edgevoice.agent.ui.shell.BottomControlBar
import com.edgevoice.agent.ui.shell.StatusCaption
import com.edgevoice.agent.ui.shell.TopTelemetryBar
import com.edgevoice.agent.ui.theme.EdgeVoiceTheme
import com.edgevoice.agent.ui.theme.ScreenBottom
import com.edgevoice.agent.ui.theme.ScreenMid
import com.edgevoice.agent.ui.theme.ScreenTop
import com.edgevoice.agent.ui.theme.accent

/**
 * 三层复合视界主屏幕（设计规范 §二）：
 *
 *   Layer 2  Camera Overlay（半透明视觉浮窗，置顶）
 *   Layer 1  Dynamic Orb（灵动声波核心区，居中）
 *   壳层     TopBar / Caption / BottomBar / Debug Drawer
 *
 * 纯展示：除 [actions] 外不触达任何 SDK / 硬件 API。
 * [cameraPreview] 由宿主注入（Stage 8 接 CameraX PreviewView）。
 */
@Composable
fun AgentScreen(
    state: AgentUiState,
    actions: AgentUiActions = AgentUiActions(),
    cameraPreview: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    EdgeVoiceTheme {
        val accent by animateColorAsState(state.state.accent(), spring(stiffness = 400f), label = "screen-accent")
        var drawerOpen by remember { mutableStateOf(false) }

        Box(
            modifier = modifier
                .fillMaxSize()
                .drawBehind {
                    // 深空底 + 顶部环境光 + 右下紫光（对齐 body/screen 背景）
                    drawRect(
                        Brush.verticalGradient(
                            0f to ScreenTop,
                            0.45f to ScreenMid,
                            1f to ScreenBottom,
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.18f), Color.Transparent),
                            center = Offset(size.width * 0.5f, -size.height * 0.04f),
                            radius = size.width * 1.2f,
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color(0x298B5CF6), Color.Transparent),
                            center = Offset(size.width * 0.92f, size.height * 1.05f),
                            radius = size.width * 1.1f,
                        ),
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                TopTelemetryBar(
                    mode = state.mode,
                    state = state.state,
                    rttMs = state.telemetry.rttMs,
                    session = state.session,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 视口：相机浮窗置顶，灵动球居中
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    // 光球背后环境辉光
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                                        center = center,
                                        radius = size.minDimension * 0.42f,
                                    ),
                                    center = center,
                                    radius = size.minDimension * 0.42f,
                                )
                            },
                    )
                    DynamicOrb(
                        state = state.state,
                        inputLevel = state.inputLevel,
                        outputLevel = state.outputLevel,
                        onClick = actions.onOrbClick,
                    )

                    CameraOverlay(
                        enabled = state.visionEnabled,
                        state = state.state,
                        connectionReady = state.session.phase == ConnectionPhase.READY,
                        lastFrameAgeMs = state.telemetry.visionLastFrameAgeMs,
                        recentFps = state.telemetry.visionRecentFps,
                        preview = cameraPreview,
                        onToggle = actions.onVisionToggle,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp),
                    )

                    // 证据浮层不参与主视口的高度分配，避免保存后压缩相机画面。
                    HistoryEvidenceCard(
                        state.history,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                        compact = maxHeight < 320.dp,
                        onConfirmSave = actions.onHistorySaveConfirm,
                        onDiscardSave = actions.onHistorySaveDiscard,
                    )
                }

                StatusCaption(
                    state = state.state,
                    session = state.session,
                    visionEnabled = state.visionEnabled,
                    conversationTurns = state.conversationTurns,
                    userText = state.userText,
                    assistantText = state.assistantText,
                    assistantInterrupted = state.assistantInterrupted,
                    errorMessage = state.errorMessage,
                    onRecoveryClick = actions.onRecoveryClick,
                    modifier = Modifier.fillMaxWidth(),
                )

                BottomControlBar(
                    mode = state.mode,
                    state = state.state,
                    visionEnabled = state.visionEnabled,
                    sessionRunning = state.sessionRunning,
                    onModeClick = {
                        actions.onModeChange(
                            if (state.mode == AgentMode.CLOUD) AgentMode.LOCAL else AgentMode.CLOUD,
                        )
                    },
                    onMicClick = actions.onMicClick,
                    onVisionClick = actions.onVisionToggle,
                    onDrawerClick = { drawerOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // barge-in 红色闪光（< 50ms 视觉响应）
            BargeInFlash(visibleState = state.state)

            // 调试抽屉（覆盖层，不参与 Column 布局）
            DebugDrawer(
                open = drawerOpen,
                mode = state.mode,
                telemetry = state.telemetry,
                accent = accent,
                onClose = { drawerOpen = false },
                onModeChange = actions.onModeChange,
            )
        }
    }
}

/** INTERRUPTED 一次性红闪：0.9 → 0.18 → 0.55 → 0（对齐设计稿 flashHit）。 */
@Composable
private fun BargeInFlash(visibleState: AgentState) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(visibleState) {
        if (visibleState == AgentState.INTERRUPTED) {
            alpha.snapTo(0.9f)
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 500
                    0.9f at 0
                    0.18f at 140
                    0.55f at 290
                    0f at 500
                },
            )
        } else if (alpha.value > 0f) {
            alpha.snapTo(0f)
        }
    }
    if (alpha.value > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color(0xD9EF4444).copy(alpha = alpha.value), Color.Transparent),
                            center = Offset(size.width * 0.5f, size.height * 0.44f),
                            radius = size.minDimension * 0.75f,
                        ),
                    )
                },
        )
    }
}

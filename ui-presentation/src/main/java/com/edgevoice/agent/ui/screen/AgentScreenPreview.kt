package com.edgevoice.agent.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.edgevoice.agent.core.state.AgentState
import com.edgevoice.agent.ui.model.AgentMode
import com.edgevoice.agent.ui.model.AgentUiActions
import com.edgevoice.agent.ui.model.AgentUiState
import com.edgevoice.agent.ui.model.DebugTelemetry
import com.edgevoice.agent.ui.model.SessionUiState
import com.edgevoice.agent.ui.model.ConnectionPhase

/**
 * 设计稿对照预览：点击光球循环 IDLE/LISTENING/PROCESSING/SPEAKING，
 * 麦克风按钮可触发 INTERRUPTED；右侧 Tune 打开调试抽屉；相机图标折叠视觉浮窗。
 */
@Preview(
    name = "EdgeVoice Phone",
    device = "spec:shape=Normal,width=390,height=844,unit=dp,dpi=440",
    showSystemUi = false,
)
@Composable
private fun AgentScreenPreview() {
    var state by remember { mutableStateOf(AgentState.IDLE_OBSERVING) }
    var mode by remember { mutableStateOf(AgentMode.CLOUD) }
    var vision by remember { mutableStateOf(true) }

    val cycle = listOf(
        AgentState.IDLE_OBSERVING,
        AgentState.LISTENING,
        AgentState.PROCESSING,
        AgentState.SPEAKING,
    )

    AgentScreen(
        state = AgentUiState(
            state = state,
            mode = mode,
            inputLevel = if (state == AgentState.LISTENING) 0.55f else 0f,
            outputLevel = if (state == AgentState.SPEAKING) 0.7f else 0f,
            visionEnabled = vision,
            visionFps = 5f,
            session = SessionUiState(
                phase = if (state == AgentState.IDLE_OBSERVING) ConnectionPhase.STOPPED else ConnectionPhase.READY,
                audioRouteLabel = "扬声器",
            ),
            telemetry = DebugTelemetry(
                rttMs = if (mode == AgentMode.CLOUD) 42L else 8L,
                rttHistory = listOf(38, 44, 41, 46, 52, 40, 37, 43, 49, 42, 45, 39, 48, 42, 41, 44)
                    .map { if (mode == AgentMode.CLOUD) it.toLong() else (it / 5).toLong() },
                ringBufferPct = 0.34f,
                jitterLossPct = 0.2f,
                tokenUsed = 12_480,
                tokenLimit = 32_000,
                iterationUsed = 7,
                iterationLimit = 20,
                circuitBreaker = "CLOSED",
                securityHits = 0,
                renderFps = 60,
            ),
        ),
        actions = AgentUiActions(
            onOrbClick = {
                state = if (state == AgentState.INTERRUPTED) {
                    AgentState.LISTENING
                } else {
                    cycle[(cycle.indexOf(state) + 1) % cycle.size]
                }
            },
            onMicClick = {
                state = when (state) {
                    AgentState.SPEAKING -> AgentState.INTERRUPTED
                    AgentState.INTERRUPTED -> AgentState.LISTENING
                    else -> state
                }
            },
            onModeChange = { mode = it },
            onVisionToggle = { vision = !vision },
        ),
    )
}

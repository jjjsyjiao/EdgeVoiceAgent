package com.edgevoice.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.edgevoice.agent.core.state.AgentState

/** 状态机 → accent 主色（设计规范 §3.1）。 */
fun AgentState.accent(): Color = when (this) {
    AgentState.IDLE_OBSERVING -> AccentIdle
    AgentState.LISTENING -> AccentListening
    AgentState.PROCESSING -> AccentProcessing
    AgentState.SPEAKING -> AccentSpeaking
    AgentState.INTERRUPTED -> AccentInterrupted
}

private val EdgeDarkColorScheme = darkColorScheme(
    primary = AccentIdle,
    onPrimary = Color(0xFF05070D),
    background = Bg0,
    onBackground = Ink,
    surface = Bg1,
    onSurface = Ink,
    surfaceVariant = Glass,
    onSurfaceVariant = InkDim,
    outline = GlassLine,
)

/**
 * 沉浸式深色主题。UI 本身恒为深空视界，不跟随系统亮色模式。
 */
@Composable
fun EdgeVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EdgeDarkColorScheme,
        content = content,
    )
}

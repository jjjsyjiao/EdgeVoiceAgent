package com.edgevoice.agent.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgevoice.agent.core.state.AgentState
import com.edgevoice.agent.ui.model.AgentMode
import com.edgevoice.agent.ui.theme.Glass
import com.edgevoice.agent.ui.theme.Ink
import com.edgevoice.agent.ui.theme.accent

/**
 * 底部控制条：模式 chip（左）/ 主麦克风（中）/ 视觉开关 + 调试抽屉（右）。
 */
@Composable
fun BottomControlBar(
    mode: AgentMode,
    state: AgentState,
    visionEnabled: Boolean,
    modifier: Modifier = Modifier,
    onModeClick: () -> Unit = {},
    onMicClick: () -> Unit = {},
    onVisionClick: () -> Unit = {},
    onDrawerClick: () -> Unit = {},
    sessionRunning: Boolean = false,
) {
    val accent by animateColorAsState(state.accent(), spring(stiffness = 400f), label = "bar-accent")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color(0xB804060B),
                    1f to Color(0xB804060B),
                ),
            )
            .padding(start = 26.dp, end = 26.dp, top = 14.dp, bottom = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左：模式 chip
        Row(
            modifier = Modifier
                .glassSurface(shape = RoundedCornerShape(14.dp))
                .clickable(onClick = onModeClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (mode == AgentMode.CLOUD) Icons.Filled.Cloud else Icons.Filled.Bolt,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = if (mode == AgentMode.CLOUD) "Cloud" else "Local",
                color = Ink,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 中：主麦克风（LISTENING 时外圈扩散）
        MicButton(
            accent = accent,
            listening = state == AgentState.LISTENING,
            sessionRunning = sessionRunning,
            onClick = onMicClick,
        )

        // 右：视觉 + 调试抽屉
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundControl(active = visionEnabled, accent = accent, onClick = onVisionClick) {
                Icon(
                    imageVector = if (visionEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    contentDescription = "视觉开关",
                    modifier = Modifier.size(21.dp),
                )
            }
            RoundControl(active = false, accent = accent, onClick = onDrawerClick) {
                Icon(Icons.Filled.Tune, contentDescription = "调试抽屉", modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun MicButton(accent: Color, listening: Boolean, sessionRunning: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "mic-pulse")
    val progress by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "p",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
        if (listening) {
            Canvas(Modifier.size(72.dp)) {
                val p = progress
                drawCircle(
                    color = accent.copy(alpha = 0.8f * (1f - p)),
                    radius = size.minDimension / 2f * (1f + 0.6f * p),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.blendWhite(0.08f),
                            accent.blendBlack(0.28f),
                        ),
                    ),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = if (sessionRunning) "停止会话" else "开始对话",
                tint = Color(0xFF05070D),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun RoundControl(
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    // active：accent 实心 + 深色图标（对齐设计稿 .ctrl.round.active）
    val shape = RoundedCornerShape(17.dp)
    CompositionLocalProvider(LocalContentColor provides if (active) Color(0xFF04060B) else Ink) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .then(
                    if (active) Modifier.background(accent, shape) else Modifier.glassSurface(shape = shape),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private fun Color.blendWhite(t: Float): Color = Color(
    red = red + (1f - red) * t,
    green = green + (1f - green) * t,
    blue = blue + (1f - blue) * t,
    alpha = alpha,
)

private fun Color.blendBlack(t: Float): Color = Color(
    red = red * (1f - t),
    green = green * (1f - t),
    blue = blue * (1f - t),
    alpha = alpha,
)

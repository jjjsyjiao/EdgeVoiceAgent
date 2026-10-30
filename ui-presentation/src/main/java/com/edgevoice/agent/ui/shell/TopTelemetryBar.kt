package com.edgevoice.agent.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgevoice.agent.core.state.AgentState
import com.edgevoice.agent.ui.model.AgentMode
import com.edgevoice.agent.ui.model.SessionUiState
import com.edgevoice.agent.ui.model.ConnectionPhase
import com.edgevoice.agent.ui.theme.Ink
import com.edgevoice.agent.ui.theme.InkDim
import com.edgevoice.agent.ui.theme.accent

/** 顶栏：模式 Badge（左）+ RTT 遥测胶囊（右）。 */
@Composable
fun TopTelemetryBar(
    mode: AgentMode,
    state: AgentState,
    rttMs: Long,
    modifier: Modifier = Modifier,
    session: SessionUiState = SessionUiState(),
) {
    val accent by animateColorAsState(state.accent(), spring(stiffness = 400f), label = "top-accent")
    Row(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 模式 Badge
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .glassSurface(shape = RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulsingDot(accent)
            Column {
                Text(
                    text = "${if (mode == AgentMode.CLOUD) "Cloud" else "Local"} · ${session.phase.label}",
                    color = Ink,
                    fontSize = 12.5.sp,
                )
                if (session.phase == ConnectionPhase.READY && session.audioRouteLabel.isNotBlank()) {
                    Text(session.audioRouteLabel, color = InkDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (session.phase == ConnectionPhase.RECONNECTING && session.retryAttempt > 0) {
                    Text("第 ${session.retryAttempt} 次重试 · 可停止", color = InkDim, fontSize = 10.sp)
                }
            }
        }

        // RTT 遥测
        Row(
            modifier = Modifier
                .glassSurface(shape = RoundedCornerShape(999.dp))
                .padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (session.phase == ConnectionPhase.READY) SignalBars(accent)
            Text(
                text = if (session.phase == ConnectionPhase.READY && rttMs > 0) "RTT ${rttMs}ms" else "—",
                color = InkDim,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** 呼吸圆点（2s 脉冲）。 */
@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(1f, 0.45f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "a")
    val scale by transition.animateFloat(1f, 0.82f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "s")
    Canvas(
        Modifier
            .size(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        drawCircle(color.copy(alpha = alpha))
    }
}

/** 四格信号强度条（最后一格弱化，对齐设计稿）。 */
@Composable
private fun SignalBars(color: Color) {
    val heights = listOf(4.dp, 7.dp, 10.dp, 12.dp)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
    ) {
        heights.forEachIndexed { i, h ->
            val alpha = if (i == 3) 0.35f else 0.85f
            Canvas(Modifier.size(3.dp, h)) {
                drawRoundRect(
                    color = color.copy(alpha = alpha),
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
    }
}

package com.edgevoice.agent.ui.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgevoice.agent.core.state.AgentState
import com.edgevoice.agent.ui.shell.glassSurface
import com.edgevoice.agent.ui.theme.Ink
import com.edgevoice.agent.ui.theme.InkDim
import com.edgevoice.agent.ui.theme.OkGreen
import com.edgevoice.agent.ui.model.visionDeliveryLabel
import com.edgevoice.agent.ui.theme.accent

/**
 * Multimodal Camera Overlay（设计规范 §3.2）。
 *
 * - 激活态：半透明圆角卡片 + 激光扫描线 + 取景框角标 + LIVE 标签；
 * - 隐私态：折叠为「Vision Off」胶囊，点击恢复；语义对应相机链路解绑（Stage 8 接线）。
 *
 * [preview] 由宿主注入 CameraX PreviewView（AndroidView）；为空时呈现合成深色场景。
 */
@Composable
fun CameraOverlay(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    state: AgentState = AgentState.IDLE_OBSERVING,
    preview: @Composable () -> Unit = {},
    onToggle: () -> Unit = {},
    connectionReady: Boolean = false,
    lastFrameAgeMs: Long? = null,
    recentFps: Float = 0f,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedContent(
            targetState = enabled,
            transitionSpec = {
                (slideInVertically { -it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "vision-toggle",
        ) { isOn ->
            if (isOn) {
                VisionCard(
                    state = state,
                    preview = preview,
                    onCollapse = onToggle,
                    connectionReady = connectionReady,
                    lastFrameAgeMs = lastFrameAgeMs,
                    recentFps = recentFps,
                )
            } else {
                VisionOffPill(onRestore = onToggle)
            }
        }
    }
}

@Composable
private fun VisionCard(
    state: AgentState,
    preview: @Composable () -> Unit,
    onCollapse: () -> Unit,
    connectionReady: Boolean,
    lastFrameAgeMs: Long?,
    recentFps: Float,
) {
    val delivery = visionDeliveryLabel(connectionReady, lastFrameAgeMs, recentFps)
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth(0.78f)
            .height(170.dp)
            .clip(shape)
            .drawBehind {
                // 合成「相机画面」深色场景（真实预览由 preview 槽覆盖其上）
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF24406B), Color(0xFF132238), Color(0xFF080D18)),
                        center = Offset(size.width * 0.22f, size.height * 0.18f),
                        radius = size.width * 1.1f,
                    ),
                )
            }
            .glassSurface(shape = shape, background = Color.Transparent, borderColor = Color(0x24FFFFFF)),
    ) {
        // CameraX 预览（Stage 8 注入）
        Box(Modifier.fillMaxSize()) { preview() }

        // 网格 + 暗角
        Canvas(Modifier.fillMaxSize()) {
            val step = 26.dp.toPx()
            val line = Color.White.copy(alpha = 0.045f)
            var x = 0f
            while (x <= size.width) {
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0x99000000)),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.width * 0.75f,
                ),
            )
        }

        // 激光扫描线（视觉推送激活时自上而下循环）
        if (delivery.sending) LaserScanLine(accent = state.accent())

        // 取景框四角
        Canvas(Modifier.fillMaxSize()) { drawCornerBrackets() }

        // LIVE 标签
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
                .glassSurface(shape = RoundedCornerShape(8.dp), background = Color(0x99060A12))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val live = rememberInfiniteTransition(label = "live-dot")
            val liveAlpha by live.animateFloat(
                1f, 0.25f,
                infiniteRepeatable(tween(1400), RepeatMode.Reverse),
                label = "alpha",
            )
            Canvas(Modifier.size(6.dp)) {
                drawCircle((if (delivery.sending) OkGreen else InkDim).copy(alpha = liveAlpha))
            }
            Text(
                text = delivery.text,
                color = Ink,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.4.sp,
            )
        }

        // 折叠（一键切断视觉链路）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(30.dp)
                .glassSurface(shape = RoundedCornerShape(10.dp), background = Color(0x8C060A12))
                .clickable(onClick = onCollapse),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = "关闭视觉", tint = Ink, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun LaserScanLine(accent: Color) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "scan-y",
    )
    val lineH = 46.dp
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = progress * size.height - lineH.toPx() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(lineH)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.45f), Color.Transparent),
                    ),
                ),
        )
    }
}

private fun DrawScope.drawCornerBrackets() {
    val len = 16.dp.toPx()
    val margin = 10.dp.toPx()
    val w = 2.dp.toPx()
    val color = Color.White.copy(alpha = 0.55f)
    // 左上
    drawLine(color, Offset(margin, margin), Offset(margin + len, margin), w, cap = StrokeCap.Round)
    drawLine(color, Offset(margin, margin), Offset(margin, margin + len), w, cap = StrokeCap.Round)
    // 右上
    drawLine(color, Offset(size.width - margin, margin), Offset(size.width - margin - len, margin), w, cap = StrokeCap.Round)
    drawLine(color, Offset(size.width - margin, margin), Offset(size.width - margin, margin + len), w, cap = StrokeCap.Round)
    // 左下
    drawLine(color, Offset(margin, size.height - margin), Offset(margin + len, size.height - margin), w, cap = StrokeCap.Round)
    drawLine(color, Offset(margin, size.height - margin), Offset(margin, size.height - margin - len), w, cap = StrokeCap.Round)
    // 右下
    drawLine(color, Offset(size.width - margin, size.height - margin), Offset(size.width - margin - len, size.height - margin), w, cap = StrokeCap.Round)
    drawLine(color, Offset(size.width - margin, size.height - margin), Offset(size.width - margin, size.height - margin - len), w, cap = StrokeCap.Round)
}

@Composable
private fun VisionOffPill(onRestore: () -> Unit) {
    Row(
        modifier = Modifier
            .glassSurface(shape = RoundedCornerShape(999.dp))
            .clickable(onClick = onRestore)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Visibility, contentDescription = null, tint = InkDim, modifier = Modifier.size(15.dp))
        Text(
            text = "Vision Off · 视频抽帧链路已切断",
            color = InkDim,
            fontSize = 12.sp,
        )
    }
}

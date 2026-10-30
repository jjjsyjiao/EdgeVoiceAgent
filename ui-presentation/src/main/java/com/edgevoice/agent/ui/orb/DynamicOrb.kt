package com.edgevoice.agent.ui.orb

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.edgevoice.agent.core.state.AgentState
import com.edgevoice.agent.ui.theme.accent
import kotlin.math.PI
import kotlin.math.sin

// 波形条数量（对齐设计稿 48 根径向声波）
private const val BAR_COUNT = 48

/**
 * Dynamic Orb Visualizer —— 整个 UI 的灵魂（设计规范 §3.1）。
 *
 * 纯 Canvas 绘制、状态驱动：
 * - IDLE_OBSERVING：±5% 慢呼吸（4.4s）
 * - LISTENING：随麦克风 RMS 跳动 + 三道扩散波纹（2.4s）
 * - PROCESSING：conic 流光环 1.05s 高速旋转
 * - SPEAKING：随下行 RMS 强烈律动 + 2.6s 慢转环
 * - INTERRUPTED：一次性急缩（0.12s 到 0.8）+ 弹簧回弹；红色闪光在屏幕层处理
 *
 * 性能：全部动画基于 [rememberInfiniteTransition] / [Animatable]，由渲染帧驱动，
 * 无 listener、无主线程业务工作。
 */
@Composable
fun DynamicOrb(
    state: AgentState,
    inputLevel: Float,
    outputLevel: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val accent by animateColorAsState(
        targetValue = state.accent(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "orb-accent",
    )

    // ---- 连续时间轴：呼吸 / 旋转 / 波形相位全部从同一个 1.1s 相位派生 ----
    val infinite = rememberInfiniteTransition(label = "orb")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

    // ---- RMS 平滑：音频帧更新频率不稳定，用弹簧把电平追赶平滑化 ----
    val targetLevel = when (state) {
        AgentState.LISTENING -> inputLevel.coerceIn(0f, 1f)
        AgentState.SPEAKING -> outputLevel.coerceIn(0f, 1f)
        else -> 0f
    }
    val level = remember { Animatable(0f) }
    LaunchedEffect(targetLevel, state) {
        level.animateTo(targetLevel, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
    }

    // ---- 打断急缩：一次性 keyframes 急收 + 弹簧回弹 ----
    val snap = remember { Animatable(1f) }
    LaunchedEffect(state) {
        when (state) {
            AgentState.INTERRUPTED -> {
                snap.animateTo(
                    targetValue = 0.8f,
                    animationSpec = keyframes {
                        durationMillis = 120
                        1f at 0
                        0.8f at 120
                    },
                )
                snap.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                )
            }
            else -> snap.snapTo(1f)
        }
    }

    // 状态内基础律动（呼吸 / 思考微缩放 / 播报跟随电平）
    val breathArg = phase * 2f * PI.toFloat()
    val baseScale = when (state) {
        AgentState.IDLE_OBSERVING -> 1f + 0.05f * sin(breathArg * (1100f / 4400f) * 2f)
        AgentState.LISTENING -> 1f + 0.05f * sin(breathArg * (1100f / 2400f) * 2f)
        AgentState.PROCESSING -> 1f + 0.025f * sin(breathArg * (1100f / 1300f) * 2f)
        AgentState.SPEAKING -> 1f + level.value * 0.22f
        AgentState.INTERRUPTED -> 1f
    }
    // SPEAKING 的纵向/横向有机形变（近似设计稿 speak 关键帧）
    val squashX = if (state == AgentState.SPEAKING) 1f + 0.035f * sin(breathArg * 2.2f) else 1f
    val squashY = if (state == AgentState.SPEAKING) 1f - 0.035f * sin(breathArg * 2.2f) else 1f

    Box(
        modifier = modifier
            .size(300.dp)
            .graphicsLayer {
                scaleX = baseScale * snap.value * squashX
                scaleY = baseScale * snap.value * squashY
            }
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(300.dp)) {
            drawOrb(
                state = state,
                accent = accent,
                phase = phase,
                level = level.value,
            )
        }
    }
}

private fun DrawScope.drawOrb(
    state: AgentState,
    accent: Color,
    phase: Float,
    level: Float,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val half = minOf(cx, cy)
    val coreR = half * 0.44f
    val barRingR = half * 0.70f
    val haloR = half * 0.78f

    // ---- 环境光晕 ----
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.30f), Color.Transparent),
            center = Offset(cx, cy),
            radius = haloR,
        ),
        radius = haloR,
        center = Offset(cx, cy),
    )

    // ---- 外层细圈 ----
    drawCircle(
        color = accent.copy(alpha = 0.40f),
        radius = coreR + 14.dp.toPx(),
        center = Offset(cx, cy),
        style = Stroke(width = 1.dp.toPx()),
    )

    // ---- LISTENING：三道延迟扩散波纹 ----
    if (state == AgentState.LISTENING) {
        repeat(3) { i ->
            val p = (phase + i * (1f / 3f)) % 1f
            val r = coreR + (barRingR - coreR) * p
            drawCircle(
                color = accent.copy(alpha = 0.75f * (1f - p)),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }

    // ---- PROCESSING / SPEAKING：conic 旋转流光环 ----
    val (ringAlpha, ringPeriodSec) = when (state) {
        AgentState.PROCESSING -> 0.95f to 1.05f
        AgentState.SPEAKING -> 0.5f to 2.6f
        else -> 0f to 1f
    }
    if (ringAlpha > 0f) {
        val angleDeg = (phase * 1.1f / ringPeriodSec) * 360f
        rotate(degrees = angleDeg - 90f, pivot = Offset(cx, cy)) {
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.30f to accent,
                    0.55f to Color.Transparent,
                    center = Offset(cx, cy),
                ),
                radius = coreR + 20.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round),
                alpha = ringAlpha,
            )
        }
    }

    // ---- 48 根径向波形条 ----
    drawWaveBars(state, accent, phase, level, cx, cy, barRingR)

    // ---- 核心光球 ----
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.55f),
                0.32f to accent,
                0.62f to accent.compositeOverDark(0.62f),
                1f to Color(0xFF05070D),
            ),
            // 高光点偏左上（34%,28%）
            center = Offset(cx - coreR * 0.32f, cy - coreR * 0.44f),
            radius = coreR * 1.35f,
        ),
        radius = coreR,
        center = Offset(cx, cy),
    )
    // 顶部镜面高光
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
            center = Offset(cx - coreR * 0.22f, cy - coreR * 0.45f),
            radius = coreR * 0.55f,
        ),
        radius = coreR * 0.55f,
        center = Offset(cx - coreR * 0.22f, cy - coreR * 0.45f),
    )
}

private fun DrawScope.drawWaveBars(
    state: AgentState,
    accent: Color,
    phase: Float,
    level: Float,
    cx: Float,
    cy: Float,
    ringRadius: Float,
) {
    val barWidth = 3.dp.toPx()
    val minLen = 10.dp.toPx()
    val maxLen = 30.dp.toPx()

    // 各状态下波形条的存在感与透明度（对齐设计稿）
    val presence: Float
    val alpha: Float
    when (state) {
        AgentState.LISTENING -> { presence = 0.30f + 0.70f * level; alpha = 0.92f }
        AgentState.SPEAKING -> { presence = 0.45f + 0.95f * level; alpha = 1f }
        AgentState.PROCESSING -> { presence = 0.15f; alpha = 0.20f }
        AgentState.INTERRUPTED -> { presence = 0.18f; alpha = 0.30f }
        AgentState.IDLE_OBSERVING -> { presence = 0.12f; alpha = 0.42f }
    }

    val angleStep = 360f / BAR_COUNT
    for (i in 0 until BAR_COUNT) {
        // 每根条带相位差，形成环形波浪
        val wave = (sin(phase * 2f * PI.toFloat() + i * 0.55f) + 1f) / 2f
        val len = minLen + (maxLen - minLen) * (0.25f + 0.75f * wave) * presence
        rotate(degrees = angleStep * i, pivot = Offset(cx, cy)) {
            val top = cy - ringRadius - len
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(accent.copy(alpha = alpha), accent.copy(alpha = 0.30f * alpha)),
                    startY = top,
                    endY = cy - ringRadius + minLen,
                ),
                topLeft = Offset(cx - barWidth / 2f, top),
                size = Size(barWidth, len),
                cornerRadius = CornerRadius(barWidth, barWidth),
            )
        }
    }
}

/** 模拟设计稿 color-mix(accent t%, #060A12) 的深色混合。 */
private fun Color.compositeOverDark(t: Float): Color {
    val base = Color(0xFF060A12)
    return Color(
        red = red * t + base.red * (1 - t),
        green = green * t + base.green * (1 - t),
        blue = blue * t + base.blue * (1 - t),
        alpha = alpha,
    )
}

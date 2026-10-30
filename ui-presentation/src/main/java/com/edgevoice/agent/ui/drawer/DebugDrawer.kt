package com.edgevoice.agent.ui.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgevoice.agent.ui.model.AgentMode
import com.edgevoice.agent.ui.model.DebugTelemetry
import com.edgevoice.agent.ui.theme.AccentIdle
import com.edgevoice.agent.ui.theme.GlassCard
import com.edgevoice.agent.ui.theme.Ink
import com.edgevoice.agent.ui.theme.InkDim
import com.edgevoice.agent.ui.theme.InkFaint
import com.edgevoice.agent.ui.theme.OkGreen
import com.edgevoice.agent.ui.theme.WarnAmber
import com.edgevoice.agent.ui.theme.WarnRed

/**
 * Developer Debug Drawer（设计规范 §3.3）。
 *
 * 右侧毛玻璃抽屉：双模热切换分段控件（带触觉反馈）+ RTT 折线 + 媒体水位 + 治理看板。
 * 由 [AgentScreen] 以覆盖层方式承载；本组件不感知引擎，全部数据来自 [telemetry] 快照。
 */
@Composable
fun DebugDrawer(
    open: Boolean,
    mode: AgentMode,
    telemetry: DebugTelemetry,
    accent: Color,
    onClose: () -> Unit,
    onModeChange: (AgentMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x8C020408))
                    .clickable(onClick = onClose),
            )
        }

        // 抽屉面板
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            DrawerContent(
                mode = mode,
                telemetry = telemetry,
                accent = accent,
                onClose = onClose,
                onModeChange = onModeChange,
            )
        }
    }
}

@Composable
private fun DrawerContent(
    mode: AgentMode,
    telemetry: DebugTelemetry,
    accent: Color,
    onClose: () -> Unit,
    onModeChange: (AgentMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(
                Brush.linearGradient(listOf(Color(0xDB141C2C), Color(0xEB080C14))),
                RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp),
            )
            .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 30.dp),
    ) {
        // 头部
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text("Developer Debug Drawer", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "极客调试抽屉 · SDK v3.1.0",
                    color = InkFaint,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color(0x0FFFFFFF), RoundedCornerShape(11.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Ink, modifier = Modifier.size(15.dp))
            }
        }

        // 双模分段切换
        ModeSegmented(mode = mode, onModeChange = onModeChange)

        // ---- Network ----
        SectionLabel("Network · RTT Latency")
        CardShell {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("WebSocket Round-Trip", color = InkDim, fontSize = 12.sp)
                Text(
                    "${telemetry.rttMs}ms",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            RttLineChart(
                history = telemetry.rttHistory,
                accent = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .padding(top = 8.dp),
            )
        }

        // ---- Media Pipeline ----
        SectionLabel("Media Pipeline")
        MetricCard(
            name = "Ring Buffer · 内存水位",
            valueText = "${(telemetry.ringBufferPct * 100f).toInt()}%",
            fraction = telemetry.ringBufferPct,
            warnAt = 0.8f,
        )
        MetricCard(
            name = "Jitter Buffer · 丢包率",
            valueText = "${"%.1f".format(telemetry.jitterLossPct)}%",
            // 设计稿：1.5% 丢包即满格警戒；0.9% 约占 8% 条宽，用 *0.30 缩放
            fraction = (telemetry.jitterLossPct / 5f).coerceIn(0f, 1f),
            warnAt = 0.30f,
        )
        MetricCard(
            name = "Token 预算占用",
            valueText = "${telemetry.tokenUsed} / ${telemetry.tokenLimit}",
            fraction = if (telemetry.tokenLimit > 0)
                telemetry.tokenUsed.toFloat() / telemetry.tokenLimit else 0f,
            warnAt = 0.8f,
        )
        if (telemetry.visionActive) {
            val total = telemetry.visionSentFrames +
                telemetry.visionDroppedFrames + telemetry.visionRejectedFrames
            MetricCard(
                name = "视觉上行 · 帧（↑发出 / 丢 / 超限拒）",
                valueText = "↑${telemetry.visionSentFrames} · " +
                    "丢${telemetry.visionDroppedFrames} · " +
                    "拒${telemetry.visionRejectedFrames} · ${telemetry.visionLastFrameKb}KB",
                // 条宽=丢弃占比；256KB 红线由编码器+执行器双闸保证，体积只展示不告警
                fraction = if (total > 0L)
                    telemetry.visionDroppedFrames.toFloat() / total else 0f,
                warnAt = 0.3f,
            )
        }
        if (telemetry.recallEnabled) {
            val oldestAge = telemetry.frameRingOldestAgeSec
            MetricCard(
                name = "情景回忆 · 磁盘帧环（帧 / MB / 最旧秒）",
                valueText = "${telemetry.frameRingCount}帧 · " +
                    "${telemetry.frameRingMb}MB · " +
                    if (oldestAge == null) "空环" else "${oldestAge}s",
                // 条宽=最旧帧年龄对 30min 上限的占比；逼近上限是正常滚动，不告警
                fraction = if (oldestAge != null)
                    (oldestAge.toFloat() / 1800f).coerceIn(0f, 1f) else 0f,
                warnAt = 1.1f,
            )
            MetricCard(
                name = "情景回忆 · 回放（成功次数 / 最近门控）",
                valueText = "▶${telemetry.replayCount}" +
                    (telemetry.replayLastSkip?.let { " · $it" } ?: ""),
                fraction = 0f,
                warnAt = 1.1f,
            )
        }

        if (telemetry.sessionFrameEnabled) {
            val sessionBatches = telemetry.sessionUploadBatches + telemetry.sessionUploadFailures
            MetricCard(
                name = "云端留存 · 上传（帧 / 批 / 失败 / 待发）",
                valueText = "↑${telemetry.sessionUploadFrames}帧 · " +
                    "批${telemetry.sessionUploadBatches} · " +
                    "败${telemetry.sessionUploadFailures} · " +
                    "待${telemetry.sessionUploadPendingFrames}",
                // 条宽=失败批次占比；持续失败即降级告警（增强能力，不阻断主链路）
                fraction = if (sessionBatches > 0L)
                    telemetry.sessionUploadFailures.toFloat() / sessionBatches else 0f,
                warnAt = 0.5f,
            )
        }

        // ---- Governance ----
        SectionLabel("Agent Governance")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MiniStat(
                "MaxIterationGuard",
                "%02d / %d".format(telemetry.iterationUsed, telemetry.iterationLimit),
                OkGreen,
                Modifier.weight(1f),
            )
            MiniStat(
                "Circuit Breaker",
                telemetry.circuitBreaker,
                if (telemetry.circuitBreaker == "CLOSED") OkGreen else WarnRed,
                Modifier.weight(1f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            MiniStat(
                "SecuritySanitizer",
                "${telemetry.securityHits} hit",
                if (telemetry.securityHits == 0) OkGreen else WarnAmber,
                Modifier.weight(1f),
            )
            MiniStat("Render FPS", telemetry.renderFps.toString(), Ink, Modifier.weight(1f))
        }

        // 注脚
        Text(
            text = "切换 Cloud / Local 将触达 IAgentExecutor 策略热切换，顶部 Badge 与 RTT 指标实时联动。" +
                "所有动效采用 Spring Physics 曲线，由渲染帧驱动。",
            color = InkFaint,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            modifier = Modifier
                .padding(top = 14.dp)
                .background(Color(0x14FFFFFF), RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
    }
}

@Composable
private fun ModeSegmented(mode: AgentMode, onModeChange: (AgentMode) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp)
            .background(Color(0x0FFFFFFF), RoundedCornerShape(15.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SegmentButton(
            selected = mode == AgentMode.CLOUD,
            text = "Cloud · Realtime",
            icon = Icons.Filled.Cloud,
            modifier = Modifier.weight(1f),
        ) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            onModeChange(AgentMode.CLOUD)
        }
        SegmentButton(
            selected = mode == AgentMode.LOCAL,
            text = "On-Device · NPU",
            icon = Icons.Filled.Bolt,
            modifier = Modifier.weight(1f),
        ) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            onModeChange(AgentMode.LOCAL)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SegmentButton(
    selected: Boolean,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(
                if (selected) AccentIdle else Color.Transparent,
                RoundedCornerShape(11.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Color(0xFF04060B) else InkDim,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text,
            color = if (selected) Color(0xFF04060B) else InkDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = InkFaint,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 12.dp),
    )
}

@Composable
private fun CardShell(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassCard, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun MetricCard(
    name: String,
    valueText: String,
    fraction: Float,
    warnAt: Float,
) {
    val warn = fraction >= warnAt
    val barColor = if (warn) Brush.horizontalGradient(listOf(WarnAmber, WarnRed))
    else Brush.horizontalGradient(listOf(AccentIdle.copy(alpha = 0.55f), AccentIdle))
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        CardShell {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = InkDim, fontSize = 12.sp)
                Text(
                    valueText,
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Box(
                modifier = Modifier
                    .padding(top = 9.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.09f), RoundedCornerShape(4.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .background(barColor, RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

@Composable
private fun MiniStat(key: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(GlassCard, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Text(key, color = InkFaint, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
        Text(
            value,
            color = valueColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

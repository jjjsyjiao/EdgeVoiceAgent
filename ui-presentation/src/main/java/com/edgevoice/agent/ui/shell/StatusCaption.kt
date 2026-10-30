package com.edgevoice.agent.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgevoice.agent.core.ConversationTurn
import com.edgevoice.agent.core.state.AgentState
import com.edgevoice.agent.ui.model.SessionUiState
import com.edgevoice.agent.ui.model.ConnectionPhase
import com.edgevoice.agent.ui.model.RecoveryAction
import com.edgevoice.agent.ui.theme.Ink
import com.edgevoice.agent.ui.theme.InkFaint
import com.edgevoice.agent.ui.theme.WarnRed
import com.edgevoice.agent.ui.theme.accent

private const val FOLLOW_BOTTOM_THRESHOLD_PX = 48

/** 无对话内容时的操作提示；实际转写和回答由引擎提供。 */
private data class Caption(val main: String, val sub: String)

private fun AgentState.caption(session: SessionUiState, visionEnabled: Boolean): Caption {
    when (session.phase) {
        ConnectionPhase.CONNECTING -> return Caption("Connecting…", "正在连接云端 · 点击麦克风可取消")
        ConnectionPhase.CONFIGURING -> return Caption("Preparing session…", "正在准备会话 · 麦克风尚未开始聆听")
        ConnectionPhase.RECONNECTING -> return Caption("Reconnecting…", "网络暂时中断 · 正在恢复连接，可点击麦克风停止")
        ConnectionPhase.FAILED -> return Caption("Connection failed.", "连接未能恢复 · 请按提示处理后手动开始")
        ConnectionPhase.STOPPED -> return if (visionEnabled) Caption("Camera preview is on.", "仅预览画面 · 点击麦克风开始对话")
            else Caption("Ready when you are.", "点击麦克风开始对话 · 可按需开启摄像头")
        ConnectionPhase.READY -> Unit
    }
    return when (this) {
    AgentState.IDLE_OBSERVING -> when {
        visionEnabled -> Caption("Camera preview is on.", "仅预览画面 · 点击麦克风开始语音与视觉对话")
        else -> Caption("Ready when you are.", "点击麦克风开始对话 · 可按需开启摄像头")
    }
    AgentState.LISTENING -> Caption(
        "Listening… Speak anytime to interrupt.",
        "聆听中 · 随时开口即可打断",
    )
    AgentState.PROCESSING -> Caption(
        "Thinking… fusing multimodal context.",
        "正在思考 · 回答会实时显示在这里",
    )
    AgentState.SPEAKING -> Caption(
        "Speaking… barge-in enabled.",
        "语音播报中 · 支持抢占打断",
    )
    AgentState.INTERRUPTED -> Caption(
        "Interrupted — recalibrating.",
        "已打断回答 · 请继续说话",
    )
}
}

/** 光球下方字幕：保留状态标签，流式呈现最近五轮问答，长内容可滚动。 */
@Composable
fun StatusCaption(
    state: AgentState,
    modifier: Modifier = Modifier,
    session: SessionUiState = SessionUiState(),
    visionEnabled: Boolean = false,
    conversationTurns: List<ConversationTurn> = emptyList(),
    userText: String = "",
    assistantText: String = "",
    assistantInterrupted: Boolean = false,
    errorMessage: String? = null,
    onRecoveryClick: () -> Unit = {},
) {
    val accent by animateColorAsState(state.accent(), spring(stiffness = 400f), label = "caption-accent")
    val caption = state.caption(session, visionEnabled)
    val recentTurns = conversationTurns.filter { it.userText.isNotBlank() || it.assistantText.isNotBlank() }.takeLast(5)
    val hasConversation = recentTurns.isNotEmpty() || userText.isNotBlank() || assistantText.isNotBlank()
    val scroll = rememberScrollState()
    val isDragged by scroll.interactionSource.collectIsDraggedAsState()
    var followLatest by remember { mutableStateOf(true) }
    var previousTurns by remember { mutableStateOf<List<ConversationTurn>>(emptyList()) }
    LaunchedEffect(scroll, hasConversation) {
        if (hasConversation) {
            // Activity 重建后等新布局完成，再定位到最新回答。
            withFrameNanos { }
            scroll.scrollTo(scroll.maxValue)
        }
    }
    LaunchedEffect(recentTurns, userText, assistantText, errorMessage) {
        // 新问题显示到最新轮；回答增量不改变用户主动上滑后的选择。
        val newTurn = recentTurns.isNotEmpty() && previousTurns.isNotEmpty() &&
            (recentTurns.size > previousTurns.size || recentTurns.dropLast(1) != previousTurns.dropLast(1))
        previousTurns = recentTurns
        if (newTurn && !isDragged) followLatest = true
    }
    LaunchedEffect(isDragged) {
        if (isDragged) {
            followLatest = false
        } else if (scroll.maxValue - scroll.value <= FOLLOW_BOTTOM_THRESHOLD_PX) {
            followLatest = true
        }
    }
    LaunchedEffect(scroll) {
        // 手动滚回底部后恢复跟随；内容增长本身不会把跟随状态误判为 false。
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            if (!isDragged && max - value <= FOLLOW_BOTTOM_THRESHOLD_PX) followLatest = true
        }
    }
    LaunchedEffect(scroll, followLatest, isDragged) {
        if (followLatest && !isDragged) {
            // maxValue 在布局完成后才更新；独立收集避免快速流式增量反复取消滚动任务。
            snapshotFlow { scroll.maxValue }.collect { max -> scroll.scrollTo(max) }
        }
    }
    Column(
        modifier = modifier.padding(horizontal = 40.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (session.phase == ConnectionPhase.READY) state.name else session.phase.name,
            color = accent,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp,
            modifier = Modifier
                .background(accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .border(1.dp, accent.copy(alpha = 0.34f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
        if (!session.message.isNullOrBlank() && errorMessage == null) {
            Text(session.message, color = InkFaint, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        if (errorMessage != null) {
            Text(
                errorMessage,
                color = WarnRed,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            val recovery = session.recovery
            if (recovery != null && recovery.action != RecoveryAction.NONE &&
                (session.phase == ConnectionPhase.READY || !session.phase.active)
            ) {
                TextButton(onClick = onRecoveryClick) { Text(recovery.actionLabel, color = accent, fontSize = 12.sp) }
            }
        }
        if (hasConversation && session.phase != ConnectionPhase.READY && errorMessage == null) {
            Text(caption.sub, color = InkFaint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        if (hasConversation) {
            Column(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .heightIn(max = if (errorMessage != null) 100.dp else 180.dp)
                    .verticalScroll(scroll),
            ) {
                if (recentTurns.isEmpty()) {
                    // 兼容只传当前轮字幕的预览和既有调用方。
                    CaptionTurn(userText, assistantText, assistantInterrupted, accent)
                } else {
                    recentTurns.forEachIndexed { index, turn ->
                        CaptionTurn(
                            turn.userText,
                            turn.assistantText,
                            turn.assistantInterrupted,
                            accent,
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp),
                        )
                    }
                }
            }
        } else if (errorMessage == null) {
            Text(
                text = caption.main,
                color = Ink,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = caption.sub,
                color = InkFaint,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun CaptionTurn(
    userText: String,
    assistantText: String,
    assistantInterrupted: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (userText.isNotBlank()) {
            Text("你", color = InkFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Text(userText, color = InkFaint, fontSize = 13.sp)
        }
        if (assistantText.isNotBlank()) {
            Text(
                text = if (assistantInterrupted) "助手 · 已打断" else "助手",
                color = accent,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(assistantText, color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

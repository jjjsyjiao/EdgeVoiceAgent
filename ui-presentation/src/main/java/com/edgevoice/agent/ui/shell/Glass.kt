package com.edgevoice.agent.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.edgevoice.agent.ui.theme.Glass
import com.edgevoice.agent.ui.theme.GlassLine

/**
 * 毛玻璃表面（Glassmorphism）。
 *
 * 真模糊（blur）在 RenderNode 上代价高且老版本行为不一，这里以设计稿同色值的半透明底 +
 * 1px 描边表达「毛玻璃卡片」语义；其下层是深空渐变，视觉与 index.html 一致。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(16.dp),
    background: Color = Glass,
    borderColor: Color = GlassLine,
): Modifier = this
    .background(background, shape)
    .border(width = 1.dp, color = borderColor, shape = shape)

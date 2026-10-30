package com.edgevoice.agent.ui.drawer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * RTT 实时折线图（对齐设计稿 #rttChart）。
 *
 * 纯函数式绘制：入参即历史数据快照，不持有任何状态。
 * 网格 4 等分、渐变面积填充、辉光折线、末端白点。
 */
@Composable
fun RttLineChart(
    history: List<Long>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 背景网格
        val grid = Color.White.copy(alpha = 0.07f)
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (history.size < 2) return@Canvas

        val maxV = maxOf(90f, (history.maxOrNull()?.toFloat() ?: 0f) * 1.2f)
        val minV = 0f
        val stepX = w / (history.size - 1)
        fun toY(v: Long) = h - ((v - minV) / (maxV - minV)).coerceIn(0f, 1f) * h

        val linePath = Path()
        val fillPath = Path()
        history.forEachIndexed { i, v ->
            val x = i * stepX
            val y = toY(v)
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        drawPath(
            fillPath,
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.34f), Color.Transparent),
            ),
        )
        drawPath(
            linePath,
            color = accent,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // 末端数据点
        drawCircle(
            color = Color.White,
            radius = 3.dp.toPx(),
            center = Offset(w, toY(history.last())),
        )
    }
}

package com.pdfnote.app.ink

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle

/** 표면 좌표계(이미 변환이 적용된 DrawScope)에 획들을 그린다 */
fun DrawScope.drawInk(strokes: List<Stroke>, current: Stroke?, cursor: EraserCursor?) {
    for (s in strokes) drawStroke(s)
    if (current != null) drawStroke(current)
    if (cursor != null) {
        drawCircle(Color(0x22000000), cursor.radius, cursor.center)
        drawCircle(
            Color(0x99000000), cursor.radius, cursor.center,
            style = StrokeStyle(width = cursor.radius * 0.08f),
        )
    }
}

private fun DrawScope.drawStroke(s: Stroke) {
    val pts = s.points
    if (pts.isEmpty()) return
    val color = Color(s.color)
    val highlighter = s.tool == InkTool.HIGHLIGHTER
    val alpha = if (highlighter) 0.45f else 1f
    val blend = if (highlighter) BlendMode.Multiply else BlendMode.SrcOver

    if (pts.size == 1) {
        drawCircle(color, s.width / 2f, pts[0], alpha = alpha, blendMode = blend)
        return
    }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        if (pts.size == 2) {
            lineTo(pts[1].x, pts[1].y)
        } else {
            // 중점을 잇는 2차 곡선으로 부드럽게
            for (i in 1 until pts.size - 1) {
                val mid = (pts[i] + pts[i + 1]) / 2f
                quadraticTo(pts[i].x, pts[i].y, mid.x, mid.y)
            }
            val last = pts.last()
            lineTo(last.x, last.y)
        }
    }
    drawPath(
        path = path,
        color = color,
        alpha = alpha,
        style = StrokeStyle(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = blend,
    )
}

package com.pdfnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfnote.app.ink.EraserMode
import com.pdfnote.app.ink.InkMode
import com.pdfnote.app.ink.InkStore
import com.pdfnote.app.ink.InkTools

/** 필기 툴바: 도구 선택 + 도구별 옵션 + 되돌리기 */
@Composable
fun InkToolbar(tools: InkTools, ink: InkStore, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Color(0xFFF7F7F9))) {
        HorizontalDivider(color = Color(0xFFE0E0E4))

        // 1줄: 도구 + 되돌리기
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModeChip("손", InkMode.HAND, tools)
            ModeChip("펜", InkMode.PEN, tools)
            ModeChip("형광펜", InkMode.HIGHLIGHTER, tools)
            ModeChip("지우개", InkMode.ERASER, tools)
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp), color = Color(0xFFD0D0D4))
            TextButton(onClick = { ink.undo() }, enabled = ink.canUndo) { Text("↶ 실행취소") }
            TextButton(onClick = { ink.redo() }, enabled = ink.canRedo) { Text("↷") }
        }

        // 2줄: 도구별 옵션
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (tools.mode) {
                InkMode.HAND -> Text(
                    "펜/형광펜을 고르면 S펜으로 필기, 손가락은 스크롤 · S펜 버튼을 누른 채 그으면 지우개",
                    color = Color(0xFF8A8A8A),
                    fontSize = 12.sp,
                )
                InkMode.PEN -> {
                    FilterChip(
                        selected = tools.straightLine,
                        onClick = { tools.straightLine = !tools.straightLine },
                        label = { Text("직선") },
                    )
                    for (c in InkTools.PEN_COLORS) {
                        ColorDot(c, selected = tools.penColor == c) { tools.penColor = c }
                    }
                    InkTools.PEN_WIDTHS.forEachIndexed { i, w ->
                        FilterChip(
                            selected = tools.penWidth == w,
                            onClick = { tools.penWidth = w },
                            label = { Text(InkTools.WIDTH_LABELS[i]) },
                        )
                    }
                    FingerChip(tools)
                }
                InkMode.HIGHLIGHTER -> {
                    FilterChip(
                        selected = tools.straightLine,
                        onClick = { tools.straightLine = !tools.straightLine },
                        label = { Text("직선") },
                    )
                    for (c in InkTools.HIGHLIGHTER_COLORS) {
                        ColorDot(c, selected = tools.highlighterColor == c) { tools.highlighterColor = c }
                    }
                    InkTools.HIGHLIGHTER_WIDTHS.forEachIndexed { i, w ->
                        FilterChip(
                            selected = tools.highlighterWidth == w,
                            onClick = { tools.highlighterWidth = w },
                            label = { Text(InkTools.WIDTH_LABELS[i]) },
                        )
                    }
                    FingerChip(tools)
                }
                InkMode.ERASER -> {
                    FilterChip(
                        selected = tools.eraserMode == EraserMode.STROKE,
                        onClick = { tools.eraserMode = EraserMode.STROKE },
                        label = { Text("획 지우기") },
                    )
                    FilterChip(
                        selected = tools.eraserMode == EraserMode.AREA,
                        onClick = { tools.eraserMode = EraserMode.AREA },
                        label = { Text("영역 지우기") },
                    )
                    FingerChip(tools)
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, mode: InkMode, tools: InkTools) {
    FilterChip(
        selected = tools.mode == mode,
        onClick = { tools.mode = mode },
        label = { Text(label) },
    )
}

@Composable
private fun FingerChip(tools: InkTools) {
    FilterChip(
        selected = tools.fingerDraws,
        onClick = { tools.fingerDraws = !tools.fingerDraws },
        label = { Text("손가락 필기") },
    )
}

@Composable
private fun ColorDot(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

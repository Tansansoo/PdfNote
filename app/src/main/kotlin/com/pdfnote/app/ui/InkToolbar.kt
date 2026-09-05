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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // 색 선택 창을 어느 도구용으로 열었는지 (null이면 닫힘)
    var pickerFor by remember { mutableStateOf<InkMode?>(null) }

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
                InkMode.PEN -> PenOptions(
                    tools = tools,
                    highlighter = false,
                    onOpenPicker = { pickerFor = InkMode.PEN },
                )
                InkMode.HIGHLIGHTER -> PenOptions(
                    tools = tools,
                    highlighter = true,
                    onOpenPicker = { pickerFor = InkMode.HIGHLIGHTER },
                )
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

    pickerFor?.let { mode ->
        val highlighter = mode == InkMode.HIGHLIGHTER
        ColorPickerDialog(
            title = if (highlighter) "형광펜 색" else "펜 색",
            initial = if (highlighter) tools.highlighterColor else tools.penColor,
            palette = tools.paletteFor(highlighter),
            onPick = { c ->
                if (highlighter) tools.highlighterColor = c else tools.penColor = c
                pickerFor = null
            },
            onSaveToPalette = { c -> tools.addToPalette(highlighter, c) },
            onRemoveFromPalette = { c -> tools.removeFromPalette(highlighter, c) },
            onDismiss = { pickerFor = null },
        )
    }
}

/** 펜/형광펜 공통 옵션: 직선, 색(기본 + 저장한 색 + 추가), 굵기, 손가락 필기 */
@Composable
private fun PenOptions(tools: InkTools, highlighter: Boolean, onOpenPicker: () -> Unit) {
    val current = if (highlighter) tools.highlighterColor else tools.penColor
    val defaults = if (highlighter) InkTools.HIGHLIGHTER_COLORS else InkTools.PEN_COLORS
    val widths = if (highlighter) InkTools.HIGHLIGHTER_WIDTHS else InkTools.PEN_WIDTHS
    val currentWidth = if (highlighter) tools.highlighterWidth else tools.penWidth
    fun setColor(c: Long) {
        if (highlighter) tools.highlighterColor = c else tools.penColor = c
    }

    FilterChip(
        selected = tools.straightLine,
        onClick = { tools.straightLine = !tools.straightLine },
        label = { Text("직선") },
    )
    for (c in defaults) {
        ColorDot(c, selected = current == c) { setColor(c) }
    }
    val palette = tools.paletteFor(highlighter)
    if (palette.isNotEmpty()) {
        VerticalDivider(Modifier.height(22.dp), color = Color(0xFFD0D0D4))
        for (c in palette) {
            ColorDot(c, selected = current == c) { setColor(c) }
        }
    }
    // 현재 색이 목록에 없으면(색상환으로 고른 색) 그 색도 보여준다
    if (current !in defaults && current !in palette) {
        ColorDot(current, selected = true) { }
    }
    AddColorButton(onClick = onOpenPicker)
    VerticalDivider(Modifier.height(22.dp), color = Color(0xFFD0D0D4))
    widths.forEachIndexed { i, w ->
        FilterChip(
            selected = currentWidth == w,
            onClick = { if (highlighter) tools.highlighterWidth = w else tools.penWidth = w },
            label = { Text(InkTools.WIDTH_LABELS[i]) },
        )
    }
    FingerChip(tools)
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

@Composable
private fun AddColorButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.dp, Color(0xFF9A9AA3), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = Color(0xFF555555), fontSize = 18.sp)
    }
}

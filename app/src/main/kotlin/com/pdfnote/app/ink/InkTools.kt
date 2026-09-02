package com.pdfnote.app.ink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class InkMode { HAND, PEN, HIGHLIGHTER, ERASER }

/** 필기 도구 설정 (툴바 상태) */
class InkTools {
    var mode by mutableStateOf(InkMode.HAND)

    /** 직선 모드: 펜/형광펜이 시작점과 끝점을 잇는 직선을 그린다 */
    var straightLine by mutableStateOf(false)

    var penColor by mutableLongStateOf(PEN_COLORS[0])
    var penWidth by mutableFloatStateOf(PEN_WIDTHS[1])
    var highlighterColor by mutableLongStateOf(HIGHLIGHTER_COLORS[0])
    var highlighterWidth by mutableFloatStateOf(HIGHLIGHTER_WIDTHS[1])
    var eraserMode by mutableStateOf(EraserMode.STROKE)

    /** 켜면 손가락으로도 그린다. 꺼져 있으면 S펜만 그리고 손가락은 스크롤/이동 */
    var fingerDraws by mutableStateOf(false)

    val isInking: Boolean get() = mode != InkMode.HAND

    companion object {
        val PEN_COLORS: List<Long> = listOf(0xFF1E1E1E, 0xFF1F5FD8, 0xFFE03131, 0xFF2B8A3E, 0xFF7048E8)
        val PEN_WIDTHS: List<Float> = listOf(1.5f, 3f, 5f)
        val HIGHLIGHTER_COLORS: List<Long> = listOf(0xFFFFE53B, 0xFF8CFF66, 0xFFFF8BD1, 0xFF7FD3FF)
        val HIGHLIGHTER_WIDTHS: List<Float> = listOf(10f, 16f, 24f)
        val WIDTH_LABELS = listOf("얇게", "보통", "굵게")
    }
}

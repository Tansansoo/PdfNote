package com.pdfnote.app.ink

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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

    /** 사용자가 저장한 색 (펜 / 형광펜 따로) */
    val penPalette = mutableStateListOf<Long>()
    val highlighterPalette = mutableStateListOf<Long>()

    val isInking: Boolean get() = mode != InkMode.HAND

    fun paletteFor(highlighter: Boolean) = if (highlighter) highlighterPalette else penPalette

    fun addToPalette(highlighter: Boolean, color: Long) {
        val list = paletteFor(highlighter)
        if (color !in list) list.add(color)
    }

    fun removeFromPalette(highlighter: Boolean, color: Long) {
        paletteFor(highlighter).remove(color)
    }

    /** 저장 대상 설정 스냅샷 */
    data class Saved(
        val penColor: Long,
        val penWidth: Float,
        val highlighterColor: Long,
        val highlighterWidth: Float,
        val penPalette: List<Long>,
        val highlighterPalette: List<Long>,
        val eraserMode: EraserMode,
        val fingerDraws: Boolean,
        val straightLine: Boolean,
    )

    fun saved() = Saved(
        penColor, penWidth, highlighterColor, highlighterWidth,
        penPalette.toList(), highlighterPalette.toList(),
        eraserMode, fingerDraws, straightLine,
    )

    fun restore(s: Saved) {
        penColor = s.penColor
        penWidth = s.penWidth
        highlighterColor = s.highlighterColor
        highlighterWidth = s.highlighterWidth
        penPalette.clear(); penPalette.addAll(s.penPalette)
        highlighterPalette.clear(); highlighterPalette.addAll(s.highlighterPalette)
        eraserMode = s.eraserMode
        fingerDraws = s.fingerDraws
        straightLine = s.straightLine
    }

    companion object {
        /** 툴바에 항상 보이는 기본 색 */
        val PEN_COLORS: List<Long> = listOf(0xFF1E1E1E, 0xFF1F5FD8, 0xFFE03131, 0xFF2B8A3E, 0xFF7048E8)
        val PEN_WIDTHS: List<Float> = listOf(1.5f, 3f, 5f)
        val HIGHLIGHTER_COLORS: List<Long> = listOf(0xFFFFE53B, 0xFF8CFF66, 0xFFFF8BD1, 0xFF7FD3FF)
        val HIGHLIGHTER_WIDTHS: List<Float> = listOf(10f, 16f, 24f)
        val WIDTH_LABELS = listOf("얇게", "보통", "굵게")

        /** 표준 팔레트 (6열 격자) */
        val STANDARD_PALETTE: List<Long> = listOf(
            0xFF000000, 0xFF424242, 0xFF757575, 0xFF9E9E9E, 0xFFBDBDBD, 0xFFFFFFFF,
            0xFFB71C1C, 0xFFE53935, 0xFFEF5350, 0xFFFF8A80, 0xFF3E2723, 0xFF6D4C41,
            0xFFE65100, 0xFFFB8C00, 0xFFFFB74D, 0xFFFFD180, 0xFFA1887F, 0xFFD7CCC8,
            0xFFF9A825, 0xFFFDD835, 0xFFFFEE58, 0xFFFFFF8D, 0xFF827717, 0xFFC0CA33,
            0xFF1B5E20, 0xFF43A047, 0xFF66BB6A, 0xFFB9F6CA, 0xFF006064, 0xFF00ACC1,
            0xFF4DD0E1, 0xFF84FFFF, 0xFF0D47A1, 0xFF1E88E5, 0xFF64B5F6, 0xFF82B1FF,
            0xFF4A148C, 0xFF8E24AA, 0xFFBA68C8, 0xFFEA80FC, 0xFF880E4F, 0xFFD81B60,
            0xFFF06292, 0xFFFF80AB, 0xFF37474F, 0xFF607D8B, 0xFF90A4AE, 0xFFCFD8DC,
        )
    }
}

/** 도구 설정을 SharedPreferences에 저장/복원 */
object InkPrefs {
    private const val NAME = "ink_tools"

    fun load(context: Context): InkTools.Saved? {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (!p.contains("penColor")) return null
        return runCatching {
            InkTools.Saved(
                penColor = p.getLong("penColor", InkTools.PEN_COLORS[0]),
                penWidth = p.getFloat("penWidth", InkTools.PEN_WIDTHS[1]),
                highlighterColor = p.getLong("hlColor", InkTools.HIGHLIGHTER_COLORS[0]),
                highlighterWidth = p.getFloat("hlWidth", InkTools.HIGHLIGHTER_WIDTHS[1]),
                penPalette = parseList(p.getString("penPalette", "")),
                highlighterPalette = parseList(p.getString("hlPalette", "")),
                eraserMode = runCatching { EraserMode.valueOf(p.getString("eraserMode", "STROKE") ?: "STROKE") }
                    .getOrDefault(EraserMode.STROKE),
                fingerDraws = p.getBoolean("fingerDraws", false),
                straightLine = p.getBoolean("straightLine", false),
            )
        }.getOrNull()
    }

    fun save(context: Context, s: InkTools.Saved) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putLong("penColor", s.penColor)
            .putFloat("penWidth", s.penWidth)
            .putLong("hlColor", s.highlighterColor)
            .putFloat("hlWidth", s.highlighterWidth)
            .putString("penPalette", s.penPalette.joinToString(","))
            .putString("hlPalette", s.highlighterPalette.joinToString(","))
            .putString("eraserMode", s.eraserMode.name)
            .putBoolean("fingerDraws", s.fingerDraws)
            .putBoolean("straightLine", s.straightLine)
            .apply()
    }

    private fun parseList(s: String?): List<Long> =
        s.orEmpty().split(",").mapNotNull { it.trim().toLongOrNull() }
}

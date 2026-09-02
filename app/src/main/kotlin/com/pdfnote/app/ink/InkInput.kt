@file:OptIn(ExperimentalComposeUiApi::class)

package com.pdfnote.app.ink

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import java.util.UUID

/**
 * 화면 픽셀 좌표 ↔ 필기 표면 좌표 변환.
 * @param origin 표면 원점의 화면상 위치 (이 modifier가 붙은 노드 기준 px)
 * @param unitsPerPx 픽셀 1개가 표면 단위 몇 개인지
 * @param clip 표면 좌표 범위 (페이지 밖으로 나가지 않게), null이면 무제한
 */
class SurfaceTransform(
    val key: String,
    val origin: Offset,
    val unitsPerPx: Float,
    val clip: Rect?,
) {
    fun toUnits(p: Offset): Offset {
        val u = (p - origin) * unitsPerPx
        val c = clip ?: return u
        return Offset(u.x.coerceIn(c.left, c.right), u.y.coerceIn(c.top, c.bottom))
    }
}

private const val HOLD_TO_STRAIGHTEN_MS = 550L
private const val MIN_STRAIGHTEN_LENGTH_PX = 40f
private const val MOVE_SLOP_PX = 4f

/** S펜 측면 버튼(또는 다른 스타일러스 버튼)이 눌렸는지 */
private fun PointerEvent.stylusButtonPressed(): Boolean =
    buttons.isPrimaryPressed || buttons.isSecondaryPressed || buttons.isTertiaryPressed

/**
 * 필기 입력. 이 modifier를 스크롤/제스처보다 바깥(앞)에 두면 Initial 패스에서 먼저 받아
 * 필기 이벤트만 소비하고, 나머지(손가락 스크롤 등)는 그대로 흘려보낸다.
 *
 * 규칙:
 * - S펜 측면 버튼을 누른 채 긋기 / 지우개 팁: 어떤 모드에서든 지우개
 * - 손 모드: 아무것도 하지 않는다
 * - 펜/형광펜/지우개 모드: S펜은 그리고, 손가락은 fingerDraws가 켜진 경우에만 그린다
 */
fun Modifier.inkInput(
    tools: InkTools,
    ink: InkStore,
    eraserRadiusPx: Float,
    haptic: HapticFeedback,
    resolve: (Offset) -> SurfaceTransform?,
): Modifier = pointerInput(tools, ink, eraserRadiusPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val isStylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
        val hardwareEraser = down.type == PointerType.Eraser ||
            (isStylus && currentEvent.stylusButtonPressed())
        val canDraw = isStylus || tools.fingerDraws
        val erase = when {
            hardwareEraser -> true
            !canDraw || tools.mode == InkMode.HAND -> return@awaitEachGesture
            tools.mode == InkMode.ERASER -> true
            else -> false
        }
        val surface = resolve(down.position) ?: return@awaitEachGesture
        down.consume()
        if (erase) eraseGesture(down, surface, tools, ink, eraserRadiusPx)
        else drawGesture(down, surface, tools, ink, haptic)
    }
}

private suspend fun AwaitPointerEventScope.drawGesture(
    down: PointerInputChange,
    surface: SurfaceTransform,
    tools: InkTools,
    ink: InkStore,
    haptic: HapticFeedback,
) {
    val highlighter = tools.mode == InkMode.HIGHLIGHTER
    val tool = if (highlighter) InkTool.HIGHLIGHTER else InkTool.PEN
    val color = if (highlighter) tools.highlighterColor else tools.penColor
    val width = if (highlighter) tools.highlighterWidth else tools.penWidth
    val start = surface.toUnits(down.position)
    val minStepUnits = 1.5f * surface.unitsPerPx

    var points = ArrayList<Offset>().apply { add(start) }
    var straight = tools.straightLine
    var lastMovePos = down.position
    var lastMoveTime = System.currentTimeMillis()

    ink.beginStroke(surface.key, Stroke(UUID.randomUUID().toString(), tool, color, width, listOf(start)))

    fun lengthPx() = InkGeometry.pathLength(points) / surface.unitsPerPx
    fun addPoint(u: Offset) {
        if ((u - points.last()).getDistance() >= minStepUnits) points.add(u)
    }
    fun straighten() {
        straight = true
        if (points.size >= 2) {
            points = arrayListOf(points.first(), points.last())
            ink.updateStroke(points.toList())
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    while (true) {
        // 잠시 멈추면(이벤트가 없으면) 직선으로 편다
        val event = withTimeoutOrNull(HOLD_TO_STRAIGHTEN_MS) { awaitPointerEvent(PointerEventPass.Initial) }
        val now = System.currentTimeMillis()
        if (event == null) {
            if (!straight && lengthPx() >= MIN_STRAIGHTEN_LENGTH_PX) straighten()
            continue
        }
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null) {
            ink.endStroke()
            return
        }
        change.consume()
        if (!change.pressed) {
            ink.endStroke()
            return
        }
        val pos = change.position
        if ((pos - lastMovePos).getDistance() > MOVE_SLOP_PX) {
            lastMovePos = pos
            lastMoveTime = now
        } else if (!straight && now - lastMoveTime >= HOLD_TO_STRAIGHTEN_MS && lengthPx() >= MIN_STRAIGHTEN_LENGTH_PX) {
            straighten()
        }
        if (straight) {
            points = arrayListOf(points.first(), surface.toUnits(pos))
        } else {
            for (h in change.historical) addPoint(surface.toUnits(h.position))
            addPoint(surface.toUnits(pos))
        }
        ink.updateStroke(points.toList())
    }
}

private suspend fun AwaitPointerEventScope.eraseGesture(
    down: PointerInputChange,
    surface: SurfaceTransform,
    tools: InkTools,
    ink: InkStore,
    eraserRadiusPx: Float,
) {
    ink.beginErase(surface.key, eraserRadiusPx * surface.unitsPerPx)
    ink.eraseAt(surface.toUnits(down.position), tools.eraserMode)
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        if (!change.pressed) break
        for (h in change.historical) ink.eraseAt(surface.toUnits(h.position), tools.eraserMode)
        ink.eraseAt(surface.toUnits(change.position), tools.eraserMode)
    }
    ink.endErase()
}

package com.pdfnote.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pdfnote.app.model.Selection
import com.pdfnote.app.pdf.MuPdfDocument
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val MAX_RENDER_WIDTH_PX = 2600
private val PAGE_GAP = 8.dp
private val PAGE_MARGIN = 8.dp

/** 특정 페이지/영역으로 이동 요청. nonce는 같은 곳으로 다시 이동할 때도 효과가 재실행되게 한다. */
data class JumpTarget(val pageIndex: Int, val rect: Rect?, val nonce: Long)

/** 뷰어 상태: 스크롤, 현재 선택 영역, 강조 표시, 이동 요청 */
class PdfViewerState {
    val listState = LazyListState()
    var selection by mutableStateOf<Selection?>(null)
    var highlight by mutableStateOf<Selection?>(null)
    var jumpTarget by mutableStateOf<JumpTarget?>(null)

    fun jumpTo(pageIndex: Int, rect: Rect?) {
        jumpTarget = JumpTarget(pageIndex, rect, System.nanoTime())
    }
}

/** 발췌 드래그 이벤트 (좌표는 루트 좌표계, px) */
class ExcerptDragCallbacks(
    val onStart: (Selection, Offset) -> Unit,
    val onMove: (Offset) -> Unit,
    val onEnd: (Offset) -> Unit,
    val onCancel: () -> Unit,
)

/**
 * 세로 스크롤 PDF 뷰어.
 * - 한 손가락: 스크롤 (세로/가로)
 * - 두 손가락: 핀치 줌 (1x ~ 5x)
 * - 길게 누르고 끌기: 영역 선택 / 선택 영역 안에서 길게 누르고 끌기: 워크스페이스로 드래그
 */
@Composable
fun PdfViewer(
    doc: MuPdfDocument,
    state: PdfViewerState,
    dragCallbacks: ExcerptDragCallbacks,
    onSendSelection: (Selection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = state.listState
    var zoom by remember(doc) { mutableFloatStateOf(1f) }
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BoxWithConstraints(modifier.background(Color(0xFFE8E8EC))) {
        val containerWidth = maxWidth
        val containerWidthPx = with(density) { containerWidth.toPx() }
        val contentWidth = containerWidth * zoom
        val pageWidth = contentWidth - PAGE_MARGIN * 2

        // 줌 단계에 따른 렌더링 해상도 (재렌더링 횟수를 줄이기 위해 양자화)
        val renderScale = when {
            zoom < 1.5f -> 1f
            zoom < 2.5f -> 2f
            zoom < 3.5f -> 3f
            else -> 4f
        }
        val renderWidthPx = (containerWidthPx * renderScale).roundToInt().coerceAtMost(MAX_RENDER_WIDTH_PX)

        // 각 페이지의 화면상 높이(px) — 줌 중심 보정 계산에 사용
        val gapPx = with(density) { PAGE_GAP.toPx() }
        val pageWidthPx = with(density) { pageWidth.toPx() }
        fun pageHeightPx(i: Int) = pageWidthPx * doc.pageSizes[i].aspect

        // 이동 요청 처리: 해당 페이지/영역으로 스크롤 후 잠시 강조
        LaunchedEffect(state.jumpTarget) {
            val target = state.jumpTarget ?: return@LaunchedEffect
            if (target.pageIndex !in 0 until doc.pageCount) return@LaunchedEffect
            val pxPerPt = pageWidthPx / doc.pageSizes[target.pageIndex].width
            val topPad = with(density) { 24.dp.toPx() }
            val offsetPx = target.rect?.let { (it.top * pxPerPt - topPad).roundToInt().coerceAtLeast(0) } ?: 0
            listState.animateScrollToItem(target.pageIndex, offsetPx)
            state.highlight = target.rect?.let { Selection(target.pageIndex, it) }
            delay(1800)
            if (state.jumpTarget === target) state.highlight = null
        }

        Box(
            Modifier
                .fillMaxSize()
                .pinchZoom { zoomChange, centroid ->
                    val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val actual = newZoom / zoom
                    if (actual == 1f) return@pinchZoom
                    // 현재 세로 절대 스크롤 위치 계산
                    val first = listState.firstVisibleItemIndex
                    var absY = listState.firstVisibleItemScrollOffset.toFloat()
                    for (i in 0 until first) absY += pageHeightPx(i) + gapPx
                    val absX = hScroll.value.toFloat()
                    zoom = newZoom
                    // 손가락 중심점이 같은 문서 위치를 가리키도록 스크롤 보정
                    scope.launch {
                        listState.scrollBy((absY + centroid.y) * (actual - 1f))
                        hScroll.scrollBy((absX + centroid.x) * (actual - 1f))
                    }
                }
                .horizontalScroll(hScroll)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(contentWidth)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = PAGE_GAP),
            ) {
                items(count = doc.pageCount, key = { it }) { index ->
                    val size = doc.pageSizes[index]
                    PdfPage(
                        doc = doc,
                        pageIndex = index,
                        renderWidthPx = renderWidthPx,
                        pageWidthPx = pageWidthPx,
                        selection = state.selection,
                        highlight = state.highlight,
                        onSelectionChange = { state.selection = it },
                        dragCallbacks = dragCallbacks,
                        onSend = onSendSelection,
                        modifier = Modifier
                            .padding(bottom = PAGE_GAP)
                            .size(pageWidth, pageWidth * size.aspect),
                    )
                }
            }
        }

        // 페이지 번호 표시
        val currentPage = listState.firstVisibleItemIndex + 1
        Text(
            text = "$currentPage / ${doc.pageCount}",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private fun rectOf(a: Offset, b: Offset) =
    Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

/**
 * 한 페이지. 렌더링이 끝날 때까지는 흰 바탕만 보여주고, 해상도가 바뀌면 이전 비트맵을 유지한 채 교체한다.
 * 길게 누르고 끌면 영역을 선택하고, 선택 영역 안에서 다시 길게 누르고 끌면 발췌 드래그가 시작된다.
 */
@Composable
private fun PdfPage(
    doc: MuPdfDocument,
    pageIndex: Int,
    renderWidthPx: Int,
    pageWidthPx: Float,
    selection: Selection?,
    highlight: Selection?,
    onSelectionChange: (Selection?) -> Unit,
    dragCallbacks: ExcerptDragCallbacks,
    onSend: (Selection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageSize = doc.pageSizes[pageIndex]
    val pxPerPt = pageWidthPx / pageSize.width
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var bitmap by remember(doc, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentSelection by rememberUpdatedState(selection)
    val currentCallbacks by rememberUpdatedState(dragCallbacks)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)

    LaunchedEffect(doc, pageIndex, renderWidthPx) {
        runCatching { doc.renderPage(pageIndex, renderWidthPx) }
            .onSuccess { bitmap = it }
    }

    val mySelection = selection?.takeIf { it.pageIndex == pageIndex }
    val myHighlight = highlight?.takeIf { it.pageIndex == pageIndex }

    Box(
        modifier
            .shadow(2.dp, RoundedCornerShape(2.dp))
            .background(Color.White)
            .onGloballyPositioned { coords = it }
            .pointerInput(pageIndex, pxPerPt) {
                var anchor = Offset.Zero
                var dragging = false
                var lastRoot = Offset.Zero
                fun toRoot(p: Offset): Offset = coords?.localToRoot(p) ?: p
                fun clampPt(p: Offset) = Offset(
                    p.x.coerceIn(0f, pageSize.width),
                    p.y.coerceIn(0f, pageSize.height),
                )
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val sel = currentSelection
                        val pt = pos / pxPerPt
                        if (sel != null && sel.pageIndex == pageIndex && sel.rect.contains(pt)) {
                            dragging = true
                            lastRoot = toRoot(pos)
                            currentCallbacks.onStart(sel, lastRoot)
                        } else {
                            dragging = false
                            anchor = clampPt(pt)
                            currentOnSelectionChange(Selection(pageIndex, rectOf(anchor, anchor)))
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (dragging) {
                            lastRoot = toRoot(change.position)
                            currentCallbacks.onMove(lastRoot)
                        } else {
                            val p = clampPt(change.position / pxPerPt)
                            currentOnSelectionChange(Selection(pageIndex, rectOf(anchor, p)))
                        }
                    },
                    onDragEnd = {
                        if (dragging) {
                            dragging = false
                            currentCallbacks.onEnd(lastRoot)
                        } else {
                            val s = currentSelection
                            if (s != null && (s.rect.width < 6f || s.rect.height < 6f)) {
                                currentOnSelectionChange(null)
                            }
                        }
                    },
                    onDragCancel = {
                        if (dragging) {
                            dragging = false
                            currentCallbacks.onCancel()
                        } else {
                            currentOnSelectionChange(null)
                        }
                    },
                )
            }
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "${pageIndex + 1} 페이지",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }

        // 이동 후 강조 표시
        myHighlight?.let { h ->
            val r = h.rect
            Box(
                Modifier
                    .offset { IntOffset((r.left * pxPerPt).roundToInt(), (r.top * pxPerPt).roundToInt()) }
                    .size(
                        with(density) { (r.width * pxPerPt).toDp() },
                        with(density) { (r.height * pxPerPt).toDp() },
                    )
                    .background(Color(0x66FFD54F))
                    .border(2.dp, Color(0xFFFFB300)),
            )
        }

        // 선택 영역과 동작 버튼
        mySelection?.let { s ->
            val r = s.rect
            val left = (r.left * pxPerPt).roundToInt()
            val top = (r.top * pxPerPt).roundToInt()
            val w = (r.width * pxPerPt).roundToInt()
            val h = (r.height * pxPerPt).roundToInt()
            Box(
                Modifier
                    .offset { IntOffset(left, top) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .background(Color(0x332F6FE0))
                    .border(1.5.dp, Color(0xFF2F6FE0)),
            )
            if (w > 6 && h > 6) {
                val rowH = with(density) { 44.dp.toPx() }.roundToInt()
                val gap = with(density) { 6.dp.toPx() }.roundToInt()
                val pageH = (pageSize.height * pxPerPt).roundToInt()
                val rowTop = when {
                    top + h + gap + rowH <= pageH -> top + h + gap
                    top - gap - rowH >= 0 -> top - gap - rowH
                    else -> top + gap
                }
                Row(
                    Modifier
                        .offset { IntOffset(left, rowTop) }
                        .background(Color(0xEE1F1F24), RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp),
                ) {
                    TextButton(onClick = { onSend(s) }) {
                        Text("워크스페이스로 보내기", color = Color.White)
                    }
                    TextButton(onClick = { onSelectionChange(null) }) {
                        Text("취소", color = Color(0xFFBBBBBB))
                    }
                }
            }
        }
    }
}

/**
 * 두 손가락 핀치 줌 감지.
 * Initial 패스에서 두 손가락 이벤트를 먼저 가로채 소비하므로 LazyColumn 스크롤과 충돌하지 않는다.
 * 한 손가락 이벤트는 건드리지 않아 스크롤이 그대로 동작한다.
 */
private fun Modifier.pinchZoom(onZoom: (zoomChange: Float, centroid: Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val zoomChange = event.calculateZoom()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (zoomChange != 1f && centroid != Offset.Unspecified) {
                        onZoom(zoomChange, centroid)
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }

package com.pdfnote.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.horizontalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pdfnote.app.pdf.MuPdfDocument
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val MAX_RENDER_WIDTH_PX = 2600
private val PAGE_GAP = 8.dp
private val PAGE_MARGIN = 8.dp

/**
 * 세로 스크롤 PDF 뷰어.
 * - 한 손가락: 스크롤 (세로/가로)
 * - 두 손가락: 핀치 줌 (1x ~ 5x)
 */
@Composable
fun PdfViewer(
    doc: MuPdfDocument,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
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

/** 한 페이지. 렌더링이 끝날 때까지는 흰 바탕만 보여주고, 해상도가 바뀌면 이전 비트맵을 유지한 채 교체한다. */
@Composable
private fun PdfPage(
    doc: MuPdfDocument,
    pageIndex: Int,
    renderWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(doc, pageIndex) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(doc, pageIndex, renderWidthPx) {
        runCatching { doc.renderPage(pageIndex, renderWidthPx) }
            .onSuccess { bitmap = it }
    }

    Box(
        modifier
            .shadow(2.dp, RoundedCornerShape(2.dp))
            .background(Color.White)
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "${pageIndex + 1} 페이지",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
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

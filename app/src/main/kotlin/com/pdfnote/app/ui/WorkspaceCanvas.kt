package com.pdfnote.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfnote.app.model.ExcerptItem
import com.pdfnote.app.model.NoteItem
import com.pdfnote.app.model.WorkItem
import kotlin.math.roundToInt

private const val MIN_CANVAS_ZOOM = 0.3f
private const val MAX_CANVAS_ZOOM = 4f
private val NOTE_HEADER_HEIGHT = 22.dp

/** 워크스페이스 캔버스의 상태: 항목 목록, 뷰 변환(이동/줌), 선택 */
class WorkspaceState(private val onRemoved: (WorkItem) -> Unit = {}) {
    val items = mutableStateListOf<WorkItem>()
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var selectedId by mutableStateOf<String?>(null)

    /** 캔버스가 화면(루트 좌표계)에서 차지하는 영역 */
    var bounds by mutableStateOf(Rect.Zero)

    fun containsRoot(rootPos: Offset): Boolean = bounds.contains(rootPos)

    /** 루트 좌표(px) → 캔버스 좌표(dp) */
    fun rootToCanvasDp(rootPos: Offset, density: Float): Offset {
        val local = rootPos - bounds.topLeft - offset
        return local / (scale * density)
    }

    /** 화면에 보이는 캔버스 영역의 중심(캔버스 dp 좌표) */
    fun viewportCenterDp(density: Float): Offset = rootToCanvasDp(bounds.center, density)

    fun add(item: WorkItem) {
        items.add(item)
        selectedId = item.id
    }

    fun move(id: String, dxDp: Float, dyDp: Float) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].movedBy(dxDp, dyDp)
    }

    fun remove(id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) {
            val removed = items.removeAt(i)
            onRemoved(removed)
        }
        if (selectedId == id) selectedId = null
    }

    fun updateNote(id: String, text: String) {
        val i = items.indexOfFirst { it.id == id }
        val item = items.getOrNull(i) as? NoteItem ?: return
        items[i] = item.copy(text = text)
    }

    fun resetView() {
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * 무한 캔버스 워크스페이스.
 * - 빈 곳 한 손가락 드래그: 이동 / 두 손가락: 줌
 * - 카드 드래그: 카드 이동 / 카드 탭: 선택 (발췌 카드는 원본 페이지로 이동)
 */
@Composable
fun WorkspaceCanvas(
    state: WorkspaceState,
    loadImage: suspend (ExcerptItem) -> Bitmap?,
    onJump: (ExcerptItem) -> Unit,
    dropHover: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density

    Box(
        modifier
            .clipToBounds()
            .background(Color(0xFFF4F1EA))
            .onGloballyPositioned { state.bounds = it.boundsInRoot() }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (state.scale * zoom).coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
                    val k = newScale / state.scale
                    state.offset = (state.offset - centroid) * k + centroid + pan
                    state.scale = newScale
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { state.selectedId = null })
            }
    ) {
        // 배경 격자
        Canvas(Modifier.matchParentSize()) {
            val step = 48.dp.toPx() * state.scale
            if (step >= 12f) {
                val lineColor = Color(0x14000000)
                var x = state.offset.x % step
                while (x < size.width) {
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = state.offset.y % step
                while (y < size.height) {
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
            }
        }

        // 카드들. 캔버스 이동/줌은 카드마다 개별 적용한다
        // (거대한 단일 레이어는 고밀도 화면에서 Compose 크기 상한을 넘겨 앱이 죽는다)
        for (item in state.items) {
            key(item.id) {
                WorkItemCard(
                    item = item,
                    state = state,
                    density = density,
                    loadImage = loadImage,
                    onJump = onJump,
                )
            }
        }

        if (state.items.isEmpty()) {
            Text(
                text = "PDF 페이지를 길게 눌러 영역을 선택한 뒤\n이곳으로 끌어다 놓으세요",
                color = Color(0xFF8A8A8A),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        if (dropHover) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(3.dp, Color(0xFF2F6FE0))
                    .background(Color(0x142F6FE0))
            )
        }

        // 줌 표시 / 초기화
        TextButton(
            onClick = { state.resetView() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            Text("${(state.scale * 100).roundToInt()}%", color = Color(0xFF555555))
        }
    }
}

@Composable
private fun WorkItemCard(
    item: WorkItem,
    state: WorkspaceState,
    density: Float,
    loadImage: suspend (ExcerptItem) -> Bitmap?,
    onJump: (ExcerptItem) -> Unit,
) {
    val selected = state.selectedId == item.id
    val current by rememberUpdatedState(item)
    val shape = RoundedCornerShape(6.dp)

    // 카드 이동 제스처
    val dragModifier = Modifier.pointerInput(item.id) {
        detectDragGestures(
            onDragStart = { state.selectedId = item.id },
            onDrag = { change, drag ->
                change.consume()
                val k = state.scale * density
                state.move(item.id, drag.x / k, drag.y / k)
            },
        )
    }

    Box(
        Modifier
            .offset {
                val k = state.scale * density
                IntOffset(
                    (item.x * k + state.offset.x).roundToInt(),
                    (item.y * k + state.offset.y).roundToInt(),
                )
            }
            .graphicsLayer {
                scaleX = state.scale
                scaleY = state.scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .width(item.width.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(if (selected) 8.dp else 2.dp, shape)
                .background(Color.White, shape)
                .then(if (selected) Modifier.border(2.dp, Color(0xFF2F6FE0), shape) else Modifier)
        ) {
            when (val cur = current) {
                is ExcerptItem -> ExcerptCardContent(
                    item = cur,
                    loadImage = loadImage,
                    modifier = dragModifier.pointerInput(item.id) {
                        detectTapGestures(onTap = {
                            state.selectedId = item.id
                            (current as? ExcerptItem)?.let(onJump)
                        })
                    },
                )
                is NoteItem -> NoteCardContent(
                    item = cur,
                    onTextChange = { text -> state.updateNote(item.id, text) },
                    headerModifier = dragModifier.pointerInput(item.id) {
                        detectTapGestures(onTap = { state.selectedId = item.id })
                    },
                )
            }
        }

        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp)
                    .size(24.dp)
                    .background(Color(0xFF444444), CircleShape)
                    .clickable { state.remove(item.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ExcerptCardContent(
    item: ExcerptItem,
    loadImage: suspend (ExcerptItem) -> Bitmap?,
    modifier: Modifier,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.id) {
        value = loadImage(item)
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / item.aspect)
                .background(Color(0xFFF0F0F0))
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "${item.pageIndex + 1}페이지 발췌",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFEEF3FB))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "p.${item.pageIndex + 1}",
                color = Color(0xFF2F6FE0),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "탭하여 이동",
                color = Color(0xFF8A9BB8),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun NoteCardContent(
    item: NoteItem,
    onTextChange: (String) -> Unit,
    headerModifier: Modifier,
) {
    Column {
        // 손잡이: 여기를 잡고 끈다
        Box(
            headerModifier
                .fillMaxWidth()
                .height(NOTE_HEADER_HEIGHT)
                .background(Color(0xFFF2C94C), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(28.dp).height(3.dp).background(Color(0x55000000), CircleShape))
        }
        BasicTextField(
            value = item.text,
            onValueChange = onTextChange,
            textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF222222)),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF7D6))
                .padding(8.dp),
            decorationBox = { inner ->
                if (item.text.isEmpty()) {
                    Text("메모 입력...", color = Color(0xFF9A9A9A), fontSize = 14.sp)
                }
                inner()
            },
        )
    }
}

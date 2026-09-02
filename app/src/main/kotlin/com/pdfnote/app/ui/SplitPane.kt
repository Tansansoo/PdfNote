package com.pdfnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val HANDLE_THICKNESS = 14.dp
private val SIDE_BY_SIDE_MIN_WIDTH = 640.dp

/**
 * 두 영역을 나누는 분할 화면. 넓은 화면은 좌/우, 좁은 화면은 위/아래로 나눈다.
 * 가운데 손잡이를 끌어 비율을 바꿀 수 있다.
 */
@Composable
fun SplitPane(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fraction by remember { mutableFloatStateOf(0.55f) }

    BoxWithConstraints(modifier) {
        val horizontal = maxWidth >= SIDE_BY_SIDE_MIN_WIDTH
        val density = LocalDensity.current
        val totalPx = with(density) { (if (horizontal) maxWidth else maxHeight).toPx() }

        val handleGesture = Modifier.pointerInput(horizontal, totalPx) {
            detectDragGestures { change, drag ->
                change.consume()
                val delta = if (horizontal) drag.x else drag.y
                fraction = (fraction + delta / totalPx).coerceIn(0.2f, 0.8f)
            }
        }

        if (horizontal) {
            Row(Modifier.fillMaxSize()) {
                first(Modifier.weight(fraction).fillMaxHeight())
                Box(
                    handleGesture
                        .width(HANDLE_THICKNESS)
                        .fillMaxHeight()
                        .background(Color(0xFFD9D9DE)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(4.dp).height(36.dp).background(Color(0xFF9A9AA3), CircleShape))
                }
                second(Modifier.weight(1f - fraction).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                first(Modifier.weight(fraction).fillMaxWidth())
                Box(
                    handleGesture
                        .height(HANDLE_THICKNESS)
                        .fillMaxWidth()
                        .background(Color(0xFFD9D9DE)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.height(4.dp).width(36.dp).background(Color(0xFF9A9AA3), CircleShape))
                }
                second(Modifier.weight(1f - fraction).fillMaxWidth())
            }
        }
    }
}

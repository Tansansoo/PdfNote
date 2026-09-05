package com.pdfnote.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfnote.app.ink.InkTools

private fun hsvToLong(h: Float, s: Float, v: Float): Long =
    android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL

private fun longToHsv(color: Long): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toInt(), hsv)
    return hsv
}

private fun hexOf(color: Long): String = "#%06X".format(color and 0xFFFFFF)

private fun parseHex(text: String): Long? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return 0xFF000000 or t.toLong(16)
}

/**
 * 색 선택 창. 표준 팔레트와 사용자 지정(색상환 + 저장한 색) 두 탭.
 * 확인을 누르면 onPick, 저장 버튼은 onSaveToPalette, 저장한 색을 길게 누르면 onRemoveFromPalette.
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Long,
    palette: List<Long>,
    onPick: (Long) -> Unit,
    onSaveToPalette: (Long) -> Unit,
    onRemoveFromPalette: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableLongStateOf(initial) }
    var tab by remember { mutableIntStateOf(if (initial in InkTools.STANDARD_PALETTE) 0 else 1) }
    var confirmRemove by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("표준") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("사용자 지정") })
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (tab == 0) {
                        StandardPalette(selected = selected, onSelect = { selected = it })
                    } else {
                        CustomTab(
                            selected = selected,
                            palette = palette,
                            onSelect = { selected = it },
                            onSave = { onSaveToPalette(selected) },
                            onLongPressSaved = { confirmRemove = it },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 미리보기
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(selected))
                            .border(1.dp, Color(0x33000000), CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(hexOf(selected), fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) { Text("확인") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )

    confirmRemove?.let { color ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("팔레트에서 삭제") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color(color)))
                    Spacer(Modifier.width(8.dp))
                    Text("${hexOf(color)} 색을 삭제할까요?")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveFromPalette(color)
                    confirmRemove = null
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("취소") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StandardPalette(selected: Long, onSelect: (Long) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 6,
    ) {
        for (c in InkTools.STANDARD_PALETTE) {
            Swatch(color = c, selected = c == selected, onClick = { onSelect(c) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomTab(
    selected: Long,
    palette: List<Long>,
    onSelect: (Long) -> Unit,
    onSave: () -> Unit,
    onLongPressSaved: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("저장한 색", style = MaterialTheme.typography.labelMedium, color = Color(0xFF777777))
        Spacer(Modifier.height(6.dp))
        if (palette.isEmpty()) {
            Text("아직 없습니다. 아래에서 색을 만들고 \"팔레트에 저장\"을 누르세요.", fontSize = 12.sp, color = Color(0xFF999999))
        } else {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (c in palette) {
                    Swatch(
                        color = c,
                        selected = c == selected,
                        onClick = { onSelect(c) },
                        onLongClick = { onLongPressSaved(c) },
                    )
                }
            }
            Text("길게 누르면 삭제", fontSize = 11.sp, color = Color(0xFF999999))
        }
        Spacer(Modifier.height(12.dp))
        HsvPicker(color = selected, onChange = onSelect)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HexField(color = selected, onChange = onSelect, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSave, enabled = selected !in palette) { Text("팔레트에 저장") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Swatch(
    color: Long,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000),
                shape = CircleShape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

/** 색조 막대 + 채도/명도 영역 */
@Composable
private fun HsvPicker(color: Long, onChange: (Long) -> Unit) {
    val initial = remember { longToHsv(color) }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var sat by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }

    // 바깥에서 색이 바뀌면(팔레트 탭 등) 슬라이더도 맞춘다
    LaunchedEffect(color) {
        if (hsvToLong(hue, sat, value) != color) {
            val hsv = longToHsv(color)
            // 무채색은 색조 정보가 없으므로 기존 색조를 유지
            if (hsv[1] > 0.001f) hue = hsv[0]
            sat = hsv[1]
            value = hsv[2]
        }
    }

    fun emit() = onChange(hsvToLong(hue, sat, value))

    // 채도(가로) / 명도(세로) 영역
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                fun update(p: Offset) {
                    sat = (p.x / size.width).coerceIn(0f, 1f)
                    value = (1f - p.y / size.height).coerceIn(0f, 1f)
                    emit()
                }
                detectDragGestures(onDragStart = { update(it) }) { change, _ ->
                    change.consume()
                    update(change.position)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    sat = (it.x / size.width).coerceIn(0f, 1f)
                    value = (1f - it.y / size.height).coerceIn(0f, 1f)
                    emit()
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val hueColor = Color(hsvToLong(hue, 1f, 1f))
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = c, style = Stroke(width = 3.dp.toPx()))
            drawCircle(Color.Black, radius = 9.dp.toPx(), center = c, style = Stroke(width = 1.dp.toPx()))
        }
    }
    Spacer(Modifier.height(10.dp))
    // 색조 막대
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .pointerInput(Unit) {
                fun update(p: Offset) {
                    hue = (p.x / size.width).coerceIn(0f, 0.9999f) * 360f
                    emit()
                }
                detectDragGestures(onDragStart = { update(it) }) { change, _ ->
                    change.consume()
                    update(change.position)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    hue = (it.x / size.width).coerceIn(0f, 0.9999f) * 360f
                    emit()
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stops = (0..12).map { Color(hsvToLong(it * 30f % 360f, 1f, 1f)) }
            drawRect(Brush.horizontalGradient(stops))
            val x = hue / 360f * size.width
            drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(width = 3.dp.toPx()))
            drawCircle(Color.Black, radius = 10.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(width = 1.dp.toPx()))
        }
    }
}

@Composable
private fun HexField(color: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(hexOf(color)) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(color) {
        if (!editing) text = hexOf(color)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            editing = true
            text = t.uppercase()
            parseHex(t)?.let { onChange(it) }
            if (parseHex(t) != null) editing = false
        },
        label = { Text("HEX") },
        singleLine = true,
        modifier = modifier,
    )
}

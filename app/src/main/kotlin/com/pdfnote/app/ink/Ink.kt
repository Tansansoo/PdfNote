package com.pdfnote.app.ink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

enum class InkTool { PEN, HIGHLIGHTER }
enum class EraserMode { STROKE, AREA }

/**
 * 획 하나. 좌표 단위는 그려진 표면에 따라 다르다.
 * - PDF 페이지: 페이지 좌상단 기준 PDF 포인트
 * - 워크스페이스: 캔버스 dp (줌 1배 기준)
 * width도 같은 단위.
 */
data class Stroke(
    val id: String,
    val tool: InkTool,
    val color: Long, // ARGB
    val width: Float,
    val points: List<Offset>,
)

/** 되돌리기 단위. removed를 지우고 added를 넣으면 앞으로, 반대로 하면 뒤로. */
data class InkEdit(val surface: String, val removed: List<Stroke>, val added: List<Stroke>)

data class EraserCursor(val surface: String, val center: Offset, val radius: Float)

/**
 * 문서 하나의 모든 필기. surface 키: "page:<번호>" 또는 "canvas".
 * 그리는 중인 획(current)과 되돌리기/다시실행 스택을 함께 관리한다.
 */
class InkStore {
    private val surfaces = mutableStateMapOf<String, List<Stroke>>()

    var current by mutableStateOf<Stroke?>(null)
        private set
    var currentSurface by mutableStateOf<String?>(null)
        private set
    var eraserCursor by mutableStateOf<EraserCursor?>(null)
        private set

    private val undoStack = ArrayDeque<InkEdit>()
    private val redoStack = ArrayDeque<InkEdit>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** 저장이 필요한 변경이 있을 때마다 증가 */
    var revision by mutableIntStateOf(0)
        private set

    fun strokes(surface: String): List<Stroke> = surfaces[surface] ?: emptyList()

    fun snapshot(): Map<String, List<Stroke>> = surfaces.toMap()

    fun replaceAll(data: Map<String, List<Stroke>>) {
        surfaces.clear()
        surfaces.putAll(data)
        undoStack.clear()
        redoStack.clear()
        current = null
        currentSurface = null
        canUndo = false
        canRedo = false
    }

    // ---- 그리기 ----

    fun beginStroke(surface: String, stroke: Stroke) {
        currentSurface = surface
        current = stroke
    }

    fun updateStroke(points: List<Offset>) {
        current = current?.copy(points = points)
    }

    fun endStroke() {
        val s = current
        val surface = currentSurface
        current = null
        currentSurface = null
        if (s != null && surface != null && s.points.isNotEmpty()) {
            applyForward(InkEdit(surface, emptyList(), listOf(s)))
            pushUndo(InkEdit(surface, emptyList(), listOf(s)))
        }
    }

    fun cancelStroke() {
        current = null
        currentSurface = null
    }

    // ---- 지우기 (한 번의 드래그가 되돌리기 한 단위) ----

    private var eraseSurface: String? = null
    private var eraseRadius = 0f
    private val eraseRemoved = ArrayList<Stroke>()
    private val eraseAdded = ArrayList<Stroke>()

    fun beginErase(surface: String, radius: Float) {
        eraseSurface = surface
        eraseRadius = radius
        eraseRemoved.clear()
        eraseAdded.clear()
    }

    fun eraseAt(point: Offset, mode: EraserMode) {
        val surface = eraseSurface ?: return
        eraserCursor = EraserCursor(surface, point, eraseRadius)
        val list = surfaces[surface] ?: return
        var changed = false
        val out = ArrayList<Stroke>(list.size)
        for (s in list) {
            if (!InkGeometry.strokeHits(s, point, eraseRadius)) {
                out.add(s)
                continue
            }
            changed = true
            // 이번 드래그에서 새로 생긴 조각을 다시 지우는 경우는 추가 목록에서만 뺀다
            if (!eraseAdded.removeAll { it.id == s.id }) eraseRemoved.add(s)
            if (mode == EraserMode.AREA) {
                for (piece in InkGeometry.splitStroke(s, point, eraseRadius)) {
                    out.add(piece)
                    eraseAdded.add(piece)
                }
            }
        }
        if (changed) surfaces[surface] = out
    }

    fun endErase() {
        val surface = eraseSurface
        eraserCursor = null
        if (surface != null && (eraseRemoved.isNotEmpty() || eraseAdded.isNotEmpty())) {
            pushUndo(InkEdit(surface, eraseRemoved.toList(), eraseAdded.toList()))
        }
        eraseSurface = null
        eraseRemoved.clear()
        eraseAdded.clear()
    }

    // ---- 되돌리기 ----

    private fun applyForward(edit: InkEdit) {
        val removedIds = edit.removed.mapTo(HashSet()) { it.id }
        surfaces[edit.surface] = strokes(edit.surface).filterNot { it.id in removedIds } + edit.added
    }

    private fun applyBackward(edit: InkEdit) {
        val addedIds = edit.added.mapTo(HashSet()) { it.id }
        surfaces[edit.surface] = strokes(edit.surface).filterNot { it.id in addedIds } + edit.removed
    }

    private fun pushUndo(edit: InkEdit) {
        undoStack.addLast(edit)
        if (undoStack.size > 300) undoStack.removeFirst()
        redoStack.clear()
        updateFlags()
    }

    fun undo() {
        val e = undoStack.removeLastOrNull() ?: return
        applyBackward(e)
        redoStack.addLast(e)
        updateFlags()
    }

    fun redo() {
        val e = redoStack.removeLastOrNull() ?: return
        applyForward(e)
        undoStack.addLast(e)
        updateFlags()
    }

    private fun updateFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        revision++
    }
}

object InkGeometry {
    fun distToSegment(p: Offset, a: Offset, b: Offset): Float {
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        if (len2 == 0f) return (p - a).getDistance()
        val ap = p - a
        val t = ((ap.x * ab.x + ap.y * ab.y) / len2).coerceIn(0f, 1f)
        return (p - (a + ab * t)).getDistance()
    }

    fun pathLength(points: List<Offset>): Float {
        var len = 0f
        for (i in 1 until points.size) len += (points[i] - points[i - 1]).getDistance()
        return len
    }

    /** 지우개 원이 획에 닿는지 */
    fun strokeHits(s: Stroke, p: Offset, radius: Float): Boolean {
        val r = radius + s.width / 2f
        val pts = s.points
        if (pts.isEmpty()) return false
        if (pts.size == 1) return (pts[0] - p).getDistance() <= r
        for (i in 0 until pts.size - 1) {
            if (distToSegment(p, pts[i], pts[i + 1]) <= r) return true
        }
        return false
    }

    /** 긴 구간을 잘게 나눠 점 단위 판정이 가능하게 한다 */
    private fun densify(points: List<Offset>, maxStep: Float): List<Offset> {
        if (points.size < 2) return points
        val out = ArrayList<Offset>(points.size * 2)
        out.add(points[0])
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = (b - a).getDistance()
            val n = (d / maxStep).toInt()
            for (k in 1 until n) {
                val t = k.toFloat() / n
                out.add(a + (b - a) * t)
            }
            out.add(b)
        }
        return out
    }

    /** 영역 지우기: 원 안에 들어간 부분을 잘라내고 남은 조각들을 돌려준다 */
    fun splitStroke(s: Stroke, p: Offset, radius: Float): List<Stroke> {
        val r = radius + s.width / 2f
        val pts = densify(s.points, max(radius / 2f, 0.5f))
        val out = ArrayList<Stroke>()
        var run = ArrayList<Offset>()
        fun flush() {
            if (run.size >= 2) out.add(s.copy(id = UUID.randomUUID().toString(), points = run.toList()))
            run = ArrayList()
        }
        for (q in pts) {
            if ((q - p).getDistance() <= r) flush() else run.add(q)
        }
        flush()
        return out
    }
}

object InkJson {
    fun encode(data: Map<String, List<Stroke>>): String {
        val surfaces = JSONObject()
        for ((key, strokes) in data) {
            if (strokes.isEmpty()) continue
            val arr = JSONArray()
            for (s in strokes) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("tool", s.tool.name)
                o.put("color", s.color)
                o.put("width", s.width.toDouble())
                val pts = JSONArray()
                for (pt in s.points) {
                    pts.put(pt.x.toDouble())
                    pts.put(pt.y.toDouble())
                }
                o.put("pts", pts)
                arr.put(o)
            }
            surfaces.put(key, arr)
        }
        return JSONObject().put("version", 1).put("surfaces", surfaces).toString()
    }

    fun decode(json: String): Map<String, List<Stroke>> {
        val root = JSONObject(json)
        val surfaces = root.optJSONObject("surfaces") ?: return emptyMap()
        val out = HashMap<String, List<Stroke>>()
        val keys = surfaces.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = surfaces.optJSONArray(key) ?: continue
            val list = ArrayList<Stroke>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    val pts = o.getJSONArray("pts")
                    val points = ArrayList<Offset>(pts.length() / 2)
                    var k = 0
                    while (k + 1 < pts.length()) {
                        points.add(Offset(pts.getDouble(k).toFloat(), pts.getDouble(k + 1).toFloat()))
                        k += 2
                    }
                    Stroke(
                        id = o.getString("id"),
                        tool = runCatching { InkTool.valueOf(o.getString("tool")) }.getOrDefault(InkTool.PEN),
                        color = o.getLong("color"),
                        width = o.getDouble("width").toFloat(),
                        points = points,
                    )
                }.getOrNull()?.let(list::add)
            }
            out[key] = list
        }
        return out
    }
}

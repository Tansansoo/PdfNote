package com.pdfnote.app.model

import android.graphics.RectF
import androidx.compose.ui.geometry.Rect
import org.json.JSONArray
import org.json.JSONObject

/** PDF 페이지 위의 선택 영역. rect는 페이지 좌상단 기준, PDF 포인트 단위 */
data class Selection(val pageIndex: Int, val rect: Rect)

fun Rect.toRectF(): RectF = RectF(left, top, right, bottom)

/** 워크스페이스 캔버스에 놓이는 항목. x, y, width는 캔버스 좌표(dp, 줌 1배 기준) */
sealed interface WorkItem {
    val id: String
    val x: Float
    val y: Float
    val width: Float
    fun movedBy(dx: Float, dy: Float): WorkItem
}

/** PDF에서 발췌한 영역 카드. 탭하면 원본 페이지로 이동한다. */
data class ExcerptItem(
    override val id: String,
    val pageIndex: Int,
    val rect: Rect,
    val text: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
) : WorkItem {
    val aspect: Float get() = (rect.height / rect.width).coerceIn(0.05f, 20f)
    val height: Float get() = width * aspect
    override fun movedBy(dx: Float, dy: Float) =
        copy(x = (x + dx).coerceAtLeast(0f), y = (y + dy).coerceAtLeast(0f))
}

/** 직접 입력하는 텍스트 메모 카드 */
data class NoteItem(
    override val id: String,
    val text: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
) : WorkItem {
    override fun movedBy(dx: Float, dy: Float) =
        copy(x = (x + dx).coerceAtLeast(0f), y = (y + dy).coerceAtLeast(0f))
}

/** 워크스페이스 저장 형식 (JSON) */
object WorkspaceJson {
    fun encode(items: List<WorkItem>): String {
        val arr = JSONArray()
        for (item in items) {
            val o = JSONObject()
            when (item) {
                is ExcerptItem -> {
                    o.put("type", "excerpt")
                    o.put("page", item.pageIndex)
                    o.put("x0", item.rect.left.toDouble())
                    o.put("y0", item.rect.top.toDouble())
                    o.put("x1", item.rect.right.toDouble())
                    o.put("y1", item.rect.bottom.toDouble())
                    o.put("text", item.text)
                }
                is NoteItem -> {
                    o.put("type", "note")
                    o.put("text", item.text)
                }
            }
            o.put("id", item.id)
            o.put("x", item.x.toDouble())
            o.put("y", item.y.toDouble())
            o.put("w", item.width.toDouble())
            arr.put(o)
        }
        return JSONObject().put("version", 1).put("items", arr).toString()
    }

    fun decode(json: String): List<WorkItem> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<WorkItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                val id = o.getString("id")
                val x = o.getDouble("x").toFloat()
                val y = o.getDouble("y").toFloat()
                val w = o.getDouble("w").toFloat()
                when (o.getString("type")) {
                    "excerpt" -> ExcerptItem(
                        id = id,
                        pageIndex = o.getInt("page"),
                        rect = Rect(
                            o.getDouble("x0").toFloat(),
                            o.getDouble("y0").toFloat(),
                            o.getDouble("x1").toFloat(),
                            o.getDouble("y1").toFloat(),
                        ),
                        text = o.optString("text", ""),
                        x = x, y = y, width = w,
                    )
                    "note" -> NoteItem(id = id, text = o.optString("text", ""), x = x, y = y, width = w)
                    else -> null
                }
            }.getOrNull()?.let(out::add)
        }
        return out
    }
}

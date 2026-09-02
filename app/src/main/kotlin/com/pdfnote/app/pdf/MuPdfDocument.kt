package com.pdfnote.app.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * PDF 한 페이지의 크기 (PDF 포인트 단위, 72pt = 1inch).
 * originX/originY는 MuPDF 페이지 경계의 좌상단 좌표 (보통 0,0).
 */
data class PageSize(
    val width: Float,
    val height: Float,
    val originX: Float = 0f,
    val originY: Float = 0f,
) {
    val aspect: Float get() = height / width
}

/**
 * MuPDF Document를 감싸는 클래스.
 * MuPDF 객체는 스레드 안전하지 않으므로 모든 호출을 전용 단일 스레드에서 실행한다.
 * @param key 문서를 식별하는 키 (워크스페이스 저장에 사용)
 */
class MuPdfDocument private constructor(
    private val doc: Document,
    val pageCount: Int,
    val pageSizes: List<PageSize>,
    val displayName: String,
    val key: String,
) {
    companion object {
        // 한 페이지를 렌더링할 때 허용하는 최대 픽셀 너비
        private const val MAX_PAGE_PX = 3000f

        // MuPDF 전용 스레드
        private val mupdfDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mupdf-worker")
        }.asCoroutineDispatcher()

        /** 앱 저장소 안의 PDF 파일을 연다 */
        suspend fun openFile(file: File, displayName: String, key: String): MuPdfDocument =
            withContext(mupdfDispatcher) {
                val doc = Document.openDocument(file.absolutePath)
                val count = doc.countPages()
                val sizes = ArrayList<PageSize>(count)
                for (i in 0 until count) {
                    val page = doc.loadPage(i)
                    try {
                        val b = page.bounds
                        sizes.add(PageSize(b.x1 - b.x0, b.y1 - b.y0, b.x0, b.y0))
                    } finally {
                        page.destroy()
                    }
                }
                MuPdfDocument(doc, count, sizes, displayName, key)
            }
    }

    /**
     * 페이지를 지정한 픽셀 너비로 렌더링한다.
     * @param pageIndex 0부터 시작하는 페이지 번호
     * @param targetWidthPx 결과 비트맵의 너비(px)
     */
    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap = withContext(mupdfDispatcher) {
        val page = doc.loadPage(pageIndex)
        try {
            val scale = targetWidthPx.toFloat() / pageSizes[pageIndex].width
            AndroidDrawDevice.drawPage(page, Matrix.Scale(scale))
        } finally {
            page.destroy()
        }
    }

    /**
     * 페이지의 일부 영역만 렌더링한다.
     * @param rect 페이지 좌상단 기준 영역 (PDF 포인트)
     * @param pxPerPt 1pt당 픽셀 수 (해상도)
     */
    suspend fun renderRegion(pageIndex: Int, rect: RectF, pxPerPt: Float): Bitmap = withContext(mupdfDispatcher) {
        val size = pageSizes[pageIndex]
        val scale = pxPerPt.coerceIn(0.5f, MAX_PAGE_PX / size.width)
        val page = doc.loadPage(pageIndex)
        try {
            val full = AndroidDrawDevice.drawPage(page, Matrix.Scale(scale))
            val x = (rect.left * scale).roundToInt().coerceIn(0, full.width - 1)
            val y = (rect.top * scale).roundToInt().coerceIn(0, full.height - 1)
            val w = (rect.width() * scale).roundToInt().coerceIn(1, full.width - x)
            val h = (rect.height() * scale).roundToInt().coerceIn(1, full.height - y)
            val cropped = Bitmap.createBitmap(full, x, y, w, h)
            if (cropped !== full) full.recycle()
            cropped
        } finally {
            page.destroy()
        }
    }

    /**
     * 영역 안에 들어 있는 글자를 읽기 순서대로 추출한다. 실패하면 빈 문자열.
     * @param rect 페이지 좌상단 기준 영역 (PDF 포인트)
     */
    suspend fun extractText(pageIndex: Int, rect: RectF): String = withContext(mupdfDispatcher) {
        runCatching {
            val size = pageSizes[pageIndex]
            // MuPDF 절대 좌표로 변환
            val left = rect.left + size.originX
            val top = rect.top + size.originY
            val right = rect.right + size.originX
            val bottom = rect.bottom + size.originY
            val page = doc.loadPage(pageIndex)
            try {
                val st = page.toStructuredText()
                try {
                    val sb = StringBuilder()
                    for (block in st.blocks) {
                        for (line in block.lines) {
                            val b = line.bbox
                            val cy = (b.y0 + b.y1) / 2f
                            if (cy < top || cy > bottom || b.x1 <= left || b.x0 >= right) continue
                            val lineText = StringBuilder()
                            for (ch in line.chars) {
                                val q = ch.quad
                                val cx = (q.ll_x + q.lr_x) / 2f
                                if (cx >= left && cx <= right) lineText.appendCodePoint(ch.c)
                            }
                            val t = lineText.toString().trim()
                            if (t.isNotEmpty()) {
                                if (sb.isNotEmpty()) sb.append('\n')
                                sb.append(t)
                            }
                        }
                    }
                    sb.toString()
                } finally {
                    st.destroy()
                }
            } finally {
                page.destroy()
            }
        }.getOrDefault("")
    }

    suspend fun close() = withContext(mupdfDispatcher) {
        runCatching { doc.destroy() }
        Unit
    }
}

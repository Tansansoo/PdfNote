package com.pdfnote.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/** PDF 한 페이지의 크기 (PDF 포인트 단위, 72pt = 1inch) */
data class PageSize(val width: Float, val height: Float) {
    val aspect: Float get() = height / width
}

/**
 * MuPDF Document를 감싸는 클래스.
 * MuPDF 객체는 스레드 안전하지 않으므로 모든 호출을 전용 단일 스레드에서 실행한다.
 */
class MuPdfDocument private constructor(
    private val doc: Document,
    val pageCount: Int,
    val pageSizes: List<PageSize>,
    val displayName: String,
) {
    companion object {
        // MuPDF 전용 스레드
        private val mupdfDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mupdf-worker")
        }.asCoroutineDispatcher()

        suspend fun open(context: Context, uri: Uri): MuPdfDocument = withContext(mupdfDispatcher) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("파일을 열 수 없습니다: $uri")
            val doc = Document.openDocument(bytes, "pdf")
            val count = doc.countPages()
            val sizes = ArrayList<PageSize>(count)
            for (i in 0 until count) {
                val page = doc.loadPage(i)
                try {
                    val b = page.bounds
                    sizes.add(PageSize(b.x1 - b.x0, b.y1 - b.y0))
                } finally {
                    page.destroy()
                }
            }
            MuPdfDocument(doc, count, sizes, queryDisplayName(context, uri))
        }

        private fun queryDisplayName(context: Context, uri: Uri): String {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) return c.getString(idx) ?: ""
                        }
                    }
            }
            return uri.lastPathSegment ?: "document.pdf"
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

    suspend fun close() = withContext(mupdfDispatcher) {
        runCatching { doc.destroy() }
        Unit
    }
}

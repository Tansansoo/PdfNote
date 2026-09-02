package com.pdfnote.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.pdfnote.app.pdf.MuPdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** 앱 안에 보관 중인 PDF 한 권 */
data class LibraryEntry(
    val id: String,
    val title: String,
    val pageCount: Int,
    val addedAt: Long,
    val lastOpenedAt: Long,
)

/**
 * 문서 보관함. PDF를 가져오면 원본은 그대로 두고 앱 내부 저장소에 복사본을 만든다.
 * - documents/<id>.pdf : 복사본
 * - thumbs/<id>.png    : 첫 페이지 썸네일
 * - library.json       : 목록
 */
class LibraryStore(context: Context) {
    private val app = context.applicationContext
    private val docsDir = File(app.filesDir, "documents").apply { mkdirs() }
    private val thumbDir = File(app.filesDir, "thumbs").apply { mkdirs() }
    private val indexFile = File(app.filesDir, "library.json")

    fun pdfFile(id: String): File = File(docsDir, "$id.pdf")
    private fun thumbFile(id: String): File = File(thumbDir, "$id.png")

    suspend fun load(): List<LibraryEntry> = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONObject(indexFile.readText()).optJSONArray("documents") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    LibraryEntry(
                        id = o.getString("id"),
                        title = o.optString("title", "문서"),
                        pageCount = o.optInt("pages", 0),
                        addedAt = o.optLong("addedAt", 0L),
                        lastOpenedAt = o.optLong("lastOpenedAt", 0L),
                    )
                }
            }.filter { pdfFile(it.id).exists() }
        }.getOrDefault(emptyList())
    }

    suspend fun save(entries: List<LibraryEntry>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("title", e.title)
                    .put("pages", e.pageCount)
                    .put("addedAt", e.addedAt)
                    .put("lastOpenedAt", e.lastOpenedAt)
            )
        }
        val tmp = File(app.filesDir, "library.json.tmp")
        tmp.writeText(JSONObject().put("documents", arr).toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
        Unit
    }

    /** URI가 가리키는 PDF를 앱 저장소로 복사하고 목록 항목을 만든다. 원본은 건드리지 않는다. */
    suspend fun import(uri: Uri): LibraryEntry {
        val id = UUID.randomUUID().toString()
        val file = pdfFile(id)
        try {
            withContext(Dispatchers.IO) {
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw IOException("파일을 읽을 수 없습니다")
                input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            }
            val title = queryDisplayName(uri)
                .removeSuffix(".pdf").removeSuffix(".PDF")
                .ifBlank { "문서" }
            val doc = MuPdfDocument.openFile(file, title, id)
            val pageCount = try {
                if (doc.pageCount > 0) {
                    val bmp = doc.renderPage(0, 400)
                    withContext(Dispatchers.IO) {
                        FileOutputStream(thumbFile(id)).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    }
                }
                doc.pageCount
            } finally {
                doc.close()
            }
            val now = System.currentTimeMillis()
            return LibraryEntry(id, title, pageCount, now, now)
        } catch (e: Exception) {
            file.delete()
            thumbFile(id).delete()
            throw e
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        pdfFile(id).delete()
        thumbFile(id).delete()
        Unit
    }

    suspend fun loadThumb(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = thumbFile(id)
        if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    private fun queryDisplayName(uri: Uri): String {
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return c.getString(idx) ?: ""
                    }
                }
        }
        return uri.lastPathSegment ?: "문서"
    }
}

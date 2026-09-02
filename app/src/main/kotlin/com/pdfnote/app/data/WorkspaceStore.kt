package com.pdfnote.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pdfnote.app.model.ExcerptItem
import com.pdfnote.app.model.WorkItem
import com.pdfnote.app.model.WorkspaceJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 문서별 워크스페이스를 앱 내부 저장소에 보관한다.
 * - workspaces/<key>.json : 카드 목록 (key = 문서 ID)
 * - excerpt_images/<id>.png : 발췌 카드 이미지
 */
class WorkspaceStore(context: Context) {
    private val dir = File(context.filesDir, "workspaces").apply { mkdirs() }
    private val imgDir = File(context.filesDir, "excerpt_images").apply { mkdirs() }

    suspend fun load(key: String): List<WorkItem> = withContext(Dispatchers.IO) {
        val f = File(dir, "$key.json")
        if (!f.exists()) emptyList()
        else runCatching { WorkspaceJson.decode(f.readText()) }.getOrDefault(emptyList())
    }

    suspend fun save(key: String, items: List<WorkItem>) = withContext(Dispatchers.IO) {
        val f = File(dir, "$key.json")
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(WorkspaceJson.encode(items))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        Unit
    }

    /** 문서가 삭제될 때 워크스페이스와 발췌 이미지를 함께 지운다 */
    suspend fun deleteWorkspace(key: String) = withContext(Dispatchers.IO) {
        val items = load(key)
        for (item in items) if (item is ExcerptItem) deleteImage(item.id)
        File(dir, "$key.json").delete()
        Unit
    }

    suspend fun saveImage(id: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        FileOutputStream(File(imgDir, "$id.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        Unit
    }

    suspend fun loadImage(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(imgDir, "$id.png")
        if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    fun deleteImage(id: String) {
        File(imgDir, "$id.png").delete()
    }
}

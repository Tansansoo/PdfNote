package com.pdfnote.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pdfnote.app.model.WorkItem
import com.pdfnote.app.model.WorkspaceJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 문서별 워크스페이스를 앱 내부 저장소에 보관한다.
 * - workspaces/<key>.json : 카드 목록
 * - excerpt_images/<id>.png : 발췌 카드 이미지
 */
class WorkspaceStore(context: Context) {
    private val dir = File(context.filesDir, "workspaces").apply { mkdirs() }
    private val imgDir = File(context.filesDir, "excerpt_images").apply { mkdirs() }

    /** 문서 URI로부터 저장 키를 만든다 */
    fun keyFor(uriString: String): String =
        MessageDigest.getInstance("MD5").digest(uriString.toByteArray())
            .joinToString("") { "%02x".format(it) }

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

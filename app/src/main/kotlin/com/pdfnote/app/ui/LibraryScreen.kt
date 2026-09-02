package com.pdfnote.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfnote.app.data.LibraryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 홈 화면: 가져온 PDF 목록 */
@Composable
fun LibraryScreen(
    entries: List<LibraryEntry>,
    importing: Boolean,
    message: String?,
    onDismissMessage: () -> Unit,
    loadThumb: suspend (String) -> Bitmap?,
    onImport: () -> Unit,
    onOpen: (LibraryEntry) -> Unit,
    onRename: (LibraryEntry, String) -> Unit,
    onDelete: (LibraryEntry) -> Unit,
) {
    var editing by remember { mutableStateOf<LibraryEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PdfNote",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onImport, enabled = !importing) {
                Text(if (importing) "가져오는 중..." else "PDF 가져오기")
            }
        }

        message?.let { msg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissMessage) { Text("닫기") }
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "아직 가져온 PDF가 없습니다.\n오른쪽 위 \"PDF 가져오기\"로 시작하세요.\n\n원본 파일은 그대로 두고 앱 안에 복사본을 보관합니다.",
                    color = Color(0xFF7A7A7A),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    LibraryCard(
                        entry = entry,
                        loadThumb = loadThumb,
                        onClick = { onOpen(entry) },
                        onLongClick = { editing = entry },
                    )
                }
            }
        }
    }

    editing?.let { entry ->
        EditEntryDialog(
            entry = entry,
            onDismiss = { editing = null },
            onRename = { name ->
                onRename(entry, name)
                editing = null
            },
            onDelete = {
                onDelete(entry)
                editing = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCard(
    entry: LibraryEntry,
    loadThumb: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = entry.id) {
        value = loadThumb(entry.id)
    }
    val dateText = remember(entry.lastOpenedAt) {
        SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(Date(entry.lastOpenedAt))
    }

    Column(
        Modifier
            .shadow(2.dp, shape)
            .background(Color.White, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .background(Color(0xFFF0F0F0), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("PDF", color = Color(0xFFB0B0B0), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${entry.pageCount}쪽 · $dateText",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8A8A8A),
        )
    }
}

@Composable
private fun EditEntryDialog(
    entry: LibraryEntry,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(entry.title) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("삭제할까요?") },
            text = { Text("\"${entry.title}\"의 복사본과 워크스페이스가 함께 삭제됩니다. 원본 파일은 영향을 받지 않습니다.") },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("취소") }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("문서 관리") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { if (name.isNotBlank()) onRename(name.trim()) },
                    enabled = name.isNotBlank(),
                ) { Text("저장") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("삭제", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onDismiss) { Text("취소") }
                }
            },
        )
    }
}

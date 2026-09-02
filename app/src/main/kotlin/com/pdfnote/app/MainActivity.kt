package com.pdfnote.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pdfnote.app.data.WorkspaceStore
import com.pdfnote.app.model.ExcerptItem
import com.pdfnote.app.model.NoteItem
import com.pdfnote.app.model.Selection
import com.pdfnote.app.model.WorkItem
import com.pdfnote.app.model.toRectF
import com.pdfnote.app.pdf.MuPdfDocument
import com.pdfnote.app.ui.ExcerptDragCallbacks
import com.pdfnote.app.ui.PdfViewer
import com.pdfnote.app.ui.PdfViewerState
import com.pdfnote.app.ui.SplitPane
import com.pdfnote.app.ui.WorkspaceCanvas
import com.pdfnote.app.ui.WorkspaceState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchUri: Uri? = intent?.data
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    PdfNoteApp(launchUri = launchUri)
                }
            }
        }
    }
}

private const val PREFS = "pdfnote"
private const val KEY_LAST_URI = "last_uri"

// 발췌 카드 기본 너비(dp): 선택 영역 크기에 비례하되 범위를 제한
private fun excerptWidthDp(selection: Selection): Float =
    (selection.rect.width * 1.1f).coerceIn(140f, 420f)

/** PDF에서 워크스페이스로 끌고 가는 중인 발췌 */
private data class DragPayload(
    val selection: Selection,
    val preview: Bitmap?,
    val position: Offset, // 루트 좌표(px)
)

@OptIn(FlowPreview::class)
@Composable
fun PdfNoteApp(launchUri: Uri?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val store = remember { WorkspaceStore(context) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    // 열 파일: 외부에서 넘어온 URI > 마지막으로 열었던 파일
    var currentUri by remember {
        mutableStateOf(launchUri ?: prefs.getString(KEY_LAST_URI, null)?.let(Uri::parse))
    }
    var document by remember { mutableStateOf<MuPdfDocument?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val workspace = remember {
        WorkspaceState(onRemoved = { item -> if (item is ExcerptItem) store.deleteImage(item.id) })
    }
    val pdfState = remember(document) { PdfViewerState() }
    var drag by remember { mutableStateOf<DragPayload?>(null) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            prefs.edit().putString(KEY_LAST_URI, uri.toString()).apply()
            currentUri = uri
        }
    }

    // URI가 바뀌면 문서를 다시 연다
    LaunchedEffect(currentUri) {
        val uri = currentUri ?: return@LaunchedEffect
        loading = true
        error = null
        val old = document
        document = null
        old?.close()
        runCatching { MuPdfDocument.open(context, uri) }
            .onSuccess { document = it }
            .onFailure {
                error = "PDF를 열지 못했습니다: ${it.message ?: it::class.simpleName}"
                prefs.edit().remove(KEY_LAST_URI).apply()
            }
        loading = false
    }

    // 문서별 워크스페이스 불러오기 + 바뀔 때마다 저장
    LaunchedEffect(document) {
        workspace.items.clear()
        workspace.selectedId = null
        workspace.resetView()
        drag = null
        val doc = document ?: return@LaunchedEffect
        val key = store.keyFor(doc.uriString)
        workspace.items.addAll(store.load(key))
        try {
            snapshotFlow { workspace.items.toList() }
                .drop(1)
                .debounce(400)
                .collect { store.save(key, it) }
        } finally {
            val snapshot: List<WorkItem> = workspace.items.toList()
            withContext(NonCancellable) { store.save(key, snapshot) }
        }
    }

    // 선택 영역을 발췌 카드로 만들어 워크스페이스에 추가
    fun addExcerpt(selection: Selection, topLeftDp: Offset) {
        val doc = document ?: return
        scope.launch {
            val id = UUID.randomUUID().toString()
            val widthDp = excerptWidthDp(selection)
            val rectF = selection.rect.toRectF()
            // 카드 너비의 2배 해상도로 저장
            val pxPerPt = widthDp * density * 2f / selection.rect.width
            val bitmap = runCatching { doc.renderRegion(selection.pageIndex, rectF, pxPerPt) }.getOrNull()
            if (bitmap != null) store.saveImage(id, bitmap)
            val text = doc.extractText(selection.pageIndex, rectF)
            workspace.add(
                ExcerptItem(
                    id = id,
                    pageIndex = selection.pageIndex,
                    rect = selection.rect,
                    text = text,
                    x = topLeftDp.x.coerceAtLeast(0f),
                    y = topLeftDp.y.coerceAtLeast(0f),
                    width = widthDp,
                )
            )
            pdfState.selection = null
        }
    }

    fun addNote() {
        val center = workspace.viewportCenterDp(density)
        workspace.add(
            NoteItem(
                id = UUID.randomUUID().toString(),
                text = "",
                x = (center.x - 90f).coerceAtLeast(0f),
                y = (center.y - 40f).coerceAtLeast(0f),
                width = 180f,
            )
        )
    }

    val dragCallbacks = remember(document) {
        ExcerptDragCallbacks(
            onStart = { selection, pos ->
                drag = DragPayload(selection, null, pos)
                val doc = document
                if (doc != null) scope.launch {
                    val ghostPxPerPt = 200f * density / selection.rect.width
                    val bmp = runCatching {
                        doc.renderRegion(selection.pageIndex, selection.rect.toRectF(), ghostPxPerPt)
                    }.getOrNull()
                    val d = drag
                    if (d != null && d.selection == selection) drag = d.copy(preview = bmp)
                }
            },
            onMove = { pos -> drag = drag?.copy(position = pos) },
            onEnd = { pos ->
                val d = drag
                drag = null
                if (d != null && workspace.containsRoot(pos)) {
                    val widthDp = excerptWidthDp(d.selection)
                    val heightDp = widthDp * d.selection.rect.height / d.selection.rect.width
                    val canvasPos = workspace.rootToCanvasDp(pos, density)
                    addExcerpt(d.selection, canvasPos - Offset(widthDp / 2f, heightDp / 2f))
                }
            },
            onCancel = { drag = null },
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        // 상단 툴바
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = document?.displayName ?: "PdfNote",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (document != null) {
                TextButton(onClick = { addNote() }) { Text("메모 추가") }
            }
            TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                Text("PDF 열기")
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
        ) {
            val doc = document
            when {
                doc != null -> {
                    val hovering = drag?.let { workspace.containsRoot(it.position) } ?: false
                    SplitPane(
                        first = { m ->
                            PdfViewer(
                                doc = doc,
                                state = pdfState,
                                dragCallbacks = dragCallbacks,
                                onSendSelection = { sel ->
                                    val center = workspace.viewportCenterDp(density)
                                    val w = excerptWidthDp(sel)
                                    val h = w * sel.rect.height / sel.rect.width
                                    addExcerpt(sel, center - Offset(w / 2f, h / 2f))
                                },
                                modifier = m,
                            )
                        },
                        second = { m ->
                            WorkspaceCanvas(
                                state = workspace,
                                loadImage = { item ->
                                    store.loadImage(item.id) ?: runCatching {
                                        val px = item.width * density * 2f / item.rect.width
                                        doc.renderRegion(item.pageIndex, item.rect.toRectF(), px)
                                            .also { store.saveImage(item.id, it) }
                                    }.getOrNull()
                                },
                                onJump = { item -> pdfState.jumpTo(item.pageIndex, item.rect) },
                                dropHover = hovering,
                                modifier = m,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // 끌고 가는 발췌의 잔상
                    drag?.let { d ->
                        val ghostW = 200.dp
                        val ghostH = ghostW * (d.selection.rect.height / d.selection.rect.width).coerceIn(0.05f, 3f)
                        val ghostWPx = ghostW.value * density
                        val ghostHPx = ghostH.value * density
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(
                                        (d.position.x - overlayOrigin.x - ghostWPx / 2f).roundToInt(),
                                        (d.position.y - overlayOrigin.y - ghostHPx / 2f).roundToInt(),
                                    )
                                }
                                .size(ghostW, ghostH)
                                .alpha(0.88f)
                                .shadow(10.dp, RoundedCornerShape(6.dp))
                                .background(Color.White, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val preview = d.preview
                            if (preview != null) {
                                Image(
                                    bitmap = preview.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds,
                                )
                            } else {
                                Text("발췌", color = Color(0xFF2F6FE0))
                            }
                        }
                    }
                }
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                    }
                    Button(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                        Text("PDF 파일 열기")
                    }
                }
            }
        }
    }
}

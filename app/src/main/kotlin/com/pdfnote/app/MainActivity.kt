package com.pdfnote.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.pdfnote.app.data.LibraryEntry
import com.pdfnote.app.data.LibraryStore
import com.pdfnote.app.data.WorkspaceStore
import com.pdfnote.app.ink.InkStore
import com.pdfnote.app.ink.InkTools
import com.pdfnote.app.model.ExcerptItem
import com.pdfnote.app.model.NoteItem
import com.pdfnote.app.model.Selection
import com.pdfnote.app.model.WorkItem
import com.pdfnote.app.model.toRectF
import com.pdfnote.app.pdf.MuPdfDocument
import com.pdfnote.app.ui.ExcerptDragCallbacks
import com.pdfnote.app.ui.InkToolbar
import com.pdfnote.app.ui.LibraryScreen
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
    // "PdfNote로 열기"로 넘어온 파일 URI (앱 실행 중 새로 들어올 수도 있음)
    private val pendingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingUri.value = intent?.data
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    PdfNoteApp(
                        launchUri = pendingUri.value,
                        onLaunchUriConsumed = { pendingUri.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingUri.value = intent.data
    }
}

@Composable
fun PdfNoteApp(launchUri: Uri?, onLaunchUriConsumed: () -> Unit) {
    val context = LocalContext.current
    val library = remember { LibraryStore(context) }
    val store = remember { WorkspaceStore(context) }
    val scope = rememberCoroutineScope()

    val entries = remember { mutableStateListOf<LibraryEntry>() }
    var libraryLoaded by remember { mutableStateOf(false) }
    var openEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var document by remember { mutableStateOf<MuPdfDocument?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entries.addAll(library.load())
        libraryLoaded = true
    }

    fun persistLibrary() {
        val snapshot = entries.toList()
        scope.launch { library.save(snapshot) }
    }

    fun importUri(uri: Uri) {
        scope.launch {
            importing = true
            runCatching { library.import(uri) }
                .onSuccess { entry ->
                    entries.add(0, entry)
                    persistLibrary()
                    openEntry = entry
                }
                .onFailure {
                    message = "PDF를 가져오지 못했습니다: ${it.message ?: it::class.simpleName}"
                }
            importing = false
        }
    }

    // 파일 관리자에서 "PdfNote로 열기"
    LaunchedEffect(launchUri, libraryLoaded) {
        if (launchUri != null && libraryLoaded) {
            onLaunchUriConsumed()
            importUri(launchUri)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri)
    }

    // 선택한 문서 열기 / 닫기
    LaunchedEffect(openEntry) {
        val entry = openEntry
        val old = document
        document = null
        old?.close()
        if (entry == null) return@LaunchedEffect
        loading = true
        runCatching { MuPdfDocument.openFile(library.pdfFile(entry.id), entry.title, entry.id) }
            .onSuccess { doc ->
                document = doc
                val i = entries.indexOfFirst { it.id == entry.id }
                if (i >= 0) {
                    entries[i] = entries[i].copy(lastOpenedAt = System.currentTimeMillis())
                    persistLibrary()
                }
            }
            .onFailure {
                message = "PDF를 열지 못했습니다: ${it.message ?: it::class.simpleName}"
                openEntry = null
            }
        loading = false
    }

    BackHandler(enabled = openEntry != null) { openEntry = null }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        val entry = openEntry
        if (entry == null) {
            LibraryScreen(
                entries = entries.sortedByDescending { it.lastOpenedAt },
                importing = importing,
                message = message,
                onDismissMessage = { message = null },
                loadThumb = { id -> library.loadThumb(id) },
                onImport = { picker.launch(arrayOf("application/pdf")) },
                onOpen = { openEntry = it },
                onRename = { e, name ->
                    val i = entries.indexOfFirst { it.id == e.id }
                    if (i >= 0) {
                        entries[i] = entries[i].copy(title = name)
                        persistLibrary()
                    }
                },
                onDelete = { e ->
                    entries.removeAll { it.id == e.id }
                    persistLibrary()
                    scope.launch {
                        library.delete(e.id)
                        store.deleteWorkspace(e.id)
                    }
                },
            )
        } else {
            ViewerScreen(
                title = entry.title,
                document = document,
                loading = loading,
                store = store,
                onBack = { openEntry = null },
            )
        }
    }
}

// 발췌 카드 기본 너비(dp): 선택 영역 크기에 비례하되 범위를 제한
private fun excerptWidthDp(selection: Selection): Float =
    (selection.rect.width * 1.1f).coerceIn(140f, 420f)

/** PDF에서 워크스페이스로 끌고 가는 중인 발췌 */
private data class DragPayload(
    val selection: Selection,
    val preview: Bitmap?,
    val position: Offset, // 루트 좌표(px)
)

/** 뷰어 화면: 툴바 + (PDF | 워크스페이스) 분할 + 드래그 잔상 + 필기 툴바 */
@OptIn(FlowPreview::class)
@Composable
private fun ViewerScreen(
    title: String,
    document: MuPdfDocument?,
    loading: Boolean,
    store: WorkspaceStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    val workspace = remember {
        WorkspaceState(onRemoved = { item -> if (item is ExcerptItem) store.deleteImage(item.id) })
    }
    val pdfState = remember(document) { PdfViewerState() }
    val tools = remember { InkTools() }
    val ink = remember(document) { InkStore() }
    var drag by remember { mutableStateOf<DragPayload?>(null) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    // 문서별 워크스페이스 불러오기 + 바뀔 때마다 저장
    LaunchedEffect(document) {
        workspace.items.clear()
        workspace.selectedId = null
        workspace.resetView()
        drag = null
        val doc = document ?: return@LaunchedEffect
        val key = doc.key
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

    // 문서별 필기 불러오기 + 저장
    LaunchedEffect(document) {
        val doc = document ?: return@LaunchedEffect
        val key = doc.key
        ink.replaceAll(store.loadInk(key))
        try {
            snapshotFlow { ink.revision }
                .drop(1)
                .debounce(600)
                .collect { store.saveInk(key, ink.snapshot()) }
        } finally {
            val snapshot = ink.snapshot()
            withContext(NonCancellable) { store.saveInk(key, snapshot) }
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
                    x = topLeftDp.x,
                    y = topLeftDp.y,
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
                x = center.x - 90f,
                y = center.y - 40f,
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

    Column(Modifier.fillMaxSize()) {
        // 상단 툴바
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("◀ 목록") }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (document != null) {
                TextButton(onClick = { addNote() }) { Text("메모 추가") }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
        ) {
            val doc = document
            if (doc != null) {
                val hovering = drag?.let { workspace.containsRoot(it.position) } ?: false
                SplitPane(
                    first = { m ->
                        PdfViewer(
                            doc = doc,
                            state = pdfState,
                            tools = tools,
                            ink = ink,
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
                            tools = tools,
                            ink = ink,
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
            } else if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }

        if (document != null) {
            InkToolbar(tools = tools, ink = ink)
        }
    }
}

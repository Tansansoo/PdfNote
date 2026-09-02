package com.pdfnote.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfnote.app.pdf.MuPdfDocument
import com.pdfnote.app.ui.PdfViewer

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

@Composable
fun PdfNoteApp(launchUri: Uri?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // 열 파일: 외부에서 넘어온 URI > 마지막으로 열었던 파일
    var currentUri by remember {
        mutableStateOf(launchUri ?: prefs.getString(KEY_LAST_URI, null)?.let(Uri::parse))
    }
    var document by remember { mutableStateOf<MuPdfDocument?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
            TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                Text("PDF 열기")
            }
        }

        Box(Modifier.fillMaxSize()) {
            val doc = document
            when {
                doc != null -> PdfViewer(doc = doc, modifier = Modifier.fillMaxSize())
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

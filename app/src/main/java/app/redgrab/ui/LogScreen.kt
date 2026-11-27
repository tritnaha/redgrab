package app.redgrab.ui

import app.redgrab.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.redgrab.AppLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by AppLog.version.collectAsStateWithLifecycle()
    val lines = remember(version) { AppLog.read().lines().filter { it.isNotEmpty() } }
    val list = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Activity log") },
                expandedHeight = TopBarHeight,
                navigationIcon = { IconButton(onClick = onBack) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back to Settings") } },
                actions = {
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, "Clear log") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    copyLog(ctx)
                    scope.launch { snack.showSnackbar("Log copied to clipboard") }
                },
                icon = { Icon(Icons.Default.ContentCopy, null) },
                text = { Text("Copy log") },
            )
        },
    ) { pad ->
        if (lines.isEmpty()) {
            Box(Modifier.padding(pad).padding(24.dp)) {
                Text("Nothing logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            SelectionContainer(Modifier.padding(pad)) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    itemsIndexed(lines) { _, line ->
                        val color = when {
                            line.contains(" E ") || line.contains("CRASH") -> MaterialTheme.colorScheme.error
                            line.contains(" W ") -> RgAmber
                            line.startsWith("\t") || line.startsWith("Caused by") -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(line, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp))
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the log?") },
            confirmButton = { TextButton(onClick = { AppLog.clear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

fun copyText(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The log without sign-ins, marked as sensitive so the keyboard and clipboard previews don't show it. */
fun copyLog(ctx: Context) {
    val clip = ClipData.newPlainText("RedGrab log", app.redgrab.BugReport.redact(AppLog.read(), app.redgrab.BugReport.secrets(ctx)))
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

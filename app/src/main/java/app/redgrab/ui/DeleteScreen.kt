package app.redgrab.ui

import app.redgrab.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.redgrab.Clip
import app.redgrab.DeleteRequest
import app.redgrab.Format
import app.redgrab.MainViewModel
import coil3.compose.AsyncImage

/**
 * Confirms a delete. Lists the clips being deleted and, separately, any edited copies made
 * from them: each copy has its own checkbox (not deleted unless ticked) and a play button, so
 * you can check what a copy is before deciding. Unticking one copy never cancels the rest.
 */
@Composable
fun DeleteScreen(vm: MainViewModel, r: DeleteRequest) {
    var hide by remember { mutableStateOf(true) }
    BackHandler { vm.cancelDelete() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::cancelDelete) { Icon(Icons.Default.Close, "Cancel") }
                Text(
                    buildString {
                        append("Delete ")
                        if (r.originals.isNotEmpty()) append("${r.originals.size} clip${if (r.originals.size == 1) "" else "s"}")
                        if (r.originals.isNotEmpty() && r.selectedCopies.isNotEmpty()) append(" + ")
                        if (r.selectedCopies.isNotEmpty()) append("${r.selectedCopies.size} cop${if (r.selectedCopies.size == 1) "y" else "ies"}")
                        if (r.originals.isEmpty() && r.selectedCopies.isEmpty()) append("nothing selected")
                    },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                )
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.originals.isNotEmpty()) {
                    item {
                        Text("These saved files are deleted from the save folder:", style = MaterialTheme.typography.bodyMedium)
                    }
                    items(r.originals, key = { "o-" + it.id }) { c -> ClipRow(vm, c, checked = null, onToggle = null) }
                    // Hiding is about not downloading a RedGifs clip again; imported files have nothing to hide.
                    if (r.originals.any { !it.imported }) item {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { hide = !hide }
                            .background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(hide, { hide = it })
                            Column {
                                Text("Hide from library", fontWeight = FontWeight.Medium)
                                Text("\"Save all\" won't download them again. Unhide in Settings.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (r.copies.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(if (r.originals.isNotEmpty()) "Edited copies of these clips" else "Edited copies",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Tick the copies to delete as well. Unticked copies are kept. Tap ▶ to watch one first.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { r.copies.filter { it.id !in r.selectedCopies }.forEach { vm.toggleDeleteCopy(it.id) } }) { Text("Tick all") }
                            TextButton(onClick = { r.copies.filter { it.id in r.selectedCopies }.forEach { vm.toggleDeleteCopy(it.id) } }) { Text("Untick all") }
                        }
                    }
                    items(r.copies, key = { "c-" + it.id }) { c ->
                        ClipRow(vm, c, checked = c.id in r.selectedCopies, onToggle = { vm.toggleDeleteCopy(c.id) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::cancelDelete, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { vm.confirmDelete(hide) },
                    enabled = r.originals.isNotEmpty() || r.selectedCopies.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.DeleteForever, null); Spacer(Modifier.width(6.dp)); Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ClipRow(vm: MainViewModel, c: Clip, checked: Boolean?, onToggle: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) Checkbox(checked, { onToggle?.invoke() })
        Box(Modifier.width(56.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(thumbModel(vm.state.value, vm.library, c), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (vm.sfw) Modifier.blur(16.dp) else Modifier))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.copyFile?.substringAfterLast('/') ?: c.id, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when {
                    c.isCopy -> "${Format.duration(c.duration)} · ${c.copyNote}"
                    c.isImage -> "${c.sources.firstOrNull() ?: "Imported"} · ${c.fileExt.uppercase()}"
                    c.imported -> "${c.sources.firstOrNull() ?: "Imported"} · ${Format.duration(c.duration)}"
                    else -> "${Format.duration(c.duration)} · ${c.user}"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        IconButton(onClick = { vm.openPlayer(listOf(c), 0) }, enabled = !vm.sfw) {
            Icon(Icons.Default.PlayCircle, "Watch", Modifier.size(28.dp))
        }
    }
}

package app.redgrab.ui

import app.redgrab.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.redgrab.MainViewModel

/**
 * Edit performer, category and tags for one or more clips. Fields start with the clips' current
 * info (RedGifs' unless edited before). With several clips, a field left as "(mixed)" is unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InfoEditor(vm: MainViewModel, ids: List<String>) {
    val clips = remember(ids) { val set = ids.toHashSet(); vm.state.value.clips.filter { it.id in set } }
    if (clips.isEmpty()) { vm.closeInfoEditor(); return }
    val one = clips.size == 1

    val commonUser = clips.map { it.user }.distinct().singleOrNull()
    val commonCategory = clips.map { it.category }.distinct().singleOrNull()
    var performer by remember { mutableStateOf(commonUser ?: "") }
    var category by remember { mutableStateOf(commonCategory ?: "") }
    var performerTouched by remember { mutableStateOf(false) }
    var categoryTouched by remember { mutableStateOf(false) }

    // Tag → how many of the selected clips have it.
    val tagCounts = remember(clips) { countLabels(clips.map { it.tags }) }
    // Niches work like tags: current ones (with counts), plus a cloud of every known niche to add from.
    val nicheCounts = remember(clips) { countLabels(clips.map { it.niches }) }
    val allNiches = remember { vm.library.allNiches() }
    val removedNiches = remember { mutableStateListOf<String>() }
    val addedNiches = remember { mutableStateListOf<String>() }
    var nicheFilter by remember { mutableStateOf("") }
    val removed = remember { mutableStateListOf<String>() }
    val added = remember { mutableStateListOf<String>() }
    var newTag by remember { mutableStateOf("") }

    // Suggestions: tags and categories already used in the library.
    val allCategories = remember { vm.state.value.clips.map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }

    fun addTag() {
        newTag.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { t ->
            if (added.none { it.equals(t, true) } && tagCounts.keys.none { it.equals(t, true) && it !in removed }) added += t
            removed.removeAll { it.equals(t, true) }
        }
        newTag = ""
    }

    ModalBottomSheet(onDismissRequest = vm::closeInfoEditor, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(if (one) "Edit info" else "Edit info for ${clips.size} clips", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Pre-filled from RedGifs. Your changes are kept in the app and survive syncs.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(performer, { performer = it; performerTouched = true }, Modifier.fillMaxWidth(),
                label = { Text("Performer") }, singleLine = true,
                placeholder = { if (commonUser == null) Text("(mixed — leave empty to keep)") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(category, { category = it; categoryTouched = true }, Modifier.fillMaxWidth(),
                label = { Text("Category / kink") }, singleLine = true,
                placeholder = { Text(if (commonCategory == null) "(mixed — leave empty to keep)" else "e.g. Favourites, Outdoor…") })
            if (allCategories.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    allCategories.take(12).forEach { c ->
                        FilterChip(category.equals(c, true), { category = c; categoryTouched = true }, { Text(c) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Niches", fontWeight = FontWeight.SemiBold)
            Text("What kind of clip this is. Tap a niche below to add it${if (one) "" else " to all selected clips"}; tap one it already has to remove it. " +
                "Use this when RedGifs filed a clip under the wrong niche, or none.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (nicheCounts.isNotEmpty() || addedNiches.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    nicheCounts.forEach { (n, count) ->
                        val gone = n in removedNiches
                        InputChip(
                            selected = !gone,
                            onClick = { if (gone) removedNiches.remove(n) else removedNiches.add(n) },
                            label = { Text(if (one || count == clips.size) n else "$n · $count", textDecoration = if (gone) TextDecoration.LineThrough else null) },
                            trailingIcon = { Icon(if (gone) Icons.AutoMirrored.Filled.Undo else Icons.Default.Close, null, Modifier.size(16.dp)) },
                        )
                    }
                    addedNiches.forEach { n ->
                        InputChip(selected = true, onClick = { addedNiches.remove(n) }, label = { Text("+ $n") },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                    }
                }
            }
            OutlinedTextField(nicheFilter, { nicheFilter = it }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true,
                label = { Text("Find or create a niche") })
            // The cloud: every niche the app knows (from RedGifs and ones you made), filtered as you type.
            val f = nicheFilter.trim()
            val cloud = allNiches.filter { n ->
                (f.isEmpty() || n.contains(f, true)) && addedNiches.none { it.equals(n, true) } &&
                    nicheCounts.keys.none { it.equals(n, true) && it !in removedNiches }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (f.isNotEmpty() && allNiches.none { it.equals(f, true) } && addedNiches.none { it.equals(f, true) }) {
                    FilterChip(false, { addedNiches += f; nicheFilter = "" }, { Text("Create “$f”") },
                        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
                }
                cloud.take(if (f.isEmpty()) 40 else 80).forEach { n ->
                    FilterChip(false, { addedNiches += n; removedNiches.removeAll { it.equals(n, true) } }, { Text(n) })
                }
            }
            if (f.isEmpty() && cloud.size > 40) Text("${cloud.size - 40} more — type to find them",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            Text("Tags", fontWeight = FontWeight.SemiBold)
            Text(if (one) "Tap a tag to remove it." else "Tap a tag to remove it from all selected clips. Numbers show how many have it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                tagCounts.forEach { (t, n) ->
                    val gone = t in removed
                    InputChip(
                        selected = !gone,
                        onClick = { if (gone) removed.remove(t) else removed.add(t) },
                        label = {
                            Text(if (one || n == clips.size) t else "$t · $n",
                                textDecoration = if (gone) TextDecoration.LineThrough else null)
                        },
                        trailingIcon = { Icon(if (gone) Icons.AutoMirrored.Filled.Undo else Icons.Default.Close, null, Modifier.size(16.dp)) },
                    )
                }
                added.forEach { t ->
                    InputChip(selected = true, onClick = { added.remove(t) }, label = { Text("+ $t") },
                        trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newTag, { newTag = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Add tags (comma separated)") })
                IconButton(onClick = ::addTag, enabled = newTag.isNotBlank()) { Icon(Icons.Default.Add, "Add tag") }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.resetInfo(ids) }) { Text("Reset to RedGifs info") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::closeInfoEditor) { Text("Cancel") }
                Button(onClick = {
                    if (newTag.isNotBlank()) addTag()
                    vm.saveInfo(
                        ids,
                        performer = if (performerTouched || (commonUser != null && performer != commonUser)) performer.takeIf { it.isNotBlank() || one } else null,
                        category = if (categoryTouched) category else null,
                        addTags = added.toList(),
                        removeTags = removed.toSet(),
                        addNiches = addedNiches.toList(),
                        removeNiches = removedNiches.toSet(),
                    )
                }) { Text("Save") }
            }
        }
    }
}

/** How many of the given lists contain each label (case-insensitive), keeping the first spelling seen. */
private fun countLabels(lists: List<List<String>>): LinkedHashMap<String, Int> {
    val spelling = HashMap<String, String>()   // lower-case → first spelling
    val counts = LinkedHashMap<String, Int>()  // first spelling → count
    for (list in lists) {
        for (key in list.mapTo(HashSet()) { it.lowercase() }) {
            val name = spelling.getOrPut(key) { list.first { it.lowercase() == key } }
            counts[name] = (counts[name] ?: 0) + 1
        }
    }
    return counts
}

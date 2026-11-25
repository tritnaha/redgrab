package app.redgrab.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redgrab.AdvFilters
import app.redgrab.Length
import app.redgrab.LibraryState
import app.redgrab.MainViewModel
import app.redgrab.Sort
import app.redgrab.visibleClips

/** Sort and extra filters, built from metadata RedGifs provides with each clip. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(vm: MainViewModel, s: LibraryState, onClose: () -> Unit) {
    val a = vm.adv
    fun set(f: AdvFilters) { vm.adv = f }
    val audiences = remember(s.clips) { s.clips.flatMap { it.sexuality }.map { it.lowercase() }.distinct().sorted() }
    val matching = remember(s.clips, s.saved, s.failures, vm.filter, vm.query, vm.facet, a, vm.origin) { visibleClips(s, vm.filter, vm.query, vm.facet, a, vm.origin).size }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Filters & sort", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            Label("Show")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(vm.filter == app.redgrab.Filter.Saved, { vm.filter = app.redgrab.Filter.Saved }, { Text("On phone") })
                FilterChip(vm.filter == app.redgrab.Filter.New, { vm.filter = app.redgrab.Filter.New }, { Text("Not saved") })
                FilterChip(vm.filter == app.redgrab.Filter.All, { vm.filter = app.redgrab.Filter.All }, { Text("Everything") })
                FilterChip(vm.filter == app.redgrab.Filter.HD, { vm.filter = app.redgrab.Filter.HD }, { Text("Saved in HD") })
                FilterChip(vm.filter == app.redgrab.Filter.SD, { vm.filter = app.redgrab.Filter.SD }, { Text("Saved in SD") })
                FilterChip(vm.filter == app.redgrab.Filter.Edited, { vm.filter = app.redgrab.Filter.Edited }, { Text("Edited") })
            }

            Label("Sort by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sort.entries.forEach { o -> FilterChip(a.sort == o, { set(a.copy(sort = o)) }, { Text(o.label) }) }
            }

            Label("Length")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Length.entries.forEach { l -> FilterChip(a.length == l, { set(a.copy(length = l)) }, { Text(l.label) }) }
            }

            Label("Orientation")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(a.vertical == null, { set(a.copy(vertical = null)) }, { Text("Any") })
                FilterChip(a.vertical == true, { set(a.copy(vertical = true)) }, { Text("Vertical") })
                FilterChip(a.vertical == false, { set(a.copy(vertical = false)) }, { Text("Horizontal") })
            }

            Label("Sound")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(a.sound == null, { set(a.copy(sound = null)) }, { Text("Any") })
                FilterChip(a.sound == true, { set(a.copy(sound = true)) }, { Text("With sound") })
                FilterChip(a.sound == false, { set(a.copy(sound = false)) }, { Text("Silent") })
            }

            if (audiences.isNotEmpty()) {
                Label("Audience (RedGifs label)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(a.sexuality == null, { set(a.copy(sexuality = null)) }, { Text("Any") })
                    audiences.forEach { x ->
                        FilterChip(a.sexuality.equals(x, true), { set(a.copy(sexuality = x)) }, { Text(x.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { set(AdvFilters()) }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose) { Text("Show %,d clips".format(matching)) }
            }
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

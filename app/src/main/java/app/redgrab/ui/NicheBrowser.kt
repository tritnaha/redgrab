package app.redgrab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.redgrab.ContentPrefs
import app.redgrab.Feed
import app.redgrab.Format
import app.redgrab.MainViewModel
import app.redgrab.NicheInfo
import app.redgrab.NicheOrder
import coil3.compose.AsyncImage

/** Follow / Following for one niche. Changes your RedGifs account. */
@Composable
fun FollowButton(vm: MainViewModel, n: NicheInfo, modifier: Modifier = Modifier) {
    val following = vm.follows(n.id)
    val busy = n.id in vm.followBusy
    if (following) OutlinedButton(onClick = { vm.toggleFollow(n) }, enabled = !busy, modifier = modifier) { Text("Following") }
    else FilledTonalButton(onClick = { vm.toggleFollow(n) }, enabled = !busy, modifier = modifier) { Text("Follow") }
}

/**
 * Every niche on RedGifs: all of them or one group, searched by name, with a button to follow.
 * Tapping a niche opens its clips. Takes the place of the feed in the Online tab while open.
 */
@Composable
fun NicheBrowser(vm: MainViewModel, bottomPad: androidx.compose.ui.unit.Dp) {
    val b = vm.nicheBrowse ?: return
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf(b.query) }
    val list = rememberLazyListState()
    BackHandler { vm.closeNicheBrowser() }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, b.items.size) { if (nearEnd && b.items.isNotEmpty()) vm.loadNiches() }
    LaunchedEffect(b.query, b.category, b.order) { list.scrollToItem(0) }

    LazyColumn(state = list, contentPadding = PaddingValues(bottom = bottomPad + 12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            BarSearchField(query, { query = it }, "Search niches", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), leadingIcon = true,
                onSearch = { keyboard?.hide(); vm.searchNiches(query = query) })
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(b.category == null, { vm.searchNiches(query = query, category = null) }, { Text("All") })
                b.categories.forEach { c -> FilterChip(b.category == c, { vm.searchNiches(query = query, category = if (b.category == c) null else c) }, { Text(c) }) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(NicheOrder.Subscribers, NicheOrder.Posts, NicheOrder.Name).forEach { o ->
                    FilterChip(b.order == o, { vm.searchNiches(query = query, order = o) }, { Text(o.label) })
                }
                if (b.total > 0) Text("%,d niches".format(b.total), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
        }
        items(b.items, key = { it.id }) { n ->
            Row(Modifier.fillMaxWidth().clickable { vm.closeNicheBrowser(); vm.openFeed(Feed.Niche(n.id, n.name)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(n.thumb, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (vm.sfw) Modifier.blur(12.dp) else Modifier))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Format.count(n.clips)} clips · ${Format.count(n.subscribers)} following", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    if (n.tags.isNotEmpty()) Text(n.tags.take(5).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                FollowButton(vm, n)
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                when {
                    b.loading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    b.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(b.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.loadNiches(reset = b.items.isEmpty()) }) { Text("Try again") }
                    }
                    b.items.isEmpty() -> Text("No niches found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The content filter: which kinds of content the Online tab shows. Opened from the Online tab and from Settings. */
@Composable
fun ContentPrefsDialog(vm: MainViewModel, firstTime: Boolean = false, onClose: () -> Unit) {
    var chosen by remember { mutableStateOf(vm.contentPrefs) }
    val open = ContentPrefs.unfiltered(chosen)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (firstTime) "What do you want to see?" else "Show") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { chosen = if (open) ContentPrefs.DEFAULT else ContentPrefs.ALL.toSet() }, verticalAlignment = Alignment.CenterVertically) {
                    Text("No filter", Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.SemiBold)
                    Switch(open, { on -> chosen = if (on) ContentPrefs.ALL.toSet() else ContentPrefs.DEFAULT })
                }
                ContentPrefs.ALL.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (p in chosen) chosen - p else chosen + p }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p in chosen, { on -> chosen = if (on) chosen + p else chosen - p })
                        Text(ContentPrefs.label(p))
                    }
                }
                Text(if (firstTime) "Change it later in Settings → Online." else "All sites, searches and suggestions. Also saved to your RedGifs account.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { vm.changeContentPrefs(chosen); onClose() }) { Text("Save") } },
        dismissButton = { if (!firstTime) TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

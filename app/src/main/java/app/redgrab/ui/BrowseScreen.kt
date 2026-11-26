package app.redgrab.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Switch
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Verified
import androidx.compose.ui.draw.blur
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.redgrab.Facet
import app.redgrab.MainViewModel
import coil3.compose.AsyncImage

/** One row in Browse: a tag or performer, how many clips, how many saved, and a preview. */
private data class Group(val name: String, val count: Int, val saved: Int, val thumb: Any?, val verified: Boolean = false, val subtitle: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(vm: MainViewModel, onOpen: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Tab 4 = Collections (shown first): your likes and RedGifs collections, each with a sync switch.
    var tab by rememberSaveable { mutableStateOf(4) }
    fun thumbOf(c: app.redgrab.Clip): Any? = thumbModel(s, vm.library, c)
    var filter by rememberSaveable { mutableStateOf("") }

    // Built from the app's own stored metadata; updates after every sync.
    val tags = remember(s.clips, s.saved) {
        // key = lower-cased tag; value = (name as first seen, clips with that tag)
        val m = LinkedHashMap<String, Pair<String, MutableList<app.redgrab.Clip>>>()
        s.clips.forEach { c ->
            c.tags.forEach { t -> m.getOrPut(t.trim().lowercase()) { t.trim() to mutableListOf() }.second.add(c) }
        }
        m.values.map { (name, list) -> Group(name, list.size, list.count { it.id in s.saved }, thumbOf(list.first())) }
            .sortedByDescending { it.count }
    }
    val performers = remember(s.clips, s.saved, s.performers) {
        s.clips.filter { it.user.isNotBlank() }.groupBy { it.user.lowercase() }
            .map { (key, list) ->
                val p = s.performers[key]
                Group(list.first().user, list.size, list.count { it.id in s.saved }, if (vm.offline) thumbOf(list.first()) else p?.avatar ?: thumbOf(list.first()),
                    verified = p?.verified == true || list.any { it.verified },
                    subtitle = listOfNotNull(p?.name?.takeIf { it.isNotBlank() && !it.equals(list.first().user, true) },
                        p?.followers?.takeIf { it > 0 }?.let { "%,d followers".format(it) }).joinToString(" · "))
            }
            .sortedByDescending { it.count }
    }
    val niches = remember(s.clips, s.saved) {
        s.clips.flatMap { c -> c.niches.map { it to c } }.groupBy { it.first.lowercase() }
            .map { (_, pairs) ->
                val list = pairs.map { it.second }
                Group(pairs.first().first, list.size, list.count { it.id in s.saved }, thumbOf(list.first()))
            }
            .sortedByDescending { it.count }
    }
    val categories = remember(s.clips, s.saved) {
        s.clips.filter { it.category.isNotBlank() }.groupBy { it.category.lowercase() }
            .map { (_, list) -> Group(list.first().category, list.size, list.count { it.id in s.saved }, thumbOf(list.first())) }
            .sortedByDescending { it.count }
    }
    val kind = when (tab) { 0 -> Facet.Kind.Tag; 1 -> Facet.Kind.Performer; 2 -> Facet.Kind.Niche; else -> Facet.Kind.Category }
    val groups = (when (tab) { 0 -> tags; 1 -> performers; 2 -> niches; else -> categories }).filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { TopAppBar(title = { Text("Browse") }, expandedHeight = TopBarHeight) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // The tab row lists Collections first although its index is 4.
            PrimaryScrollableTabRow(selectedTabIndex = if (tab == 4) 0 else tab + 1, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
                Tab(tab == 4, { tab = 4 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text("Collections · ${s.collections.size + 1}") })
                Tab(tab == 0, { tab = 0 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text("Tags · ${tags.size}") })
                Tab(tab == 1, { tab = 1 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text("Performers · ${performers.size}") })
                Tab(tab == 2, { tab = 2 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text("Niches · ${niches.size}") })
                Tab(tab == 3, { tab = 3 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text("Mine · ${categories.size}") })
            }
            if (tab == 4) {
                CollectionsList(vm, s, onOpen)
                return@Column
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                placeholder = { Text(when (tab) { 0 -> "Find a tag (e.g. anal, pov)…"; 1 -> "Find a performer…"; 2 -> "Find a niche…"; else -> "Find one of your categories…" }) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Close, "Clear") } },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            if (groups.isEmpty()) {
                Text(
                    when {
                        s.clips.isEmpty() -> "Sync your likes first. Tags and performers come from RedGifs and are stored in the app."
                        filter.isNotBlank() -> "Nothing matches “$filter”."
                        tab == 2 -> "No niches yet. RedGifs niches (its own communities, like categories) are read during sync."
                        tab == 3 -> "No categories yet. Long-press clips on the Saved tab, then tap the edit-info icon to set one."
                        else -> "No ${if (tab == 0) "tags" else "performers"} yet. Pull down on the Saved tab to sync."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(groups, key = { it.name }) { g ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            vm.browse(Facet(kind, g.name))
                            onOpen()
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            g.thumb, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp).aspectRatio(1f)
                                .clip(if (tab == 1) CircleShape else RoundedCornerShape(10.dp))
                                .then(if (vm.sfw) Modifier.blur(16.dp) else Modifier),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (g.verified) Icon(Icons.Default.Verified, "Verified", tint = androidx.compose.ui.graphics.Color(0xFF5BB8FF),
                                    modifier = Modifier.padding(start = 4.dp).size(16.dp))
                            }
                            if (g.subtitle.isNotBlank()) Text(g.subtitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text("%,d clips · %,d saved".format(g.count, g.saved),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

/**
 * Your likes and RedGifs collections. The switch marks one as synced: everything in it is
 * downloaded, and anything added to it later is downloaded automatically after each sync.
 */
@Composable
private fun CollectionsList(vm: MainViewModel, s: app.redgrab.LibraryState, onOpen: () -> Unit) {
    data class Row_(val key: String, val name: String, val clips: List<app.redgrab.Clip>, val onlineCount: Int)
    val rows = remember(s.clips, s.collections) {
        val originals = s.originals
        listOf(Row_(app.redgrab.Library.WATCH_LIKES, "Likes", originals.filter { it.liked }, s.likedTotal)) +
            s.collections.map { c -> Row_(c.id, c.name, originals.filter { c.id in it.colIds }, c.count) }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Switch on a collection to keep it synced: its clips are saved to the phone, and new ones you add on RedGifs are saved automatically after each sync. A clip in several collections is stored once.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        items(rows, key = { it.key }) { r ->
            val saved = r.clips.count { it.id in s.saved }
            val watched = r.key in s.watched
            val failed = r.clips.count { it.id in s.failed && it.id !in s.saved }
            val dead = r.clips.count { it.id in s.dead && it.id !in s.saved }
            Row(
                Modifier.fillMaxWidth().clickable { vm.openCollection(r.key); onOpen() }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    r.clips.firstOrNull()?.let { thumbModel(s, vm.library, it) }, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .then(if (vm.sfw) Modifier.blur(16.dp) else Modifier),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (r.key == app.redgrab.Library.WATCH_LIKES) Icons.Default.Favorite else Icons.Default.Bookmark, null,
                            Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "%,d clips · %,d saved".format(r.clips.size, saved) + when {
                            failed > 0 -> " · %,d failed".format(failed)
                            // Deleted clips can't be saved: the collection is in sync once the rest is.
                            watched && saved + dead < r.clips.size -> " · syncing"
                            watched -> " · in sync"
                            else -> ""
                        } + if (dead > 0) " · %,d removed".format(dead) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = when { failed > 0 -> RgAmber; watched -> RgGreen; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    )
                }
                Switch(watched, { vm.setWatched(r.key, it) })
            }
        }
        if (s.collections.isEmpty()) item {
            Text("No collections found yet. Collections you make on RedGifs appear here after a sync (with \"Include my collections\" on in Settings).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
        }
    }
}

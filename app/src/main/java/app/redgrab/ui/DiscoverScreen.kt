package app.redgrab.ui

import app.redgrab.*
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import app.redgrab.NicheInfo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.redgrab.Clip
import app.redgrab.Feed
import app.redgrab.Format
import app.redgrab.MainViewModel
import app.redgrab.OnlineOrder
import app.redgrab.OnlineSource
import coil3.compose.AsyncImage

/**
 * Online browsing, one site at a time (switch in the title): RedGifs (the niches you follow,
 * search, a creator's clips, "related"), Reddit (front page, subreddits, your saved and upvoted
 * posts), FikFap (a hashtag or a user) and sex.com (GIF search). Nothing here is saved until
 * you save it; saved or liked clips are marked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(vm: MainViewModel, onSignIn: () -> Unit, onOpenSettings: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val d = vm.discover
    val source = d.feed.source
    // Kept in the view model, so the place in a feed survives a visit to another tab.
    val grid = vm.onlineGrid
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable(d.feed) { mutableStateOf(d.feed.query) }
    val byId = remember(s.clips) { s.clips.associateBy { it.id } }
    val home = d.feed == vm.homeFeed(source)
    // What each site needs before anything can load: RedGifs a sign-in, Reddit a client ID.
    val ready = when (source) {
        OnlineSource.All -> true
        OnlineSource.RedGifs -> vm.signedIn
        OnlineSource.Reddit -> vm.redditClientId.isNotBlank()
        else -> true
    }

    LaunchedEffect(ready, vm.sfw, source, vm.redditUser) {
        if (ready && !vm.sfw) {
            if (source == OnlineSource.RedGifs) vm.loadFollowedNiches()
            if (source == OnlineSource.RedGifs || source == OnlineSource.All) vm.ensureSearchIndex()
            if (source == OnlineSource.Reddit) vm.loadSubreddits()
            if (d.items.isEmpty() && !d.loading && d.error == null) vm.loadMoreDiscover()
        }
    }
    // Load the next page shortly before the end of the list.
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 9 } }
    LaunchedEffect(nearEnd, d.items.size) { if (nearEnd && d.items.isNotEmpty()) vm.loadMoreDiscover() }
    // A different feed, order or page starts at the top; coming back to the same one doesn't.
    LaunchedEffect(d.feed, d.order, d.firstPage) {
        val key = Triple(d.feed, d.order, d.firstPage)
        if (vm.onlineGridFor != key) { if (vm.onlineGridFor != null) grid.scrollToItem(0); vm.onlineGridFor = key }
    }
    val spot = followClipView(vm, grid, remember(d.items) { d.items.map { it.id } })

    BackHandler(enabled = !home) { vm.openFeed(vm.homeFeed(source)) }
    var showPrefs by remember { mutableStateOf(false) }
    if (showPrefs) ContentPrefsDialog(vm) { showPrefs = false }

    val m = gridMetrics(vm.wall)
    val navPad = LocalNavPad.current
    val bars = rememberChromeScroll(grid, pinned = false)
    var showView by remember { mutableStateOf(false) }
    if (showView) ViewSheet(vm) { showView = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.nestedScroll(bars.nestedScrollConnection),
        topBar = {
            TopAppBar(
                expandedHeight = TopBarHeight, scrollBehavior = bars,
                // Accent-coloured header: you always know this is the online side.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                actions = {
                    OnlinePill()
                    // The content filter: applies to every site, and is the same setting as in Settings.
                    if (!vm.sfw) IconButton(onClick = { showPrefs = true }) { Icon(Icons.Default.Tune, "Filter: what to show") }
                    IconButton(onClick = { showView = true }) { Icon(Icons.Default.GridView, "View: grid or wall, preview size") }
                },
                navigationIcon = {
                    if (vm.nicheBrowserOpen && source == OnlineSource.RedGifs) IconButton(onClick = vm::closeNicheBrowser) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else if (!home) IconButton(onClick = { vm.openFeed(vm.homeFeed(source)) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to all") }
                },
                title = {
                    // The title is the source switch: tap it to pick another site.
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(if (vm.nicheBrowserOpen && source == OnlineSource.RedGifs) "Niches" else if (home) source.label else d.feed.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(if (home) "Online" else source.label, d.order.label.takeIf { d.feed.orders.isNotEmpty() }).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.ArrowDropDown, "Choose source")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            OnlineSource.entries.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text(o.label) },
                                    onClick = { menu = false; vm.changeSource(o) },
                                    trailingIcon = { if (o == source) Icon(Icons.Default.Check, "Selected") },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        // What stands in for the feed when it can't be shown.
        val notice = Modifier.padding(pad).padding(bottom = navPad)
        when {
            source == OnlineSource.RedGifs && !vm.signedIn -> { Notice(notice, title = "Sign in to browse RedGifs", action = "Sign in", onAction = onSignIn); return@Scaffold }
            source == OnlineSource.Reddit && vm.redditClientId.isBlank() -> { Notice(notice, title = "Reddit needs a client ID", action = "Settings", onAction = onOpenSettings); return@Scaffold }
            // SFW mode: online browsing is paused entirely; no thumbnails are fetched.
            vm.sfw -> {
                Notice(notice, Icons.Default.VisibilityOff, "Online browsing is paused in SFW mode", "Nothing is loaded while SFW mode is on.", "Turn off SFW mode") { vm.changeSfw(false) }
                return@Scaffold
            }
            vm.offline -> { Notice(notice, Icons.Default.CloudOff, "You're offline", "Online browsing needs a connection. Everything on the Saved tab still works."); return@Scaffold }
        }
        // Online clips animate only if "also preview unsaved clips" is on; saved ones always can.
        val streamOk = vm.previewUnsaved
        val onlineById = remember(d.items) { d.items.associateBy { it.id } }
        val moving = rememberActivePreviews(grid, vm.previewsActive, vm.previewLimit, changeKey = s.saved.size,
            cost = { id -> previewCost(byId[id] ?: onlineById[id], s.qualities[id], vm.previewCopies && id in s.localPreviews) }) { id -> id in s.saved || streamOk }
        val head = Modifier.padding(horizontal = m.head)
        // Shortcuts: on RedGifs "For you" and the general feed; on Reddit your saved and upvoted posts and the
        // subreddits you joined; elsewhere what you opened recently.
        // The niche list takes the place of the feed while it is open.
        if (source == OnlineSource.RedGifs && vm.nicheBrowserOpen) {
            Box(Modifier.padding(pad).fillMaxSize()) { NicheBrowser(vm, navPad) }
            return@Scaffold
        }
        val shortcuts = remember(source, d.subs, vm.recents, vm.redditUser) {
            val mine = if (source == OnlineSource.Reddit && vm.redditUser != null) listOf(Feed.RedditSaved, Feed.RedditUpvoted) else emptyList()
            val recent = if (source == OnlineSource.RedGifs) listOf(Feed.MyNiches, Feed.ForYou, Feed.Search("")) else vm.recents.map { Feed.fromQuery(source, it) }
            (mine + recent + d.subs.map { Feed.Subreddit(it) }).distinctBy { it.title.lowercase() }.filter { !it.needsQuery }
        }
        Box(Modifier.padding(pad).fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            state = grid,
            columns = StaggeredGridCells.Fixed(gridColumns(vm.columns)),
            contentPadding = PaddingValues(start = m.side, end = m.side, top = 6.dp, bottom = navPad + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(m.gap),
            verticalItemSpacing = m.gap,
            modifier = Modifier.fillMaxSize().then(pinchColumns { vm.changeColumns(vm.columns + it) }),
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                val hint = when (source) {
                    OnlineSource.All -> "Search all sites"
                    OnlineSource.RedGifs -> "Search RedGifs (tags, words)…"
                    OnlineSource.Reddit -> "Subreddit, or u/name"
                    OnlineSource.FikFap -> "Hashtag, or @user"
                    OnlineSource.SexCom -> "Search GIFs"
                    OnlineSource.EroMe -> "Search, or @user"
                    OnlineSource.FourChan -> "Search /gif/ threads"
                }
                BarSearchField(query, { query = it; vm.suggest(it) }, hint, modifier = head, leadingIcon = true,
                    onSearch = { keyboard?.hide(); vm.suggest(""); vm.openFeed(vm.feedFor(source, query)) })
            }
            // While typing: things that exist and match (tags and niches from the lists kept on the phone).
            if (vm.suggestions.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Column(head.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        vm.suggestions.forEach { sug ->
                            Row(Modifier.fillMaxWidth().clickable { keyboard?.hide(); vm.suggest(""); vm.openFeed(sug.feed) }.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(sug.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(sug.kind.word, sug.detail).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
            if (shortcuts.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CompactTouch {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = m.head)) {
                            items(shortcuts, key = { it.title }) { f -> FilterChip(d.feed == f, { vm.openFeed(f) }, { Text(f.title, maxLines = 1) }) }
                            // Every niche on RedGifs, to look through, search and follow.
                            if (source == OnlineSource.RedGifs) item(key = "browse-niches") {
                                AssistChip(onClick = vm::openNicheBrowser, label = { Text("Browse niches") }, leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                }
            }
            // Niches are a RedGifs thing.
            if (source == OnlineSource.RedGifs && d.niches.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Column {
                        Text("Niches you follow", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = head.padding(top = 2.dp, bottom = 4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = m.head)) {
                            items(d.niches, key = { it.id }) { n ->
                                val selected = (d.feed as? Feed.Niche)?.id == n.id
                                Row(
                                    Modifier.clip(RoundedCornerShape(50))
                                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable { vm.openFeed(Feed.Niche(n.id, n.name)) }.padding(end = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(n.thumb, null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                                            .then(if (vm.sfw) Modifier.blur(10.dp) else Modifier))
                                    Spacer(Modifier.width(8.dp))
                                    Text(n.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            } else if (source == OnlineSource.RedGifs && d.nichesLoaded && home) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text("Follow niches or tags to fill “My feed”.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = head)
                }
            }
            // Tags you follow (kept in the app): one tap opens the tag.
            if (source == OnlineSource.RedGifs && vm.followedTags.isNotEmpty() && (home || d.feed is Feed.Search || d.feed is Feed.Tags)) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CompactTouch {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = m.head), verticalAlignment = Alignment.CenterVertically) {
                            item(key = "followed-tags-label") { Text("Tags you follow", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                            items(vm.followedTags, key = { "ft$it" }) { t ->
                                FilterChip((d.feed is Feed.Search || d.feed is Feed.Tags) && d.feed.query.equals(t, ignoreCase = true), { vm.openFeed(vm.feedFor(OnlineSource.RedGifs, t)) }, { Text("#$t", maxLines = 1) })
                            }
                        }
                    }
                }
            }
            // Clips the filter left out of this feed.
            if (d.hidden > 0) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Row(head, verticalAlignment = Alignment.CenterVertically) {
                        Text("%,d hidden by your filter".format(d.hidden), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { showPrefs = true }) { Text("Filter") }
                    }
                }
            }
            // Words that are existing tags: clips with exactly those tags, or the site's looser text search.
            val asTags = when (val f = d.feed) { is Feed.Tags -> f.tags; is Feed.Search -> vm.tagsIn(f.query); else -> null }
            if (asTags != null) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CompactTouch {
                        Row(head, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(d.feed is Feed.Tags, { if (d.feed !is Feed.Tags) vm.openFeed(Feed.Tags(asTags)) }, { Text(if (asTags.size > 1) "Every tag" else "Exact tag") })
                            FilterChip(d.feed is Feed.Search, { if (d.feed !is Feed.Search) vm.openFeed(Feed.Search(asTags.joinToString(" "))) }, { Text("Text search") })
                        }
                    }
                }
            }
            // A tag, or a search for words: follow it from here.
            ((d.feed as? Feed.Search)?.query?.takeIf { it.isNotBlank() } ?: (d.feed as? Feed.Tags)?.tags?.singleOrNull())?.let { tag ->
                item(span = StaggeredGridItemSpan.FullLine) {
                    Row(head, verticalAlignment = Alignment.CenterVertically) {
                        Text("#$tag", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (vm.followsTag(tag)) androidx.compose.material3.OutlinedButton(onClick = { vm.toggleTag(tag) }) { Text("Following tag") }
                        else androidx.compose.material3.FilledTonalButton(onClick = { vm.toggleTag(tag) }) { Text("Follow tag") }
                    }
                }
            }
            // A niche's own feed: follow it from here.
            (d.feed as? Feed.Niche)?.let { f ->
                item(span = StaggeredGridItemSpan.FullLine) {
                    val known = d.niches.firstOrNull { it.id == f.id } ?: vm.nicheBrowse?.items?.firstOrNull { it.id == f.id } ?: NicheInfo(f.id, f.name, null, 0, 0)
                    Row(head, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (known.subscribers > 0) "${Format.count(known.clips)} clips · ${Format.count(known.subscribers)} following" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        FollowButton(vm, known)
                    }
                }
            }
            if (d.feed.orders.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CompactTouch {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = m.head), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.feed.orders.forEach { o -> FilterChip(d.order == o, { vm.changeDiscoverOrder(o) }, { Text(o.label) }) }
                        }
                    }
                }
            }
            itemsIndexed(d.items, key = { _, c -> c.id }) { i, c ->
                val mine = byId[c.id]
                OnlineTile(c, wall = vm.wall, corner = m.corner, compact = gridColumns(vm.columns) >= 5, liked = mine?.liked == true, inCollection = mine != null && mine.colIds.isNotEmpty(),
                    saved = c.id in s.saved, sfw = vm.sfw,
                    preview = if (isPreviewing(moving, c.id)) ({ VideoPreview(vm, mine ?: c, streamOk, Modifier.fillMaxSize()) }) else null,
                    spot = c.id == spot,
                ) { vm.openOnlinePlayer(d.items, i) }
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        d.loading -> CircularProgressIndicator()
                        d.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.loadMoreDiscover() }) { Text("Try again") }
                        }
                        d.feed.needsQuery -> Text(when (source) {
                                OnlineSource.FikFap -> "Enter a hashtag or @user."
                                OnlineSource.EroMe -> "Enter a search or @user."
                                else -> "Enter a search."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // More to come: normally loaded by scrolling; the button is for pages that added nothing to scroll to.
                        d.hasMore -> { if (d.page > 0) TextButton(onClick = { vm.loadMoreDiscover() }) { Text("Load more") } }
                        d.items.isEmpty() -> Text("Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text("That's everything.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        // Where you are in the feed, and the way back up. Pages are the site's own; on sites that
        // number them (RedGifs, sex.com) any page can be opened, on the others only those loaded so far.
        val itemIndex = remember(d.items) { HashMap<String, Int>(d.items.size * 2).apply { d.items.forEachIndexed { i, c -> put(c.id, i) } } }
        val page by remember(d.items, d.pageStarts, d.firstPage) {
            derivedStateOf {
                pageAt(grid.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { (it.key as? String)?.let(itemIndex::get) } ?: 0, d.pageStarts, d.firstPage)
            }
        }
        val atTop by remember { derivedStateOf { grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0 } }
        val lastLoaded = d.firstPage + (d.pageStarts.size - 1).coerceAtLeast(0)
        val known = d.feed.numbered && d.pages > 0
        if (d.items.isNotEmpty() && (lastLoaded > 1 || known || !atTop)) {
            val jumps = rememberCoroutineScope()
            PositionPill(page, if (known) d.pages else lastLoaded, more = !known && d.hasMore,
                reach = if (known) d.pages else lastLoaded, atTop = atTop && d.firstPage == 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = navPad + 8.dp),
                offset = vm.pillOffset, onMoved = vm::changePillOffset,
                onTop = { if (d.firstPage > 1) vm.jumpToOnlinePage(1) else jumps.launch { grid.scrollToItem(0) } },
                onJump = { n ->
                    val at = d.pageStarts.getOrNull(n - d.firstPage)
                    when {
                        // Already loaded: scroll there. The rows above the clips come first in the grid; one row follows them.
                        at != null -> jumps.launch { grid.scrollToItem(if (n == d.firstPage) 0 else (grid.layoutInfo.totalItemsCount - d.items.size - 1).coerceAtLeast(0) + at) }
                        d.feed.numbered -> vm.jumpToOnlinePage(n)
                    }
                })
        }
        }
    }
}

/** Small "ONLINE" label used wherever content comes from a site rather than the phone. */
@Composable
fun OnlinePill(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(RgRed).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Public, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("ONLINE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OnlineTile(c: Clip, wall: Boolean, corner: Dp, compact: Boolean, liked: Boolean, inCollection: Boolean, saved: Boolean, sfw: Boolean,
                       preview: (@Composable () -> Unit)?, spot: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(tileAspect(c, wall)).clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .spotlit(spot, RoundedCornerShape(corner), Color.White).clickable(onClick = onClick),
    ) {
        AsyncImage(c.thumb, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (sfw) Modifier.blur(28.dp) else Modifier))
        if (!sfw) preview?.invoke()
        if (!wall && !compact) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
            // Pictures say their type; a length is shown when the site gives one.
            Text(if (c.isImage) c.fileExt.uppercase() else if (c.duration > 0) Format.duration(c.duration) else "",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp))
        }
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(28.dp)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent))))
        // Every online clip shows one of three states.
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            // Same icon language as the Saved tab: heart / bookmark / globe, green and filled once it's on the phone.
            SourceIcon(liked = liked, inCollection = inCollection, saved = saved, size = if (compact) 14.dp else 18.dp)
        }
    }
}

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
    val grid = rememberLazyStaggeredGridState()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable(d.feed) { mutableStateOf(d.feed.query) }
    val byId = remember(s.clips) { s.clips.associateBy { it.id } }
    val home = d.feed == Feed.home(source)
    // What each site needs before anything can load: RedGifs a sign-in, Reddit a client ID.
    val ready = when (source) {
        OnlineSource.RedGifs -> vm.signedIn
        OnlineSource.Reddit -> vm.redditClientId.isNotBlank()
        else -> true
    }

    LaunchedEffect(ready, vm.sfw, source, vm.redditUser) {
        if (ready && !vm.sfw) {
            if (source == OnlineSource.RedGifs) vm.loadFollowedNiches()
            if (source == OnlineSource.Reddit) vm.loadSubreddits()
            if (d.items.isEmpty() && !d.loading && d.error == null) vm.loadMoreDiscover()
        }
    }
    // Load the next page shortly before the end of the list.
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 9 } }
    LaunchedEffect(nearEnd, d.items.size) { if (nearEnd && d.items.isNotEmpty()) vm.loadMoreDiscover() }
    LaunchedEffect(d.feed, d.order) { grid.scrollToItem(0) }

    BackHandler(enabled = !home) { vm.openFeed(Feed.home(source)) }

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
                    IconButton(onClick = { showView = true }) { Icon(Icons.Default.GridView, "View: grid or wall, preview size") }
                },
                navigationIcon = {
                    if (!home) IconButton(onClick = { vm.openFeed(Feed.home(source)) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to all") }
                },
                title = {
                    // The title is the source switch: tap it to pick another site.
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(if (home) source.label else d.feed.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
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
        if (source == OnlineSource.RedGifs && !vm.signedIn) {
            Column(Modifier.padding(pad).padding(bottom = navPad).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Sign in to browse RedGifs", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSignIn) { Text("Sign in") }
            }
            return@Scaffold
        }
        if (source == OnlineSource.Reddit && vm.redditClientId.isBlank()) {
            Column(Modifier.padding(pad).padding(bottom = navPad).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Reddit needs a client ID", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenSettings) { Text("Settings") }
            }
            return@Scaffold
        }
        if (vm.sfw) {
            // SFW mode: online browsing is paused entirely; no thumbnails are fetched.
            Column(Modifier.padding(pad).padding(bottom = navPad).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.VisibilityOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("Online browsing is paused in SFW mode", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text("Nothing is loaded while SFW mode is on. Turn it off with the eye icon on the Saved tab.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            return@Scaffold
        }
        if (vm.offline) {
            Column(Modifier.padding(pad).padding(bottom = navPad).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("You're offline", style = MaterialTheme.typography.titleMedium)
                Text("Online browsing needs a connection. Everything on the Saved tab still works.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            return@Scaffold
        }
        // Online clips animate only if "also preview unsaved clips" is on; saved ones always can.
        val streamOk = vm.previewUnsaved
        val onlineById = remember(d.items) { d.items.associateBy { it.id } }
        val moving = rememberActivePreviews(grid, vm.previewsActive, vm.previewLimit, changeKey = s.saved.size,
            cost = { id -> previewCost(byId[id] ?: onlineById[id], s.qualities[id]) }) { id -> id in s.saved || streamOk }
        val head = Modifier.padding(horizontal = m.head)
        // Shortcuts: on Reddit your saved and upvoted posts and the subreddits you joined; then what you opened recently.
        val shortcuts = remember(source, d.subs, vm.recents, vm.redditUser) {
            val mine = if (source == OnlineSource.Reddit && vm.redditUser != null) listOf(Feed.RedditSaved, Feed.RedditUpvoted) else emptyList()
            val recent = if (source == OnlineSource.RedGifs) emptyList() else vm.recents.map { Feed.fromQuery(source, it) }
            (mine + recent + d.subs.map { Feed.Subreddit(it) }).distinctBy { it.title.lowercase() }.filter { !it.needsQuery }
        }
        LazyVerticalStaggeredGrid(
            state = grid,
            columns = StaggeredGridCells.Fixed(vm.columns),
            contentPadding = PaddingValues(start = m.side, end = m.side, top = 6.dp, bottom = navPad + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(m.gap),
            verticalItemSpacing = m.gap,
            modifier = Modifier.padding(pad).fillMaxSize().then(pinchColumns { vm.changeColumns(vm.columns + it) }),
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                val hint = when (source) {
                    OnlineSource.RedGifs -> "Search RedGifs (tags, words)…"
                    OnlineSource.Reddit -> "Subreddit, or u/name"
                    OnlineSource.FikFap -> "Hashtag, or @user"
                    OnlineSource.SexCom -> "Search GIFs"
                    OnlineSource.EroMe -> "Search, or @user"
                    OnlineSource.FourChan -> "Search /gif/ threads"
                }
                BarSearchField(query, { query = it }, hint, modifier = head, leadingIcon = true,
                    onSearch = { keyboard?.hide(); vm.openFeed(Feed.fromQuery(source, query)) })
            }
            if (shortcuts.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = m.head)) {
                            items(shortcuts, key = { it.title }) { f -> FilterChip(d.feed == f, { vm.openFeed(f) }, { Text(f.title, maxLines = 1) }) }
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
                    Text("Niches you follow on RedGifs show up here. Follow some on RedGifs, or search above.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = head)
                }
            }
            if (d.feed.orders.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = m.head), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.feed.orders.forEach { o -> FilterChip(d.order == o, { vm.changeDiscoverOrder(o) }, { Text(o.label) }) }
                        }
                    }
                }
            }
            itemsIndexed(d.items, key = { _, c -> c.id }) { i, c ->
                val mine = byId[c.id]
                OnlineTile(c, wall = vm.wall, corner = m.corner, compact = vm.columns >= 5, liked = mine?.liked == true, inCollection = mine != null && mine.colIds.isNotEmpty(),
                    saved = c.id in s.saved, sfw = vm.sfw,
                    preview = if (isPreviewing(moving, c.id)) ({ VideoPreview(vm, mine ?: c, streamOk, Modifier.fillMaxSize()) }) else null,
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
                       preview: (@Composable () -> Unit)?, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(tileAspect(c, wall)).clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick),
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

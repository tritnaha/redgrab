package app.redgrab.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.Sd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.blur
import app.redgrab.Sort
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SwipeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.redgrab.AppLog
import app.redgrab.Clip
import app.redgrab.DownloadProgress
import app.redgrab.Facet
import app.redgrab.Filter
import app.redgrab.Origin
import app.redgrab.Quality
import app.redgrab.visibleClips
import app.redgrab.Format
import app.redgrab.LibraryState
import app.redgrab.MainViewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: MainViewModel, snack: SnackbarHostState, onSignIn: () -> Unit, onOpenSettings: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val selecting = vm.selection.isNotEmpty()

    // Re-render "synced x min ago" every 30 s.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val shown = remember(s.clips, s.qualities, s.failures, s.unseen, s.watched, vm.filter, vm.query, vm.facet, vm.adv, vm.origin) {
        visibleClips(s, vm.filter, vm.query, vm.facet, vm.adv, vm.origin)
    }
    // Per-origin counts for the chips, within the current state filter and search.
    val originCounts = remember(s.clips, s.qualities, s.failures, s.unseen, s.watched, vm.filter, vm.query, vm.adv) {
        val base = visibleClips(s, vm.filter, vm.query, null, vm.adv)
        Origin.entries.associateWith { o -> base.count { o.matches(s, it) } }
    }
    val has = remember(s.clips, s.watched, s.collections) {
        mapOf(
            Origin.Collections to s.collections.isNotEmpty(), Origin.Synced to s.watched.isNotEmpty(),
            Origin.Imported to s.clips.any { Origin.Imported.matches(s, it) }, Origin.Online to s.clips.any { Origin.Online.matches(s, it) },
        )
    }
    var colMenu by remember { mutableStateOf(false) }
    // Download progress updates several times a second; only these three derived values touch this screen.
    val dlState = vm.download.collectAsStateWithLifecycle()
    val dlRunning by remember { derivedStateOf { dlState.value.running } }
    val dlCurrent by remember { derivedStateOf { dlState.value.currentId } }
    val dlHasMessage by remember { derivedStateOf { dlState.value.message.isNotEmpty() } }
    var showFilters by remember { mutableStateOf(false) }
    var showView by remember { mutableStateOf(false) }
    var confirmSfwOff by remember { mutableStateOf(false) }
    var confirmSaveAll by remember { mutableStateOf(false) }
    var selMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Search lives in the top bar: closed it's an icon, open it replaces the title.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searching = searchOpen || vm.query.isNotEmpty()
    BackHandler(searching && !selecting) { vm.query = ""; searchOpen = false }
    // Chip counts follow the current search/tag/performer filter.
    val narrowed = remember(s.clips, s.qualities, vm.query, vm.facet, vm.adv) { visibleClips(s, Filter.All, vm.query, vm.facet, vm.adv) }
    val counts = remember(narrowed, s.qualities) {
        intArrayOf(
            narrowed.count { it.id !in s.saved },
            narrowed.count { it.id in s.saved },
            narrowed.count { Quality.HD in (s.qualities[it.id] ?: emptySet()) },
            narrowed.count { Quality.SD in (s.qualities[it.id] ?: emptySet()) },
            narrowed.count { it.isCopy || it.metaEdited },
        )
    }
    val newHere = counts[0]; val savedHere = counts[1]; val hdHere = counts[2]; val sdHere = counts[3]; val editedHere = counts[4]

    // One layout for both modes: "Grid" gives every tile the same shape, "Wall" each clip's own.
    val gridState = rememberLazyStaggeredGridState()
    val m = gridMetrics(vm.wall)
    val navPad = LocalNavPad.current
    // Both bars slide away while you scroll down and come back when you scroll up.
    val bars = rememberChromeScroll(gridState, pinned = selecting)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.nestedScroll(bars.nestedScrollConnection),
        topBar = {
            if (selecting) {
                val toSave = vm.selection.count { it !in s.saved }
                TopAppBar(
                    expandedHeight = TopBarHeight, scrollBehavior = bars,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.primaryContainer),
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Cancel selection") } },
                    title = { Text("${vm.selection.size} selected", style = MaterialTheme.typography.titleMedium, maxLines = 1) },
                    actions = {
                        // Saving the selection: a plain icon with the count, where the big button used to be.
                        if (toSave > 0) IconButton(onClick = vm::downloadSelected) {
                            BadgedBox(badge = { androidx.compose.material3.Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(shortCount(toSave)) } }) {
                                Icon(Icons.Default.Download, "Save the $toSave selected clips that aren't on this phone")
                            }
                        }
                        IconButton(onClick = { vm.shareSelectedToWhatsApp() }) { Icon(Icons.AutoMirrored.Filled.Send, "Share to WhatsApp") }
                        IconButton(onClick = { vm.requestDelete(vm.selection) }) { Icon(Icons.Default.DeleteOutline, "Delete selected") }
                        Box {
                            IconButton(onClick = { selMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(selMenu, { selMenu = false }) {
                                DropdownMenuItem(text = { Text("Select all not saved") }, leadingIcon = { Icon(Icons.Default.DoneAll, null) },
                                    onClick = { selMenu = false; vm.selectAllNew() })
                                DropdownMenuItem(text = { Text("Edit info (performer, tags…)") }, leadingIcon = { Icon(Icons.Default.EditNote, null) },
                                    onClick = { selMenu = false; vm.editInfo(vm.selection) })
                                DropdownMenuItem(text = { Text("Download in SD") }, leadingIcon = { Icon(Icons.Default.Sd, null) },
                                    onClick = { selMenu = false; vm.downloadSelectedAs(Quality.SD) })
                                DropdownMenuItem(text = { Text("Download in HD") }, leadingIcon = { Icon(Icons.Default.Hd, null) },
                                    onClick = { selMenu = false; vm.downloadSelectedAs(Quality.HD) })
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    expandedHeight = TopBarHeight, scrollBehavior = bars,
                    colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surface),
                    title = {
                        if (searching) {
                            BarSearchField(vm.query, { vm.query = it }, "Search tags, performers, IDs…",
                                autoFocus = vm.query.isEmpty(), onClose = { vm.query = ""; searchOpen = false })
                        } else Column {
                            Text("Saved", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            // The three big number cards, folded into one line.
                            Text(
                                when {
                                    s.refreshing -> s.refreshMessage.ifEmpty { "Syncing…" }
                                    s.clips.isEmpty() -> if (vm.offline) "Offline" else "On this phone"
                                    else -> (if (vm.offline) "Offline · " else "") + "%,d on phone".format(s.savedCount) +
                                        (if (s.newCount > 0) " · %,d to save".format(s.newCount) else " · all saved")
                                },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (!searching) IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, "Search") }
                        // "Save all": a small icon with the number waiting, instead of a button over the previews.
                        if (!dlRunning && s.newCount > 0 && vm.signedIn) IconButton(onClick = { confirmSaveAll = true }) {
                            BadgedBox(badge = { androidx.compose.material3.Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(shortCount(s.newCount)) } }) {
                                Icon(Icons.Default.CloudDownload, "Save all %,d clips that aren't on this phone yet".format(s.newCount))
                            }
                        }
                        IconButton(onClick = { if (vm.sfw) confirmSfwOff = true else vm.changeSfw(true) }) {
                            Icon(if (vm.sfw) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (vm.sfw) "SFW mode on (tap to turn off)" else "Turn on SFW mode",
                                tint = if (vm.sfw) RgAmber else LocalContentColor.current)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "View and sync") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Column { Text("View"); Text(if (vm.wall) "Wall · ${vm.columns} per row" else "Grid · ${vm.columns} per row",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                    leadingIcon = { Icon(Icons.Default.GridView, null) },
                                    onClick = { menu = false; showView = true })
                                DropdownMenuItem(
                                    text = { Column { Text(if (s.refreshing) "Syncing…" else "Sync now"); Text("Synced ${Format.ago(s.lastSynced, now)}",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null) }, enabled = !s.refreshing,
                                    onClick = { menu = false; vm.refresh() })
                            }
                        }
                    },
                )
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = s.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            // Moving previews: the first few eligible tiles on screen, once scrolling stops.
            val streamOk = vm.previewUnsaved && !vm.offline
            val shownById = remember(shown) { shown.associateBy { it.id } }
            val moving = rememberActivePreviews(gridState, vm.previewsActive, vm.previewLimit, changeKey = s.saved.size,
                cost = { id -> previewCost(shownById[id], s.qualities[id], vm.previewCopies && id in s.localPreviews) }) { id ->
                id in s.saved || (streamOk && shownById[id]?.let { it.sd != null || it.hd != null } == true)
            }
            // Rows above the tiles keep a margin even when the tiles themselves run edge to edge.
            val head = Modifier.padding(horizontal = m.head)
            LazyVerticalStaggeredGrid(
                state = gridState,
                columns = StaggeredGridCells.Fixed(gridColumns(vm.columns)),
                contentPadding = PaddingValues(start = m.side, end = m.side, top = 2.dp, bottom = navPad + 12.dp),
                horizontalArrangement = Arrangement.spacedBy(m.gap),
                verticalItemSpacing = m.gap,
                // Pinch with two fingers to change how many previews fit per row.
                modifier = Modifier.fillMaxSize().then(pinchColumns { vm.changeColumns(vm.columns + it) }),
            ) {
                if (!vm.signedIn || vm.needsSignIn) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Box(head) {
                            Banner(Icons.AutoMirrored.Filled.Login,
                                if (vm.needsSignIn) "Your RedGifs session expired" else "Sign in to RedGifs",
                                "RedGrab reads your likes and collections from your account.",
                                if (vm.needsSignIn) "Renew" else "Sign in", onSignIn)
                        }
                    }
                }
                if (vm.signedIn && !vm.hasFolder) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Box(head) {
                            Banner(Icons.Default.FolderOpen, "Choose where to save clips",
                                "Pick any folder, including one inside Secure Folder.", "Choose folder", onOpenSettings)
                        }
                    }
                }
                if (dlRunning || dlHasMessage) {
                    item(span = StaggeredGridItemSpan.FullLine) { Box(head) { DownloadCard(dlState.value, s.failed.size, vm) } }
                }
                if (s.clips.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        // One slim row: the tag/performer you're browsing (if any), then the filters.
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = m.head)) {
                                vm.facet?.let { f ->
                                    InputChip(
                                        selected = true,
                                        onClick = { vm.facet = null },
                                        label = {
                                            val name = if (f.kind == Facet.Kind.Collection)
                                                (if (f.value == app.redgrab.Library.WATCH_LIKES) "Likes" else s.collections.firstOrNull { it.id == f.value }?.name ?: f.value)
                                            else f.value
                                            Text("${f.kind.label}: $name")
                                        },
                                        leadingIcon = { Icon(if (f.kind == Facet.Kind.Tag) Icons.Default.Sell else Icons.Default.Person, null, Modifier.size(18.dp)) },
                                        trailingIcon = { Icon(Icons.Default.Close, "Remove filter", Modifier.size(18.dp)) },
                                    )
                                }
                                FilterChip(
                                    selected = vm.adv.activeCount > 0 || vm.adv.sort != Sort.Liked,
                                    onClick = { showFilters = true },
                                    label = { Text(if (vm.adv.activeCount > 0) "${vm.adv.activeCount}" else "Filters") },
                                    leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) },
                                )
                                // On by default: only what's on the phone. Off: everything, saved or not.
                                FilterChip(vm.filter == Filter.Saved, { vm.filter = if (vm.filter == Filter.Saved) Filter.All else Filter.Saved },
                                    { Text("On phone") },
                                    leadingIcon = if (vm.filter == Filter.Saved) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null)
                                // A state picked in the Filters sheet shows here, so it's visible and one tap clears it.
                                when (vm.filter) { Filter.New -> "Not saved"; Filter.HD -> "HD"; Filter.SD -> "SD"; Filter.Edited -> "Edited"; else -> null }?.let { label ->
                                    InputChip(selected = true, onClick = { vm.filter = Filter.Saved }, label = { Text(label) },
                                        trailingIcon = { Icon(Icons.Default.Close, "Clear", Modifier.size(16.dp)) })
                                }
                                // Where from. Only the kinds you actually have are offered.
                                Origin.entries.filter { it == Origin.All || it == Origin.Liked || has[it] == true }.forEach { o ->
                                    if (o == Origin.Collections) Box {
                                        FilterChip(vm.origin == o, { colMenu = true }, { Text("Collections · %,d".format(originCounts[o] ?: 0)) },
                                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) })
                                        DropdownMenu(colMenu, { colMenu = false }) {
                                            DropdownMenuItem(text = { Text("All collections") }, onClick = {
                                                colMenu = false; vm.origin = o
                                                if (vm.facet?.kind == Facet.Kind.Collection) vm.facet = null
                                            })
                                            s.collections.forEach { c ->
                                                DropdownMenuItem(text = { Text("${c.name} · ${c.count}") }, onClick = {
                                                    colMenu = false; vm.origin = o; vm.facet = Facet(Facet.Kind.Collection, c.id)
                                                })
                                            }
                                        }
                                    } else FilterChip(vm.origin == o, {
                                        vm.origin = o
                                        if (vm.facet?.kind == Facet.Kind.Collection) vm.facet = null
                                    }, { Text(if (o == Origin.All) "All" else "${o.label} · %,d".format(originCounts[o] ?: 0)) })
                                }
                                // Shown only when there is something: new arrivals and failed downloads.
                                if (s.unseen.isNotEmpty()) FilterChip(vm.filter == Filter.Unseen,
                                    { vm.filter = if (vm.filter == Filter.Unseen) Filter.Saved else Filter.Unseen },
                                    { Text("New · %,d".format(s.unseen.size)) })
                                if (s.failed.isNotEmpty()) FilterChip(vm.filter == Filter.Failed,
                                    { vm.filter = if (vm.filter == Filter.Failed) Filter.Saved else Filter.Failed },
                                    { Text("Failed · %,d".format(s.failed.size)) },
                                    leadingIcon = { Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp), tint = RgAmber) })
                                // Clips the site has deleted: kept in the list, never downloaded again.
                                if (s.dead.isNotEmpty()) FilterChip(vm.filter == Filter.Dead,
                                    { vm.filter = if (vm.filter == Filter.Dead) Filter.Saved else Filter.Dead },
                                    { Text("Removed · %,d".format(s.dead.size)) },
                                    leadingIcon = { Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                }
                if (vm.filter == Filter.Unseen && s.unseen.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = head.fillMaxWidth()) {
                            Text("Arrived since your last syncs and not opened yet.", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.markAllSeen(); vm.filter = Filter.Saved }) { Text("Mark all seen") }
                        }
                    }
                }
                if (vm.filter == Filter.Dead && s.dead.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = head.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("Deleted on the site. Not downloaded again.", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.retryFailed(s.dead) }) { Text("Check again") }
                        }
                    }
                }
                if (vm.filter == Filter.Failed && s.failed.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        val gaveUp = s.failures.values.count { it.gaveUp && !it.dead }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = head.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("Open a clip to see why it failed." + if (gaveUp > 0) " $gaveUp stopped retrying automatically (after ${app.redgrab.Failure.MAX_ATTEMPTS} runs or 7 days)." else " They're retried automatically on the next save.",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.retryFailed() }) { Text("Retry all") }
                        }
                    }
                }
                items(shown, key = { it.id }) { clip ->
                    ClipTile(
                        clip = clip,
                        unseen = clip.id in s.unseen,
                        thumb = thumbModel(s, vm.library, clip),
                        wall = vm.wall,
                        corner = m.corner,
                        compact = gridColumns(vm.columns) >= 5,
                        preview = if (isPreviewing(moving, clip.id)) ({ VideoPreview(vm, clip, streamOk, Modifier.fillMaxSize()) }) else null,
                        saved = clip.id in s.saved,
                        qualities = s.qualities[clip.id] ?: emptySet(),
                        partial = clip.id in s.partial,
                        sfw = vm.sfw,
                        failed = clip.id in s.failed,
                        dead = clip.id in s.dead,
                        downloading = dlRunning && dlCurrent == clip.id,
                        selected = clip.id in vm.selection,
                        selecting = selecting,
                        onClick = { if (selecting) vm.toggle(clip.id) else vm.openPlayer(shown, shown.indexOf(clip)) },
                        onLongClick = { vm.toggle(clip.id) },
                    )
                }
                if (vm.signedIn && s.clips.isEmpty() && !s.refreshing) {
                    item(span = StaggeredGridItemSpan.FullLine) { EmptyHint(s.lastSynced == 0L) }
                }
                if (s.clips.isNotEmpty() && shown.isEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(when {
                                vm.query.isNotBlank() || vm.facet != null || vm.adv.activeCount > 0 || vm.origin != Origin.All -> "No clips match."
                                vm.filter == Filter.New -> "Everything is saved."
                                vm.filter == Filter.Saved -> "Nothing is saved on this phone yet."
                                else -> "Nothing here."
                            }, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // The Saved view is empty but there are liked clips waiting: point to them.
                            if (vm.filter == Filter.Saved && newHere > 0) {
                                Spacer(Modifier.height(12.dp))
                                FilledTonalButton(onClick = { vm.filter = Filter.New }) { Text("Show %,d not saved yet".format(newHere)) }
                            }
                        }
                    }
                }
            }
            // Where you are in the list, and the way back up. The Saved grid has no pages of its
            // own, so a page is every 50 clips of what is listed now.
            val shownIndex = remember(shown) { HashMap<String, Int>(shown.size * 2).apply { shown.forEachIndexed { i, c -> put(c.id, i) } } }
            val page by remember(shown) {
                derivedStateOf { pageOf(gridState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { (it.key as? String)?.let(shownIndex::get) } ?: 0) }
            }
            val atTop by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 } }
            val pages = pageCount(shown.size)
            if (pages > 1 && !selecting) {
                val jumps = rememberCoroutineScope()
                PositionPill(page, pages, more = false, reach = pages, atTop = atTop,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = navPad + 8.dp),
                    offset = vm.pillOffset, onMoved = vm::changePillOffset,
                    onTop = { jumps.launch { gridState.scrollToItem(0) } },
                    onJump = { n ->
                        jumps.launch {
                            // The rows above the clips (search, filters, banners) come first in the grid.
                            val head = (gridState.layoutInfo.totalItemsCount - shown.size).coerceAtLeast(0)
                            gridState.scrollToItem(if (n <= 1) 0 else head + (n - 1) * LIBRARY_PAGE)
                        }
                    })
            }
        }
    }

    if (confirmSaveAll) {
        AlertDialog(
            onDismissRequest = { confirmSaveAll = false },
            title = { Text("Save %,d clips?".format(s.newCount)) },
            text = { Text("Everything that isn't on this phone yet is downloaded in the background. You can pause it at any time.") },
            confirmButton = { TextButton(onClick = { confirmSaveAll = false; vm.downloadAllNew() }) { Text("Save all") } },
            dismissButton = { TextButton(onClick = { confirmSaveAll = false }) { Text("Cancel") } },
        )
    }
    SfwAndFilterDialogs(vm, s, showFilters, { showFilters = false }, confirmSfwOff, { confirmSfwOff = false })
    if (showView) ViewSheet(vm) { showView = false }
}

/** Counts for the small badges: 87, 999, 1.2k, 12k. */
fun shortCount(n: Int): String = when {
    n < 1000 -> "$n"
    n < 10_000 -> "%.1fk".format(n / 1000.0)
    else -> "${n / 1000}k"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SfwAndFilterDialogs(vm: MainViewModel, s: LibraryState, showFilters: Boolean, onCloseFilters: () -> Unit,
                                confirmSfwOff: Boolean, onCloseSfw: () -> Unit) {
    if (confirmSfwOff) {
        AlertDialog(
            onDismissRequest = onCloseSfw,
            title = { Text("Turn off SFW mode?") },
            text = { Text("Previews will be visible and clips can be played again.") },
            confirmButton = { TextButton(onClick = { vm.changeSfw(false); onCloseSfw() }) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = onCloseSfw) { Text("Keep on") } },
        )
    }
    if (showFilters) FilterSheet(vm, s, onCloseFilters)
}

@Composable
private fun DownloadCard(p: DownloadProgress, failedCount: Int, vm: MainViewModel) {
    Card(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp)) {
            when {
                p.running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append("%,d of %,d saved".format(p.done, p.queued))
                                append(if (p.bytesPerSec > 0) " · " + Format.speed(p.bytesPerSec) else " · starting…")
                                if (p.etaSec >= 0) append(" · ${Format.eta(p.etaSec)} left")
                                if (p.failed > 0) append(" · ${p.failed} failed")
                            },
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = vm::stopDownload) { Text("Pause") }
                    }
                    val frac by animateFloatAsState(if (p.queued > 0) p.done.toFloat() / p.queued else 0f, label = "dl")
                    val bar = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp).height(3.dp).clip(CircleShape)
                    if (p.queued == 0) LinearProgressIndicator(bar) else LinearProgressIndicator(progress = { frac }, modifier = bar)
                }
                // Lost the connection: nothing to do but wait; it carries on by itself.
                p.waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudOff, null, tint = RgAmber)
                    Spacer(Modifier.width(12.dp))
                    Text(p.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::stopDownload) { Text("Pause") }
                }
                // Paused by you, or left over from last time.
                p.paused -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PauseCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(p.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::resumeDownload) { Text("Resume") }
                    IconButton(onClick = vm::clearDownloadQueue) { Icon(Icons.Default.Close, "Forget these") }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (p.failed > 0) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null,
                        tint = if (p.failed > 0) RgAmber else RgGreen)
                    Spacer(Modifier.width(12.dp))
                    Text(p.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (failedCount > 0) TextButton(onClick = { vm.retryFailed() }) { Text("Retry $failedCount") }
                    IconButton(onClick = vm::dismissDownloadMessage) { Icon(Icons.Default.Close, "Dismiss") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipTile(
    clip: Clip, unseen: Boolean, thumb: Any?, wall: Boolean, corner: Dp, compact: Boolean, preview: (@Composable () -> Unit)?, saved: Boolean, qualities: Set<Quality>, partial: Boolean, sfw: Boolean, failed: Boolean, dead: Boolean = false, downloading: Boolean,
    selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (selected) 0.92f else 1f, label = "sel")
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .aspectRatio(tileAspect(clip, wall))
            .scale(scale)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            ),
    ) {
        AsyncImage(
            model = thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // SFW mode: heavy blur so nothing is recognisable.
            modifier = Modifier.fillMaxSize().then(if (sfw) Modifier.blur(28.dp) else Modifier),
        )
        // Moving preview on top of the still (never in SFW mode).
        if (!sfw) preview?.invoke()
        // Length and "no sound" along the bottom: only in the roomy grid. The wall is all picture.
        if (!wall && !compact) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            )
            Row(Modifier.align(Alignment.BottomStart).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (clip.isImage) clip.fileExt.uppercase() else Format.duration(clip.duration),
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                if (!clip.hasAudio && !clip.isImage) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.VolumeOff, "No sound", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(28.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent)))
        )
        // Status (top-right).
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            when {
                downloading -> CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp, color = Color.White,
                )
                clip.isCopy -> TileLabel(if (compact) "E" else "EDIT", RgPurple)
                partial && !saved -> Icon(Icons.Default.Downloading, "Partly downloaded; resumes next time", tint = RgAmber,
                    modifier = Modifier.size(20.dp))
                dead && !saved -> Icon(Icons.Default.LinkOff, "Removed from the site", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                failed && !saved -> Icon(Icons.Default.ErrorOutline, "Failed", tint = RgAmber, modifier = Modifier.size(20.dp))
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    // Saved quality as plain icons (hidden when previews are very small).
                    if (saved && !compact) {
                        if (Quality.HD in qualities) Icon(Icons.Outlined.Hd, "Saved in HD", tint = Color.White, modifier = Modifier.size(18.dp))
                        if (Quality.SD in qualities) Icon(Icons.Outlined.Sd, "Saved in SD", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    // One icon for source + saved state: heart/bookmark/globe, outline = not saved, green = saved.
                    SourceIcon(liked = clip.liked, inCollection = clip.colIds.isNotEmpty(), saved = saved, size = if (compact) 14.dp else 18.dp, imported = "Imported" in clip.sources)
                }
            }
        }
        // New since the last sync and not opened yet: a small dot.
        if (unseen && !selecting) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(9.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.dp, Color.White, CircleShape))
        // Selection tick (top-left).
        AnimatedVisibility(selecting, Modifier.align(Alignment.TopStart).padding(6.dp), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun TileLabel(text: String, bg: Color) {
    Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EmptyHint(neverSynced: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.SwipeDown, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(if (neverSynced) "Pull down to sync your likes" else "No liked clips found",
            style = MaterialTheme.typography.titleMedium)
        Text("New likes from the RedGifs app or website show up here after a sync.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp, vertical = 4.dp))
    }
}

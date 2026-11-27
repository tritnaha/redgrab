package app.redgrab

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Online tab: feeds, search, suggestions, niches, the content filter, Reddit. Part of MainViewModel, kept in a file of its own.

/** [startPage]: open a numbered feed at that page instead of the first. */
fun MainViewModel.openFeed(feed: Feed, order: OnlineOrder = discover.order, startPage: Int = 1) {
    val first = if (feed.numbered) startPage.coerceAtLeast(1) else 1
    discoverJob?.cancel()
    if (feed.source != discover.feed.source) {
        prefs.onlineSource = feed.source.name
        recents = prefs.recent(feed.source.name)
    }
    discover = discover.copy(feed = feed, order = order, items = emptyList(), page = first - 1, cursor = null, firstPage = first,
        pageStarts = emptyList(), pages = 0, hasMore = true, loading = false, error = null, hidden = 0)
    loadMoreDiscover()
}

/** Opens the feed on screen at page [n] (numbered feeds only). */
fun MainViewModel.jumpToOnlinePage(n: Int) = openFeed(discover.feed, discover.order, startPage = n)

fun MainViewModel.changeDiscoverOrder(order: OnlineOrder) = openFeed(discover.feed, order)

/** Switches the Online tab to another site. */
fun MainViewModel.changeSource(s: OnlineSource) { if (s != discover.feed.source) openFeed(homeFeed(s)) }

/** What a source shows first. On RedGifs: your niches mixed; without any, "For you"; without that, everything. */
fun MainViewModel.homeFeed(s: OnlineSource): Feed = when {
    s != OnlineSource.RedGifs -> Feed.home(s)
    !noNiches -> Feed.MyNiches
    !forYouUnavailable -> Feed.ForYou
    else -> Feed.Search("")
}

fun MainViewModel.contentPrefsAnswered() { prefs.contentPrefsAsked = true; askContentPrefs = false }

fun MainViewModel.changePruneCollections(v: Boolean) { prefs.pruneCollections = v; pruneCollections = v }

/** Whether [clip] passes the content filter: its RedGifs labels, and what its tags, niches and title say. */
fun MainViewModel.allowed(clip: Clip): Boolean = ContentPrefs.allows(contentPrefs, clip.sexuality,
    // A RedGifs clip has no title of its own; elsewhere the title is often all there is.
    if (OnlineSource.of(clip.id) == OnlineSource.RedGifs) clip.tags + clip.niches + clip.nicheIds else clip.tags + clip.sources + clip.description)

/**
 * Sets which kinds of content the Online tab shows (the same setting in Settings and behind the
 * filter button). Applied here to every feed, search and suggestion of every site, and stored on
 * your RedGifs account as the site's own "preferences" dialog does, so its feeds follow it too.
 */
fun MainViewModel.changeContentPrefs(allowed: Set<String>) {
    if (allowed == contentPrefs) return
    prefs.contentPrefs = allowed
    contentPrefs = prefs.contentPrefs
    AppLog.i("Content filter: ${ContentPrefs.summary(contentPrefs)}")
    suggest("")
    if (discover.items.isNotEmpty() || discover.hidden > 0 || discover.error != null) openFeed(discover.feed)
    if (!prefs.signedIn) return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { withRedGifs(getApplication()) { it.setAccountPrefs(ContentPrefs.ALL.filter { p -> p in allowed }) } }
            .onFailure { AppLog.w("Preferences not stored on RedGifs (they still apply in the app): ${it.message}") }
    }
}

fun MainViewModel.openNicheBrowser() {
    if (sfw) return say("SFW mode is on.")
    val old = nicheBrowse ?: MainViewModel.NicheBrowse()
    nicheBrowse = old
    nicheBrowserOpen = true
    if (old.categories.isEmpty()) viewModelScope.launch {
        val cats = withContext(Dispatchers.IO) { runCatching { withRedGifs(getApplication()) { it.nicheCategories() } }.getOrDefault(emptyList()) }
        nicheBrowse = nicheBrowse?.copy(categories = cats)
    }
    if (old.items.isEmpty()) loadNiches(reset = true)
}

fun MainViewModel.closeNicheBrowser() { nicheBrowserOpen = false }

/** Shows the niche list for these words, group and order, from its first page. */
fun MainViewModel.searchNiches(query: String = nicheBrowse?.query.orEmpty(), category: String? = nicheBrowse?.category, order: NicheOrder = nicheBrowse?.order ?: NicheOrder.Subscribers) {
    val b = nicheBrowse ?: return
    nicheBrowse = b.copy(query = query, category = category, order = order)
    loadNiches(reset = true)
}

fun MainViewModel.loadNiches(reset: Boolean = false) {
    val b = nicheBrowse ?: return
    if (!reset && (b.loading || b.page >= b.pages)) return
    nicheJob?.cancel()
    val next = if (reset) 1 else b.page + 1
    nicheBrowse = b.copy(loading = true, error = null, items = if (reset) emptyList() else b.items, page = if (reset) 0 else b.page)
    nicheJob = viewModelScope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { withRedGifs(getApplication()) { it.searchNiches(b.query, b.category, b.order, next) } } }
        val now = nicheBrowse ?: return@launch
        r.onSuccess { pg ->
            // Niches for an audience you switched off are left out; unlabelled ones stay.
            val shown = pg.niches.filter { ContentPrefs.allows(contentPrefs, it.preferences, listOf(it.name)) }
            val seen = now.items.mapTo(HashSet()) { it.id }
            nicheBrowse = now.copy(items = now.items + shown.filter { it.id !in seen }, page = next, pages = pg.pages.coerceAtLeast(next), total = pg.total, loading = false)
            withContext(Dispatchers.IO) { library.learnNiches(pg.niches.associate { it.id to it.name }) }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) return@onFailure
            AppLog.w("Niche list failed: ${e.message}")
            nicheBrowse = now.copy(loading = false, error = friendly(e, OnlineSource.RedGifs).replace("Sync failed", "Couldn't load"))
        }
    }
}

fun MainViewModel.follows(nicheId: String) = discover.niches.any { it.id == nicheId }

fun MainViewModel.followsTag(tag: String) = followedTags.any { it.equals(tag.trim(), ignoreCase = true) }

fun MainViewModel.toggleTag(tag: String) {
    val t = tag.trim()
    if (t.isEmpty()) return
    prefs.followedTags = if (followsTag(t)) followedTags.filter { !it.equals(t, ignoreCase = true) } else followedTags + t
    followedTags = prefs.followedTags
    if (followedTags.isNotEmpty()) noNiches = false
    if (discover.feed == Feed.MyNiches) openFeed(Feed.MyNiches)
}

/** Follows or stops following a niche on your RedGifs account. */
fun MainViewModel.toggleFollow(n: NicheInfo) {
    if (n.id in followBusy) return
    val follow = !follows(n.id)
    followBusy = followBusy + n.id
    viewModelScope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { withRedGifs(getApplication()) { it.followNiche(n.id, follow) } } }
        followBusy = followBusy - n.id
        r.onSuccess {
            discover = discover.copy(niches = if (follow) discover.niches + n else discover.niches.filter { it.id != n.id }, nichesLoaded = true)
            if (follow) noNiches = false
            AppLog.i((if (follow) "Following" else "Stopped following") + " the niche ${n.name}")
            // The mix is made from what you follow: redo it when that changes.
            if (discover.feed == Feed.MyNiches) openFeed(Feed.MyNiches)
        }.onFailure { e ->
            AppLog.w("Could not ${if (follow) "follow" else "unfollow"} ${n.id}: ${e.message}")
            say("Couldn't ${if (follow) "follow" else "unfollow"} ${n.name}: ${friendly(e, OnlineSource.RedGifs).removePrefix("Sync failed: ")}")
        }
    }
}

/** Reads the stored tag and niche lists, and fetches them again when they are missing or a week old. */
fun MainViewModel.ensureSearchIndex() {
    if (indexJob?.isActive == true || sfw) return
    indexJob = viewModelScope.launch(Dispatchers.IO) {
        searchIndex.load()
        val fresh = searchIndex.ready && System.currentTimeMillis() - prefs.searchIndexAt < SearchIndex.MAX_AGE_MS
        if (fresh || !prefs.signedIn || !isOnline()) return@launch
        runCatching {
            val tags = withRedGifs(getApplication()) { it.allTags() }
            // The tags alone already make suggestions work; the niches take about twenty requests more.
            searchIndex.store(tags, emptyList())
            val niches = runCatching { withRedGifs(getApplication()) { it.allNiches() } }
                .onFailure { AppLog.w("Niche list for suggestions not read: ${it.message}") }.getOrDefault(emptyList())
            searchIndex.store(tags, niches)
            if (tags.isNotEmpty()) prefs.searchIndexAt = System.currentTimeMillis()
            AppLog.i("Search suggestions: ${searchIndex.tags.size} tags, ${searchIndex.niches.size} niches stored")
        }.onFailure { AppLog.w("Tag list for suggestions not read: ${it.message}") }
    }
}

/** The tags [query] is made of, when every word of it belongs to an existing tag; null otherwise (or before the tag list is on the phone). */
fun MainViewModel.tagsIn(query: String): List<String>? = searchIndex.tagNames.takeIf { it.isNotEmpty() }?.let { Suggest.asTags(it, query, atLeast = 1) }

/**
 * The feed for what was typed. On RedGifs, words that are all existing tags open the clips
 * that carry exactly those tags; anything else is the site's text search, which is loose
 * ("gay" also finds "Age Gap" and "Gagged") and doesn't need every word to be there.
 */
fun MainViewModel.feedFor(source: OnlineSource, text: String): Feed {
    if (source == OnlineSource.RedGifs) tagsIn(text)?.let { return Feed.Tags(it) }
    return Feed.fromQuery(source, text)
}

/**
 * Fills the list under the search box with things that exist and match what has been typed.
 * Tags and niches come from the lists kept on the phone, at once; creators and subreddits are
 * asked for after a short pause in typing.
 */
fun MainViewModel.suggest(text: String) {
    suggestJob?.cancel()
    val q = Suggest.norm(text)
    val site = discover.feed.source
    val redGifs = (site == OnlineSource.RedGifs || site == OnlineSource.All) && prefs.signedIn
    val reddit = (site == OnlineSource.Reddit || site == OnlineSource.All) && !prefs.redditClientId.isNullOrBlank()
    if (q.length < 2 || sfw || (!redGifs && !reddit)) { suggestions = emptyList(); return }
    val ok = { k: Known -> ContentPrefs.allows(contentPrefs, k.labels, listOf(k.name)) }
    val local = if (!redGifs || !searchIndex.ready) emptyList() else {
        val tags = searchIndex.tags
        Suggest.combos(searchIndex.tagNames, tags, q, 3).filter { c -> ContentPrefs.allows(contentPrefs, emptyList(), c) }
            .map { Suggestion(Suggestion.Kind.Tags, it.joinToString(" + "), "", Feed.Tags(it)) } +
            Suggest.rank(tags.filter(ok), q, 6).map {
                Suggestion(Suggestion.Kind.Tag, it.name, Format.count(it.count), if (site == OnlineSource.All) Feed.AllSites(it.name) else Feed.Tags(listOf(it.name)))
            } +
            Suggest.rank(searchIndex.niches.filter(ok), q, 4).map { Suggestion(Suggestion.Kind.Niche, it.name, Format.count(it.count), Feed.Niche(it.id, it.name)) }
    }
    suggestions = local
    suggestJob = viewModelScope.launch {
        kotlinx.coroutines.delay(350)
        val live = withContext(Dispatchers.IO) {
            val out = ArrayList<Suggestion>()
            if (redGifs) {
                // Until the lists are on the phone, the site is asked instead.
                if (!searchIndex.ready) {
                    runCatching { withRedGifs(getApplication()) { it.suggestTags(q) } }.getOrDefault(emptyList())
                        .filter { ContentPrefs.allows(contentPrefs, emptyList(), listOf(it.text)) }.take(6)
                        .forEach { out += Suggestion(Suggestion.Kind.Tag, it.text, Format.count(it.clips), if (site == OnlineSource.All) Feed.AllSites(it.text) else Feed.Search(it.text)) }
                    runCatching { withRedGifs(getApplication()) { it.searchNiches(q, null, NicheOrder.Match, 1, count = 10).niches } }.getOrDefault(emptyList())
                        .filter { ContentPrefs.allows(contentPrefs, it.preferences, listOf(it.name)) }.take(4)
                        .forEach { out += Suggestion(Suggestion.Kind.Niche, it.name, Format.count(it.subscribers), Feed.Niche(it.id, it.name)) }
                }
                runCatching { withRedGifs(getApplication()) { it.suggestCreators(q) } }.getOrDefault(emptyList())
                    .filter { ContentPrefs.allows(contentPrefs, emptyList(), listOf(it)) }.take(3)
                    .forEach { out += Suggestion(Suggestion.Kind.Creator, "@$it", "", Feed.Performer(it)) }
            }
            // Reddit's counterpart of a niche is a subreddit.
            if (reddit) runCatching { RedditApi(prefs, RedGifsApi.clientFor(getApplication<Application>().userAgent)).searchSubreddits(q) }
                .onFailure { AppLog.w("Subreddit search failed: ${it.message}") }.getOrDefault(emptyList())
                .filter { ContentPrefs.allows(contentPrefs, emptyList(), listOf(it.name)) }.take(4)
                .forEach { out += Suggestion(Suggestion.Kind.Subreddit, "r/${it.name}", Format.count(it.subscribers), Feed.Subreddit(it.name)) }
            out
        }
        suggestions = local + live
    }
}

/** A new client ID means a different Reddit app: the old sign-in doesn't belong to it. */
fun MainViewModel.changeRedditClientId(v: String) {
    val id = v.trim()
    if (id == redditClientId) return
    prefs.redditSignOut()
    prefs.redditClientId = id.ifEmpty { null }
    redditClientId = id
    redditUser = null
    discover = discover.copy(subs = emptyList(), subsLoaded = false)
    AppLog.i(if (id.isEmpty()) "Reddit client ID removed" else "Reddit client ID set")
}

fun MainViewModel.redditSignOut() {
    prefs.redditSignOut()
    redditUser = null
    discover = discover.copy(subs = emptyList(), subsLoaded = false)
    AppLog.i("Signed out of Reddit")
}

/** Subreddits the Reddit account has joined, for the shortcuts row. */
fun MainViewModel.loadSubreddits(force: Boolean = false) {
    if (sfw || prefs.redditRefresh == null) return
    if (discover.subsLoaded && !force) return
    viewModelScope.launch {
        val list = withContext(Dispatchers.IO) {
            runCatching { RedditApi(prefs, RedGifsApi.clientFor(getApplication<Application>().userAgent)).mySubreddits() }
                .onFailure { AppLog.w("Could not read your subreddits: ${it.message}") }.getOrNull()
        }
        discover = discover.copy(subs = list ?: discover.subs, subsLoaded = true)
    }
}

/** Shows RedGifs' related clips for [clip] in Discover. */
fun MainViewModel.openRelated(clip: Clip) {
    val id = clip.copyOf ?: clip.id
    if (OnlineSource.of(id) != OnlineSource.RedGifs) return
    player = null
    openFeed(Feed.Related(id, clip.user.ifBlank { "this clip" }))
    discoverRequests++
}

/** Opens a niche online, by its RedGifs name (as shown on a clip). */
fun MainViewModel.openNicheOnline(name: String) {
    val id = library.nicheId(name) ?: return say("“$name” is one of your own niches, so there's no RedGifs feed for it.")
    player = null
    openFeed(Feed.Niche(id, name))
    discoverRequests++
}

/** Searches [source] for [query] (on Reddit: opens that subreddit; on FikFap: that hashtag). */
fun MainViewModel.searchOnline(query: String, source: OnlineSource = discover.feed.source) {
    player = null
    openFeed(feedFor(source, query))
    discoverRequests++
}

/** Opens a creator's (on Reddit: a user's) clips on [source]. */
fun MainViewModel.openPerformerOnline(name: String, source: OnlineSource = OnlineSource.RedGifs) {
    player = null
    openFeed(when (source) {
        OnlineSource.All -> Feed.AllSites(name)
        OnlineSource.RedGifs -> Feed.Performer(name)
        OnlineSource.Reddit -> Feed.fromQuery(source, "u/$name")
        OnlineSource.FikFap -> Feed.FikFapUser(name)
        OnlineSource.SexCom -> Feed.SexSearch(name)
        OnlineSource.EroMe -> Feed.fromQuery(source, "@$name")
        OnlineSource.FourChan -> Feed.ChanThreads(name)
    })
    discoverRequests++
}

fun MainViewModel.loadMoreDiscover() {
    val d = discover
    if (sfw) return // SFW mode: nothing is fetched from any site
    if (d.loading || !d.hasMore || d.feed.needsQuery) return
    val source = d.feed.source
    // Each source needs its own thing first: RedGifs a sign-in, Reddit a client ID, the others nothing.
    // The Online tab asks for it on screen; loading starts by itself once it's there.
    if (source == OnlineSource.RedGifs && !prefs.signedIn) return
    if (source == OnlineSource.Reddit && prefs.redditClientId.isNullOrBlank()) return
    discover = d.copy(loading = true, error = null)
    discoverJob = viewModelScope.launch {
        val next = d.page + 1
        val result = withContext(Dispatchers.IO) { runCatching { fetchPage(d, next) } }
        val failure = result.exceptionOrNull()
        // "My niches" with no niche followed: "For you" takes its place until you follow one.
        if (d.feed == Feed.MyNiches && failure is NoNichesException) {
            noNiches = true
            discover = discover.copy(loading = false)
            openFeed(homeFeed(OnlineSource.RedGifs))
            return@launch
        }
        // "For you" with nothing in it (or refused by the site): show the general feed instead.
        if (d.feed == Feed.ForYou && next == d.firstPage && d.items.isEmpty() &&
            // A refusal counts too: if the sign-in really has lapsed, the general feed will say so.
            (result.getOrNull()?.clips?.isEmpty() == true || failure is HttpException || failure is AuthException)) {
            AppLog.w("For you: ${failure?.message ?: "empty"}; showing all of RedGifs instead")
            forYouUnavailable = true
            discover = discover.copy(loading = false)
            openFeed(Feed.Search(""))
            return@launch
        }
        result.onSuccess { pg ->
            val seen = discover.items.mapTo(HashSet()) { it.id }
            // The content filter: clips labelled or tagged for an audience you switched off are left out, on every site.
            val wanted = pg.clips.filter(::allowed)
            val fresh = wanted.filter { it.id !in seen }
            discover = discover.copy(items = discover.items + fresh, page = next, cursor = pg.cursor, hidden = discover.hidden + pg.clips.size - wanted.size,
                // A page that added nothing isn't a place you can be.
                pageStarts = if (fresh.isEmpty()) discover.pageStarts else discover.pageStarts + discover.items.size,
                pages = if (pg.pages > 0) pg.pages else discover.pages,
                // A page can come back empty (no playable posts) and still have more behind it.
                hasMore = pg.hasMore && (pg.clips.isNotEmpty() || pg.cursor != null), loading = false)
            // A page with nothing left after filtering: go on to the next one (a few times at most).
            if (fresh.isEmpty() && pg.clips.isNotEmpty() && discover.hasMore && emptyPages++ < 6) loadMoreDiscover() else if (fresh.isNotEmpty()) emptyPages = 0
            if (source != OnlineSource.RedGifs && next == 1 && pg.clips.isNotEmpty() && d.feed.query.isNotBlank()) {
                prefs.addRecent(source.name, d.feed.query)
                recents = prefs.recent(source.name)
            }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) return@onFailure
            if (e is AuthException) needsSignIn = true
            if (e is VerificationRequiredException) { prefs.pendingVerification = e.subject; verifyNeeded = true }
            AppLog.e("Online failed (${source.label}, ${d.feed.title}): ${e.message}",
                e.takeUnless { it is AuthException || it is HttpException || it is RedditAuthException || it is SiteBlockedException })
            discover = discover.copy(loading = false, error = friendly(e, source).replace("Sync failed", "Couldn't load"))
        }
    }
}

/** Page [next] of the feed on screen, from whichever site it belongs to. */
internal suspend fun MainViewModel.fetchPage(d: DiscoverState, next: Int): OnlinePage {
    suspend fun redgifs(load: suspend (RedGifsApi, FeedExtras) -> OnlinePage): OnlinePage {
        val extras = FeedExtras()
        val page = withRedGifs(getApplication()) { load(it, extras) }
        library.learn(extras)
        return page.copy(clips = page.clips.map(library::resolveNiches))
    }
    val http by lazy { RedGifsApi.clientFor(getApplication<Application>().userAgent) }
    return when (val f = d.feed) {
        is Feed.MyNiches -> {
            var followed = d.niches
            if (followed.isEmpty() && !d.nichesLoaded) {
                followed = withRedGifs(getApplication()) { it.followedNiches() }
                withContext(Dispatchers.Main) { discover = discover.copy(niches = followed, nichesLoaded = true) }
            }
            // Niches (followed on RedGifs) and tags (followed in the app) take turns alike.
            val tags = followedTags
            if (followed.isEmpty() && tags.isEmpty()) throw NoNichesException()
            redgifs { api, x ->
                // A few clips from each in turn. One of them failing doesn't spoil the page; a lapsed sign-in still does.
                val parts = NicheMix.plan(next, followed.map { "n:" + it.id } + tags.map { "t:$it" }).map { (key, p) ->
                    try {
                        if (key.startsWith("n:")) api.nicheClips(key.drop(2), OnlineOrder.Trending, p, x, count = NicheMix.PER_NICHE)
                        // A followed tag that exists is asked for exactly; followed words go through the text search.
                        else searchIndex.tagNames[Suggest.norm(key.drop(2))]?.let { api.tagClips(listOf(it), OnlineOrder.Trending, p, x, count = NicheMix.PER_NICHE) }
                            ?: api.searchClips(key.drop(2), OnlineOrder.Trending, p, x, count = NicheMix.PER_NICHE)
                    } catch (e: HttpException) { AppLog.w("My feed: $key page $p: ${e.message}"); null }
                }
                OnlinePage(NicheMix.interleave(parts.map { it?.clips ?: emptyList() }), next, 0, more = parts.any { it != null && it.hasMore })
            }
        }
        is Feed.ForYou -> redgifs { api, x -> api.forYou(next, x) }
        is Feed.Search -> redgifs { api, x -> api.searchClips(f.query, d.order, next, x) }
        is Feed.Tags -> redgifs { api, x -> api.tagClips(f.tags, d.order, next, x) }
        is Feed.Niche -> redgifs { api, x -> api.nicheClips(f.id, d.order, next, x) }
        is Feed.Performer -> redgifs { api, x -> api.performerClips(f.name, d.order, next, x) }
        is Feed.Related -> redgifs { api, x -> api.relatedClips(f.clipId, next, x) }
        is Feed.RedditHome, is Feed.Subreddit, is Feed.RedditUser, is Feed.RedditSaved, is Feed.RedditUpvoted ->
            RedditApi(prefs, http).listing(f, d.order, d.cursor, next)
        is Feed.FikFapTag -> FikFapApi(http).tagPosts(f.tag, d.cursor, next)
        is Feed.FikFapUser -> FikFapApi(http).userPosts(f.name, d.cursor, next)
        is Feed.SexSearch -> SexComApi(http).search(f.query, next)
        is Feed.EromeSearch -> EromeApi(http).search(f.query, d.cursor, next)
        is Feed.EromeUser -> EromeApi(http).user(f.name, d.cursor, next)
        is Feed.ChanThreads -> FourChanApi(http).threads(f.query, d.cursor, next)
        is Feed.AllSites -> {
            val place = AllSites.decode(d.cursor)
            val subs = AllSites.feeds(f.query, redGifs = prefs.signedIn, reddit = !prefs.redditClientId.isNullOrBlank(),
                personal = d.niches.isNotEmpty() || followedTags.isNotEmpty() || !d.nichesLoaded).filter { it.source.name !in place.done }
            val cursors = HashMap(place.cursors)
            val done = HashSet(place.done)
            val parts = subs.map { sub ->
                val name = sub.source.name
                try {
                    val sd = d.copy(feed = sub, order = if (d.order in sub.orders) d.order else OnlineOrder.Trending, cursor = place.cursors[name])
                    val pg = try { fetchPage(sd, next) } catch (e: NoNichesException) { fetchPage(sd.copy(feed = Feed.Search("")), next) }
                    if (pg.cursor != null) cursors[name] = pg.cursor else cursors.remove(name)
                    if (!pg.hasMore || (pg.clips.isEmpty() && pg.cursor == null)) done += name
                    pg.clips
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    // One site failing (not set up, down, blocked) doesn't stop the others; it sits out the rest of this feed.
                    AppLog.w("All sites: ${sub.source.label} left out: ${e.message}")
                    done += name
                    emptyList()
                }
            }
            OnlinePage(NicheMix.interleave(parts), next, 0, cursor = AllSites.encode(AllSites.Place(cursors, done)), more = subs.any { it.source.name !in done })
        }
    }
}

fun MainViewModel.loadFollowedNiches(force: Boolean = false) {
    if (sfw) return
    if (discover.nichesLoaded && !force) return
    if (!prefs.signedIn) return
    viewModelScope.launch {
        val list = withContext(Dispatchers.IO) {
            runCatching { withRedGifs(getApplication()) { it.followedNiches() } }
                .onFailure { AppLog.w("Could not read followed niches: ${it.message}") }.getOrNull()
        }
        discover = discover.copy(niches = list ?: discover.niches, nichesLoaded = true)
        if (list != null) withContext(Dispatchers.IO) { library.learnNiches(list.associate { it.id to it.name }) }
    }
}

fun MainViewModel.movePlayer(delta: Int) {
    val p = player ?: return
    val next = p.index + delta
    if (next in p.ids.indices) player = p.copy(index = next)
}

fun MainViewModel.closePlayer() { player = null }

fun MainViewModel.changePlayMuted(v: Boolean) { prefs.playMuted = v; playMuted = v }

fun MainViewModel.changePlayerFill(v: Boolean) { prefs.playerFill = v; playerFill = v }

fun MainViewModel.changePillOffset(o: androidx.compose.ui.geometry.Offset) { prefs.pillX = o.x; prefs.pillY = o.y; pillOffset = o }

fun MainViewModel.browse(f: Facet) {
    facet = f
    query = ""
    filter = Filter.All
    origin = Origin.All
    player = null
}

fun MainViewModel.shareToWhatsApp(clip: Clip) = shareToWhatsApp(listOf(clip))

/** Shares the selected clips (up to 30 at once) to WhatsApp. */
fun MainViewModel.shareSelectedToWhatsApp() {
    val byId = state.value.clips.associateBy { it.id }
    val clips = selection.mapNotNull(byId::get)
    selection = emptySet()
    shareToWhatsApp(clips)
}

fun MainViewModel.shareToWhatsApp(clips: List<Clip>) {
    if (clips.isEmpty()) return
    if (clips.size > WhatsAppShare.MAX_CLIPS) return say("WhatsApp takes up to ${WhatsAppShare.MAX_CLIPS} clips at a time. Select fewer.")
    val apps = WhatsAppShare.installed(getApplication())
    when (apps.size) {
        0 -> say("WhatsApp isn't installed in this profile. Inside Secure Folder, add WhatsApp to Secure Folder too.")
        1 -> shareTo(clips, apps.keys.first())
        else -> shareChoice = clips to apps
    }
}

fun MainViewModel.shareTo(clips: List<Clip>, pkg: String) {
    shareChoice = null
    if (sharing) return
    sharing = true
    viewModelScope.launch {
        runCatching {
            val uris = WhatsAppShare.prepare(getApplication(), clips)
            WhatsAppShare.send(getApplication(), pkg, uris)
        }.onFailure { e ->
            AppLog.e("Share to WhatsApp failed: ${e.message}", e.takeUnless { it is HttpException })
            say(friendly(e).replace("Sync failed", "Share failed"))
        }
        sharing = false
    }
}

fun MainViewModel.cancelShareChoice() { shareChoice = null }

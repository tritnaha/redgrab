package app.redgrab

/** What the Online tab is showing: one feed of one source. */
sealed interface Feed {
    val title: String
    val source: OnlineSource
    /** What the search box holds for this feed; also what's remembered as "recent". */
    val query: String get() = ""
    /** Nothing to load until something is typed (FikFap and sex.com have no general feed). */
    val needsQuery: Boolean get() = false
    /** Sort orders the feed offers; empty = it has just one order. */
    val orders: List<OnlineOrder> get() = emptyList()
    /** The site numbers this feed's pages, so any page can be opened directly (others only continue from the last one). */
    val numbered: Boolean get() = false

    /** Every site that is set up, in one feed; with [query], every site searched for it (see AllSites). */
    data class AllSites(override val query: String) : Feed {
        override val title get() = if (query.isBlank()) "All sites" else "All sites: $query"
        override val source get() = OnlineSource.All
    }

    // RedGifs
    /** One feed made from the niches and tags you follow (see NicheMix). */
    data object MyNiches : Feed {
        override val title get() = "My feed"
        override val source get() = OnlineSource.RedGifs
    }
    /** What the site's home page shows you when signed in. */
    data object ForYou : Feed {
        override val title get() = "For you"
        override val source get() = OnlineSource.RedGifs
        override val numbered get() = true
    }
    data class Search(override val query: String) : Feed {
        override val title get() = if (query.isBlank()) "All of RedGifs" else "Search: $query"
        override val source get() = OnlineSource.RedGifs
        override val orders get() = OnlineOrder.entries
        override val numbered get() = true
    }
    /** Clips that carry every one of [tags] (a search for words also returns clips with only some of them). */
    data class Tags(val tags: List<String>) : Feed {
        override val title get() = tags.joinToString(" + ") { "#$it" }
        override val query get() = tags.joinToString(" ")
        override val source get() = OnlineSource.RedGifs
        override val orders get() = OnlineOrder.entries
        override val numbered get() = true
    }
    data class Niche(val id: String, val name: String) : Feed {
        override val title get() = name
        override val source get() = OnlineSource.RedGifs
        // Niche feeds have no "top this week / month"; RedGifs rejects those.
        override val orders get() = listOf(OnlineOrder.Trending, OnlineOrder.New, OnlineOrder.TopAll)
        override val numbered get() = true
    }
    data class Performer(val name: String) : Feed {
        override val title get() = name
        override val source get() = OnlineSource.RedGifs
        // Creator feeds only sort by newest or top.
        override val orders get() = listOf(OnlineOrder.New, OnlineOrder.TopAll)
        override val numbered get() = true
    }
    data class Related(val clipId: String, val label: String) : Feed {
        override val title get() = "Related to $label"
        override val source get() = OnlineSource.RedGifs
        override val numbered get() = true
    }

    // Reddit
    data object RedditHome : Feed {
        override val title get() = "Front page"
        override val source get() = OnlineSource.Reddit
        override val orders get() = OnlineOrder.entries
    }
    data class Subreddit(val name: String) : Feed {
        override val title get() = "r/$name"
        override val source get() = OnlineSource.Reddit
        override val query get() = name
        override val orders get() = OnlineOrder.entries
    }
    data class RedditUser(val name: String) : Feed {
        override val title get() = "u/$name"
        override val source get() = OnlineSource.Reddit
        override val query get() = "u/$name"
        override val orders get() = OnlineOrder.entries
    }
    data object RedditSaved : Feed {
        override val title get() = "Saved"
        override val source get() = OnlineSource.Reddit
    }
    data object RedditUpvoted : Feed {
        override val title get() = "Upvoted"
        override val source get() = OnlineSource.Reddit
    }

    // FikFap
    data class FikFapTag(val tag: String) : Feed {
        override val title get() = "#$tag"
        override val source get() = OnlineSource.FikFap
        override val query get() = tag
        override val needsQuery get() = tag.isBlank()
    }
    data class FikFapUser(val name: String) : Feed {
        override val title get() = "@$name"
        override val source get() = OnlineSource.FikFap
        override val query get() = "@$name"
    }

    // sex.com: a GIF search. An empty search is refused by the site (HTTP 400, seen from GitHub's runner 2026-11).
    data class SexSearch(override val query: String) : Feed {
        override val title get() = query
        override val source get() = OnlineSource.SexCom
        override val needsQuery get() = query.isBlank()
        override val numbered get() = true
    }

    // EroMe: the site's own "explore" lists, albums found by search, or one uploader's albums
    data class EromeExplore(val newest: Boolean) : Feed {
        override val title get() = if (newest) "New" else "Hot"
        override val source get() = OnlineSource.EroMe
    }
    data class EromeSearch(override val query: String) : Feed {
        override val title get() = query
        override val source get() = OnlineSource.EroMe
        override val needsQuery get() = query.isBlank()
    }
    data class EromeUser(val name: String) : Feed {
        override val title get() = "@$name"
        override val source get() = OnlineSource.EroMe
        override val query get() = "@$name"
    }

    // 4chan: every thread of a board, or the threads whose subject has the words
    data class ChanThreads(override val query: String, val board: String = FourChanApi.BOARD) : Feed {
        override val title get() = if (query.isBlank()) "/$board/" else "/$board/ $query"
        override val source get() = OnlineSource.FourChan
    }

    companion object {
        /** What a source shows first. */
        fun home(s: OnlineSource): Feed = when (s) {
            OnlineSource.All -> AllSites("")
            OnlineSource.RedGifs -> MyNiches
            OnlineSource.Reddit -> RedditHome
            OnlineSource.FikFap -> FikFapTag("")
            OnlineSource.SexCom -> SexSearch("")
            OnlineSource.EroMe -> EromeExplore(false)
            OnlineSource.FourChan -> ChanThreads("")
        }

        private val REDDIT_NAME = Regex("[^A-Za-z0-9_+-]")
        /** EroMe user names go into the page address: letters, digits, dash, underscore, dot. Anything after that is dropped. */
        private val EROME_NAME = Regex("^[A-Za-z0-9_.-]+")

        /** The feed for what was typed in the search box (or tapped in "recent"). */
        fun fromQuery(s: OnlineSource, text: String): Feed {
            val t = text.trim()
            return when (s) {
                OnlineSource.All -> AllSites(t)
                OnlineSource.RedGifs -> Search(t)
                OnlineSource.Reddit -> {
                    val path = t.removePrefix("/")
                    val user = path.startsWith("u/", ignoreCase = true) || path.startsWith("user/", ignoreCase = true)
                    val name = path.substringAfter('/', path).replace(REDDIT_NAME, "")
                    when {
                        name.isEmpty() -> RedditHome
                        user -> RedditUser(name)
                        else -> Subreddit(name)
                    }
                }
                OnlineSource.FikFap -> if (t.startsWith("@")) t.drop(1).trim().let { if (it.isEmpty()) FikFapTag("") else FikFapUser(it) }
                    else FikFapTag(t.trimStart('#').trim())
                OnlineSource.SexCom -> SexSearch(t)
                OnlineSource.EroMe -> if (t.startsWith("@")) EROME_NAME.find(t.drop(1).trim())?.value?.let { EromeUser(it) } ?: EromeExplore(false)
                    else if (t.isEmpty()) EromeExplore(false) else EromeSearch(t)
                OnlineSource.FourChan -> ChanThreads(t)
            }
        }
    }
}

data class DiscoverState(
    val feed: Feed = Feed.Search(""),
    val order: OnlineOrder = OnlineOrder.Trending,
    val items: List<Clip> = emptyList(),
    val page: Int = 0,
    /** Where the next page starts, for feeds that continue from a marker instead of a page number. */
    val cursor: String? = null,
    /** The page the list starts at (not 1 after jumping straight to a page). */
    val firstPage: Int = 1,
    /** Where in [items] each loaded page begins, in order; the first belongs to [firstPage]. */
    val pageStarts: List<Int> = emptyList(),
    /** How many pages the site says there are; 0 = it doesn't say. */
    val pages: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val niches: List<NicheInfo> = emptyList(),
    val nichesLoaded: Boolean = false,
    /** Subreddits the Reddit account has joined. */
    val subs: List<String> = emptyList(),
    val subsLoaded: Boolean = false,
    /** Clips of this feed left out by the content filter so far. */
    val hidden: Int = 0,
)

/** One line in the list under the Online search box: something that exists and what tapping it opens. */
data class Suggestion(val kind: Kind, val label: String, val detail: String, val feed: Feed) {
    enum class Kind(val word: String) { Tags("Every tag"), Tag("Tag"), Niche("Niche"), Creator("Creator"), Subreddit("Subreddit") }
}

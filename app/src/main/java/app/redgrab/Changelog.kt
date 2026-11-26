package app.redgrab

import android.content.Context

/** One version in the changelog: its sections ("Added", "Changed", "Fixed") with one line per change. */
data class ChangelogEntry(val version: String, val build: Int, val date: String,
                          val sections: List<Pair<String, List<String>>>, val link: String?)

/**
 * The app's changelog. It is written in CHANGELOG.md at the top of the repository, which the
 * build copies into the app, so the list in the app, the file on GitHub and the release notes
 * are the same text.
 */
object Changelog {
    private val HEAD = Regex("^##\\s+(\\S+)\\s+\\(build\\s+(\\d+)\\)(?:\\s*[·-]\\s*(.+))?$")
    private val LINK = Regex("^\\[[^\\]]+]\\((https://[^)\\s]+)\\)$")

    /** Reads the file: "## 13.6 (build 19) · date", then "### Section" headings with "- " lines, and a "[Details](address)" line. */
    fun parse(text: String): List<ChangelogEntry> {
        val out = ArrayList<ChangelogEntry>()
        var head: MatchResult? = null
        var sections = ArrayList<Pair<String, ArrayList<String>>>()
        var link: String? = null
        fun close() {
            val h = head ?: return
            out += ChangelogEntry(h.groupValues[1], h.groupValues[2].toInt(), h.groupValues[3].trim(),
                sections.filter { it.second.isNotEmpty() }.map { it.first to it.second.toList() }, link)
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val h = HEAD.matchEntire(line)
            when {
                h != null -> { close(); head = h; sections = ArrayList(); link = null }
                head == null -> {}
                line.startsWith("### ") -> sections += line.removePrefix("### ").trim() to ArrayList()
                line.startsWith("- ") -> (sections.lastOrNull() ?: ("Changed" to ArrayList<String>()).also { sections += it }).second += line.removePrefix("- ").trim()
                else -> LINK.matchEntire(line)?.let { link = it.groupValues[1] }
            }
        }
        close()
        return out
    }

    fun load(ctx: Context): List<ChangelogEntry> =
        runCatching { parse(ctx.assets.open(FILE).bufferedReader().use { it.readText() }) }
            .onFailure { AppLog.w("The changelog couldn't be read: ${it.message}") }.getOrDefault(emptyList())

    /**
     * What to show after an update from build [lastSeen] to build [current]: every version in
     * between, newest first. When the earlier build isn't known (0), just the current version.
     */
    fun since(entries: List<ChangelogEntry>, lastSeen: Int, current: Int): List<ChangelogEntry> =
        entries.filter { it.build <= current && if (lastSeen > 0) it.build > lastSeen else it.build == current }

    /** Where on GitHub a version is described: its own link, else (the newest has none yet) the commit this build was made from. */
    fun linkFor(e: ChangelogEntry, repoPage: String, runningBuild: Int, runningCommit: String): String = when {
        e.link != null -> e.link
        e.build == runningBuild && runningCommit.matches(Regex("[0-9a-f]{7,40}")) -> "$repoPage/commit/$runningCommit"
        else -> "$repoPage/blob/HEAD/$FILE"
    }

    const val FILE = "CHANGELOG.md"
}

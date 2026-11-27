package app.redgrab

import java.io.File

/** Something that exists on RedGifs and can be searched for: a tag (count = clips) or a niche (count = followers). */
data class Known(val name: String, val count: Long, val id: String = "", val labels: List<String> = emptyList())

/** Matching what has been typed against names that exist. No network, no Android: plain lists. */
object Suggest {
    private val SPACES = Regex("[\\s,#]+")

    /** Lower case, single spaces; commas and # count as spaces ("BDSM,  #sensual" → "bdsm sensual"). */
    fun norm(s: String): String = s.lowercase().replace(SPACES, " ").trim()

    /** 0 = the name itself, 1 = starts with it, 2 = a later word starts with it, 3 = has it inside, -1 = no match. */
    fun score(name: String, typed: String): Int {
        val n = name.lowercase()
        return when {
            typed.isEmpty() -> -1
            n == typed -> 0
            n.startsWith(typed) -> 1
            n.contains(" $typed") || n.contains("-$typed") || n.contains("/$typed") -> 2
            typed.length >= 3 && n.contains(typed) -> 3
            else -> -1
        }
    }

    /** The best matches for [typed]: closest kind of match first, then the most used. */
    fun rank(all: List<Known>, typed: String, limit: Int): List<Known> {
        val t = norm(typed)
        if (t.isEmpty()) return emptyList()
        return all.mapNotNull { k -> score(k.name, t).takeIf { it >= 0 }?.let { it to k } }
            .sortedWith(compareBy<Pair<Int, Known>> { it.first }.thenByDescending { it.second.count })
            .take(limit).map { it.second }
    }

    /**
     * Reads [query] as a row of existing tags: "big tits blonde" → [Big Tits, Blonde]. [names]: every
     * tag, lower case → as written. Null when some word isn't part of any tag, or fewer than [atLeast] come out.
     */
    fun asTags(names: Map<String, String>, query: String, atLeast: Int = 2): List<String>? {
        val words = norm(query).split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            // The longest run of words that is a tag wins ("big tits" before "big").
            var taken = 0
            for (n in minOf(4, words.size - i) downTo 1) {
                val hit = names[words.subList(i, i + n).joinToString(" ")] ?: continue
                if (out.none { it.equals(hit, ignoreCase = true) }) out += hit
                taken = n
                break
            }
            if (taken == 0) return null
            i += taken
        }
        return out.takeIf { it.size >= atLeast }
    }

    /**
     * Tag combinations for a search of several words: the words that already are tags, plus tags
     * that finish the last, half-typed one ("bdsm sen" → [BDSM, Sensual], …). A query that is
     * made of tags as it stands comes first.
     */
    fun combos(names: Map<String, String>, all: List<Known>, query: String, limit: Int): List<List<String>> {
        val words = norm(query).split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return emptyList()
        val out = ArrayList<List<String>>()
        asTags(names, query)?.let { out += it }
        for (k in words.size - 1 downTo 1) {
            val head = asTags(names, words.subList(0, k).joinToString(" "), atLeast = 1) ?: continue
            val tail = words.subList(k, words.size).joinToString(" ")
            val ends = all.asSequence().filter { score(it.name, tail) in 0..2 && head.none { h -> h.equals(it.name, ignoreCase = true) } }
                .sortedByDescending { it.count }.take(limit).map { head + it.name }.toList()
            ends.forEach { c -> if (out.none { it.map(String::lowercase) == c.map(String::lowercase) }) out += c }
            if (ends.isNotEmpty()) break
        }
        return out.take(limit)
    }
}

/**
 * Every tag and niche RedGifs has, kept on the phone so the search box can suggest only things
 * that exist, at once and without a request per keystroke. The lists come from the site (one
 * request for the tags, about twenty for the niches) and are read again after [MAX_AGE_MS].
 */
class SearchIndex(private val file: File) {
    @Volatile var tags: List<Known> = emptyList(); private set
    @Volatile var niches: List<Known> = emptyList(); private set
    /** Tag names, lower case → as written. */
    @Volatile var tagNames: Map<String, String> = emptyMap(); private set
    @Volatile private var read = false

    val ready get() = tags.isNotEmpty()

    /** Reads the stored lists (once). */
    @Synchronized fun load() {
        if (read) return
        read = true
        runCatching {
            if (!file.exists()) return
            val t = ArrayList<Known>(); val n = ArrayList<Known>()
            file.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size >= 3 && p[0] == "T" -> t += Known(p[1], p[2].toLongOrNull() ?: 0)
                    p.size >= 5 && p[0] == "N" -> n += Known(p[2], p[3].toLongOrNull() ?: 0, p[1], p[4].split(',').filter { it.isNotEmpty() })
                }
            }
            set(t, n)
        }.onFailure { AppLog.w("Stored search suggestions couldn't be read: ${it.message}") }
    }

    private fun set(t: List<Known>, n: List<Known>) {
        tags = t; niches = n
        tagNames = t.associate { Suggest.norm(it.name) to it.name }
    }

    /** Replaces the lists and stores them. An empty list keeps what was there. */
    @Synchronized fun store(t: List<Known>, n: List<Known>) {
        val newTags = t.ifEmpty { tags }
        val newNiches = n.ifEmpty { niches }
        set(newTags, newNiches)
        read = true
        runCatching {
            fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()
            val tmp = File(file.path + ".tmp")
            file.parentFile?.mkdirs()
            tmp.bufferedWriter().use { w ->
                newTags.forEach { w.append("T\t").append(clean(it.name)).append('\t').append(it.count.toString()).append('\n') }
                newNiches.forEach { w.append("N\t").append(clean(it.id)).append('\t').append(clean(it.name)).append('\t').append(it.count.toString()).append('\t').append(it.labels.joinToString(",")).append('\n') }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { AppLog.w("Search suggestions couldn't be stored: ${it.message}") }
    }

    companion object {
        /** The lists are read from the site again after a week. */
        const val MAX_AGE_MS = 7L * 24 * 3_600_000
    }
}

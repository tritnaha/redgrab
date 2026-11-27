package app.redgrab

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The changelog, notification wording, stream sizes and the sideways grid. */
class ChangelogTest {

    private val entries = Changelog.parse(File("../CHANGELOG.md").readText())

    /** A version can't be built without saying what it changes: the newest entry must be this build. */
    @Test fun everyVersionHasAnEntry() {
        val top = entries.first()
        assertEquals("CHANGELOG.md: the first entry must be the version in app/build.gradle.kts", BuildConfig.VERSION_NAME.removeSuffix("-test"), top.version)
        assertEquals(BuildConfig.VERSION_CODE, top.build)
        assertTrue("the newest version lists no changes", top.sections.sumOf { it.second.size } > 0)
        // Newest first, no build twice, and every entry says something under a known heading.
        assertEquals(entries.map { it.build }.sortedDescending(), entries.map { it.build })
        assertEquals(entries.size, entries.map { it.build }.toSet().size)
        for (e in entries) {
            assertTrue("${e.version} has no changes", e.sections.isNotEmpty())
            assertTrue("${e.version} has a heading other than Added/Changed/Fixed", e.sections.all { it.first in setOf("Added", "Changed", "Fixed") })
            assertTrue("${e.version} has no date", Regex("\\d{4}-\\d{2}-\\d{2}").matches(e.date))
            assertTrue("${e.version}: a change is too long for one line in the app", e.sections.all { s -> s.second.all { it.length in 8..190 } })
        }
        // Every version but the newest links to its code on GitHub (the newest links to the commit it is built from).
        for (e in entries.drop(1)) assertTrue("${e.version} has no link", e.link?.startsWith("https://github.com/${BuildConfig.GITHUB_REPO}/") == true)
    }

    @Test fun whatsNewShowsTheVersionsSinceTheOneYouHad() {
        val current = entries.first().build
        // From the build before: just the new one. From three back: three. Unknown earlier build: the current one only.
        assertEquals(listOf(current), Changelog.since(entries, current - 1, current).map { it.build })
        assertEquals(listOf(current, current - 1, current - 2), Changelog.since(entries, current - 3, current).map { it.build })
        assertEquals(listOf(current), Changelog.since(entries, 0, current).map { it.build })
        assertTrue(Changelog.since(entries, current, current).isEmpty())
        // An older build doesn't show versions it isn't.
        assertTrue(Changelog.since(entries, 0, current - 1).none { it.build == current })
    }

    @Test fun linksLeadToGitHub() {
        val page = "https://github.com/o/r"
        val newest = ChangelogEntry("9.9", 99, "2026-01-01", emptyList(), null)
        assertEquals("$page/commit/abc1234", Changelog.linkFor(newest, page, 99, "abc1234"))
        assertEquals("$page/blob/HEAD/CHANGELOG.md", Changelog.linkFor(newest, page, 99, "unknown"))
        assertEquals("$page/blob/HEAD/CHANGELOG.md", Changelog.linkFor(newest, page, 98, "abc1234"))
        assertEquals("https://x/y", Changelog.linkFor(newest.copy(link = "https://x/y"), page, 99, "abc1234"))
    }

    @Test fun parsing() {
        val list = Changelog.parse("""
            # Changelog
            intro text
            ## 2.0 (build 5) · 2026-02-01
            ### Added
            - One thing
            - Another
            ### Fixed
            - A bug
            [Details](https://github.com/o/r/commit/abc)
            ## 1.0 (build 4)
            - Loose line
        """.trimIndent())
        assertEquals(2, list.size)
        assertEquals(ChangelogEntry("2.0", 5, "2026-02-01", listOf("Added" to listOf("One thing", "Another"), "Fixed" to listOf("A bug")), "https://github.com/o/r/commit/abc"), list[0])
        assertEquals(listOf("Changed" to listOf("Loose line")), list[1].sections)
        assertNull(list[1].link)
    }

    @Test fun notificationsNameNoSiteAndNoClip() {
        val all = listOf(Notices.SAVING, Notices.CHECK_TITLE, Notices.CHECK, Notices.SIGN_IN, Notices.SYNC_CHECK,
            Notices.progress(3, 10, "2.1 MB/s", "1 min"), Notices.progress(0, 1, null, null),
            Notices.done(5, 0, false, false), Notices.done(5, 2, false, false), Notices.done(0, 0, true, false), Notices.done(1, 0, false, true))
        val telling = OnlineSource.entries.map { it.label.lowercase() } + listOf("gif", "clip", "video", "porn", "nsfw", "tag", "creator")
        for (text in all) for (word in telling) assertFalse("\"$text\" says \"$word\"", text.lowercase().contains(word))
        assertEquals("3 of 10 · 2.1 MB/s · 1 min left", Notices.progress(3, 10, "2.1 MB/s", "1 min"))
        assertEquals("Saved 5, 2 failed.", Notices.done(5, 2, false, false))
    }

    @Test fun streamSizeIsAskedBeforeDownloading() = runBlocking {
        // The pieces are asked for several at a time, so the list is filled from more than one thread.
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        fun client(sizeOf: (String) -> String?): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            asked += req.method + " " + req.url.encodedPath
            val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody("video/mp2t".toMediaType()))
            sizeOf(req.url.encodedPath)?.let { b.header("Content-Length", it) }
            b.build()
        }.build()
        val urls = (1..5).map { "https://cdn.example/v/seg$it.ts".toHttpUrl() }
        // Every piece answers: the total is their sum, and nothing was downloaded to learn it.
        assertEquals(5 * 1000L, HlsSaver.probe(client { "1000" }, urls))
        assertTrue(asked.all { it.startsWith("HEAD ") } && asked.size == 5)
        // One piece without a size: no total at all, rather than a wrong one.
        assertNull(HlsSaver.probe(client { if (it.endsWith("seg3.ts")) null else "1000" }, urls))
        assertNull(HlsSaver.probe(client { "1000" }, emptyList()))
        // Without sizes the total is estimated from the pieces fetched so far.
        assertEquals(-1L, HlsSaver.estimateTotal(0, 0, 5))
        assertEquals(5000L, HlsSaver.estimateTotal(1000, 1, 5))
        assertEquals(5500L, HlsSaver.estimateTotal(2200, 2, 5))
        assertEquals(4321L, HlsSaver.estimateTotal(4321, 5, 5))
    }

    @Test fun sidewaysGridsShowMoreClipsPerRow() {
        assertEquals(3, app.redgrab.ui.columnsFor(3, 412, 915))   // upright: as chosen
        assertEquals(7, app.redgrab.ui.columnsFor(3, 915, 412))   // sideways: same tile size, more of them
        assertEquals(4, app.redgrab.ui.columnsFor(2, 915, 412))
        assertEquals(14, app.redgrab.ui.columnsFor(8, 1200, 500)) // never absurdly many
        assertEquals(3, app.redgrab.ui.columnsFor(3, 0, 0))
    }
}

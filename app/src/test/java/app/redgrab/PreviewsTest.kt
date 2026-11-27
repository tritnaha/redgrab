package app.redgrab

import app.redgrab.ui.pickPreviews
import app.redgrab.ui.previewCost
import app.redgrab.ui.visibleShare
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rules for which grid previews play: how much of a tile is on screen, and how many fit. */
class PreviewsTest {

    @Test fun shareOfATileThatIsOnScreen() {
        // The visible part of the grid runs from 0 to 1000.
        assertEquals(1f, visibleShare(top = 100, height = 300, viewStart = 0, viewEnd = 1000), 0.001f)
        assertEquals(0.5f, visibleShare(top = 850, height = 300, viewStart = 0, viewEnd = 1000), 0.001f)  // the next row, halfway in
        assertEquals(0.2f, visibleShare(top = -240, height = 300, viewStart = 0, viewEnd = 1000), 0.001f) // a row almost scrolled away
        assertEquals(0f, visibleShare(top = 1000, height = 300, viewStart = 0, viewEnd = 1000), 0.001f)
        assertEquals(0f, visibleShare(top = -300, height = 300, viewStart = 0, viewEnd = 1000), 0.001f)
        assertEquals(0f, visibleShare(top = 0, height = 0, viewStart = 0, viewEnd = 1000), 0.001f)
        // A tile taller than the screen can still be fully "there" only in part.
        assertEquals(0.5f, visibleShare(top = -500, height = 2000, viewStart = 0, viewEnd = 1000), 0.001f)
    }

    @Test fun previewsAreAddedFromTheTopWithinCountAndDecodingBudget() {
        val all = listOf("a", "b", "c", "d", "e")
        // Room for three by count.
        assertEquals(listOf("a", "b", "c"), pickPreviews(all, emptySet(), max = 3, budget = 99f) { 1f })
        // Ones already playing stay and count; only newcomers are returned.
        assertEquals(listOf("a", "d"), pickPreviews(all, setOf("b", "c"), max = 4, budget = 99f) { 1f })
        // Decoding budget of two full-HD streams: two big ones, or one big and several small.
        assertEquals(listOf("a", "b"), pickPreviews(all, emptySet(), max = 10, budget = 2f) { 1f })
        val cost = mapOf("a" to 1f, "b" to 1f, "c" to 0.25f, "d" to 1f, "e" to 0.25f)
        assertEquals(listOf("a", "c", "e"), pickPreviews(all, emptySet(), max = 10, budget = 1.5f) { cost.getValue(it) })
        // Pictures cost nothing and always fit the budget (but still count).
        assertEquals(all, pickPreviews(all, emptySet(), max = 10, budget = 0f) { 0f })
        assertEquals(emptyList<String>(), pickPreviews(all, all.toSet(), max = 10, budget = 99f) { 1f })
    }

    @Test fun pagesOfTheSavedGridAndOfOnlineFeeds() {
        // The Saved grid: a page is every 50 clips.
        assertEquals(1, app.redgrab.ui.pageOf(0)); assertEquals(1, app.redgrab.ui.pageOf(49)); assertEquals(2, app.redgrab.ui.pageOf(50))
        assertEquals(13, app.redgrab.ui.pageOf(612))
        assertEquals(1, app.redgrab.ui.pageCount(0)); assertEquals(1, app.redgrab.ui.pageCount(50)); assertEquals(2, app.redgrab.ui.pageCount(51))
        assertEquals(29, app.redgrab.ui.pageCount(1430))
        // Online: the site's own pages, of whatever length each turned out to be.
        val starts = listOf(0, 26, 66, 104)
        assertEquals(1, app.redgrab.ui.pageAt(0, starts, firstPage = 1))
        assertEquals(1, app.redgrab.ui.pageAt(25, starts, firstPage = 1))
        assertEquals(2, app.redgrab.ui.pageAt(26, starts, firstPage = 1))
        assertEquals(4, app.redgrab.ui.pageAt(500, starts, firstPage = 1))
        // After opening a feed straight at page 30.
        assertEquals(30, app.redgrab.ui.pageAt(0, listOf(0, 40), firstPage = 30))
        assertEquals(31, app.redgrab.ui.pageAt(40, listOf(0, 40), firstPage = 30))
        assertEquals(1, app.redgrab.ui.pageAt(0, emptyList(), firstPage = 1))
    }

    @Test fun updaterReadsTheBuildNumberFromTheReleaseFile() {
        assertEquals(14, Updater.buildOf("RedGrab-13.1-14.apk"))
        assertEquals(205, Updater.buildOf("RedGrab-14.0-beta-205.APK"))
        assertEquals(null, Updater.buildOf("RedGrab-13.1.apk"))
        assertEquals(null, Updater.buildOf("notes.txt"))
        val assets = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/assets"
        val r = Updater.parseRelease("""{"tag_name":"v13.2","name":"RedGrab 13.2","body":"- Fix one\n- Fix two","assets":[
            {"name":"checksums.txt","url":"$assets/1","size":10},
            {"name":"RedGrab-13.2-15.apk","url":"$assets/2","size":18153487}]}""")!!
        assertEquals("13.2", r.version); assertEquals(15, r.build)
        assertEquals("$assets/2", r.assetUrl)
        assertEquals(18153487L, r.size)
        assertEquals("- Fix one\n- Fix two", r.notes)
        assertEquals(null, Updater.parseRelease("""{"tag_name":"v13.2","assets":[]}"""))
        assertEquals("", Updater.parseRelease("""{"tag_name":"v1","body":null,"assets":[{"name":"a-2.apk","url":"$assets/9","size":1}]}""")!!.notes)
        // A file that isn't one of this repository's own release assets is never fetched (the token would go with the request).
        assertEquals(null, Updater.parseRelease("""{"tag_name":"v9","assets":[{"name":"RedGrab-9.0-99.apk","url":"https://example.com/RedGrab-9.0-99.apk","size":1}]}"""))
        assertEquals(null, Updater.parseRelease("""{"tag_name":"v9","assets":[{"name":"RedGrab-9.0-99.apk","url":"https://api.github.com/repos/someone/else/releases/assets/5","size":1}]}"""))
    }

    @Test fun reportsNeverCarrySignInTokens() {
        val jwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLzEyMyIsImV4cCI6MTc5NTczNDQ4N30.c2lnbmF0dXJlLXBhcnQtaGVyZQ"
        val text = "Signed in with $jwt ok; token github_pat_11ABCDEFG0abcdefghijklmnop_zyxw and ghp_abcdefghijklmnopqrstuvwxyz0123456789 end"
        val out = BugReport.redact(text)
        assertEquals("Signed in with [token removed] ok; token [token removed] and [token removed] end", out)
        assertEquals("Refresh done: 12 clips (3 liked)", BugReport.redact("Refresh done: 12 clips (3 liked)"))
        // Stored sign-ins are removed by their exact value too, whatever they look like (a Reddit token has no telling shape).
        assertEquals("Reddit said no to [removed] for u/[removed]", BugReport.redact("Reddit said no to 123456-AbCdEfGhIjKl for u/somebody", listOf("123456-AbCdEfGhIjKl", null, "somebody", "abc")))
    }

    @Test fun aDraggedIndicatorStaysOnTheGrid() {
        val size = androidx.compose.ui.unit.IntSize(200, 80)
        val room = androidx.compose.ui.unit.IntSize(1000, 2000)
        val base = androidx.compose.ui.geometry.Offset(780f, 1700f) // its usual place, near the bottom right
        fun at(x: Float, y: Float) = app.redgrab.ui.clampOffset(androidx.compose.ui.geometry.Offset(x, y), base, size, room)
        assertEquals(androidx.compose.ui.geometry.Offset(-300f, -900f), at(-300f, -900f))   // anywhere inside is kept
        assertEquals(androidx.compose.ui.geometry.Offset(-780f, -1700f), at(-5000f, -5000f)) // not past the top left
        assertEquals(androidx.compose.ui.geometry.Offset(20f, 220f), at(5000f, 5000f))       // not past the bottom right
        // A grid smaller than the indicator: it stays at the start rather than jumping about.
        assertEquals(androidx.compose.ui.geometry.Offset(0f, 0f),
            app.redgrab.ui.clampOffset(androidx.compose.ui.geometry.Offset(50f, 50f), androidx.compose.ui.geometry.Offset.Zero, size, androidx.compose.ui.unit.IntSize(100, 40)))
    }

    private fun clip(w: Int, h: Int, hd: String? = "https://x/y.mp4", sd: String? = "https://x/y-sd.mp4", file: String? = null) =
        Clip(id = "c", hd = hd, sd = sd, thumb = null, duration = 5.0, width = w, height = h, hasAudio = false, user = "", created = 0,
            sources = emptySet(), copyFile = file)

    @Test fun smallFilesCostLessToPreviewThanHdOnes() {
        val hd = clip(1080, 1920)
        assertEquals(1f, previewCost(hd, setOf(Quality.HD)), 0.001f)                 // only the HD file is saved: that one plays
        assertEquals(0.25f, previewCost(hd, setOf(Quality.HD, Quality.SD)), 0.001f)  // the SD file plays when there is one
        assertEquals(0.25f, previewCost(hd, emptySet()), 0.001f)                     // not saved: the SD stream
        assertEquals(1f, previewCost(clip(1080, 1920, sd = null), null), 0.001f)     // not saved and no SD version
        assertEquals(1f, previewCost(clip(0, 0), setOf(Quality.HD)), 0.001f)         // size unknown: counted as full HD
        assertEquals(0f, previewCost(clip(300, 200, hd = null, sd = null, file = "imported/a.gif"), null), 0.001f) // a picture
        assertEquals(1f, previewCost(null, null), 0.001f)
        assertEquals(0.1f, previewCost(clip(320, 240), setOf(Quality.SD)), 0.001f)   // never counted as free
    }

    @Test fun previewCopiesAreSmallShortAndCheap() {
        // The short side becomes 360, whichever way the clip is turned; sizes stay even; nothing is scaled up.
        assertEquals(360, PreviewMaker.targetHeight(1920, 1080))
        assertEquals(640, PreviewMaker.targetHeight(1080, 1920))
        assertEquals(360, PreviewMaker.targetHeight(1080, 1080))
        assertEquals(240, PreviewMaker.targetHeight(426, 240))
        assertEquals(0, PreviewMaker.targetHeight(1080, 1350) % 2)
        assertEquals(360, PreviewMaker.targetHeight(0, 0))
        // Short clips play from the start; longer ones a little way in, never far.
        assertEquals(0L, PreviewMaker.startMs(8_000))
        assertEquals(4_500L, PreviewMaker.startMs(30_000))
        assertEquals(5_000L, PreviewMaker.startMs(600_000))
        // A copy costs the same small amount whatever the clip's own size, so a screenful fits the budget.
        val hd = clip(1920, 1080)
        assertEquals(1f, app.redgrab.ui.previewCost(hd, setOf(Quality.HD)), 0.001f)
        assertEquals(PreviewMaker.COST, app.redgrab.ui.previewCost(hd, setOf(Quality.HD), copy = true), 0.001f)
        val ids = (1..20).map { "c$it" }
        assertEquals(20, app.redgrab.ui.pickPreviews(ids, emptySet(), 20, 4f) { PreviewMaker.COST }.size)
        assertEquals(4, app.redgrab.ui.pickPreviews(ids, emptySet(), 20, 4f) { 1f }.size)
    }
}

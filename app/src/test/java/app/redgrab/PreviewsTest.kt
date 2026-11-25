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
}

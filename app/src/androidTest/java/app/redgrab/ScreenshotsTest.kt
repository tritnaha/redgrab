package app.redgrab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import coil3.request.allowHardware
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Takes the pictures in the README. Runs the app on a phone or emulator, fills it with made-up
 * clips whose thumbnails are drawn here (skies and hills), and photographs a few screens. Nothing
 * in the pictures comes from any site, and nothing is fetched.
 *
 * Not part of the normal build or tests. To retake them, with a device attached:
 *
 *     ./gradlew assembleDebug assembleDebugAndroidTest
 *     adb install -r app/build/outputs/apk/debug/app-debug.apk
 *     adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *     adb shell am instrument -w -e class app.redgrab.ScreenshotsTest app.redgrab.test/androidx.test.runner.AndroidJUnitRunner
 *     (one picture: -e class app.redgrab.ScreenshotsTest#online)
 *     adb pull /sdcard/Android/data/app.redgrab/files/shots screenshots
 *
 * It overwrites the app's sign-in and library on that device: use an emulator or a spare phone.
 *
 * On an emulator without a graphics card behind it, app windows can come out black. The pictures
 * in the repository were taken on such an emulator with a local debug build that draws in
 * software (android:hardwareAccelerated="false" in a debug manifest that isn't kept in the
 * repository); what is drawn is the same.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotsTest {

    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    private val app get() = ApplicationProvider.getApplicationContext<RedGrabApp>()
    private val out by lazy { File(app.getExternalFilesDir(null), "shots").apply { mkdirs() } }

    // ---- sample pictures: a sky, a sun or moon, hills -------------------------------------

    private val palettes = listOf(
        intArrayOf(0x1B2A49, 0xF2994A, 0xFFE29A, 0x2D4B3E, 0x16302B), // dusk
        intArrayOf(0x0B1026, 0x3A2E6E, 0xE8E6F0, 0x1C2541, 0x0B132B), // night
        intArrayOf(0x7EC8E3, 0xE3F6FF, 0xFFF3B0, 0x4F9D69, 0x2F6F4F), // day
        intArrayOf(0x3D1E4F, 0xE85D75, 0xFFD6A5, 0x5B2A5E, 0x2B1535), // magenta dusk
        intArrayOf(0x0F4C5C, 0x9AD1D4, 0xFDFCDC, 0x1B6B73, 0x0B3C49), // teal
        intArrayOf(0x22223B, 0xC9ADA7, 0xF2E9E4, 0x4A4E69, 0x2B2D42), // slate
    )
    private fun opaque(rgb: Int) = rgb or (0xFF shl 24)
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt() and 0xFF) shl shift
        return opaque(ch(16) or ch(8) or ch(0))
    }

    private fun picture(file: File, w: Int, h: Int, seed: Int) {
        val r = Random(seed)
        val p = palettes[seed % palettes.size]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.75f, opaque(p[0]), opaque(p[1]), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = opaque(p[2])
        c.drawCircle(w * (0.2f + r.nextFloat() * 0.6f), h * (0.18f + r.nextFloat() * 0.25f), w * (0.10f + r.nextFloat() * 0.10f), paint)
        for (layer in 0..2) {
            val base = h * (0.58f + layer * 0.13f)
            val amp = h * (0.05f + r.nextFloat() * 0.05f)
            val phase = r.nextDouble() * 2 * PI
            val waves = 1.0 + r.nextDouble() * 1.5
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                var x = 0
                while (x <= w) { lineTo(x.toFloat(), base + amp * sin(phase + x.toDouble() / w * 2 * PI * waves).toFloat()); x += 4 }
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            paint.color = mix(p[3], p[4], layer / 2f)
            c.drawPath(path, paint)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
    }

    private val users = listOf("northlight", "tidepool", "slowshutter", "highmeadow", "duskwalker", "harbourfog")
    private val tagSets = listOf(listOf("timelapse", "sunset"), listOf("night sky", "stars"), listOf("hills", "morning"),
        listOf("coast", "loop"), listOf("clouds", "wide"), listOf("valley", "mist"))
    private val shapes = listOf(1080 to 1920, 1080 to 1920, 1920 to 1080, 1080 to 1350, 1080 to 1080, 1080 to 1920, 1920 to 1080)

    private fun sample(i: Int, prefix: String, sources: Set<String>): Clip {
        val (w, h) = shapes[i % shapes.size]
        val file = File(app.cacheDir, "samples/$prefix$i.jpg")
        picture(file, w / 3, h / 3, i + prefix.length * 7)
        return Clip(id = "$prefix$i", hd = "https://example.invalid/$prefix$i.mp4", sd = "https://example.invalid/$prefix$i-sd.mp4",
            thumb = Uri.fromFile(file).toString(), duration = 8.0 + i * 3, width = w, height = h, hasAudio = i % 3 != 0,
            user = users[i % users.size], created = 1_760_000_000L - i * 86_400L, sources = sources,
            tags = tagSets[i % tagSets.size], views = 1200L * (i + 3), likes = 40L * (i + 1), verified = i % 4 == 0)
    }

    // ---- the app, as someone with a small library would see it ------------------------------

    private fun seed(): List<Clip> {
        val lib = app.library
        // Plain bitmaps, so the pictures also draw where the screen is drawn in software (some emulators).
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(app).allowHardware(false).build())
        lib.prefs.apply {
            userAgent = "Mozilla/5.0 (sample)"
            token = "sample"
            tokenExp = System.currentTimeMillis() / 1000 + 20 * 3600
            lastSynced = System.currentTimeMillis() - 40 * 60_000   // recent: opening the app doesn't sync
            lastUpdateCheck = System.currentTimeMillis()
            previews = false                                        // there is no video behind the samples
            // A save folder, so the "choose a folder" card isn't the first thing in the picture. Nothing is written to it.
            folderUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies%2FRedGrab")
            folderLabel = "Movies/RedGrab"
            lastSeenBuild = BuildConfig.VERSION_CODE
            contentPrefsAsked = true                                // the first-run question isn't part of these pictures
            searchIndexAt = System.currentTimeMillis()              // the lists below count as fresh: nothing is fetched
        }
        // What the search box suggests from: a few tags and niches, in the form the app stores RedGifs' lists.
        File(app.filesDir, "search-index.tsv").writeText(
            "T\tDancing\t140000\nT\tSunset\t90000\nT\tSunrise\t40000\nT\tNight Sky\t30000\nT\tGay\t700000\n" +
                "N\tslow-dance\tSlow Dance\t637380\tstraight,lesbian\nN\tsun-seekers\tSun Seekers\t1200\tstraight\n")
        val mine = (0 until 18).map { i ->
            sample(i, "sample", if (i % 4 == 3) setOf("Favourites") else if (i % 5 == 4) setOf("Liked", "Landscapes") else setOf("Liked"))
        }
        // Likes and collections arrive through a sync; here they are put in place directly.
        Library::class.java.getDeclaredField("raw").apply { isAccessible = true }.set(lib, mine)
        lib.addExtra(sample(40, "saved", setOf("Discover")))
        mine.forEachIndexed { i, c -> if (i != 2 && i != 9) lib.markSaved(c.id, if (i % 6 == 5) Quality.SD else Quality.HD) }
        lib.markSaved("saved40", Quality.HD)
        lib.markFailed(mine[9].id, "No longer on RedGifs", dead = true)
        lib.setCategory(mine[0].id, "Favourites")
        lib.setCategory(mine[4].id, "Coast")
        return mine
    }

    /** Waits until [text] is on screen: the sign that a screen has been drawn (a slow emulator can take a while). */
    private fun await(text: String) = check(device.wait(Until.hasObject(By.text(text)), 120_000)) { "\"$text\" didn't appear" }

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(4000) // pictures are decoded off the main thread; a slow emulator needs a moment
        device.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("no screenshot")
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Taps a tab of the bar at the bottom, as a finger would. */
    private fun tab(label: String) {
        device.wait(Until.hasObject(By.text(label)), 20_000)
        (device.findObjects(By.text(label)).maxByOrNull { it.visibleBounds.top } ?: error("no \"$label\" on screen")).click()
        device.waitForIdle()
        Thread.sleep(1500)
    }

    private fun MainViewModel.showOnline(clips: List<Clip>) {
        val next = discover.copy(items = clips, page = 1, firstPage = 1, pages = 40, pageStarts = listOf(0), hasMore = false, loading = false, error = null)
        discover = next
    }

    /** Opens the app with the sample library and hands over its view model once the first screen is up. */
    private fun withApp(block: (ActivityScenario<MainActivity>, MainViewModel, List<Clip>) -> Unit) {
        val mine = seed()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MainViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }
            // A busy emulator can take a while to draw the first screen, and may put one of its own dialogs
            // ("... isn't responding") on top: that one is answered with "Wait".
            var up = false
            for (attempt in 1..12) {
                if (device.wait(Until.hasObject(By.text("Browse")), 10_000)) { up = true; break }
                device.findObject(By.text("Wait"))?.click()
            }
            check(up) {
                // What is on screen instead, so a failure says why.
                val texts = device.findObjects(By.clazz("android.widget.TextView")).mapNotNull { runCatching { it.text }.getOrNull() }.filter { it.isNotBlank() }.take(12)
                "the app's first screen didn't come up; in front: ${device.currentPackageName}, showing $texts"
            }
            block(scenario, vm, mine)
        }
    }

    /** A feed of clips that aren't saved, with two that are in the library. */
    private fun feed(mine: List<Clip>) = (0 until 14).map { sample(it, "online", emptySet()) }.toMutableList().apply { add(3, mine[1]); add(8, mine[6]) }

    /**
     * The first clip of the feed with a real video behind it, so the clip view has something to play:
     * eight seconds of its own thumbnail, kept with this test (assets/sample.mp4, made with ffmpeg).
     */
    private fun playable(c: Clip): Clip {
        val file = File(app.cacheDir, "samples/sample.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("sample.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val url = Uri.fromFile(file).toString()
        return c.copy(hd = url, sd = url)
    }

    // One picture per test, so each can be retaken by itself.

    @Test fun saved() = withApp { _, _, _ -> shot("v13-saved") }

    @Test fun online() = withApp { scenario, vm, mine ->
        val feed = feed(mine)
        scenario.onActivity { vm.showOnline(feed) }
        tab("Online")
        await("For you")
        shot("v13-online")
    }

    /** Suggestions under the search box: tags and niches that exist, from the lists kept on the phone. */
    @Test fun search() = withApp { scenario, vm, mine ->
        scenario.onActivity { vm.changeSource(OnlineSource.RedGifs); vm.showOnline(feed(mine)) }
        tab("Online")
        await("For you")
        Thread.sleep(3000)
        scenario.onActivity { vm.showOnline(feed(mine)); vm.suggest("sun") }
        await("Sunset")
        // Creators are looked up on the site; the picture shows only the made-up tags and niches.
        Thread.sleep(4000)
        scenario.onActivity { vm.suggestions = vm.suggestions.filter { it.kind != Suggestion.Kind.Creator && it.kind != Suggestion.Kind.Subreddit } }
        shot("v13-search")
    }

    /** A clip opened over the grid. */
    @Test fun clip() = withApp { scenario, vm, mine ->
        val feed = feed(mine).also { it[0] = playable(it[0]) }
        scenario.onActivity { vm.showOnline(feed) }
        tab("Online")
        await("For you")
        scenario.onActivity { vm.openOnlinePlayer(feed, 0) }
        await("WhatsApp")
        shot("v13-clip")
    }

    /** The niche list opens (on a device without a route to RedGifs it shows its own "couldn't load" line). */
    @Test fun niches() = withApp { scenario, vm, mine ->
        scenario.onActivity { vm.showOnline(feed(mine)) }
        tab("Online")
        await("For you")
        tab("Browse niches")
        await("Most followed")
        shot("v13-niches")
    }

    // ---- behaviour checks (no pictures): things that only show on a device -------------------

    /** Going to another tab and back keeps your place; Back returns to the tab you came from. */
    @Test fun placeIsKeptAndBackGoesToThePreviousTab() = withApp { scenario, vm, _ ->
        // Scrolled directly rather than by a swipe: an emulator without acceleration can't keep up with touch input.
        scenario.onActivity { kotlinx.coroutines.MainScope().launch { vm.savedGrid.scrollToItem(12) } }
        Thread.sleep(3000)
        device.waitForIdle()
        val before = vm.savedGrid.firstVisibleItemIndex
        check(before > 0) { "the grid didn't scroll (at $before)" }
        tab("Settings"); await("Account")
        // The Saved tab's own title line ("17 on phone · …") stays put while the grid is scrolled.
        fun awaitSaved() = check(device.wait(Until.hasObject(By.textContains("on phone")), 120_000)) { "the Saved tab didn't come up" }
        tab("Saved"); awaitSaved()
        Thread.sleep(1500)
        check(vm.savedGrid.firstVisibleItemIndex == before) { "place lost: was at $before, now at ${vm.savedGrid.firstVisibleItemIndex}" }
        // Back: from Settings to Saved, not out of the app.
        tab("Settings"); await("Account")
        device.pressBack()
        awaitSaved()
        check(vm.savedGrid.firstVisibleItemIndex == before) { "place lost after Back: was at $before, now at ${vm.savedGrid.firstVisibleItemIndex}" }
        check(device.currentPackageName == app.packageName) { "Back left the app" }
    }

    /** Paging through clips in the clip view moves the grid behind it; closing leaves you at the last clip seen. */
    @Test fun gridFollowsTheClipView() = withApp { scenario, vm, _ ->
        val shown = visibleClips(vm.state.value, vm.filter, vm.query, vm.facet, vm.adv, vm.origin)
        check(shown.size >= 16) { "only ${shown.size} sample clips" }
        scenario.onActivity { vm.openPlayer(shown, 0) }
        await("WhatsApp")
        scenario.onActivity { repeat(15) { vm.movePlayer(1) } }
        Thread.sleep(4000)
        val target = shown[15].id
        scenario.onActivity { vm.closePlayer() }
        Thread.sleep(2500)
        device.waitForIdle()
        val visible = vm.savedGrid.layoutInfo.visibleItemsInfo.map { it.key }
        check(target in visible) { "the last clip seen ($target) isn't on screen after closing; visible: $visible" }
    }

    /** The combined view opens and takes a search (without a route to the sites it simply finds nothing). */
    @Test fun allSitesOpens() = withApp { scenario, vm, _ ->
        scenario.onActivity { vm.changeSource(OnlineSource.All) }
        tab("Online")
        await("All sites")
        scenario.onActivity { vm.openFeed(Feed.AllSites("night sky")) }
        await("All sites: night sky")
        Thread.sleep(8000)
        check(vm.discover.feed == Feed.AllSites("night sky")) { "All sites didn't stay open: ${vm.discover.feed}" }
    }

    /** The list under the search box: only things that exist, the filter applied, and a tap opens the exact tag. */
    @Test fun searchSuggestsWhatExists() = withApp { scenario, vm, mine ->
        scenario.onActivity { vm.changeSource(OnlineSource.RedGifs); vm.showOnline(feed(mine)) }
        tab("Online")
        await("For you")
        Thread.sleep(3000) // the stored lists are read when the tab opens
        scenario.onActivity { vm.suggest("dan") }
        await("Dancing")
        await("Slow Dance")
        check(vm.suggestions.map { it.label } == listOf("Dancing", "Slow Dance")) { "suggestions for \"dan\": ${vm.suggestions}" }
        // Several words: the ones that are tags stay, the last is completed.
        scenario.onActivity { vm.suggest("sunset dan") }
        await("Sunset + Dancing")
        // "Gay" exists but is filtered out unless asked for.
        scenario.onActivity { vm.suggest("ga") }
        Thread.sleep(1500)
        check(vm.suggestions.none { it.label == "Gay" }) { "a filtered tag was suggested: ${vm.suggestions}" }
        scenario.onActivity { vm.changeContentPrefs(ContentPrefs.ALL.toSet()); vm.suggest("ga") }
        await("Gay")
        scenario.onActivity { vm.changeContentPrefs(ContentPrefs.DEFAULT); vm.suggest("sun") }
        await("Sunset")
        device.findObject(By.text("Sunset")).click()
        Thread.sleep(2500)
        check(vm.discover.feed == Feed.Tags(listOf("Sunset"))) { "tapping a tag opened ${vm.discover.feed}" }
        check(vm.suggestions.isEmpty()) { "the list stayed open" }
        // Typed words that are all tags open those tags; anything else is the site's text search.
        check(vm.feedFor(OnlineSource.RedGifs, "night sky sunset") == Feed.Tags(listOf("Night Sky", "Sunset")))
        check(vm.feedFor(OnlineSource.RedGifs, "night sky over water") == Feed.Search("night sky over water"))
        // The filter button opens the same dialog as Settings.
        device.findObject(By.desc("Filter: what to show")).click()
        await("No filter")
        device.pressBack()
    }

    /**
     * Clips kept inside the app: the private store takes files through the same calls the save folder
     * gets (create, write, list, read, rename, delete), a whole folder moves in at once with nothing
     * lost, and export writes plain files back out.
     */
    @Test fun privateStorageKeepsMovesAndExports() {
        val lib = app.library
        val resolver = app.contentResolver
        val before = lib.prefs.folderUri; val label = lib.prefs.folderLabel
        PrivateDocs.dir(app).deleteRecursively()
        try {
            // The private store behaves like a save folder.
            lib.prefs.folderUri = PrivateDocs.TREE
            val folder = lib.folder() ?: error("the private store isn't usable as a save folder")
            val part = folder.createFile("application/octet-stream", "clipone-hd.part") ?: error("no file created")
            resolver.openOutputStream(part.uri, "wt")!!.use { it.write(ByteArray(5000) { 7 }) }
            resolver.openOutputStream(part.uri, "wa")!!.use { it.write(ByteArray(3000) { 9 }) }
            val done = android.provider.DocumentsContract.renameDocument(resolver, part.uri, "clipone-hd.mp4") ?: error("not renamed")
            check(lib.listNames() == listOf("clipone-hd.mp4")) { "listing: ${lib.listNames()}" }
            check(lib.listEntries().single().size == 8000L) { "size: ${lib.listEntries()}" }
            check(lib.fileUri("clipone-hd.mp4")?.let(lib::exists) == true) { "the file isn't found by name" }
            check(resolver.openInputStream(done)!!.use { it.readBytes() }.let { it.size == 8000 && it[0] == 7.toByte() && it[7999] == 9.toByte() })
            check(File(PrivateDocs.dir(app), "clipone-hd.mp4").length() == 8000L)
            // Nothing outside the folder can be reached through it.
            check(runCatching { resolver.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(PrivateDocs.TREE, "clips/../shared_prefs/redgrab.xml")) }.isFailure)
            android.provider.DocumentsContract.deleteDocument(resolver, done)
            check(lib.listNames().isEmpty())

            // A folder of files, one in a sub-folder, moves in at once; the originals are gone afterwards.
            val old = File(app.getExternalFilesDir(null), "oldfolder").apply { deleteRecursively(); mkdirs() }
            File(old, "a-hd.mp4").writeBytes(ByteArray(4000) { 1 }); File(old, "b-sd.mp4").writeBytes(ByteArray(2500) { 2 })
            File(old, "imported").mkdirs(); File(old, "imported/c.gif").writeBytes(ByteArray(900) { 3 }); File(old, ".nomedia").writeBytes(ByteArray(0))
            // The old folder is reached as documents too: through the private store's own interface, one level up.
            val oldTree = android.provider.DocumentsContract.buildTreeDocumentUri(PrivateDocs.AUTHORITY, "clips/old")
            File(PrivateDocs.dir(app), "old").deleteRecursively(); old.copyRecursively(File(PrivateDocs.dir(app), "old")); old.deleteRecursively()
            lib.prefs.folderUri = oldTree
            val (moved, failed) = kotlinx.coroutines.runBlocking { lib.moveIntoApp { } }
            check(moved == 3 && failed == 0) { "moved $moved, failed $failed" }
            check(File(PrivateDocs.dir(app), "a-hd.mp4").length() == 4000L && File(PrivateDocs.dir(app), "imported/c.gif").length() == 900L) { "the moved files aren't in place" }
            check(File(PrivateDocs.dir(app), "old").walkTopDown().none { it.isFile && it.name != ".nomedia" }) { "originals were left behind" }
            File(PrivateDocs.dir(app), "old").deleteRecursively()

            // Export: plain files, sub-folder kept, the app's own copies stay.
            lib.prefs.folderUri = PrivateDocs.TREE
            File(PrivateDocs.dir(app), "out").mkdirs()
            val outTree = android.provider.DocumentsContract.buildTreeDocumentUri(PrivateDocs.AUTHORITY, "clips/out")
            // (Exporting into a folder inside the store itself, which is all a test may write to; the walk must not copy its own output.)
            val (copied, bad) = kotlinx.coroutines.runBlocking { lib.exportTo(outTree) { } }
            check(bad == 0 && copied >= 3) { "exported $copied, failed $bad" }
            check(File(PrivateDocs.dir(app), "out/a-hd.mp4").length() == 4000L && File(PrivateDocs.dir(app), "out/imported/c.gif").length() == 900L) { "exported files missing" }
            check(File(PrivateDocs.dir(app), "a-hd.mp4").exists()) { "export removed the app's copy" }
        } finally {
            PrivateDocs.dir(app).deleteRecursively()
            lib.prefs.folderUri = before; lib.prefs.folderLabel = label
        }
    }

    /** Following something on a site without following of its own: it shows in a row there, and can be undone. */
    @Test fun followsOnOtherSites() = withApp { scenario, vm, _ ->
        scenario.onActivity { vm.changeSource(OnlineSource.FikFap); if (!vm.followsOn(OnlineSource.FikFap, "sunset")) vm.toggleSiteFollow(OnlineSource.FikFap, "sunset") }
        tab("Online")
        await("Following")
        await("sunset")
        check(vm.followsOn(OnlineSource.FikFap, "SUNSET")) { "the follow wasn't kept: ${vm.siteFollows}" }
        // It is part of the mix "All sites" shows when nothing is typed.
        check(AllSites.feeds("", redGifs = false, reddit = false, personal = false, follows = vm.siteFollows).any { it.key == "FikFap|sunset" })
        scenario.onActivity { vm.toggleSiteFollow(OnlineSource.FikFap, "sunset"); vm.changeSource(OnlineSource.RedGifs) }
        check(!vm.followsOn(OnlineSource.FikFap, "sunset"))
    }

    @Test fun whatsNew() = withApp { scenario, vm, _ ->
        // What an update shows: the versions that are new to you (here, the last two).
        val recent = vm.changelog.take(2)
        scenario.onActivity { vm.whatsNew = recent }
        await("What's new")
        shot("v13-whats-new")
    }

    @Test fun settings() = withApp { _, _, _ ->
        tab("Settings")
        await("Account")
        shot("v13-settings")
    }

    /** Turned sideways: more clips per row. */
    @Test fun sideways() = withApp { scenario, _, _ ->
        scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        Thread.sleep(4000)
        shot("v13-sideways")
        scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

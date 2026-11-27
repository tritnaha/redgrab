package app.redgrab

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the app's updater asks GitHub, against the real repository and without a token. Skipped
 * unless asked for, because it needs the internet:
 *
 *     REDGRAB_LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveUpdateTest*'
 *
 * It covers finding the latest release and getting its APK; installing it is tried on an emulator
 * by the release workflow (.github/scripts/updater-e2e.sh). Run it from a plain connection: behind
 * a proxy that adds GitHub credentials it passes even while the repository is private, which an
 * installed app can't read.
 */
class LiveUpdateTest {

    @Test fun theLatestReleaseIsFoundAndItsApkCanBeFetchedWithoutAToken() {
        assumeTrue("set REDGRAB_LIVE=1 to run against GitHub", System.getenv("REDGRAB_LIVE") == "1")
        val http = RedGifsApi.clientFor("RedGrab-test")
        fun get(url: String, accept: String) = http.newCall(Request.Builder().url(url).header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28").build()).execute()

        val release = get("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest", "application/vnd.github+json").use { r ->
            assertEquals("latest release, no token", 200, r.code)
            Updater.parseRelease(r.body!!.string())
        }
        assertNotNull("the latest release has no APK named RedGrab-<version>-<build>.apk", release)
        release!!
        println("LIVE  latest release: ${release.version} (build ${release.build}), ${release.size} bytes, notes ${release.notes.length} characters")
        assertTrue("build number", release.build > 0)
        assertTrue("version", release.version.isNotBlank())
        assertTrue("release notes", release.notes.isNotBlank())

        // The APK itself, as the app asks for it: the first bytes of a ZIP, and the size the release states.
        get(release.assetUrl, "application/octet-stream").use { r ->
            assertEquals("the APK, no token", 200, r.code)
            assertEquals("size", release.size, r.body!!.contentLength())
            val head = ByteArray(2)
            r.body!!.byteStream().read(head)
            assertEquals("PK", String(head))
        }
    }
}

package app.redgrab

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sends clips to WhatsApp and nothing else: the intent is addressed to the WhatsApp package
 * explicitly, so Android never shows the general share sheet and no other app can receive it.
 * Works inside Secure Folder as long as WhatsApp is installed there too (same profile).
 *
 * Clips are handed over as copies in the app's private cache through the app's own
 * FileProvider. That works no matter where the save folder is, and WhatsApp only ever gets
 * access to exactly those files. Copies are removed after a few hours.
 */
object WhatsAppShare {
    val PACKAGES = mapOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business")
    const val MAX_CLIPS = 30
    private const val KEEP_MS = 6 * 60 * 60 * 1000L

    /** Installed WhatsApp variants in this profile, package → label. */
    fun installed(ctx: Context): Map<String, String> = PACKAGES.filterKeys { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    /** Removes share copies older than a few hours (never one WhatsApp might still be reading). */
    fun cleanUp(ctx: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        File(ctx.cacheDir, "share").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    /**
     * Makes a shareable copy of each clip: from the saved file when there is one, otherwise a
     * temporary download. Returns content URIs WhatsApp is allowed to read.
     */
    suspend fun prepare(ctx: Context, clips: List<Clip>): List<Uri> = withContext(Dispatchers.IO) {
        cleanUp(ctx)
        val lib = ctx.library
        // A fresh folder per share: a clip WhatsApp is still reading is never overwritten by the next share.
        val dir = File(ctx.cacheDir, "share/${System.currentTimeMillis()}").apply { mkdirs() }
        clips.map { clip ->
            // The type of the file on the phone, else of the online address (streams and odd addresses become MP4).
            val ext = clip.fileExt.takeIf { it in Clip.IMAGE_EXT || it in Clip.VIDEO_EXT } ?: "mp4"
            val out = File(dir, clip.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".$ext")
            val local = lib.localFile(clip)
            when {
                local != null -> ctx.contentResolver.openInputStream(local.first)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
                clip.isCopy || clip.imported -> error("The file is missing from the save folder")
                else -> {
                    // Not on the phone: a temporary download from the clip's own site.
                    val prefs = lib.prefs
                    val redgifs = OnlineSource.of(clip.id) == OnlineSource.RedGifs
                    val api = if (redgifs) RedGifsApi(prefs.token ?: error("Sign in first"), prefs.userAgent ?: error("Sign in first")) else null
                    val http = api?.http ?: RedGifsApi.clientFor(ctx.userAgent)
                    val url = clip.url(Quality.HD) ?: clip.url(Quality.SD) ?: error("No video URL")
                    if (HlsSaver.isHls(url)) {
                        val mp4 = HlsSaver.save(http, url, dir, "stream", onBytes = {})
                        if (!mp4.renameTo(out)) error("Could not prepare the clip")
                    } else {
                        val response = try {
                            RedGifsApi.download(http, url)
                        } catch (e: HttpException) {
                            val fresh = api?.clip(clip.id) ?: throw e
                            RedGifsApi.download(http, fresh.url(true) ?: throw e)
                        }
                        response.use { r -> out.outputStream().use { r.body!!.byteStream().copyTo(it) } }
                    }
                }
            }
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", out)
        }
    }

    fun send(ctx: Context, pkg: String, uris: List<Uri>) {
        require(pkg in PACKAGES) { "Only WhatsApp is allowed" }
        require(uris.isNotEmpty())
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val kinds = uris.map { it.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: "" }
        val type = when {
            kinds.all { it == "gif" } -> "image/gif"
            kinds.all { it in Clip.IMAGE_EXT } -> "image/*"
            kinds.none { it in Clip.IMAGE_EXT } -> "video/mp4"
            else -> "*/*"
        }
        intent.setType(type)
            .setPackage(pkg) // explicit target: no chooser, no other apps
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // ClipData makes the read permission apply to every attached file.
        intent.clipData = ClipData.newUri(ctx.contentResolver, "clip", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        ctx.startActivity(intent)
        AppLog.i("Shared ${uris.size} clip(s) to ${PACKAGES[pkg]}")
    }
}

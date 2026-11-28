package app.redgrab

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Saves an HLS stream (a ".m3u8" playlist of short pieces, which is how FikFap serves video) as
 * one MP4 file: picks the best version, downloads its pieces in order, then repacks them into
 * MP4 with Android's own extractor and muxer. Nothing is re-encoded, so quality is unchanged.
 * Encrypted and live streams are refused.
 */
object HlsSaver {

    /** The stream itself can't be saved (trying again wouldn't help). */
    class Unsupported(msg: String) : IOException(msg)

    fun isHls(url: String) = url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

    private val ATTR = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
    private fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun text(http: OkHttpClient, url: HttpUrl): String =
        RedGifsApi.download(http, url.toString()).use { it.text() }

    private fun HttpUrl.child(ref: String): HttpUrl = resolve(ref) ?: throw IOException("Bad address in the stream playlist")

    /** The pieces of one media playlist, in order (the first may be a header piece). */
    private fun pieces(base: HttpUrl, playlist: String): List<HttpUrl> {
        val out = ArrayList<HttpUrl>()
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") && attrs(line)["METHOD"].let { it != null && it != "NONE" } ->
                    throw Unsupported("The stream is encrypted and can't be saved")
                line.startsWith("#EXT-X-BYTERANGE") || (line.startsWith("#EXT-X-MAP") && "BYTERANGE" in attrs(line)) ->
                    throw Unsupported("This kind of stream can't be saved")
                line.startsWith("#EXT-X-MAP") -> attrs(line)["URI"]?.let { out += base.child(it) }
                line.isEmpty() || line.startsWith("#") -> {}
                else -> out += base.child(line)
            }
        }
        if ("#EXT-X-ENDLIST" !in playlist) throw Unsupported("Live streams can't be saved")
        if (out.isEmpty()) throw IOException("The stream playlist is empty")
        return out
    }

    /**
     * Downloads [url] and returns the finished MP4 in [dir] (the caller moves or deletes it).
     * [onBytes] is called with every chunk read, for progress and speed. [onTotal] is told how
     * many bytes there are to download in all: asked from the server before the first piece
     * (see [probe]), or, where it won't say, estimated from the pieces fetched so far and
     * corrected after each one.
     */
    suspend fun save(http: OkHttpClient, url: String, dir: File, name: String, onTotal: (Long) -> Unit = {},
                     onBytes: suspend (Int) -> Unit): File {
        dir.mkdirs()
        val masterUrl = url.toHttpUrl()
        val master = text(http, masterUrl)
        var videoUrl = masterUrl
        var videoList = master
        var audio: Pair<HttpUrl, String>? = null
        if ("#EXT-X-STREAM-INF" in master) {
            // Versions of the same video: take the largest picture (then the highest bit rate).
            val lines = master.lines().map { it.trim() }
            val variants = lines.mapIndexedNotNull { i, l ->
                if (!l.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
                val a = attrs(l)
                Triple(a, a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull() ?: 0, uri)
            }
            val best = variants.maxWithOrNull(compareBy({ it.second }, { it.first["BANDWIDTH"]?.toLongOrNull() ?: 0L }))
                ?: throw IOException("The stream playlist lists no video")
            videoUrl = masterUrl.child(best.third)
            videoList = text(http, videoUrl)
            // Sound kept in a playlist of its own (when it isn't inside the video pieces).
            best.first["AUDIO"]?.let { group ->
                lines.filter { it.startsWith("#EXT-X-MEDIA") }.map(::attrs)
                    .filter { it["TYPE"] == "AUDIO" && it["GROUP-ID"] == group && it["URI"] != null }
                    .let { list -> list.firstOrNull { it["DEFAULT"] == "YES" } ?: list.firstOrNull() }
                    ?.let { a -> masterUrl.child(a["URI"]!!).let { u -> audio = u to text(http, u) } }
            }
            AppLog.i("Stream for $name: ${variants.size} version(s), taking ${best.first["RESOLUTION"] ?: "the only one"}" + if (audio != null) ", sound separate" else "")
        }
        val videoFile = File(dir, "$name.video")
        val audioFile = audio?.let { File(dir, "$name.audio") }
        val out = File(dir, "$name.mp4")
        try {
            val videoPieces = pieces(videoUrl, videoList)
            val audioPieces = audio?.let { (u, list) -> pieces(u, list) } ?: emptyList()
            val all = videoPieces + audioPieces
            val known = probe(http, all)
            if (known != null) onTotal(known) else AppLog.i("Stream for $name: the server doesn't give piece sizes; progress is estimated")
            // Without sizes from the server: what the finished pieces weighed, scaled up to all of them.
            var got = 0L
            var finished = 0
            val counting: suspend (Int) -> Unit = { n -> got += n; onBytes(n) }
            val pieceDone: () -> Unit = { finished++; if (known == null) onTotal(estimateTotal(got, finished, all.size)) }
            fetch(http, videoPieces, videoFile, counting, pieceDone)
            if (audioPieces.isNotEmpty()) fetch(http, audioPieces, audioFile!!, counting, pieceDone)
            currentCoroutineContext().ensureActive()
            repack(videoFile, audioFile, out)
            return out
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            videoFile.delete()
            audioFile?.delete()
        }
    }

    /**
     * The size of all [urls] together, asked from the server without downloading anything (a HEAD
     * request each, a few at a time). Null when any of them doesn't say, so that a total is never
     * shown that is only part of the truth.
     */
    suspend fun probe(http: OkHttpClient, urls: List<HttpUrl>): Long? = kotlinx.coroutines.coroutineScope {
        if (urls.isEmpty() || urls.size > PROBE_MAX) return@coroutineScope null
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        val sizes = urls.distinct().map { u ->
            async(kotlinx.coroutines.Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        http.newCall(okhttp3.Request.Builder().url(u).head().build()).execute().use { r ->
                            if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } else null
                        }
                    }.getOrNull()
                }
            }
        }.awaitAll()
        if (sizes.any { it == null }) null else sizes.sumOf { it!! }
    }

    /** With [done] of [total] pieces fetched and [bytes] read so far, about how many bytes all of them will be. */
    fun estimateTotal(bytes: Long, done: Int, total: Int): Long =
        if (done <= 0 || total <= 0) -1 else if (done >= total) bytes else bytes / done * total + bytes % done * total / done

    private const val MAX_PIECES = 20_000
    private const val MAX_BYTES = 4L shl 30

    /** A stream in more pieces than this isn't asked piece by piece; its size is estimated instead. */
    private const val PROBE_MAX = 400

    /** Appends every piece to [into]. A piece that fails is tried three times. */
    private suspend fun fetch(http: OkHttpClient, urls: List<HttpUrl>, into: File, onBytes: suspend (Int) -> Unit, onPiece: () -> Unit = {}) {
        val buf = ByteArray(64 * 1024)
        // A playlist decides how much is fetched; these bounds keep a broken or hostile one from filling the phone.
        if (urls.size > MAX_PIECES) throw IOException("The stream has too many pieces (${urls.size})")
        var total = 0L
        into.outputStream().use { out ->
            for (u in urls) {
                var attempt = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val start = out.channel.position()
                    try {
                        RedGifsApi.download(http, u.toString()).use { r ->
                            val input = r.body!!.byteStream()
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) throw Unsupported("The stream is larger than ${MAX_BYTES shr 30} GB")
                                out.write(buf, 0, n)
                                onBytes(n)
                            }
                        }
                        onPiece()
                        break
                    } catch (e: IOException) {
                        if ((e is HttpException && e.code in 400..499 && e.code != 408 && e.code != 429) || attempt >= 2) throw e
                        attempt++
                        out.flush()
                        out.channel.truncate(start) // drop the half-read piece and read it again
                        out.channel.position(start)
                        kotlinx.coroutines.delay(1500L * attempt)
                    }
                }
            }
        }
    }

    /** Copies the video track (and the sound track, from the same or a second file) into an MP4, sample by sample. */
    private fun repack(video: File, audio: File?, out: File) {
        class Track(val ex: MediaExtractor, val index: Int, val target: Int, val isVideo: Boolean)
        val extractors = ArrayList<MediaExtractor>()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val tracks = ArrayList<Track>()
            fun add(file: File, wantVideo: Boolean, wantAudio: Boolean) {
                val ex = MediaExtractor().also { it.setDataSource(file.path) }
                extractors += ex
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    val isVideo = mime.startsWith("video/")
                    val wanted = (isVideo && wantVideo && tracks.none { it.isVideo }) || (mime.startsWith("audio/") && wantAudio && tracks.none { !it.isVideo })
                    if (!wanted) continue
                    if (isVideo && f.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION))
                    ex.selectTrack(i)
                    tracks += Track(ex, i, muxer.addTrack(f), isVideo)
                }
            }
            add(video, wantVideo = true, wantAudio = audio == null)
            audio?.let { add(it, wantVideo = false, wantAudio = true) }
            if (tracks.none { it.isVideo }) throw Unsupported("The stream has no video the phone can read")
            muxer.start()
            val buf = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val open = extractors.filterTo(ArrayList()) { ex -> tracks.any { it.ex === ex } }
            while (open.isNotEmpty()) {
                // Always the earliest sample next, so video and sound stay side by side in the file.
                val ex = open.minByOrNull { it.sampleTime.let { t -> if (t < 0) Long.MAX_VALUE else t } }!!
                val size = ex.readSampleData(buf, 0)
                if (size < 0) { open -= ex; continue }
                val t = tracks.firstOrNull { it.ex === ex && it.index == ex.sampleTrackIndex }
                if (t != null) {
                    info.set(0, size, ex.sampleTime, ex.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(t.target, buf, info)
                }
                ex.advance()
            }
            muxer.stop()
        } catch (e: IllegalStateException) {
            throw Unsupported("Couldn't turn the stream into an MP4: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw Unsupported("Couldn't turn the stream into an MP4: ${e.message}")
        } finally {
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }
}

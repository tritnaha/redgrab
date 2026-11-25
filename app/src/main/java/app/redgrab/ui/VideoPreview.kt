package app.redgrab.ui

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import android.webkit.WebSettings
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.redgrab.AppLog
import app.redgrab.Clip
import app.redgrab.MainViewModel
import app.redgrab.RedGifsApi
import app.redgrab.RedGrabApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * How many previews this phone can play at once: its limit for video decoders running side by
 * side, less one, and never more than 20. (The player needs none of them: previews stop while
 * a clip is open.)
 */
val maxPreviews: Int by lazy {
    runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { c -> !c.isEncoder && c.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } }
            .maxOf { it.getCapabilitiesForType("video/avc").maxSupportedInstances }
    }.getOrDefault(8).minus(1).coerceIn(4, 20)
}

/**
 * How much video this phone can decode at once for previews, counted in full-HD (1080p, 30
 * frames a second) streams: what its hardware decoder is rated for. Several small previews cost
 * as much as one large one (see [previewCost]). Holding back part of this (60% at first) left
 * the fourth row of an ordinary three-column grid still on a phone that could play it.
 */
val previewBudget: Float by lazy {
    val rated = runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { c -> !c.isEncoder && c.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } }
            .filter { android.os.Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated }
            .maxOf { c -> runCatching { c.getCapabilitiesForType("video/avc").videoCapabilities?.getSupportedFrameRatesFor(1920, 1080)?.upper ?: 0.0 }.getOrDefault(0.0) }
    }.getOrDefault(0.0).takeIf { it > 0 } ?: 150.0
    (rated / 30.0).toFloat().coerceIn(4f, 24f)
}

/**
 * Roughly what a clip's preview costs to decode, in full-HD streams: by the picture size of the
 * file that will play. The SD file (or the SD stream of a clip that isn't saved) is about a
 * quarter of the HD picture. Pictures cost nothing: the image loader draws them.
 */
fun previewCost(c: Clip?, saved: Set<app.redgrab.Quality>?): Float {
    if (c == null) return 1f
    if (c.isImage) return 0f
    val full = if (c.width > 0 && c.height > 0) c.width.toFloat() * c.height / (1920f * 1080f) else 1f
    val small = if (saved.isNullOrEmpty()) c.sd != null && c.copyFile == null else app.redgrab.Quality.SD in saved
    return (if (small) full / 4 else full).coerceIn(0.1f, 4f)
}

/** How much of a tile's height is on screen, 0..1. [viewStart]..[viewEnd] is the part of the grid you can see. */
fun visibleShare(top: Int, height: Int, viewStart: Int, viewEnd: Int): Float {
    if (height <= 0) return 0f
    return ((minOf(top + height, viewEnd) - maxOf(top, viewStart)).toFloat() / height).coerceIn(0f, 1f)
}

/**
 * Which previews should play, given the tiles far enough on screen to start ([startable], top
 * first) and the ones playing now: those already playing stay, then new ones are added from the
 * top while there is room in both the count ([max]) and the decoding [budget].
 */
fun pickPreviews(startable: List<String>, playing: Set<String>, max: Int, budget: Float, cost: (String) -> Float): List<String> {
    var used = playing.sumOf { cost(it).toDouble() }.toFloat()
    var count = playing.size
    val add = ArrayList<String>()
    for (id in startable) {
        if (id in playing) continue
        val c = cost(id)
        if (count >= max || used + c > budget + 0.001f) continue
        add += id; used += c; count++
    }
    return add
}

/** A tile starts playing once this much of it is on screen... */
private const val START_SHARE = 0.5f
/** ...and stops when less than this is left, so one sitting on the edge doesn't flicker on and off. */
private const val KEEP_SHARE = 0.2f
/** A tile has to stay in view this long before it starts; tiles flying past in a fast scroll never do. */
private const val DWELL_MS = 120L

/**
 * Which tiles show a moving preview right now. Only tiles you can actually see play: one starts
 * when at least half of it is on screen (the next row begins as it comes halfway into view) and
 * stops when it has nearly left. While you scroll slowly, rows start as they arrive; in a fast
 * scroll nothing new starts until it settles, which keeps scrolling smooth. New previews start a
 * couple at a time rather than all in one instant, and no more than the phone can decode at once.
 *
 * Returns a state so that a change only redraws the tiles concerned; read it inside a tile with
 * [isPreviewing].
 */
@Composable
fun rememberActivePreviews(grid: LazyStaggeredGridState, enabled: Boolean, max: Int, changeKey: Any? = null,
                           cost: (String) -> Float = { 1f }, eligible: (String) -> Boolean): State<Set<String>> {
    val active = remember { mutableStateOf<Set<String>>(emptySet()) }
    val isEligible by rememberUpdatedState(eligible)
    val costOf by rememberUpdatedState(cost)
    // [changeKey]: anything that changes which clips are eligible (e.g. a download finishing).
    LaunchedEffect(grid, enabled, max, changeKey) {
        if (!enabled) { active.value = emptySet(); return@LaunchedEffect }
        snapshotFlow {
            val info = grid.layoutInfo
            // What you can see: the grid's area without the strip under the navigation bar.
            val from = info.viewportStartOffset
            val to = info.viewportEndOffset - info.afterContentPadding
            val start = ArrayList<String>()
            val keep = HashSet<String>()
            for (item in info.visibleItemsInfo) {
                val id = item.key as? String ?: continue
                val share = visibleShare(item.offset.y, item.size.height, from, to)
                if (share >= START_SHARE) start += id
                if (share >= KEEP_SHARE) keep += id
            }
            start to keep
        }.collectLatest { (start, keep) ->
            // Gone from view: stop at once, which frees its decoder.
            if (active.value.any { it !in keep }) active.value = active.value.filterTo(LinkedHashSet()) { it in keep }
            delay(DWELL_MS) // a new scroll position cancels this
            val add = pickPreviews(start.filter { isEligible(it) }, active.value, max, previewBudget) { costOf(it) }
            for (pair in add.chunked(2)) {
                active.value = active.value + pair
                delay(45)
            }
        }
    }
    return if (enabled) active else remember { mutableStateOf(emptySet()) }
}

/** Whether this tile's preview should play. Only this tile is redrawn when its own answer changes. */
@Composable
fun isPreviewing(moving: State<Set<String>>, id: String): Boolean {
    val on by remember(moving, id) { derivedStateOf { id in moving.value } }
    return on
}

/**
 * A muted, looping preview drawn over a tile's still image. Saved clips play from the phone
 * (SD file if there is one, since it's lighter); unsaved ones stream their SD version when
 * [allowStream] is on. Fades in once the first frame is ready, so there's never a black flash.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(vm: MainViewModel, clip: Clip, allowStream: Boolean, modifier: Modifier = Modifier) {
    if (clip.isImage) {
        // GIF/WebP: the image loader animates it; no video decoder needed.
        var uri by remember(clip.id) { mutableStateOf<Uri?>(null) }
        // From the phone when it's saved; an online GIF only if unsaved clips may animate.
        LaunchedEffect(clip.id, clip.copyFile, allowStream) {
            uri = withContext(Dispatchers.IO) { vm.library.localFile(clip)?.first } ?: clip.hd?.takeIf { allowStream && clip.copyFile == null }?.let(Uri::parse)
        }
        uri?.let { coil3.compose.AsyncImage(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = modifier) }
        return
    }
    val ctx = LocalContext.current
    var ready by remember(clip.id) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (ready) 1f else 0f, label = "preview")

    val exo = remember(clip.id) {
        // Small buffers: previews are short loops, and several run at once.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 4_000, 300, 500).build()
        ExoPlayer.Builder(ctx).setLoadControl(load).build().apply {
            // Previews are silent: with the sound track switched off no audio decoder is started at all.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    LaunchedEffect(clip.id, allowStream) {
        // No check that the file is still there (that asks the storage provider and delays every start);
        // a missing file simply leaves the still picture.
        val source = withContext(Dispatchers.IO) { vm.library.previewSource(clip, allowStream, verify = false) } ?: return@LaunchedEffect
        runCatching {
            val (uri, local) = source
            val factory = if (local) DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx)) else {
                val ua = vm.library.prefs.userAgent ?: runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrDefault("Mozilla/5.0")
                val upstream = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(RedGifsApi.clientFor(ua)))
                DefaultMediaSourceFactory(
                    CacheDataSource.Factory().setCache((ctx.applicationContext as RedGrabApp).mediaCache).setUpstreamDataSourceFactory(upstream)
                )
            }
            exo.setMediaSource(factory.createMediaSource(MediaItem.fromUri(uri)))
            exo.prepare()
        }.onFailure { AppLog.w("Preview for ${clip.id} couldn't start: ${it.message}") }
    }

    // Stop playing when the app isn't in front; release the decoder when the tile leaves.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(exo, lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_STOP -> exo.pause()
                Lifecycle.Event.ON_START -> exo.play()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); exo.release() }
    }

    AndroidView(
        modifier = modifier.alpha(alpha),
        factory = { c ->
            TextureView(c).also { tv ->
                exo.setVideoTextureView(tv)
                // Fill the tile and crop the overflow (like the still image does).
                fun fit(size: VideoSize) {
                    if (size.width == 0 || size.height == 0 || tv.width == 0 || tv.height == 0) return
                    val video = size.width * size.pixelWidthHeightRatio / size.height
                    val view = tv.width.toFloat() / tv.height
                    val m = Matrix()
                    if (video > view) m.setScale(video / view, 1f, tv.width / 2f, tv.height / 2f)
                    else m.setScale(1f, view / video, tv.width / 2f, tv.height / 2f)
                    tv.setTransform(m)
                }
                exo.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) = fit(videoSize)
                    override fun onRenderedFirstFrame() { ready = true }
                })
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit(exo.videoSize) }
            }
        },
    )
}

/** Where a preview plays from: (URI, true if it's a file on the phone). */
typealias PreviewSource = Pair<Uri, Boolean>

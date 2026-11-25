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
 * Which tiles should show a moving preview right now: the first [max] eligible clips on
 * screen, and only once scrolling has stopped. While you scroll, everything is a still (and
 * the video decoders are free), so scrolling stays smooth and nothing off screen plays.
 */
@Composable
fun rememberActivePreviews(grid: LazyStaggeredGridState, enabled: Boolean, max: Int, changeKey: Any? = null, eligible: (String) -> Boolean): Set<String> {
    var active by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isEligible by rememberUpdatedState(eligible)
    // [changeKey]: anything that changes which clips are eligible (e.g. a download finishing).
    LaunchedEffect(grid, enabled, max, changeKey) {
        if (!enabled) { active = emptySet(); return@LaunchedEffect }
        snapshotFlow { grid.isScrollInProgress to grid.layoutInfo.visibleItemsInfo.map { it.key } }
            .collectLatest { (scrolling, keys) ->
                if (scrolling) { active = emptySet(); return@collectLatest }
                delay(350) // settle first; a new scroll cancels this
                active = keys.asSequence().filterIsInstance<String>().filter { isEligible(it) }.take(max).toSet()
            }
    }
    return if (enabled) active else emptySet()
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
        LaunchedEffect(clip.id) { uri = withContext(Dispatchers.IO) { vm.library.localFile(clip)?.first } }
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
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    LaunchedEffect(clip.id, allowStream) {
        val source = withContext(Dispatchers.IO) { vm.library.previewSource(clip, allowStream) } ?: return@LaunchedEffect
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
